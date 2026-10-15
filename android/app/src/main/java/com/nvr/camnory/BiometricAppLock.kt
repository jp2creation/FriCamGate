package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

internal class BiometricAppLock(
    private val activity: AppCompatActivity,
    private val onUnlocked: () -> Unit
) {
    private val prefs = activity.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("biometricLock", false)
    var locked = enabled
        private set
    private var foreground = false
    private var authenticating = false
    private var completion: ((Boolean) -> Unit)? = null
    private var overlay: View? = null
    private val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                completion?.invoke(true)
                completion = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
                completion?.invoke(false)
                completion = null
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(activity, errString, Toast.LENGTH_LONG).show()
                }
            }
        })

    fun available(): Boolean = BiometricManager.from(activity)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    fun changeEnabled(next: Boolean, onComplete: (Boolean) -> Unit) {
        if (locked || authenticating) {
            onComplete(enabled)
            return
        }
        if (next && !available()) {
            Toast.makeText(activity, R.string.biometric_unavailable, Toast.LENGTH_LONG).show()
            onComplete(enabled)
            return
        }
        authenticate(if (next) R.string.confirm_lock else R.string.confirm_disable) { success ->
            if (success) {
                prefs.edit().putBoolean("biometricLock", next).apply()
                locked = next && !foreground
                protectContent()
            }
            onComplete(enabled)
        }
    }

    fun onForeground() {
        foreground = true
        if (!locked) return
        protectContent()
        unlock()
    }

    fun onBackground() {
        foreground = false
        if (enabled) {
            locked = true
            protectContent()
        }
    }

    fun protectContent() {
        val container = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        if (!locked) {
            for (index in 0 until container.childCount) container.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            return
        }
        for (index in 0 until container.childCount) container.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(9, 9, 10))
            isClickable = true
            isFocusable = true
            addView(ImageView(activity).apply { setImageResource(R.drawable.camnory_logo) }, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(TextView(activity).apply {
                setText(R.string.locked)
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
            addView(Button(activity).apply {
                setText(R.string.unlock)
                isAllCaps = false
                textSize = 15f
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(Palette.accent)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Palette.accent)
                }
                setPadding(dp(20), 0, dp(20), 0)
                compoundDrawablePadding = dp(10)
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_unlock_outline, 0, 0, 0)
                setOnClickListener { unlock() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)))
        }
        overlay = panel
        container.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.bringToFront()
    }

    private fun unlock() {
        if (authenticating || !foreground) return
        authenticate(R.string.unlock) { success ->
            if (success && foreground) {
                locked = false
                protectContent()
                onUnlocked()
            }
        }
    }

    private fun authenticate(title: Int, result: (Boolean) -> Unit) {
        authenticating = true
        completion = result
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(title))
            .setSubtitle(activity.getString(R.string.unlock_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
