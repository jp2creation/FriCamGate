package com.nvr.camnory

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** First TV surface: remote navigation, local pairing and source-owned playback. No camera mutations. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private var generation = 0
    private var receiver: TvPairingReceiver? = null
    private var pendingBytes: ByteArray? = null
    private var configuration: TvConfiguration? = null
    private var api: MultiFrigateApi? = null
    private val onvifCards = mutableListOf<OnvifLiveCard>()
    private val haCards = mutableListOf<HomeAssistantCameraView>()
    private var stream: WebSocket? = null
    private var mediaHttp: OkHttpClient? = null
    private var player: ExoPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mode = "home"
    private var homePage = 0
    private val manualSetup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val imported = runCatching {
            TvConfiguration.fromPhone(this).takeIf { it.frigate.isNotEmpty() || it.onvif.isNotEmpty() || it.ha != null }
                ?.also { TvConfigurationStore(this).save(it) }
        }
        if (imported.isSuccess) {
            configuration = imported.getOrNull() ?: configuration
            showHome()
        } else showHome(getString(R.string.tv_pair_failed))
    }
    private data class Item(val key: String, val name: String, val source: CameraSource,
        val frigate: NativeCamera? = null, val onvif: OnvifSavedCamera? = null, val ha: HomeAssistantCamera? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configuration = runCatching { TvConfigurationStore(this).load() }.getOrNull()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (mode == "home") finish() else showHome() }
        })
    }
    override fun onResume() {
        super.onResume()
        configuration = runCatching { TvConfigurationStore(this).load() }.getOrNull()
        showHome()
    }
    private fun clear() {
        generation++
        receiver?.close(); receiver = null
        pendingBytes?.fill(0); pendingBytes = null
        onvifCards.forEach { it.setActive(false) }; onvifCards.clear()
        haCards.forEach { it.setActive(false) }; haCards.clear()
        stream?.cancel(); stream = null
        player?.release(); player = null
        mediaHttp?.dispatcher?.cancelAll(); mediaHttp?.connectionPool?.evictAll(); mediaHttp = null
        handler.removeCallbacksAndMessages(null)
        http.dispatcher.cancelAll()
    }
    private fun root(title: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setPadding(dp(32), dp(18), dp(32), dp(18))
        }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(BrandWordmarkView(this@TvActivity, 180, darkBackground = true), LinearLayout.LayoutParams(dp(180), dp(60)))
            addView(text(title, 22), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(24) })
        })
        setContentView(body)
        return body
    }
    private fun text(value: String, size: Int = 16) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(Color.WHITE)
    }
    private fun focus(view: View) {
        view.isFocusable = true; view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { target, focused ->
            target.background = GradientDrawable().apply {
                setColor(0xff17171b.toInt()); cornerRadius = dp(6).toFloat()
                setStroke(dp(if (focused) 3 else 1), if (focused) Palette.accent else 0xff3a3a40.toInt())
            }
        }
        view.background = GradientDrawable().apply { setColor(0xff17171b.toInt()); cornerRadius = dp(6).toFloat() }
    }
    private fun action(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE)
        isAllCaps = false; minimumHeight = dp(48)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        focus(this); setOnClickListener { click() }
    }
    private fun iconAction(icon: Int, label: String, click: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); setColorFilter(Color.WHITE); contentDescription = label
        setPadding(dp(12), dp(12), dp(12), dp(12)); focus(this); setOnClickListener { click() }
    }
    private fun showHome(message: String? = null) {
        clear(); mode = "home"
        api = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val config = configuration
        if (config == null) {
            setContentView(TvWelcomeView(this, message, ::startPairing, ::showManualSources))
            return
        }
        val body = root(getString(R.string.cameras))
        val header = body.getChildAt(0) as LinearLayout
        header.addView(iconAction(R.drawable.ic_refresh, getString(R.string.reload)) { showHome() }, LinearLayout.LayoutParams(dp(52), dp(48)))
        header.addView(iconAction(R.drawable.ic_settings, getString(R.string.tv_settings)) { showTvSettings() }, LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginStart = dp(12) })
        message?.let { body.addView(text(it)) }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val items = config.onvif.map { Item(CameraArrangement.key("onvif", "", it.id), it.name, CameraSource.Onvif, onvif = it) } +
            config.ha?.cameras.orEmpty().map { Item(CameraArrangement.key("ha", config.ha!!.baseUrl, it.entityId), it.name, CameraSource.HomeAssistant, ha = it) }
        populate(grid, items)
        if (config.frigate.isNotEmpty()) {
            val transport = MultiFrigateApi(config.frigate, http, this).also { api = it }
            val request = generation
            transport.loadDashboard(config.frigate.first().token) { result -> runOnUiThread {
                if (request != generation || mode != "home") return@runOnUiThread
                result.onSuccess { data ->
                    populate(grid, data.cameras.filter { it.enabled }.map { Item(transport.cameraKey(it.name), it.friendlyName, CameraSource.Frigate, frigate = it) } + items)
                }.onFailure { body.addView(text(getString(R.string.frigate_unavailable))) }
            } }
        }
    }
    private fun showTvSettings() {
        clear(); mode = "settings"
        val body = root(getString(R.string.tv_settings))
        body.addView(action(getString(R.string.tv_pair)) { startPairing() })
        body.addView(action(getString(R.string.tv_setup_manual)) { showManualSources() })
        body.addView(action(getString(R.string.onvif_back)) { showHome() })
        (body.getChildAt(1) as View).requestFocus()
    }
    private fun showManualSources() {
        clear(); mode = "manual"
        val body = root(getString(R.string.tv_setup_manual))
        body.gravity = Gravity.CENTER_HORIZONTAL
        listOf("Frigate" to FrigateServersActivity::class.java, "ONVIF" to OnvifActivity::class.java,
            "Home Assistant" to HomeAssistantActivity::class.java).forEachIndexed { index, (label, activity) ->
            body.addView(action(label) { manualSetup.launch(Intent(this, activity)) }.apply {
                tag = "tv-manual-source-$index"
                if (index == 0) requestFocus()
            }, LinearLayout.LayoutParams(dp(380), dp(54)).apply { topMargin = dp(14) })
        }
        body.addView(action(getString(R.string.onvif_back)) { showHome() },
            LinearLayout.LayoutParams(dp(380), dp(50)).apply { topMargin = dp(14) })
    }
    private fun populate(grid: LinearLayout, all: List<Item>) {
        onvifCards.forEach { it.setActive(false) }; onvifCards.clear()
        haCards.forEach { it.setActive(false) }; haCards.clear()
        grid.removeAllViews()
        val config = configuration ?: return
        val byKey = all.associateBy { it.key }
        val items = CameraArrangement.ordered(all.map { it.key }, config.order).mapNotNull { byKey[it] }.filter { it.key !in config.hidden }
        val pages = maxOf(1, (items.size + 3) / 4)
        homePage = homePage.coerceIn(0, pages - 1)
        val columns = 2
        items.drop(homePage * 4).take(4).chunked(columns).forEach { group ->
            grid.addView(LinearLayout(this).apply {
                group.forEach { item ->
                    val card = FrameLayout(this@TvActivity).apply {
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        contentDescription = item.name
                        tag = "tv-camera:${item.key}"
                        focus(this); setOnClickListener { openCamera(item) }
                    }
                    val video = FrameLayout(this@TvActivity)
                    card.addView(video, FrameLayout.LayoutParams(-1, -1))
                    addSource(video, item, false)
                    card.addView(text(item.name, 17).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(10), dp(6), dp(10), dp(6))
                        setBackgroundColor(0xb0000000.toInt())
                    }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                    addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(5), dp(6), dp(5), dp(6)) })
                }
                repeat(columns - group.size) { addView(View(this@TvActivity), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        if (items.size > 4) grid.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(action("‹") { homePage--; populate(grid, all) }.apply { isEnabled = homePage > 0; contentDescription = "Previous page" })
            addView(text("${homePage + 1} / $pages").apply { setPadding(dp(24), 0, dp(24), 0) })
            addView(action("›") { homePage++; populate(grid, all) }.apply { isEnabled = homePage + 1 < pages; contentDescription = "Next page" })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        if (items.isEmpty()) grid.addView(text(getString(R.string.no_cameras)))
        onvifCards.forEach { it.setActive(true) }; haCards.forEach { it.setActive(true) }
        grid.post { grid.findViewWithTag<View>("tv-camera:${items.getOrNull(homePage * 4)?.key}")?.requestFocus() }
    }
    private fun openCamera(item: Item) {
        clear(); mode = "live"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(frame)
        addSource(frame, item, true)
        frame.addView(action(getString(R.string.onvif_back)) { showHome() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(18), dp(18), 0, 0)
        })
        frame.addView(text(item.name, 20).apply { setPadding(dp(18), dp(8), dp(18), dp(8)); setBackgroundColor(0xb0000000.toInt()) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        frame.getChildAt(frame.childCount - 2).requestFocus()
    }
    private fun addSource(frame: FrameLayout, item: Item, live: Boolean) {
        val request = generation
        when {
            item.onvif != null -> {
                val card = OnvifLiveCard(this, item.onvif, {}, {}, playerOnly = true)
                frame.addView(card, FrameLayout.LayoutParams(-1, -1)); onvifCards.add(card)
                card.doOnLayout { if (request == generation) card.setActive(true) }
                if (live) addSound(frame, card.expandedSoundControl())
            }
            item.ha != null -> {
                val config = configuration?.ha ?: return
                val card = HomeAssistantCameraView(this, config, item.ha, {}, {}, fullscreenMode = true, playerOnly = true)
                frame.addView(card, FrameLayout.LayoutParams(-1, -1)); haCards.add(card)
                card.doOnLayout { if (request == generation) { card.setActive(true); if (live) card.startLive() } }
                if (live) addSound(frame, card.expandedSoundControl())
            }
            else -> {
                val camera = item.frigate ?: return
                val transport = api ?: return
                val server = transport.serverFor(camera.name)
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                val quality = text(getString(R.string.tv_preview_quality), 14).apply {
                    tag = "tv-preview-quality"; setBackgroundColor(0xb0000000.toInt())
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                }
                if (live) frame.addView(quality, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(18), dp(18), 0) })
                fun startMjpeg() { stream = transport.openMjpegStream(server.token, camera.streamName,
                    { bitmap -> runOnUiThread { if (request == generation) image.setImageBitmap(bitmap) } },
                    { runOnUiThread { if (request == generation && player?.isPlaying != true) frame.addView(text(getString(R.string.frigate_unavailable)), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) } }) }
                if (live) {
                    startMjpeg()
                    startFrigateHls(frame, server, transport.nativeName(camera.tvStreamName), request, ::startMjpeg)
                } else transport.loadBitmap(server.token, transport.latestFrameUrl(camera.name).toHttpUrl().newBuilder().setQueryParameter("h", "720").build().toString()) { bitmap ->
                    runOnUiThread { if (request == generation && image.isAttachedToWindow) image.setImageBitmap(bitmap) }
                }
            }
        }
    }
    private fun startFrigateHls(frame: FrameLayout, server: FrigateServer, name: String, request: Int, fallback: () -> Unit) {
        val base = server.baseUrl.toHttpUrl()
        // Check every playlist and segment request before attaching the server credential.
        val client = http.newBuilder().dispatcher(Dispatcher()).addInterceptor { chain ->
            val url = chain.request().url
            val prefix = base.encodedPath.trimEnd('/')
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                !(url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/"))) throw java.io.IOException("Invalid media origin")
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${server.token}").build())
        }.apply {
            server.localRoute?.let { addInterceptor(LocalRouteInterceptor.create(this@TvActivity, server.baseUrl, it, server.token, "/api/version")) }
        }.build().also { mediaHttp = it }
        val timeout = Runnable { client.dispatcher.cancelAll() }
        var resolved = false
        handler.postDelayed(timeout, 10_000)
        FrigateNativeApi(server.baseUrl, client, server.localRoute).requestHlsUrl(server.token, name) { result -> runOnUiThread {
            if (request != generation) return@runOnUiThread
            if (resolved) return@runOnUiThread
            resolved = true
            handler.removeCallbacks(timeout)
            val url = result.getOrNull() ?: return@runOnUiThread
            val view = PlayerView(this).apply {
                useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); tintVideoBuffering()
            }
            frame.addView(view, 1, FrameLayout.LayoutParams(-1, -1))
            val sound = ImageButton(this).apply {
                setImageResource(R.drawable.ic_volume_off); setColorFilter(Color.WHITE)
                contentDescription = getString(R.string.playback_sound)
                isEnabled = false
            }
            addSound(frame, sound)
            val playback = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))).build()
            player = playback; view.player = playback; playback.volume = 0f
            sound.setOnClickListener {
                val audible = playback.volume == 0f
                playback.volume = if (audible) 1f else 0f
                sound.setImageResource(if (audible) R.drawable.ic_volume else R.drawable.ic_volume_off)
            }
            playback.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (request == generation && state == Player.STATE_READY) {
                        sound.isEnabled = true; stream?.cancel(); stream = null
                        frame.findViewWithTag<View>("tv-preview-quality")?.visibility = View.GONE
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (request == generation) {
                        view.visibility = View.GONE; sound.visibility = View.GONE
                        frame.findViewWithTag<View>("tv-preview-quality")?.visibility = View.VISIBLE
                        playback.release(); if (player === playback) player = null
                        if (stream == null) fallback()
                    }
                }
            })
            playback.setMediaItem(MediaItem.fromUri(url)); playback.prepare(); playback.playWhenReady = true
        } }
    }
    private fun addSound(frame: FrameLayout, sound: View) {
        focus(sound)
        frame.addView(sound, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(18)) })
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
                        if (result.isSuccess) configuration = imported
                        showHome(if (result.isFailure) getString(R.string.tv_pair_failed) else null)
                    }
                }
            }
        }.apply { tag = "tv-import-accept" })
        body.addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
    }
    override fun onStop() { clear(); super.onStop() }
    override fun onDestroy() { clear(); worker.shutdownNow(); http.connectionPool.evictAll(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
