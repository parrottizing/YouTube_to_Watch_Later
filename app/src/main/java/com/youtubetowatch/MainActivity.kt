package com.youtubetowatch

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Main activity that guides users to enable required permissions
 * and provides a toggle to enable/disable the redirect.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "youtube_redirect_prefs"
        private const val PREF_ENABLED = "redirect_enabled"
    }

    private lateinit var accessibilityStatusText: TextView
    private lateinit var enableAccessibilityButton: Button
    private lateinit var redirectToggle: Switch
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize views
        accessibilityStatusText = findViewById(R.id.accessibility_status)
        enableAccessibilityButton = findViewById(R.id.btn_enable_accessibility)
        redirectToggle = findViewById(R.id.redirect_toggle)

        // Set up button click listeners
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        // Set up toggle listener
        redirectToggle.isChecked = prefs.getBoolean(PREF_ENABLED, true)
        redirectToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_ENABLED, isChecked).apply()
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
        
        // Update toggle enabled state based on accessibility service
        redirectToggle.isEnabled = isAccessibilityEnabled
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

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
