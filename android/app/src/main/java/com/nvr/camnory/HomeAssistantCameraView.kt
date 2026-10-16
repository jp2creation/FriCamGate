package com.nvr.camnory

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Only images are fetched automatically. Starting a live session is always explicit. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HomeAssistantCameraView(
    context: Context,
    private val connection: HomeAssistantConnection,
    val camera: HomeAssistantCamera,
    private val fullscreen: () -> Unit,
    private val onAudible: (HomeAssistantCameraView) -> Unit,
    private val fullscreenMode: Boolean = false
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var worker: java.util.concurrent.ExecutorService? = null
    private var client: HomeAssistantClient? = null
    private var socket: HomeAssistantSocket? = null
    private var player: ExoPlayer? = null
    private var web: WebView? = null
    private var generation = 0
    private var active = false
    private var loaded = false
    private var live = false
    private var muted = true
    private var rtcSession: String? = null
    private val localCandidates = mutableListOf<JSONObject>()
    private var streamTypes = emptySet<String>()
    private val timeout = Runnable { if (live) showFailure() }
    private val watchScroll = ViewTreeObserver.OnScrollChangedListener { syncVisibility() }
    private val watchLayout = ViewTreeObserver.OnGlobalLayoutListener { syncVisibility() }
    private val frame: FrameLayout
    private val image: ImageView
    private val state: TextView
    private val play: ImageButton
    private val sound: ImageButton
    private val retry: ImageButton

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(8).toFloat() }
        clipToOutline = true
        frame = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, if (fullscreenMode) heightMeasureSpec else
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) * 9 / 16, MeasureSpec.EXACTLY))
            }
        }.apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(frame, if (fullscreenMode) LayoutParams(-1, 0, 1f) else LayoutParams(-1, -2))
        state = TextView(context).apply { textSize = 11f; setTextColor(Palette.muted); text = context.getString(R.string.ha_image) }
        addView(LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(10), dp(7), dp(10), 0)
            addView(TextView(context).apply { text = camera.name; textSize = 14f; setTextColor(Palette.text); maxLines = 2 })
            addView(state)
        }, LayoutParams(-1, -2))
        val controls = LinearLayout(context).apply { gravity = Gravity.END }
        play = icon(android.R.drawable.ic_media_play, R.string.ha_watch_live) { if (live) stopLive() else startLive() }
        sound = icon(R.drawable.ic_volume_off, R.string.playback_sound) {
            muted = !muted
            if (!muted) onAudible(this)
            applyMute()
        }.apply { isEnabled = false }
        retry = icon(R.drawable.ic_refresh, R.string.retry) {
            val restartLive = live
            stopLive()
            if (restartLive) startLive() else { loaded = false; loadImage() }
        }
        controls.addView(play); controls.addView(sound)
        controls.addView(retry)
        controls.addView(icon(R.drawable.ic_fullscreen, R.string.camera_fullscreen) { fullscreen() })
        addView(controls, LayoutParams(-1, dp(44)))
        image.setOnClickListener { if (!live) { stopLive(); loaded = false; loadImage() } }
    }
    private fun icon(resource: Int, description: Int, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource); setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(description)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        setPadding(dp(11), dp(11), dp(11), dp(11)); layoutParams = LayoutParams(dp(44), dp(44))
        setOnClickListener { click() }
    }
    fun setActive(value: Boolean) {
        active = value
        if (!value) { stopLive(); if (image.drawable == null) loaded = false } else syncVisibility()
    }
    fun mute() { muted = true; applyMute() }
    private fun applyMute() {
        player?.volume = if (muted) 0f else 1f
        web?.evaluateJavascript("setMuted($muted)", null)
        sound.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
    }
    private fun visible() = active && isAttachedToWindow && isShown && getGlobalVisibleRect(Rect())
    private fun syncVisibility() {
        if (!visible()) {
            if (client != null || live) stopLive()
            if (image.drawable == null) loaded = false
        } else if (!loaded && !live) loadImage()
    }
    private fun executor() = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
    private fun loadImage(resultState: Int? = null) {
        if (!visible()) return
        loaded = true
        val request = ++generation
        val api = HomeAssistantClient(connection.baseUrl, connection.token).also { client = it }
        executor().submit {
            val result = runCatching { api.image(camera.entityId) }
            handler.post {
                if (request != generation || !visible()) return@post
                result.onSuccess { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
                    val bitmap = if (bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) else null
                    if (bitmap != null) image.setImageBitmap(bitmap)
                    state.text = context.getString(if (bitmap == null) R.string.ha_image_failed else resultState ?: R.string.ha_image)
                }.onFailure { state.text = context.getString(if (it is HomeAssistantAuthException) R.string.ha_auth_failed else resultState ?: R.string.ha_image_failed) }
            }
        }
    }
    fun startLive() {
        if (!visible() || live) return
        stopTransport()
        live = true; play.isEnabled = false; state.text = context.getString(R.string.connecting)
        val request = generation
        val api = HomeAssistantClient(connection.baseUrl, connection.token).also { client = it }
        val channel = api.socket().also { socket = it }
        handler.postDelayed(timeout, 30_000)
        executor().submit {
            val result = runCatching { channel.capabilities(camera.entityId) }
            handler.post {
                if (request != generation || !visible()) return@post
                result.onSuccess { types ->
                    streamTypes = types
                    when {
                        "web_rtc" in types -> startRtc(request, channel)
                        "hls" in types -> startHls(request, channel, api)
                        else -> {
                            stopLive(); state.text = context.getString(R.string.ha_image_only)
                            if (image.drawable == null) loadImage(R.string.ha_image_only)
                        }
                    }
                }.onFailure { showFailure(it is HomeAssistantAuthException) }
            }
        }
    }
    private fun startHls(request: Int, channel: HomeAssistantSocket, api: HomeAssistantClient) {
        executor().submit {
            val result = runCatching { channel.stream(camera.entityId) }
            handler.post {
                if (request != generation || !visible()) return@post
                result.onSuccess { url ->
                    val preview = android.view.LayoutInflater.from(context).inflate(R.layout.onvif_live_preview, frame, false) as PlayerView
                    frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
                    val current = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(OkHttpDataSource.Factory(api.http))).build()
                    player = current; preview.player = current; current.volume = if (muted) 0f else 1f
                    var hasFrame = false
                    current.addListener(object : Player.Listener {
                        override fun onRenderedFirstFrame() { if (player === current) { hasFrame = true; ready() } }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (player !== current) return
                            when (state) {
                                Player.STATE_BUFFERING -> buffering()
                                Player.STATE_READY -> if (hasFrame) ready()
                                Player.STATE_ENDED -> showFailure()
                            }
                        }
                        override fun onPlayerError(error: PlaybackException) { if (player === current) showFailure() }
                    })
                    current.setMediaItem(MediaItem.fromUri(url)); current.prepare(); current.playWhenReady = true
                }.onFailure { showFailure() }
            }
        }
    }
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun startRtc(request: Int, channel: HomeAssistantSocket) {
        executor().submit {
            val result = runCatching { channel.webRtcConfig(camera.entityId) }
            handler.post {
                if (request != generation || !visible()) return@post
                result.onSuccess { config ->
                    val view = WebView(context)
                    web = view
                    view.setBackgroundColor(Color.BLACK)
                    view.settings.apply {
                        javaScriptEnabled = true; mediaPlaybackRequiresUserGesture = false
                        allowFileAccess = false; allowContentAccess = false; domStorageEnabled = false
                    }
                    view.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onPermissionRequest(request: android.webkit.PermissionRequest) { request.deny() }
                    }
                    view.addJavascriptInterface(object {
                        @JavascriptInterface fun offer(sdp: String) {
                            if (sdp.length !in 1..131072) return
                            handler.post {
                                if (request != generation || !live) return@post
                                executor().submit {
                                    runCatching { channel.offer(camera.entityId, sdp) { message ->
                                        handler.post {
                                            if (request != generation || !live) return@post
                                            if (message.optString("type") == "session") {
                                                rtcSession = message.optString("session_id").takeIf { it.length in 1..256 }
                                                rtcSession?.let { session -> localCandidates.forEach { channel.candidate(camera.entityId, session, it) } }
                                                localCandidates.clear()
                                            } else if (message.optString("type") == "error") rtcFailed(request, channel)
                                            else view.evaluateJavascript("receive(${message})", null)
                                        }
                                    } }.onFailure { handler.post { if (request == generation) rtcFailed(request, channel) } }
                                }
                            }
                        }
                        @JavascriptInterface fun candidate(json: String) {
                            if (json.length > 16384) return
                            val value = runCatching { JSONObject(json) }.getOrNull() ?: return
                            handler.post {
                                if (request != generation || !live) return@post
                                val session = rtcSession
                                if (session != null) channel.candidate(camera.entityId, session, value)
                                else if (localCandidates.size < 128) localCandidates.add(value)
                            }
                        }
                        @JavascriptInterface fun ready() { handler.post { if (request == generation) this@HomeAssistantCameraView.ready() } }
                        @JavascriptInterface fun buffering() { handler.post { if (request == generation) this@HomeAssistantCameraView.buffering() } }
                        @JavascriptInterface fun failed() { handler.post { if (request == generation) rtcFailed(request, channel) } }
                    }, "CamnoryCamera")
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                            WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        override fun onPageFinished(view: WebView, url: String) {
                            if (request == generation) view.evaluateJavascript("start($config)", null)
                        }
                    }
                    frame.addView(view, FrameLayout.LayoutParams(-1, -1))
                    view.loadDataWithBaseURL("https://camnory.invalid/", context.assets.open("ha-camera.html").bufferedReader().use { it.readText() }, "text/html", "UTF-8", null)
                }.onFailure { rtcFailed(request, channel) }
            }
        }
    }
    private fun rtcFailed(request: Int, channel: HomeAssistantSocket) {
        if (request != generation || !live) return
        web?.let { frame.removeView(it); it.removeJavascriptInterface("CamnoryCamera"); it.destroy() }; web = null
        if ("hls" in streamTypes) {
            streamTypes = streamTypes - "hls"
            client?.let { startHls(request, channel, it) } ?: showFailure()
        } else showFailure()
    }
    private fun ready() {
        handler.removeCallbacks(timeout); play.isEnabled = true; sound.isEnabled = true
        play.setImageResource(android.R.drawable.ic_media_pause)
        play.contentDescription = context.getString(R.string.ha_stop_live)
        state.text = context.getString(R.string.ha_live)
    }
    private fun buffering() {
        state.text = context.getString(R.string.connecting)
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 30_000)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        sound.visibility = if (w < dp(140)) View.GONE else View.VISIBLE
        retry.visibility = if (w < dp(190)) View.GONE else View.VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun showFailure(auth: Boolean = false) {
        stopLive()
        val status = if (auth) R.string.ha_auth_failed else R.string.ha_live_failed
        state.text = context.getString(status)
        if (!auth && image.drawable == null) loadImage(status)
    }
    private fun stopTransport() {
        generation++; handler.removeCallbacksAndMessages(null)
        socket?.close(); socket = null; client?.close(); client = null
        worker?.shutdownNow(); worker = null
        player?.release(); player = null
        web?.let { it.removeJavascriptInterface("CamnoryCamera"); it.destroy() }; web = null
        while (frame.childCount > 1) frame.removeViewAt(1)
        rtcSession = null; localCandidates.clear(); streamTypes = emptySet()
    }
    private fun stopLive() {
        stopTransport(); live = false; mute(); play.isEnabled = true; sound.isEnabled = false
        play.setImageResource(android.R.drawable.ic_media_play); play.contentDescription = context.getString(R.string.ha_watch_live)
        state.text = context.getString(R.string.ha_image)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(watchScroll); viewTreeObserver.addOnGlobalLayoutListener(watchLayout)
        handler.post { syncVisibility() }
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(watchScroll); viewTreeObserver.removeOnGlobalLayoutListener(watchLayout)
        stopLive(); super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
