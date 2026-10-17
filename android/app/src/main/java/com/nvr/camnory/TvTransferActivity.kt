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
    private var selection: TransferSelectionView? = null
    private var proposal: ConfigurationTransfer? = null
    private var inventoryLoading = false
    private data class Inventory(val config: TvConfiguration, val cameras: List<TransferCamera>, val complete: Boolean,
        val catalog: CameraCatalog, val appearance: Map<String, Any>)
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
        val frame = android.widget.FrameLayout(this).apply {
            addView(body, android.widget.FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels, dp(760)), -2, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Palette.background); addView(frame) })
        body.post { TvRemoteSupport.linkForm(body) }
        return body
    }
    private fun showPage(message: String? = null) {
        pending = null; selection = null; proposal = null; inventoryLoading = false
        val ui = ConnectionFormUi(this)
        page().apply {
            addView(BrandWordmarkView(this@TvTransferActivity, 180, darkBackground = Palette.dark), LinearLayout.LayoutParams(dp(180), dp(60)))
            addView(ui.text(getString(R.string.transfer_title), 24, Palette.text))
            addView(ui.text(message ?: getString(R.string.tv_share_intro), 15).apply { setPadding(0, dp(20), 0, dp(20)) })
            addView(ui.action(getString(R.string.tv_scan), true) {
                if (!lock.locked) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)
                    .setPrompt(getString(R.string.tv_scan)).setCaptureActivity(SecureQrCaptureActivity::class.java))
            }.apply { tag = "tv-scan" })
            addView(ui.action(getString(R.string.transfer_manual), false) { if (!lock.locked) manualInvitation() }.apply { tag = "transfer-manual" })
            addView(ui.action(getString(R.string.transfer_receive), false) {
                if (!lock.locked) startActivity(android.content.Intent(this@TvTransferActivity, TvActivity::class.java).setAction("com.nvr.camnory.TV_PAIR"))
            }.apply { tag = "transfer-receive" })
            addView(ui.action(getString(R.string.onvif_back), false) { finish() })
        }
        lock.protectContent()
    }
    private fun showProposal() {
        if (lock.locked) return
        val ticket = pending ?: return
        if (proposal != null) { showReview(ticket, requireNotNull(proposal)); return }
        selection?.let { selected -> (selected.parent as? android.view.ViewGroup)?.removeView(selected); page().addView(selected); return }
        if (inventoryLoading) return
        inventoryLoading = true
        val request = ++generation
        page().addView(ConnectionFormUi(this).text(getString(R.string.loading_cameras), 20, Palette.text))
        worker.execute {
            val result = runCatching {
                val config = TvConfiguration.fromPhone(this)
                val cameras = mutableListOf<TransferCamera>()
                var complete = true
                if (config.frigate.isNotEmpty()) {
                    val api = MultiFrigateApi(config.frigate, okhttp3.OkHttpClient(), this)
                    val latch = java.util.concurrent.CountDownLatch(1)
                    api.loadDashboard("") { data ->
                        data.onSuccess { loaded -> cameras.addAll(loaded.cameras.map { TransferCamera(api.cameraKey(it.name), it.friendlyName) }) }.onFailure { complete = false }
                        complete = complete && api.unavailable.isEmpty()
                        latch.countDown()
                    }
                    check(latch.await(35, java.util.concurrent.TimeUnit.SECONDS))
                }
                cameras.addAll(config.onvif.map { TransferCamera(CameraArrangement.key("onvif", "", it.id), it.name) })
                cameras.addAll(config.ip.map { TransferCamera(CameraArrangement.key("ip", "", it.id), it.name) })
                config.ha?.let { c -> cameras.addAll(c.cameras.map { TransferCamera(CameraArrangement.key("ha", c.baseUrl, it.entityId), it.name) }) }
                Inventory(config, cameras.distinctBy { it.key }, complete, CameraCatalogStore(this).load(), TransferAppearance.read(this, true))
            }
            runOnUiThread {
                if (request != generation || isFinishing || lock.locked) return@runOnUiThread
                inventoryLoading = false
                result.onSuccess { (config, cameras, complete, catalog, appearance) ->
                    val selected = TransferSelectionView(this, config, catalog, cameras, appearance, complete) { showReview(ticket, it) }
                    selection = selected
                    page().apply {
                        addView(selected)
                        if (!complete) addView(ConnectionFormUi(this@TvTransferActivity).action(getString(R.string.retry), false) { selection = null; showProposal() })
                    }
                    TvRemoteSupport.install(window.decorView)
                    selected.findViewWithTag<View>("transfer-preset")?.requestFocus()
                }.onFailure { showPage(getString(R.string.tv_pair_failed)) }
            }
        }
    }
    private fun manualInvitation() {
        val ui = ConnectionFormUi(this)
        val body = page()
        body.addView(ui.text(getString(R.string.transfer_manual), 22, Palette.text))
        val input = ui.field(body, getString(R.string.transfer_invitation), "", key = "transfer-invitation").apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(2048))
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        val error = ui.text("", 14, Palette.accent)
        body.addView(error)
        body.addView(ui.action(getString(R.string.transfer_choose)) {
            if (!lock.locked) runCatching { TvPairingTicket.parse(input.text.toString().trim()) }.onSuccess {
                pending = it; selection = null; showProposal()
            }.onFailure { error.text = getString(R.string.tv_pair_failed) }
        }.apply { tag = "transfer-invitation-next" })
        body.addView(ui.action(getString(android.R.string.cancel), false) { showPage() })
        TvRemoteSupport.install(window.decorView)
        input.post { if (!lock.locked) input.requestFocus() }
    }
    private fun showReview(ticket: TvPairingTicket, transfer: ConfigurationTransfer) {
        proposal = transfer
        val ui = ConnectionFormUi(this)
        page().apply {
            addView(ui.text(getString(R.string.transfer_review), 24, Palette.text))
            addView(ui.text(getString(R.string.tv_pair_code, ticket.code()), 22, Palette.accent).apply { setPadding(0, dp(20), 0, dp(20)) })
            addView(ui.text(getString(R.string.transfer_summary, transfer.config?.order?.size ?: 0, transfer.catalog?.categories?.size ?: 0,
                getString(if (transfer.appearance != null) R.string.transfer_yes else R.string.transfer_no)), 18, Palette.text))
            addView(ui.text(getString(R.string.transfer_consent), 15))
            addView(ui.action(getString(R.string.tv_send), true) {
                if (!lock.locked) send(ticket, transfer)
            }.apply { tag = "tv-send" })
            addView(ui.action(getString(R.string.onvif_back), false) { proposal = null; showProposal() })
            addView(ui.action(getString(android.R.string.cancel), false) { pending = null; showPage() })
        }
        TvRemoteSupport.install(window.decorView)
    }
    private fun send(ticket: TvPairingTicket, proposal: ConfigurationTransfer) {
        val request = ++generation
        pending = null
        val transport = TvPairingSender().also { sender = it }
        page().addView(ConnectionFormUi(this).text(getString(R.string.tv_sending), 20, Palette.text))
        worker.execute {
            val result = runCatching {
                val bytes = proposal.encode()
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
    override fun onStop() { generation++; inventoryLoading = false; sender?.close(); sender = null; super.onStop() }
    override fun onDestroy() { sender?.close(); worker.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
