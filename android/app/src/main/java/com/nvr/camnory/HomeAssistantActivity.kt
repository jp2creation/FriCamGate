package com.nvr.camnory

import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Camera selection only. Never calls HA services or modifies server configuration. */
class HomeAssistantActivity : AppCompatActivity() {
    private lateinit var store: HomeAssistantStore
    private lateinit var appLock: BiometricAppLock
    private var saved: HomeAssistantConnection? = null
    private var client: HomeAssistantClient? = null
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0
    private var busy = false
    private var choosing = false
    private var disconnected = false
    private var dialog: AlertDialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark
            isAppearanceLightNavigationBars = !Palette.dark
        }
        store = HomeAssistantStore(this)
        appLock = BiometricAppLock(this) { if (!choosing) form() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (choosing) form() else finish() }
        })
        runCatching { saved = store.load() }.onFailure { Toast.makeText(this, R.string.ha_storage_failed, Toast.LENGTH_LONG).show() }
        form()
    }

    private fun root(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background)
        setPadding(dp(16), dp(12), dp(16), dp(16))
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
                contentDescription = getString(R.string.onvif_back)
                setOnClickListener { if (choosing) form() else finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(52)))
            addView(text("Home Assistant", 22, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
        }
        addView(header)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val system = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(dp(16) + system.left, dp(12) + system.top, dp(16) + system.right, dp(16) + maxOf(system.bottom, keyboard.bottom))
            insets
        }
    }
    private fun form() {
        cancel(); choosing = false
        val root = root()
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(fields); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fields.addView(text(getString(R.string.ha_cameras), 18, Palette.text).apply { setPadding(0, dp(20), 0, dp(10)) })
        fun field(label: Int, hint: String, value: String = "", secret: Boolean = false): EditText {
            fields.addView(text(getString(label), 12, Palette.muted).apply { setPadding(0, dp(18), 0, dp(8)) })
            return EditText(this).apply {
                setText(value); this.hint = hint; textSize = 16f; setTextColor(Palette.text); setHintTextColor(Palette.muted)
                setPadding(dp(14), 0, dp(14), 0); background = shape(Palette.surface)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else android.text.InputType.TYPE_TEXT_VARIATION_URI
                isSingleLine = true
                isSaveEnabled = !secret
                if (secret && android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                fields.addView(this, LinearLayout.LayoutParams(-1, dp(54)))
            }
        }
        val address = field(R.string.ha_server, "https://homeassistant.example.com", saved?.baseUrl.orEmpty())
        val token = field(R.string.ha_token, getString(if (saved == null) R.string.ha_token_hint else R.string.ha_keep_token), secret = true)
        fields.addView(text(getString(R.string.ha_token_help), 12, Palette.muted).apply { setPadding(0, dp(8), 0, dp(4)) })
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.connection_show_password); setTextColor(Palette.muted)
            setOnCheckedChangeListener { _, checked ->
                token.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                token.setSelection(token.length())
            }
        })
        val localHttp = CheckBox(this).apply {
            text = getString(R.string.ha_allow_local_http); setTextColor(Palette.muted); textSize = 12f
            isChecked = saved?.baseUrl?.startsWith("http://") == true
        }
        fields.addView(localHttp)
        val status = text("", 13, Palette.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val connect = action(R.string.ha_select_cameras, true) {}
        connect.setOnClickListener {
            if (appLock.locked || busy) return@setOnClickListener
            val base = runCatching { homeAssistantBaseUrl(address.text.toString()) }.getOrNull()
            if (base == null) { address.error = getString(R.string.ha_invalid_address); return@setOnClickListener }
            if (base.startsWith("http://") && !localHttp.isChecked) {
                status.text = getString(R.string.ha_allow_local_http); return@setOnClickListener
            }
            val secret = token.text.toString().trim().ifBlank { saved?.takeIf { it.baseUrl == base }?.token.orEmpty() }
            if (secret.isBlank()) { token.error = getString(R.string.ha_token_hint); return@setOnClickListener }
            val api = runCatching { HomeAssistantClient(base, secret) }.getOrNull()
            if (api == null) { token.error = getString(R.string.ha_token_hint); return@setOnClickListener }
            cancel(); busy = true; client = api; connect.isEnabled = false; status.text = getString(R.string.connecting)
            val request = generation
            val executor = Executors.newSingleThreadExecutor().also { worker = it }
            executor.submit {
                val result = runCatching { api.cameras() }
                runOnUiThread {
                    if (request != generation || isFinishing || isDestroyed || appLock.locked) return@runOnUiThread
                    busy = false; connect.isEnabled = true
                    result.onSuccess { cameras ->
                        token.text.clear()
                        selection(HomeAssistantConnection(base, secret, cameras))
                    }.onFailure { status.text = getString(if (it is HomeAssistantAuthException) R.string.ha_auth_failed else R.string.ha_connection_failed) }
                    api.close()
                }
            }
        }
        fields.addView(connect); fields.addView(status)
        if (saved != null) fields.addView(action(R.string.ha_disconnect, false) {
            dialog = AlertDialog.Builder(this).setMessage(R.string.ha_disconnect_question)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                    runCatching { store.save(null) }.onSuccess { saved = null; disconnected = true; finish() }
                        .onFailure { Toast.makeText(this, R.string.ha_storage_failed, Toast.LENGTH_LONG).show() }
                }.show()
        })
        setContentView(root)
    }

    private fun selection(connection: HomeAssistantConnection) {
        cancel(); choosing = true
        val root = root()
        root.addView(text(getString(R.string.ha_select_cameras), 18, Palette.text).apply { setPadding(0, dp(18), 0, dp(12)) })
        val selected = if (saved?.baseUrl == connection.baseUrl) saved?.cameras?.map { it.entityId }?.toMutableSet() ?: mutableSetOf() else mutableSetOf()
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(rows); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val count = text("", 12, Palette.muted)
        fun refresh() { count.text = getString(R.string.ha_selected_count, selected.size) }
        if (connection.cameras.isEmpty()) rows.addView(text(getString(R.string.ha_no_cameras), 14, Palette.muted))
        connection.cameras.forEach { camera ->
            rows.addView(CheckBox(this).apply {
                text = if (camera.available) camera.name else "${camera.name} · ${getString(R.string.ha_unavailable)}"
                setTextColor(Palette.text); textSize = 15f; minHeight = dp(56)
                isChecked = camera.entityId in selected
                contentDescription = text
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(camera.entityId) else selected.remove(camera.entityId); refresh() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        refresh(); root.addView(count)
        root.addView(action(R.string.ha_save_selection, true) {
            if (appLock.locked) return@action
            val value = HomeAssistantConnection(connection.baseUrl, connection.token, connection.cameras.filter { it.entityId in selected })
            runCatching { store.save(value) }.onSuccess { setResult(RESULT_OK); finish() }
                .onFailure { Toast.makeText(this, R.string.ha_storage_failed, Toast.LENGTH_LONG).show() }
        })
        setContentView(root)
    }
    private fun action(title: Int, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = getString(title); isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else Palette.text); background = shape(if (primary) Palette.accent else Palette.surface)
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16); bottomMargin = dp(4) }
        setOnClickListener { click() }
    }
    private fun text(value: String, size: Int, color: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color) }
    private fun shape(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
    private fun cancel() { generation++; client?.close(); client = null; worker?.shutdownNow(); worker = null; busy = false }
    override fun setContentView(view: View) {
        super.setContentView(view)
        androidx.core.view.ViewCompat.requestApplyInsets(view)
        view.post {
            androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.let {
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view, it)
            }
        }
        if (::appLock.isInitialized) appLock.protectContent()
    }
    override fun onStop() {
        val interrupted = busy
        cancel(); dialog?.dismiss(); dialog = null
        if (::appLock.isInitialized) appLock.onBackground()
        if (interrupted) choosing = false
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        if (::appLock.isInitialized) {
            appLock.onForeground()
            if (!appLock.locked && !choosing && !disconnected) form()
        }
    }
    override fun onDestroy() { cancel(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
