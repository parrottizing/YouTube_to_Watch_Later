package com.youtubetowatch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service that detects when YouTube app is opened
 * and redirects to the Watch Later playlist.
 */
class YouTubeDetectorService : AccessibilityService() {

    companion object {
        private const val TAG = "YouTubeDetector"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val WATCH_LATER_URL = "https://www.youtube.com/playlist?list=WL"
        
        // Cooldown to prevent redirect loops (5 seconds)
        private const val COOLDOWN_MS = 5000L
        
        // Preference keys
        private const val PREFS_NAME = "youtube_redirect_prefs"
        private const val PREF_ENABLED = "redirect_enabled"
        
        var isServiceEnabled = false
            private set
    }

    private var lastRedirectTime = 0L
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
        
        Log.d(TAG, "Window changed to: $packageName")
        
        // Check if YouTube was opened
        if (packageName == YOUTUBE_PACKAGE) {
            val currentTime = System.currentTimeMillis()
            
            // Apply cooldown to prevent redirect loops
            if (currentTime - lastRedirectTime < COOLDOWN_MS) {
                Log.d(TAG, "Cooldown active (${COOLDOWN_MS}ms), skipping redirect")
                return
            }
            
            // Check if redirect is enabled in preferences
            val isEnabled = prefs?.getBoolean(PREF_ENABLED, true) ?: true
            if (!isEnabled) {
                Log.d(TAG, "Redirect disabled in preferences")
                return
            }
            
            lastRedirectTime = currentTime
            redirectToWatchLater()
        }
    }

    private fun redirectToWatchLater() {
        Log.d(TAG, "YouTube detected! Redirecting to Watch Later...")
        
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_LATER_URL))
            intent.setPackage(YOUTUBE_PACKAGE)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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
        Log.d(TAG, "Service destroyed")
    }
}
