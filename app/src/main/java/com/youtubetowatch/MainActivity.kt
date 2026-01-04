package com.youtubetowatch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Main activity that guides users to enable required permissions:
 * 1. Accessibility Service - for detecting YouTube
 * 2. Overlay Permission - for showing the popup
 */
class MainActivity : AppCompatActivity() {

    private lateinit var accessibilityStatusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var enableAccessibilityButton: Button
    private lateinit var enableOverlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        accessibilityStatusText = findViewById(R.id.accessibility_status)
        overlayStatusText = findViewById(R.id.overlay_status)
        enableAccessibilityButton = findViewById(R.id.btn_enable_accessibility)
        enableOverlayButton = findViewById(R.id.btn_enable_overlay)

        // Set up button click listeners
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        enableOverlayButton.setOnClickListener {
            openOverlaySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
    }

    private fun updateStatusIndicators() {
        // Check Accessibility Service status
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        accessibilityStatusText.text = if (isAccessibilityEnabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        accessibilityStatusText.setTextColor(
            getColor(if (isAccessibilityEnabled) R.color.success_green else R.color.error_red)
        )

        // Check Overlay Permission status
        val isOverlayEnabled = canDrawOverlay()
        overlayStatusText.text = if (isOverlayEnabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        overlayStatusText.setTextColor(
            getColor(if (isOverlayEnabled) R.color.success_green else R.color.error_red)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${YouTubeDetectorService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        
        return enabledServices.contains(serviceName) || 
               enabledServices.contains(YouTubeDetectorService::class.java.canonicalName ?: "")
    }

    private fun canDrawOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
