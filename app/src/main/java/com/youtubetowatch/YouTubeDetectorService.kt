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
import java.util.LinkedList

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
        private const val COOLDOWN_MS = 5000L

        // Debounce and scan limits to avoid hammering YouTube's UI thread
        private const val EVENT_DEBOUNCE_MS = 400L
        private const val MIN_SCAN_INTERVAL_MS = 1000L
        private const val HOME_CONFIRMATION_MS = 1200L
        private const val DEEPLINK_SUPPRESSION_MS = 3500L
        private const val REDIRECT_IN_FLIGHT_MS = 8000L
        private const val MAX_SCAN_NODES = 2000
        private const val MAX_SCAN_MS = 120L

        private val NON_HOME_ACTIVITY_HINTS = arrayOf(
            "watchwhile.MainActivity",
            "InternalMainActivity",
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
    private var lastWindowId = -1
    private var lastWindowClassName: String? = null
    private var homeCandidateSinceUptimeMs = 0L
    private var homeCandidateWindowId = -1
    private var homeCandidateClassName: String? = null
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
        
        // Only process window state changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        
        val packageName = event.packageName?.toString() ?: return
        
        // Only care about YouTube events
        if (packageName != YOUTUBE_PACKAGE) return

        val eventClassName = event.className?.toString().orEmpty()
        val now = SystemClock.uptimeMillis()
        Log.d(
            TAG,
            "YouTube event: type=${event.eventType}, class=$eventClassName, windowId=${event.windowId}"
        )

        if (isLikelyDeepLinkActivity(eventClassName)) {
            lastNonHomeEventUptimeMs = now
            resetHomeCandidate()
            Log.d(TAG, "Deep-link/player activity seen ($eventClassName), temporarily suppressing redirect")
        }

        // Check if redirect is enabled in preferences
        val isEnabled = prefs?.getBoolean(PREF_ENABLED, true) ?: true
        if (!isEnabled) {
            Log.d(TAG, "Redirect disabled in preferences")
            return
        }

        if (now < redirectInFlightUntilMs) {
            Log.d(TAG, "Redirect in flight, skipping scan")
            return
        }

        // Debounce repeated window events
        scheduleScan(event.windowId, eventClassName)
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
            Log.d(TAG, "Redirect in flight, skipping scan")
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

        if (isLikelyDeepLinkActivity(eventClassName)) {
            resetHomeCandidate()
            Log.d(TAG, "Scan skipped for deep-link/player class ($eventClassName)")
            return
        }

        val suppressionRemainingMs = lastNonHomeEventUptimeMs + DEEPLINK_SUPPRESSION_MS - now
        if (suppressionRemainingMs > 0) {
            resetHomeCandidate()
            Log.d(
                TAG,
                "Deep-link suppression active for ${suppressionRemainingMs}ms; delaying Home confirmation scan"
            )
            scheduleScan(windowId, eventClassName, suppressionRemainingMs + 80L)
            return
        }

        if (now - lastRedirectUptimeMs < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active, skipping scan")
            return
        }

        val scanResult = scanUi()
        if (scanResult.onWatchLater) {
            resetHomeCandidate()
            Log.d(TAG, "Already on Watch Later playlist, skipping redirect")
            return
        }

        if (!scanResult.onHomeSelected) {
            resetHomeCandidate()
            Log.d(TAG, "Home tab is not selected, skipping redirect")
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
        var foundHomeSelected = false
        var nodesScanned = 0
        val start = SystemClock.uptimeMillis()
        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(rootNode)

        try {
            while (queue.isNotEmpty()) {
                val node = queue.poll() ?: continue
                try {
                    nodesScanned++
                    val contentDesc = node.contentDescription?.toString() ?: ""
                    val text = node.text?.toString() ?: ""

                    if (!foundWatchLater &&
                        (contentDesc.contains("Watch later", ignoreCase = true) ||
                         text.contains("Watch later", ignoreCase = true))) {
                        Log.d(TAG, "Found Watch Later indicator: desc='$contentDesc', text='$text'")
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

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning UI", e)
        } finally {
            recycleNodes(queue)
        }

        return ScanResult(foundWatchLater, foundHomeSelected)
    }

    private fun isLikelyDeepLinkActivity(className: String): Boolean {
        if (className.isBlank()) {
            return false
        }
        return NON_HOME_ACTIVITY_HINTS.any { className.contains(it, ignoreCase = true) }
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
        resetHomeCandidate()
        pendingScan?.let { handler.removeCallbacks(it) }
        pendingScan = null
        Log.d(TAG, "Service destroyed")
    }
}
