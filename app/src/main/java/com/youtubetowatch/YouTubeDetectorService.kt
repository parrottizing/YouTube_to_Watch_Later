package com.youtubetowatch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
        
        // Only process window state changes and content changes
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        
        val packageName = event.packageName?.toString() ?: return
        
        // Only care about YouTube events
        if (packageName != YOUTUBE_PACKAGE) return
        
        Log.d(TAG, "YouTube event: ${event.eventType}")
        
        // Check if redirect is enabled in preferences
        val isEnabled = prefs?.getBoolean(PREF_ENABLED, true) ?: true
        if (!isEnabled) {
            Log.d(TAG, "Redirect disabled in preferences")
            return
        }
        
        // Check cooldown
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRedirectTime < COOLDOWN_MS) {
            Log.d(TAG, "Cooldown active, skipping check")
            return
        }
        
        // Check if we are on the Home screen
        if (isOnHomeScreen()) {
            Log.d(TAG, "Home screen detected!")
            lastRedirectTime = currentTime
            redirectToWatchLater()
        }
    }

    /**
     * Check if the YouTube "Home" tab is currently selected.
     * This traverses the accessibility node tree looking for a node with
     * contentDescription containing "Home" that is selected.
     */
    private fun isOnHomeScreen(): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.d(TAG, "Root window not available")
            return false
        }
        
        try {
            // BFS to find the Home tab
            val queue = LinkedList<AccessibilityNodeInfo>()
            queue.add(rootNode)
            
            while (queue.isNotEmpty()) {
                val node = queue.poll() ?: continue
                
                val contentDesc = node.contentDescription?.toString() ?: ""
                val text = node.text?.toString() ?: ""
                
                // Look for "Home" tab that is selected
                // YouTube uses "Home" as content description for the bottom nav button
                if ((contentDesc.equals("Home", ignoreCase = true) || 
                     text.equals("Home", ignoreCase = true)) && 
                    node.isSelected) {
                    Log.d(TAG, "Found selected Home tab: desc='$contentDesc', text='$text', selected=${node.isSelected}")
                    node.recycle()
                    recycleNodes(queue)
                    rootNode.recycle()
                    return true
                }
                
                // Add children to queue
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        queue.add(child)
                    }
                }
                
                node.recycle()
            }
            
            rootNode.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking home screen", e)
        }
        
        return false
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
