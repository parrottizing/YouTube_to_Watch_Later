package com.youtubetowatch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Accessibility Service that detects when YouTube Home screen is active
 * and redirects to the Watch Later playlist.
 */
class YouTubeDetectorService : AccessibilityService() {

    companion object {
        private const val TAG = "YouTubeDetector"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val WATCH_LATER_URL = "https://www.youtube.com/playlist?list=WL"
        
        // Cooldown to prevent redirect loops (5 seconds)
        private const val COOLDOWN_MS = 1800L

        // Debounce and scan limits to avoid hammering YouTube's UI thread
        private const val EVENT_DEBOUNCE_MS = 220L
        private const val HOME_INTERACTION_SCAN_DELAY_MS = 180L
        private const val CONTENT_EVENT_DEBOUNCE_MS = 280L
        private const val MIN_SCAN_INTERVAL_MS = 300L
        private const val HOME_CONFIRMATION_MS = 650L
        private const val HOME_RECHECK_MS = 350L
        private const val MAX_HOME_RECHECKS = 3
        private const val DEEPLINK_SUPPRESSION_MS = 900L
        private const val REDIRECT_IN_FLIGHT_MS = 2400L
        private const val MAX_SCAN_NODES = 5000
        private const val MAX_SCAN_MS = 260L
        private const val HOME_INTERACTION_BYPASS_WINDOW_MS = 1200L
        private const val SHELL_HOME_ACTIVITY_HINT = "Shell\$HomeActivity"
        private const val HOME_ACTIVITY_FALLBACK_WINDOW_MS = 6000L

        private val NON_HOME_ACTIVITY_HINTS = arrayOf(
            "watchwhile.MainActivity",
            "UrlActivity",
            "StandalonePlayerActivity"
        )
        
        // Preference keys
        private const val PREFS_NAME = "youtube_redirect_prefs"
        private const val PREF_ENABLED = "redirect_enabled"
        
        var isServiceEnabled = false
            private set
    }

    private var lastRedirectUptimeMs = 0L
    private var lastScanUptimeMs = 0L
    private var redirectInFlightUntilMs = 0L
    private var lastNonHomeEventUptimeMs = 0L
    private var currentActivityClassName: String? = null
    private var lastWindowId = -1
    private var lastWindowClassName: String? = null
    private var homeCandidateSinceUptimeMs = 0L
    private var homeCandidateWindowId = -1
    private var homeCandidateClassName: String? = null
    private var homeRecheckWindowId = -1
    private var homeRecheckClassName: String? = null
    private var homeRecheckAttempt = 0
    private var lastHomeInteractionUptimeMs = 0L
    private var lastWindowStateUptimeMs = 0L
    private var lastWindowStateClassName: String? = null
    private var pendingScan: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        Log.d(TAG, "Service connected - YouTube redirect enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        // Only care about YouTube events
        if (packageName != YOUTUBE_PACKAGE) return

        val eventType = event.eventType
        val isWindowStateEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isContentChangedEvent = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val isHomeInteraction = isHomeInteractionEvent(event)
        if (!isWindowStateEvent && !isContentChangedEvent && !isHomeInteraction) return

        val eventClassName = if (isWindowStateEvent) {
            event.className?.toString().orEmpty()
        } else if (isContentChangedEvent) {
            currentActivityClassName ?: event.className?.toString().orEmpty()
        } else {
            currentActivityClassName.orEmpty()
        }
        val now = SystemClock.uptimeMillis()
        Log.d(
            TAG,
            "YouTube event: type=$eventType, class=$eventClassName, windowId=${event.windowId}, homeInteraction=$isHomeInteraction, contentChanged=$isContentChangedEvent"
        )

        if (isWindowStateEvent) {
            currentActivityClassName = eventClassName
            lastWindowStateUptimeMs = now
            lastWindowStateClassName = eventClassName

            if (isLikelyDeepLinkActivity(eventClassName)) {
                lastNonHomeEventUptimeMs = now
                resetHomeCandidate()
                resetHomeRecheck()
                Log.d(TAG, "Deep-link/player activity seen ($eventClassName), temporarily suppressing redirect")
            }
        }
        if (isHomeInteraction) {
            lastHomeInteractionUptimeMs = now
        }

        // Check if redirect is enabled in preferences
        val isEnabled = prefs?.getBoolean(PREF_ENABLED, true) ?: true
        if (!isEnabled) {
            Log.d(TAG, "Redirect disabled in preferences")
            return
        }

        if (now < redirectInFlightUntilMs) {
            val remainingMs = redirectInFlightUntilMs - now
            Log.d(TAG, "Redirect in flight (${remainingMs}ms remaining), deferring scan")
            scheduleScan(event.windowId, eventClassName, remainingMs + 120L)
            return
        }

        // Use a tighter delay for direct Home interactions, debounce for window transitions.
        val scanDelayMs = when {
            isHomeInteraction -> HOME_INTERACTION_SCAN_DELAY_MS
            isContentChangedEvent -> CONTENT_EVENT_DEBOUNCE_MS
            else -> EVENT_DEBOUNCE_MS
        }
        scheduleScan(event.windowId, eventClassName, scanDelayMs)
    }
    
    private fun scheduleScan(
        windowId: Int,
        eventClassName: String,
        delayMs: Long = EVENT_DEBOUNCE_MS
    ) {
        pendingScan?.let { handler.removeCallbacks(it) }
        pendingScan = Runnable {
            pendingScan = null
            performScan(windowId, eventClassName)
        }
        handler.postDelayed(pendingScan!!, delayMs)
    }

    private fun performScan(windowId: Int, eventClassName: String) {
        val now = SystemClock.uptimeMillis()
        if (now < redirectInFlightUntilMs) {
            val remainingMs = redirectInFlightUntilMs - now
            Log.d(TAG, "Redirect in flight (${remainingMs}ms remaining), deferring scan")
            scheduleScan(windowId, eventClassName, remainingMs + 120L)
            return
        }

        if (now - lastScanUptimeMs < MIN_SCAN_INTERVAL_MS &&
            windowId == lastWindowId &&
            eventClassName == lastWindowClassName
        ) {
            Log.d(TAG, "Scan throttled (windowId=$windowId class=$eventClassName)")
            return
        }
        lastScanUptimeMs = now
        lastWindowId = windowId
        lastWindowClassName = eventClassName

        val suppressionRemainingMs = lastNonHomeEventUptimeMs + DEEPLINK_SUPPRESSION_MS - now
        if (suppressionRemainingMs > 0) {
            resetHomeCandidate()
            resetHomeRecheck()
            Log.d(
                TAG,
                "Deep-link suppression active for ${suppressionRemainingMs}ms; delaying Home confirmation scan"
            )
            scheduleScan(windowId, eventClassName, suppressionRemainingMs + 80L)
            return
        }

        if (now - lastRedirectUptimeMs < COOLDOWN_MS) {
            resetHomeRecheck()
            Log.d(TAG, "Cooldown active, skipping scan")
            return
        }

        val scanResult = scanUi()
        if (scanResult.onWatchLater) {
            resetHomeCandidate()
            resetHomeRecheck()
            Log.d(TAG, "Already on Watch Later playlist, skipping redirect")
            return
        }

        if (!scanResult.onHomeSelected) {
            resetHomeCandidate()
            if (scheduleHomeRecheck(windowId, eventClassName)) {
                return
            }
            if (shouldFallbackRedirectFromHomeActivity(now)) {
                resetHomeRecheck()
                Log.d(TAG, "Home markers missing but recent HomeActivity detected, redirecting aggressively")
                lastRedirectUptimeMs = now
                redirectInFlightUntilMs = now + REDIRECT_IN_FLIGHT_MS
                redirectToWatchLater()
                return
            }
            Log.d(TAG, "Home tab is not selected, skipping redirect")
            return
        }

        resetHomeRecheck()
        if (shouldBypassHomeConfirmation(now)) {
            resetHomeCandidate()
            Log.d(TAG, "Recent explicit Home interaction detected, redirecting immediately")
            lastRedirectUptimeMs = now
            redirectInFlightUntilMs = now + REDIRECT_IN_FLIGHT_MS
            redirectToWatchLater()
            return
        }
        if (!isHomeConfirmed(now, windowId, eventClassName)) {
            scheduleScan(windowId, eventClassName, HOME_CONFIRMATION_MS)
            return
        }

        resetHomeCandidate()
        Log.d(TAG, "Stable Home feed detected, redirecting to Watch Later")
        lastRedirectUptimeMs = now
        redirectInFlightUntilMs = now + REDIRECT_IN_FLIGHT_MS
        redirectToWatchLater()
    }

    private data class ScanResult(
        val onWatchLater: Boolean,
        val onHomeSelected: Boolean
    )

    /**
     * Single-pass scan that checks both "Watch later" and selected "Home" tab.
     * This is time- and node-limited to avoid slowing down YouTube.
     */
    private fun scanUi(): ScanResult {
        val rootNode = rootInActiveWindow ?: run {
            Log.d(TAG, "Root window not available")
            return ScanResult(false, false)
        }

        var foundWatchLater = false
        var foundWatchLaterTitle = false
        var foundWatchLaterPlaylistContext = false
        var foundHomeSelected = false
        var nodesScanned = 0
        val start = SystemClock.uptimeMillis()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(rootNode)

        try {
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                try {
                    nodesScanned++
                    val contentDesc = node.contentDescription?.toString() ?: ""
                    val text = node.text?.toString() ?: ""

                    if (!foundWatchLaterTitle &&
                        (contentDesc.equals("Watch later", ignoreCase = true) ||
                         text.equals("Watch later", ignoreCase = true))) {
                        foundWatchLaterTitle = true
                    }

                    if (!foundWatchLaterPlaylistContext &&
                        (contentDesc.contains("Play all", ignoreCase = true) ||
                         text.equals("Playlist", ignoreCase = true))) {
                        foundWatchLaterPlaylistContext = true
                    }

                    if (!foundWatchLater &&
                        foundWatchLaterTitle &&
                        foundWatchLaterPlaylistContext) {
                        Log.d(
                            TAG,
                            "Confirmed Watch Later playlist context (title+playlist markers)"
                        )
                        foundWatchLater = true
                        break
                    }

                    if (!foundHomeSelected &&
                        node.isSelected &&
                        (contentDesc.equals("Home", ignoreCase = true) ||
                         text.equals("Home", ignoreCase = true))) {
                        Log.d(TAG, "Found selected Home tab: desc='$contentDesc', text='$text', selected=${node.isSelected}")
                        foundHomeSelected = true
                        // Keep scanning briefly for Watch Later to avoid false redirects
                    }

                    if (nodesScanned >= MAX_SCAN_NODES ||
                        SystemClock.uptimeMillis() - start > MAX_SCAN_MS) {
                        Log.d(TAG, "Scan budget exceeded: nodes=$nodesScanned")
                        break
                    }

                    for (i in node.childCount - 1 downTo 0) {
                        val child = node.getChild(i)
                        if (child != null) {
                            stack.addLast(child)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning UI", e)
        } finally {
            recycleNodes(stack)
        }

        return ScanResult(foundWatchLater, foundHomeSelected)
    }

    private fun isLikelyDeepLinkActivity(className: String): Boolean {
        if (className.isBlank()) {
            return false
        }
        return NON_HOME_ACTIVITY_HINTS.any { className.contains(it, ignoreCase = true) }
    }

    private fun isHomeInteractionEvent(event: AccessibilityEvent): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            return false
        }

        if (event.contentDescription?.toString()?.equals("Home", ignoreCase = true) == true) {
            return true
        }

        if (event.text.any { it?.toString()?.equals("Home", ignoreCase = true) == true }) {
            return true
        }

        val source = event.source ?: return false
        return try {
            source.contentDescription?.toString()?.equals("Home", ignoreCase = true) == true ||
                source.text?.toString()?.equals("Home", ignoreCase = true) == true
        } catch (e: Exception) {
            false
        } finally {
            source.recycle()
        }
    }

    private fun scheduleHomeRecheck(windowId: Int, eventClassName: String): Boolean {
        val sameTarget = homeRecheckWindowId == windowId && homeRecheckClassName == eventClassName
        if (!sameTarget) {
            homeRecheckWindowId = windowId
            homeRecheckClassName = eventClassName
            homeRecheckAttempt = 0
        }

        if (homeRecheckAttempt >= MAX_HOME_RECHECKS) {
            resetHomeRecheck()
            return false
        }

        homeRecheckAttempt++
        Log.d(TAG, "Home not selected yet, scheduling recheck $homeRecheckAttempt/$MAX_HOME_RECHECKS")
        scheduleScan(windowId, eventClassName, HOME_RECHECK_MS)
        return true
    }

    private fun shouldFallbackRedirectFromHomeActivity(now: Long): Boolean {
        if (lastWindowStateClassName?.contains(SHELL_HOME_ACTIVITY_HINT, ignoreCase = true) != true) {
            return false
        }
        return now - lastWindowStateUptimeMs <= HOME_ACTIVITY_FALLBACK_WINDOW_MS
    }

    private fun shouldBypassHomeConfirmation(now: Long): Boolean {
        return now - lastHomeInteractionUptimeMs <= HOME_INTERACTION_BYPASS_WINDOW_MS
    }

    private fun resetHomeRecheck() {
        homeRecheckWindowId = -1
        homeRecheckClassName = null
        homeRecheckAttempt = 0
    }

    private fun isHomeConfirmed(now: Long, windowId: Int, eventClassName: String): Boolean {
        val sameCandidate =
            homeCandidateSinceUptimeMs > 0L &&
                homeCandidateWindowId == windowId &&
                homeCandidateClassName == eventClassName

        if (!sameCandidate) {
            homeCandidateSinceUptimeMs = now
            homeCandidateWindowId = windowId
            homeCandidateClassName = eventClassName
            Log.d(TAG, "Home candidate started (windowId=$windowId class=$eventClassName)")
            return false
        }

        val ageMs = now - homeCandidateSinceUptimeMs
        if (ageMs < HOME_CONFIRMATION_MS) {
            Log.d(TAG, "Home candidate pending (${HOME_CONFIRMATION_MS - ageMs}ms remaining)")
            return false
        }
        return true
    }

    private fun resetHomeCandidate() {
        homeCandidateSinceUptimeMs = 0L
        homeCandidateWindowId = -1
        homeCandidateClassName = null
    }
    
    private fun recycleNodes(nodes: Collection<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                node.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun redirectToWatchLater() {
        Log.d(TAG, "Redirecting to Watch Later...")
        
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_LATER_URL))
            intent.setPackage(YOUTUBE_PACKAGE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
            Log.d(TAG, "Redirect intent sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to redirect to Watch Later", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceEnabled = false
        prefs = null
        currentActivityClassName = null
        lastHomeInteractionUptimeMs = 0L
        lastWindowStateUptimeMs = 0L
        lastWindowStateClassName = null
        resetHomeCandidate()
        resetHomeRecheck()
        pendingScan?.let { handler.removeCallbacks(it) }
        pendingScan = null
        Log.d(TAG, "Service destroyed")
    }
}
