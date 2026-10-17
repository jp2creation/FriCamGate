package com.nvr.camnory

import android.content.Context
import android.content.Intent
import android.graphics.Color
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

    override fun onCreate(savedInstanceState: Bundle?) {
        TelevisionMode.adoptAppearance(this)
        super.onCreate(savedInstanceState)
        TvRemoteSupport.start(application)
        val appearance = getSharedPreferences("camgate-settings", MODE_PRIVATE)
        Appearance.loadPalette(appearance, true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (mode == "home") finish() else showHome() }
        })
    }
    override fun onResume() {
        super.onResume()
        if (intent.action == "com.nvr.camnory.TV_PAIR") startPairing() else showHome()
    }
    private fun clear() {
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
            if (intent.action == "com.nvr.camnory.TV_SETTINGS") action = intent.action
        })
        finish()
    }
    private fun text(value: String, size: Int = 16) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(Palette.text)
    }
    private fun root(title: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background)
            setPadding(dp(32), dp(18), dp(32), dp(18))
            addView(BrandWordmarkView(this@TvActivity, 180, darkBackground = true), LinearLayout.LayoutParams(dp(180), dp(60)))
            addView(text(title, 22))
        }
        setContentView(body)
        TvRemoteSupport.install(window.decorView)
        return body
    }
    private fun action(label: String, click: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; isAllCaps = false
        setTextColor(Color.WHITE); minimumHeight = dp(48)
        setBackgroundColor(Palette.surface)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        setOnClickListener { click() }
    }
    private fun startPairing() {
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
                    val qr = BarcodeEncoder().encodeBitmap(pairing.ticket.qr(), BarcodeFormat.QR_CODE, 768, 768)
                    page.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ImageView(this@TvActivity).apply { setImageBitmap(qr); contentDescription = getString(R.string.tv_pair) }, LinearLayout.LayoutParams(dp(280), dp(280)))
                        addView(LinearLayout(this@TvActivity).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0)
                            addView(text(getString(R.string.tv_pair_code, pairing.ticket.code()), 28))
                            addView(text(getString(R.string.tv_pair_instructions), 18).apply { setPadding(0, dp(20), 0, dp(20)) })
                            addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(-1, 0, 1f))
                    pairing.start()
                }.onFailure { showHome(getString(R.string.tv_network_required)) }
            }
        }
    }
    private fun proposal(bytes: ByteArray) {
        val imported = runCatching { TvConfiguration.decode(bytes) }.getOrNull()
            ?: run { bytes.fill(0); showHome(getString(R.string.tv_pair_failed)); return }
        val code = receiver?.ticket?.code().orEmpty()
        receiver?.close(); receiver = null
        pendingBytes = bytes
        mode = "proposal"
        val body = root(getString(R.string.tv_import_title))
        body.addView(text(getString(R.string.tv_pair_code, code), 24))
        body.addView(text(getString(R.string.tv_config_summary, imported.frigate.size, imported.onvif.size, imported.ha?.cameras?.size ?: 0), 20).apply { setPadding(0, dp(20), 0, dp(20)) })
        if (imported.ip.isNotEmpty()) body.addView(text("${getString(R.string.ip_cameras)} : ${imported.ip.size}", 20))
        body.addView(text(getString(R.string.tv_import_warning), 16))
        body.addView(action(getString(R.string.tv_import_accept)) {
            val request = ++generation
            mode = "saving"
            body.removeAllViews(); body.addView(text(getString(R.string.tv_sending), 20))
            worker.execute {
                val result = runCatching { TvConfigurationStore(this).save(imported) }
                bytes.fill(0)
                runOnUiThread {
                    if (request == generation) {
                        showHome(if (result.isFailure) getString(R.string.tv_pair_failed) else null)
                    }
                }
            }
        }.apply { tag = "tv-import-accept" })
        body.addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
    }
    override fun onStop() { clear(); super.onStop() }
    override fun onDestroy() { clear(); worker.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
