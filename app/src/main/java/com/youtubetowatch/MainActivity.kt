package com.youtubetowatch

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Main activity with modern minimalist UI.
 * Guides users to enable required permissions and toggle redirect.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "youtube_redirect_prefs"
        private const val PREF_ENABLED = "redirect_enabled"
    }

    private lateinit var accessibilityStatusText: TextView
    private lateinit var enableAccessibilityButton: View
    private lateinit var redirectToggle: SwitchMaterial
    private lateinit var statusMessage: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make status bar transparent for immersive design
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Initialize views
        accessibilityStatusText = findViewById(R.id.accessibility_status)
        enableAccessibilityButton = findViewById(R.id.btn_enable_accessibility)
        redirectToggle = findViewById(R.id.redirect_toggle)
        statusMessage = findViewById(R.id.status_message)

        // Set up button click listener
        enableAccessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        // Set up toggle listener
        redirectToggle.isChecked = prefs.getBoolean(PREF_ENABLED, true)
        redirectToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_ENABLED, isChecked).apply()
            updateStatusMessage()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusIndicators()
        updateStatusMessage()
    }

    private fun updateStatusIndicators() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        
        accessibilityStatusText.text = if (isAccessibilityEnabled) {
            getString(R.string.status_enabled)
        } else {
            getString(R.string.status_disabled)
        }
        accessibilityStatusText.setTextColor(
            getColor(if (isAccessibilityEnabled) R.color.status_success else R.color.status_error)
        )
        
        // Update toggle enabled state
        redirectToggle.isEnabled = isAccessibilityEnabled
        redirectToggle.alpha = if (isAccessibilityEnabled) 1.0f else 0.5f
    }

    private fun updateStatusMessage() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val isRedirectEnabled = prefs.getBoolean(PREF_ENABLED, true)
        
        statusMessage.text = when {
            !isAccessibilityEnabled -> "Enable the accessibility service above to start."
            isRedirectEnabled -> "Active ✓ Opening YouTube will redirect to Watch Later."
            else -> "Redirect is paused. Toggle on to enable."
        }
        
        statusMessage.setTextColor(
            getColor(
                if (isAccessibilityEnabled && isRedirectEnabled) 
                    R.color.status_success 
                else 
                    R.color.text_tertiary
            )
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

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
