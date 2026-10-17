package com.nvr.camnory

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

class SecureQrCaptureActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
    }
}

class TvTransferActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var sender: TvPairingSender? = null
    private var generation = 0
    private var pending: TvPairingTicket? = null
    private lateinit var lock: BiometricAppLock
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            pending = runCatching { TvPairingTicket.parse(result.contents) }.getOrNull()
            if (pending == null) showPage(getString(R.string.tv_pair_failed))
            else if (!lock.locked) showProposal()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        lock = BiometricAppLock(this) { if (pending != null) showProposal() }
        showPage()
    }
    private fun page(): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background)
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.background); addView(body) })
        return body
    }
    private fun showPage(message: String? = null) {
        val ui = ConnectionFormUi(this)
        page().apply {
            addView(ui.text(getString(R.string.tv_share_title), 24, Palette.text))
            addView(ui.text(message ?: getString(R.string.tv_share_intro), 15).apply { setPadding(0, dp(20), 0, dp(20)) })
            addView(ui.action(getString(R.string.tv_scan), true) {
                if (!lock.locked) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
                    .setPrompt(getString(R.string.tv_scan)).setCaptureActivity(SecureQrCaptureActivity::class.java))
            }.apply { tag = "tv-scan" })
            addView(ui.action(getString(R.string.onvif_back), false) { finish() })
        }
        lock.protectContent()
    }
    private fun showProposal() {
        if (lock.locked) return
        val ticket = pending ?: return
        val ui = ConnectionFormUi(this)
        page().apply {
            addView(ui.text(getString(R.string.tv_share_title), 24, Palette.text))
            addView(ui.text(getString(R.string.tv_pair_code, ticket.code()), 22, Palette.accent).apply { setPadding(0, dp(20), 0, dp(20)) })
            addView(ui.text(getString(R.string.tv_share_consent), 15))
            addView(ui.action(getString(R.string.tv_send), true) {
                if (!lock.locked) send(ticket)
            }.apply { tag = "tv-send" })
            addView(ui.action(getString(android.R.string.cancel), false) { pending = null; showPage() })
        }
    }
    private fun send(ticket: TvPairingTicket) {
        val request = ++generation
        pending = null
        val transport = TvPairingSender().also { sender = it }
        page().addView(ConnectionFormUi(this).text(getString(R.string.tv_sending), 20, Palette.text))
        worker.execute {
            val result = runCatching {
                val bytes = TvConfiguration.fromPhone(this).encode()
                try { transport.send(ticket, bytes) } finally { bytes.fill(0) }
            }
            transport.close()
            runOnUiThread {
                if (request == generation && !isFinishing && !lock.locked) {
                    sender = null
                    showPage(if (result.isSuccess) getString(R.string.tv_pair_code, ticket.code()) + "\n\n" + getString(R.string.tv_sent)
                        else getString(R.string.tv_pair_failed))
                }
            }
        }
    }
    override fun onResume() {
        super.onResume(); lock.onForeground()
        if (!lock.locked && pending != null) showProposal()
    }
    override fun onPause() { lock.onBackground(); super.onPause() }
    override fun onStop() { generation++; sender?.close(); sender = null; super.onStop() }
    override fun onDestroy() { sender?.close(); worker.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
