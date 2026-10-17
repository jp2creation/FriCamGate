package com.nvr.camnory

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Frigate-only settings. Other provider stores are never read or written here. */
class FrigateServersActivity : AppCompatActivity() {
    private val store by lazy { FrigateServerStore(this) }
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private lateinit var lock: BiometricAppLock
    private var generation = 0
    private var editing: FrigateServer? = null
    private var inForm = false
    private var busy = false
    private var connectButton: Button? = null
    private var formStatus: TextView? = null
    private var ready = false

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
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark; isAppearanceLightNavigationBars = !Palette.dark
        }
        lock = BiometricAppLock(this) { if (!ready) showServers() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        if (state?.getBoolean("frigate-form", false) == true) {
            showForm(load()?.firstOrNull { it.id == state.getString("frigate-edit") })
            listOf("frigate-name", "frigate-server", "frigate-user").forEach { tag ->
                state.getString(tag)?.let { findViewById<android.view.ViewGroup>(android.R.id.content).findViewWithTag<EditText>(tag)?.setText(it) }
            }
        } else showServers()
    }
    private fun back() { generation++; client.dispatcher.cancelAll(); busy = false; if (inForm) showServers() else finish() }
    private fun load(): List<FrigateServer>? = runCatching { store.load() }.getOrElse { error(); null }
    private fun error() { Toast.makeText(this, R.string.frigate_storage_error, Toast.LENGTH_LONG).show() }
    private fun save(action: () -> Unit): Boolean = runCatching { action(); setResult(RESULT_OK); CameraNotifications.schedule(this) }.isSuccess.also { if (!it) error() }
    private fun page(title: String): Pair<ConnectionFormUi, LinearLayout> {
        val ui = ConnectionFormUi(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = getString(R.string.onvif_back); setOnClickListener { back() }
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(52)))
            addView(ui.text(title, 22, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(12), 0, ui.dp(20)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(ui.dp(20) + bars.left, ui.dp(12) + bars.top, ui.dp(20) + bars.right, ui.dp(20) + maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(root); ready = true
        ViewCompat.requestApplyInsets(root)
        root.post { ViewCompat.getRootWindowInsets(window.decorView)?.let { ViewCompat.dispatchApplyWindowInsets(root, it) } }
        lock.protectContent()
        return ui to body
    }
    private fun showServers() {
        inForm = false; editing = null; busy = false
        val request = ++generation
        val (ui, body) = page(getString(R.string.frigate_servers))
        val servers = load() ?: return
        body.addView(ui.text(getString(R.string.frigate_servers_detail), 14).apply { setPadding(0, 0, 0, ui.dp(14)) })
        servers.forEach { server ->
            val state = ui.text(getString(R.string.frigate_checking), 12).apply { tag = "frigate-state:${server.id}" }
            body.addView(LinearLayout(this).apply {
                tag = "frigate-server:${server.id}"; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(90)
                setPadding(0, ui.dp(12), 0, ui.dp(12)); isFocusable = true
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_source_frigate); setColorFilter(Palette.text) },
                    LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(16) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.text(server.name, 16, Palette.text))
                    addView(ui.text(server.baseUrl, 12).apply { maxLines = 2 })
                    addView(state)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
                setOnClickListener { showForm(server) }
            })
            body.addView(View(this).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
            FrigateNativeApi(server.baseUrl, client).loadNvrVersion(server.token) { result -> runOnUiThread {
                if (generation == request && state.isAttachedToWindow && !lock.locked) {
                    state.text = if (result.isSuccess) getString(R.string.connected, server.name) else getString(R.string.frigate_offline)
                    state.setTextColor(if (result.isSuccess) Palette.muted else Palette.accent)
                }
            } }
        }
        body.addView(ui.action(getString(R.string.frigate_add_server)) { showForm(null) }.apply { tag = "frigate-add"; isEnabled = servers.size < 20 })
    }
    private fun showForm(server: FrigateServer?) {
        generation++; inForm = true; editing = server
        val (ui, body) = page(server?.name ?: getString(R.string.frigate_add_server))
        val name = ui.field(body, getString(R.string.frigate_server_name), getString(R.string.frigate_server_name_hint), server?.name.orEmpty(), key = "frigate-name")
        val url = ui.field(body, getString(R.string.frigate_server), "https://frigate.example.com", server?.baseUrl.orEmpty(), key = "frigate-server")
        val user = ui.field(body, getString(R.string.username), "admin", server?.username.orEmpty(), key = "frigate-user")
        val password = ui.field(body, getString(R.string.password), getString(R.string.password_hint), secret = true, key = "frigate-password")
        val status = ui.text("", 13, Palette.accent).apply { tag = "frigate-status" }
        formStatus = status
        body.addView(status)
        lateinit var connect: Button
        connect = ui.action(getString(if (server == null) R.string.onvif_connect else R.string.save_reconnect)) {
            if (busy || lock.locked) return@action
            val address = runCatching { frigateServerUrl(url.text.toString()) }.getOrNull()
            val title = name.text.toString().trim(); val username = user.text.toString().trim()
            val secret = password.text.toString()
            if (address == null || title.isBlank() || title.length > 160 || username.isBlank() || secret.isBlank()) {
                status.text = getString(R.string.frigate_bad_fields); return@action
            }
            val saved = load() ?: return@action
            if (saved.any { it.id != server?.id && it.baseUrl == address }) { status.text = getString(R.string.frigate_duplicate); return@action }
            val request = ++generation
            busy = true; connect.isEnabled = false; status.text = getString(R.string.connecting)
            FrigateNativeApi(address, client).login(username, secret) { result -> runOnUiThread {
                if (request != generation || !status.isAttachedToWindow || isFinishing || isDestroyed || lock.locked) return@runOnUiThread
                busy = false; connect.isEnabled = true
                result.onSuccess { login ->
                    val next = FrigateServer(server?.id ?: FrigateServerStore.newId(), title, address, login.username, login.token, server?.legacy ?: false)
                    if (save { store.upsert(next) }) { password.text.clear(); showServers() }
                }.onFailure { status.text = getString(R.string.login_failed) }
            } }
        }.apply { tag = "frigate-connect" }
        connectButton = connect
        body.addView(connect)
        if (server != null) {
            body.addView(ui.action(getString(R.string.frigate_save_name), false) {
                val title = name.text.toString().trim()
                if (!busy && title.isNotEmpty() && title.length <= 160 && save { store.upsert(server.copy(name = title)) }) showServers()
            }.apply { tag = "frigate-rename" })
            body.addView(ui.action(getString(R.string.frigate_remove_server), false) {
                if (!busy) androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.frigate_remove_server)
                    .setMessage(getString(R.string.frigate_remove_confirm, server.name))
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                        if (save { store.remove(server.id) }) showServers()
                    }.show()
            }.apply { tag = "frigate-remove" })
        }
    }
    override fun onPause() {
        generation++; client.dispatcher.cancelAll(); busy = false
        connectButton?.isEnabled = true; formStatus?.text = ""
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("frigate-form", inForm)
        outState.putString("frigate-edit", editing?.id)
        if (inForm) listOf("frigate-name", "frigate-server", "frigate-user").forEach { tag ->
            outState.putString(tag, findViewById<android.view.ViewGroup>(android.R.id.content).findViewWithTag<EditText>(tag)?.text?.toString())
        }
        super.onSaveInstanceState(outState)
    }
    override fun onStop() { if (::lock.isInitialized) lock.onBackground(); super.onStop() }
    override fun onResume() { super.onResume(); if (::lock.isInitialized) lock.onForeground() }
    override fun onDestroy() { generation++; client.dispatcher.cancelAll(); super.onDestroy() }
}
