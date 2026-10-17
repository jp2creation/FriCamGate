package com.nvr.camnory

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.net.Inet4Address
import java.util.concurrent.Executors

/** TV entry and explicit pairing only; all configured screens live in MainActivity. */
class TvActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var receiver: TvPairingReceiver? = null
    private var pendingBytes: ByteArray? = null
    private var mode = "home"
    private lateinit var lock: BiometricAppLock
    private var invitationSheet: SelectionBottomSheet? = null
    private var importedConfiguration = false

    override fun onCreate(savedInstanceState: Bundle?) {
        TelevisionMode.adoptAppearance(this)
        super.onCreate(savedInstanceState)
        if (TelevisionMode.enabled(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        lock = BiometricAppLock(this) { if (intent.action == "com.nvr.camnory.TV_PAIR") startPairing() else showHome() }
        TvRemoteSupport.start(application)
        val appearance = getSharedPreferences("camgate-settings", MODE_PRIVATE)
        Appearance.loadPalette(appearance, resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (mode == "home") finish() else showHome() }
        })
    }
    override fun onResume() {
        super.onResume()
        lock.onForeground()
        if (!lock.locked) { if (intent.action == "com.nvr.camnory.TV_PAIR") startPairing() else showHome() }
    }
    private fun clear() {
        invitationSheet?.dismiss(); invitationSheet = null
        generation++
        receiver?.close(); receiver = null
        pendingBytes?.fill(0); pendingBytes = null
    }
    private fun showHome(message: String? = null) {
        clear(); mode = "home"
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val saved = runCatching { TvConfigurationStore(this).load() }
        if (saved.isFailure) {
            setContentView(TvWelcomeView(this, getString(R.string.tv_pair_failed), ::startPairing, ::launchSharedApplication))
        } else if (saved.getOrNull() == null) {
            setContentView(TvWelcomeView(this, message, ::startPairing, ::launchSharedApplication))
        } else launchSharedApplication()
    }
    private fun launchSharedApplication() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            if (importedConfiguration) action = "com.nvr.camnory.CONFIG_IMPORTED"
            else if (intent.action == "com.nvr.camnory.TV_SETTINGS") action = intent.action
        })
        finish()
    }
    private fun text(value: String, size: Int = 16) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(Palette.text)
    }
    private fun root(title: String): LinearLayout {
        val padding = if (resources.configuration.screenWidthDp < 700) 24 else 32
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background)
            setPadding(dp(padding), dp(18), dp(padding), dp(18))
            addView(BrandWordmarkView(this@TvActivity, 180, darkBackground = Palette.dark), LinearLayout.LayoutParams(dp(180), dp(60)))
            addView(text(title, 22))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Palette.background); addView(body) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        setContentView(scroll)
        androidx.core.view.ViewCompat.requestApplyInsets(scroll)
        if (::lock.isInitialized) lock.protectContent()
        TvRemoteSupport.install(window.decorView)
        return body
    }
    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false
        setTextColor(Palette.text); minimumHeight = dp(48)
        setBackgroundColor(Palette.surface)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        setOnClickListener { click() }
    }
    private fun startPairing() {
        if (lock.locked) return
        clear(); mode = "pair"
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = root(getString(R.string.tv_pair))
        body.addView(text(getString(R.string.loading_cameras)))
        val request = generation
        worker.execute {
            val result = runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val address = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { TvPairingTicket.privateIpv4(it.hostAddress.orEmpty()) }
                    ?: error("No local address")
                TvPairingReceiver(address, { bytes -> runOnUiThread {
                    if (request != generation || mode != "pair") bytes.fill(0) else proposal(bytes)
                } }, { runOnUiThread { if (request == generation && mode == "pair") showHome(getString(R.string.tv_pair_expired)) } })
            }
            runOnUiThread {
                if (request != generation) { result.getOrNull()?.close(); return@runOnUiThread }
                result.onSuccess { pairing ->
                    receiver = pairing
                    val page = root(getString(R.string.tv_pair))
                    val wide = resources.configuration.screenWidthDp >= 700
                    val qrSize = dp(minOf(280, resources.configuration.screenWidthDp - 48).coerceAtLeast(1))
                    val qr = BarcodeEncoder().encodeBitmap(pairing.ticket.qr(), BarcodeFormat.QR_CODE, 768, 768)
                    page.addView(LinearLayout(this).apply {
                        orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                        gravity = if (wide) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
                        addView(ImageView(this@TvActivity).apply { setImageBitmap(qr); contentDescription = getString(R.string.tv_pair) }, LinearLayout.LayoutParams(qrSize, qrSize))
                        addView(LinearLayout(this@TvActivity).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(if (wide) dp(28) else 0, if (wide) 0 else dp(20), 0, 0)
                            addView(text(getString(R.string.tv_pair_code, pairing.ticket.code()), 28))
                            addView(text(getString(R.string.tv_pair_instructions), 18).apply { setPadding(0, dp(20), 0, dp(20)) })
                            addView(action(getString(R.string.transfer_manual)) {
                                val content = LinearLayout(this@TvActivity).apply {
                                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(20))
                                    addView(text(getString(R.string.transfer_code_hint), 15))
                                    addView(text(pairing.ticket.manualCode(), 14).apply { tag = "transfer-complete-code" })
                                }
                                invitationSheet?.dismiss()
                                invitationSheet = SelectionBottomSheet(this@TvActivity, getString(R.string.transfer_invitation), content).also {
                                    it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                                    it.show()
                                }
                            })
                            addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
                        }, if (!wide) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(-1, -2))
                    pairing.start()
                }.onFailure { showHome(getString(R.string.tv_network_required)) }
            }
        }
    }
    private fun proposal(bytes: ByteArray) {
        if (lock.locked) { bytes.fill(0); return }
        val imported = runCatching { ConfigurationTransfer.decode(bytes) }.getOrNull()
            ?: run { bytes.fill(0); showHome(getString(R.string.tv_pair_failed)); return }
        val code = receiver?.ticket?.code().orEmpty()
        receiver?.close(); receiver = null
        pendingBytes = bytes
        mode = "proposal"
        val body = root(getString(R.string.tv_import_title))
        body.addView(text(getString(R.string.tv_pair_code, code), 24))
        imported.config?.let { config -> body.addView(text(getString(R.string.tv_config_summary, config.frigate.size, config.onvif.size, config.ha?.cameras?.size ?: 0), 18)) }
        body.addView(text(getString(R.string.transfer_summary, imported.config?.order?.size ?: 0, imported.catalog?.categories?.size ?: 0,
            getString(if (imported.appearance != null) R.string.transfer_yes else R.string.transfer_no)), 20).apply { setPadding(0, dp(16), 0, dp(16)) })
        val replace = CheckBox(this).apply {
            tag = "transfer-replace"; text = getString(R.string.transfer_replace); textSize = 16f; setTextColor(Palette.text)
            buttonTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
        }
        body.addView(text(getString(R.string.transfer_merge), 16))
        body.addView(replace)
        body.addView(text(getString(R.string.transfer_consent), 14))
        val error = text("", 16).apply { setTextColor(Palette.accent) }
        body.addView(error)
        body.addView(action(getString(R.string.tv_import_accept)) {
            if (lock.locked) return@action
            val request = ++generation
            // Small local writes stay on the foreground gate: backgrounding cannot commit a stale proposal.
            val result = runCatching { TransferImport.apply(this, imported, replace.isChecked) }
            if (request == generation) {
                result.onSuccess { bytes.fill(0); importedConfiguration = true; showHome() }
                    .onFailure { error.text = getString(R.string.transfer_conflict) }
            }
        }.apply { tag = "tv-import-accept" })
        body.addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
    }
    override fun onPause() { lock.onBackground(); super.onPause() }
    override fun onStop() { clear(); super.onStop() }
    override fun onDestroy() { clear(); worker.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
