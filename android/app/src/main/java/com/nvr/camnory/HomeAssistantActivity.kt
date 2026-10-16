package com.nvr.camnory

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Settings entry point; onboarding embeds the identical setup view inline. */
class HomeAssistantActivity : AppCompatActivity() {
    private lateinit var appLock: BiometricAppLock
    private var setup: HomeAssistantSetupView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark; isAppearanceLightNavigationBars = !Palette.dark
        }
        appLock = BiometricAppLock(this) { if (setup == null) render() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        render()
    }
    private fun back() { if (setup?.choosing == true) setup?.showForm() else finish() }
    private fun render() {
        val ui = ConnectionFormUi(this)
        val saved = runCatching { HomeAssistantStore(this).load() }.getOrElse {
            Toast.makeText(this, R.string.ha_storage_failed, Toast.LENGTH_LONG).show(); null
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.text)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = getString(R.string.onvif_back); setOnClickListener { back() }
                }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(52)))
                addView(ui.text("Home Assistant", 22, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        val form = HomeAssistantSetupView(this, saved, { !appLock.locked }, {
            setResult(RESULT_OK); finish()
        }, allowDisconnect = true)
        setup = form
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true; addView(form)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(ui.dp(20) + bars.left, ui.dp(12) + bars.top, ui.dp(20) + bars.right,
                ui.dp(20) + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root)
    }
    override fun setContentView(view: View) {
        super.setContentView(view)
        ViewCompat.requestApplyInsets(view)
        view.post { ViewCompat.getRootWindowInsets(window.decorView)?.let { ViewCompat.dispatchApplyWindowInsets(view, it) } }
        if (::appLock.isInitialized) appLock.protectContent()
    }
    override fun onPause() { setup?.cancel(); super.onPause() }
    override fun onStop() { setup?.cancel(); if (::appLock.isInitialized) appLock.onBackground(); super.onStop() }
    override fun onResume() { super.onResume(); if (::appLock.isInitialized) appLock.onForeground() }
    override fun onDestroy() { setup?.cancel(); super.onDestroy() }
}
