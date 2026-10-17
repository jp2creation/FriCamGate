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
    private enum class Page { Overview, Server, Cameras }
    private var page = Page.Overview

    override fun onCreate(state: Bundle?) {
        delegate.localNightMode = when (getSharedPreferences("camgate-settings", MODE_PRIVATE).getString("theme", "system")) {
            "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(state)
        val appearance = getSharedPreferences("camgate-settings", MODE_PRIVATE)
        Appearance.loadPalette(appearance, resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        page = runCatching { Page.valueOf(state?.getString("ha-page").orEmpty()) }.getOrDefault(Page.Overview)
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
    private fun back() {
        if (page == Page.Server && setup?.choosing == true) setup?.showForm()
        else if (page != Page.Overview && runCatching { HomeAssistantStore(this).load() != null }.getOrDefault(false)) {
            page = Page.Overview; render()
        } else finish()
    }
    private fun render() {
        setup?.hideSecrets(); setup?.cancel(); setup = null
        val ui = ConnectionFormUi(this)
        val saved = runCatching { HomeAssistantStore(this).load() }.getOrElse {
            Toast.makeText(this, R.string.ha_storage_failed, Toast.LENGTH_LONG).show(); null
        }
        if (saved == null) page = Page.Server
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
                addView(ui.text(getString(when (page) {
                    Page.Server -> R.string.ha_server
                    Page.Cameras -> R.string.ha_cameras
                    Page.Overview -> R.string.ha_overview_title
                }), 22, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (page == Page.Overview && saved != null) {
            body.addView(ui.text(getString(R.string.server), 12).apply { setPadding(0, ui.dp(16), 0, ui.dp(8)) })
            body.addView(LinearLayout(this).apply {
                tag = "ha-server-card"; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(82)
                background = ui.outline().apply { setColor(Palette.card) }
                setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); isFocusable = true
                contentDescription = "${getString(R.string.ha_server)}, ${saved.baseUrl}"
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_source_home_assistant); setColorFilter(Palette.text)
                }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(14) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.text(android.net.Uri.parse(saved.baseUrl).host ?: "Home Assistant", 15, Palette.text))
                    addView(ui.text(saved.baseUrl, 12).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(ServerRouteStatusView(this@HomeAssistantActivity, saved.baseUrl, "/api/", saved.localRoute))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted)
                }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
                setOnClickListener { if (!appLock.locked) { page = Page.Server; render() } }
            }, LinearLayout.LayoutParams(-1, -2))
            body.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(getString(R.string.cameras), 18, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageButton(context).apply {
                    tag = "ha-manage-cameras"; setImageResource(R.drawable.ic_list); setColorFilter(Palette.text)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    contentDescription = getString(R.string.ha_select_cameras)
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                    setOnClickListener { if (!appLock.locked) { page = Page.Cameras; render() } }
                }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(24) })
            body.addView(ui.text(getString(R.string.ha_selected_count, saved.cameras.size), 12).apply {
                tag = "connection-ha-saved-count"; setPadding(0, 0, 0, ui.dp(10))
            })
            if (saved.cameras.isEmpty()) {
                body.addView(ui.text(getString(R.string.ha_no_cameras), 14))
                body.addView(ui.action(getString(R.string.ha_select_cameras)) {
                    if (!appLock.locked) { page = Page.Cameras; render() }
                })
            }
            saved.cameras.forEach { camera ->
                body.addView(LinearLayout(this).apply {
                    tag = "ha-saved-camera:${camera.entityId}"; gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = ui.dp(72); setPadding(0, ui.dp(12), 0, ui.dp(12)); isFocusable = true
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_videocam); setColorFilter(Palette.muted)
                    }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(14) })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(ui.text(camera.name, 15, Palette.text).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(ui.text(getString(if (camera.available) R.string.ha_camera_available else R.string.ha_unavailable), 12))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted) },
                        LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
                    setOnClickListener { if (!appLock.locked) { page = Page.Cameras; render() } }
                })
                body.addView(View(this).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
            }
        } else {
            val returningToOverview = saved != null
            val form = HomeAssistantSetupView(this, saved, { !appLock.locked }, {
                setResult(RESULT_OK)
                if (returningToOverview) { page = Page.Overview; render() } else finish()
            }, allowDisconnect = true, connectionOnly = page == Page.Server && saved != null, registeredCameras = page == Page.Cameras)
            setup = form
            body.addView(form)
            if (page == Page.Cameras && saved?.cameras?.isEmpty() == true) form.post {
                if (form.isAttachedToWindow && !appLock.locked) form.findViewWithTag<View>("ha-refresh-cameras")?.performClick()
            }
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFillViewport = true; addView(body)
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
    override fun onPause() { setup?.hideSecrets(); setup?.cancel(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("ha-page", page.name)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { setup?.cancel(); if (::appLock.isInitialized) appLock.onBackground(); super.onStop() }
    override fun onResume() { super.onResume(); if (::appLock.isInitialized) appLock.onForeground() }
    override fun onDestroy() { setup?.cancel(); super.onDestroy() }
}
