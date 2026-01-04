package com.youtubetowatch

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service that detects when YouTube app is opened.
 * In Phase 1, it shows an overlay popup when YouTube is detected.
 * In Phase 2, it will redirect to Watch Later playlist.
 */
class YouTubeDetectorService : AccessibilityService() {

    companion object {
        private const val TAG = "YouTubeDetector"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        
        // Cooldown to prevent multiple triggers
        private const val COOLDOWN_MS = 3000L
        
        var isServiceEnabled = false
            private set
    }

    private var lastTriggerTime = 0L
    private var overlayManager: OverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceEnabled = true
        overlayManager = OverlayManager(this)
        Log.d(TAG, "Service connected - YouTube detection enabled")
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
            
            // Apply cooldown to prevent multiple triggers
            if (currentTime - lastTriggerTime < COOLDOWN_MS) {
                Log.d(TAG, "Cooldown active, skipping trigger")
                return
            }
            
            lastTriggerTime = currentTime
            onYouTubeDetected()
        } else {
            // Hide overlay when user leaves YouTube
            overlayManager?.hideOverlay()
        }
    }

    private fun onYouTubeDetected() {
        Log.d(TAG, "YouTube detected! Showing overlay...")
        
        // Phase 1: Show overlay popup
        overlayManager?.showOverlay()
        
        // Phase 2 (TODO): Redirect to Watch Later
        // val watchLaterUrl = "https://www.youtube.com/playlist?list=WL"
        // val intent = Intent(Intent.ACTION_VIEW, Uri.parse(watchLaterUrl))
        // intent.setPackage(YOUTUBE_PACKAGE)
        // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // startActivity(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceEnabled = false
        overlayManager?.hideOverlay()
        overlayManager = null
        Log.d(TAG, "Service destroyed")
    }
}
