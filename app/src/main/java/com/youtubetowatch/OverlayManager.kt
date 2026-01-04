package com.youtubetowatch

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Manages the overlay popup that appears when YouTube is detected.
 * This is for Phase 1 POC - will be replaced with redirect in Phase 2.
 */
class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayManager"
        private const val OVERLAY_DURATION_MS = 2000L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isOverlayShowing = false

    private val hideRunnable = Runnable {
        hideOverlay()
    }

    fun showOverlay() {
        if (!canDrawOverlay()) {
            Log.w(TAG, "No overlay permission granted")
            return
        }

        if (isOverlayShowing) {
            Log.d(TAG, "Overlay already showing, extending duration")
            handler.removeCallbacks(hideRunnable)
            handler.postDelayed(hideRunnable, OVERLAY_DURATION_MS)
            return
        }

        handler.post {
            try {
                createOverlay()
                isOverlayShowing = true
                
                // Auto-hide after duration
                handler.postDelayed(hideRunnable, OVERLAY_DURATION_MS)
                
                Log.d(TAG, "Overlay shown successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay", e)
            }
        }
    }

    fun hideOverlay() {
        handler.post {
            try {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                    overlayView = null
                    isOverlayShowing = false
                    Log.d(TAG, "Overlay hidden")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide overlay", e)
            }
        }
        handler.removeCallbacks(hideRunnable)
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private fun createOverlay() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create overlay view programmatically
        overlayView = TextView(context).apply {
            text = context.getString(R.string.youtube_detected)
            textSize = 24f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(context.getColor(R.color.overlay_background))
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        windowManager?.addView(overlayView, layoutParams)
    }
}
