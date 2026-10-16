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
    private val textScale = Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
    private val progress: ProgressBar
    private var menu: PopupWindow? = null
    private var information: androidx.appcompat.app.AlertDialog? = null

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(8).toFloat() }
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
        state = TextView(context).apply {
            tag = "ha-camera-state"; textSize = 10f * textScale; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { setColor(0xc8000000.toInt()); cornerRadius = dp(12).toFloat() }
        }
        frame.addView(state, FrameLayout.LayoutParams(-2, maxOf(dp(22), state.paint.fontSpacing.toInt() + dp(4)), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(5); marginEnd = dp(5)
        })
        setState(R.string.ha_image)
        play = icon(R.drawable.ic_play_outline, R.string.ha_watch_live) { if (live) stopLive() else startLive() }
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
        progress = ProgressBar(context).apply { visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) }
        frame.addView(progress, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        if (!fullscreenMode) {
            play.setColorFilter(Color.WHITE)
            play.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xb3000000.toInt()) }
            frame.addView(play, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
            addView(CameraSource.HomeAssistant.iconView(context), LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
            addView(TextView(context).apply {
                text = camera.name; textSize = 12f * textScale; setTextColor(Palette.text); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = camera.name
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, camera.name)
            }, LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_more_vertical, R.string.ha_camera_details) { showMenu(it) }.apply {
                tag = "ha-camera-overflow"
                contentDescription = context.getString(R.string.camera_options, camera.name)
            }, LayoutParams(dp(48), dp(48)))
        }, LayoutParams(-1, dp(48)))
        if (fullscreenMode) addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(play); addView(sound); addView(retry)
            addView(icon(R.drawable.ic_fullscreen, R.string.camera_fullscreen) { fullscreen() })
        }, LayoutParams(-1, dp(44)))
        image.setOnClickListener { if (!live) { stopLive(); loaded = false; loadImage() } }
    }
    private fun icon(resource: Int, description: Int, click: (View) -> Unit) = ImageButton(context).apply {
        setImageResource(resource); setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = context.getString(description)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        setPadding(dp(11), dp(11), dp(11), dp(11)); layoutParams = LayoutParams(dp(44), dp(44))
        setOnClickListener { click(it) }
    }
    private fun setState(detail: Int) {
        state.text = context.getString(when (detail) {
            R.string.ha_live -> R.string.ha_status_live
            R.string.connecting -> R.string.connecting
            R.string.ha_image_failed, R.string.ha_live_failed, R.string.ha_auth_failed -> R.string.ha_unavailable
            else -> R.string.ha_status_image
        })
        state.contentDescription = context.getString(detail)
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.accent); setBounds(0, 0, dp(5), dp(5)) }
        state.setCompoundDrawablesRelative(if (detail == R.string.ha_live) dot else null, null, null, null)
        state.compoundDrawablePadding = dp(4)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(state, state.contentDescription)
    }
    private fun showMenu(anchor: View) {
        menu?.dismiss()
        val actions: List<Triple<Int, Int, () -> Unit>> = listOf(
            Triple(if (live) R.drawable.ic_pause_outline else R.drawable.ic_play_outline,
                if (live) R.string.ha_stop_live else R.string.ha_watch_live, { play.performClick() }),
            Triple(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (muted) R.string.camera_enable_audio else R.string.camera_disable_audio, { sound.performClick() }),
            Triple(R.drawable.ic_refresh, R.string.reload, { retry.performClick() }),
            Triple(R.drawable.ic_fullscreen, R.string.camera_fullscreen, fullscreen),
            Triple(R.drawable.ic_info, R.string.ha_camera_details, {
                information = androidx.appcompat.app.AlertDialog.Builder(context).setTitle(camera.name)
                    .setMessage("Home Assistant\n${camera.entityId}\n\n${state.contentDescription}")
                    .setPositiveButton(android.R.string.ok, null).show()
            })
        )
        val rows = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Palette.divider) }
        }
        actions.forEachIndexed { index, (icon, title, action) ->
            rows.addView(LinearLayout(context).apply {
                tag = "ha-camera-action-$index"
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                isFocusable = true; isEnabled = index != 1 || sound.isEnabled
                if (!isEnabled) alpha = 0.45f
                contentDescription = context.getString(title)
                addView(ImageView(context).apply { setImageResource(icon); setColorFilter(Palette.text) }, LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                addView(TextView(context).apply {
                    text = context.getString(title); textSize = 14f * textScale; setTextColor(Palette.text)
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LayoutParams(0, -2, 1f))
                setOnClickListener { menu?.dismiss(); action() }
            }, LayoutParams(-1, dp(48)))
        }
        val bounds = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
        val width = minOf(dp(260), bounds.width() - dp(24)).coerceAtLeast(dp(120))
        rows.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = minOf(rows.measuredHeight, bounds.height() - dp(24))
        val location = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val x = (location[0] + anchor.width - width).coerceIn(bounds.left + dp(12), maxOf(bounds.left + dp(12), bounds.right - width - dp(12)))
        val below = location[1] + anchor.height + dp(4)
        val y = (if (below + height <= bounds.bottom - dp(12)) below else location[1] - height - dp(4))
            .coerceIn(bounds.top + dp(12), maxOf(bounds.top + dp(12), bounds.bottom - height - dp(12)))
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(rows) }
        menu = PopupWindow(scroll, width, height, true).apply {
            isOutsideTouchable = true; elevation = dp(12).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            showAtLocation(anchor.rootView, Gravity.TOP or Gravity.LEFT, x, y)
        }
    }
    fun setActive(value: Boolean) {
        active = value
        if (!value) { menu?.dismiss(); information?.dismiss(); stopLive(); if (image.drawable == null) loaded = false } else syncVisibility()
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
                    setState(if (bitmap == null) R.string.ha_image_failed else resultState ?: R.string.ha_image)
                }.onFailure { setState(if (it is HomeAssistantAuthException) R.string.ha_auth_failed else resultState ?: R.string.ha_image_failed) }
            }
        }
    }
    fun startLive() {
        if (!visible() || live) return
        stopTransport()
        live = true; play.isEnabled = false; if (!fullscreenMode) play.visibility = View.GONE
        progress.visibility = View.VISIBLE; setState(R.string.connecting)
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
                            stopLive(); setState(R.string.ha_image_only)
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
                    frame.addView(preview, 1, FrameLayout.LayoutParams(-1, -1))
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
                    frame.addView(view, 1, FrameLayout.LayoutParams(-1, -1))
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
        play.setImageResource(R.drawable.ic_pause_outline)
        play.contentDescription = context.getString(R.string.ha_stop_live)
        progress.visibility = View.GONE; if (!fullscreenMode) play.visibility = View.GONE
        setState(R.string.ha_live)
    }
    private fun buffering() {
        setState(R.string.connecting)
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, 30_000)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        state.maxWidth = (w - dp(12)).coerceAtLeast(0)
        if (!fullscreenMode) play.visibility = if (!live && w >= dp(150)) View.VISIBLE else View.GONE
        state.visibility = if (w < dp(150) && progress.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    private fun showFailure(auth: Boolean = false) {
        stopLive()
        val status = if (auth) R.string.ha_auth_failed else R.string.ha_live_failed
        setState(status)
        if (!auth && image.drawable == null) loadImage(status)
    }
    private fun stopTransport() {
        generation++; handler.removeCallbacksAndMessages(null)
        socket?.close(); socket = null; client?.close(); client = null
        worker?.shutdownNow(); worker = null
        player?.release(); player = null
        web?.let { it.removeJavascriptInterface("CamnoryCamera"); it.destroy() }; web = null
        for (index in frame.childCount - 1 downTo 1) {
            val child = frame.getChildAt(index)
            if (child is PlayerView || child is WebView) frame.removeViewAt(index)
        }
        rtcSession = null; localCandidates.clear(); streamTypes = emptySet()
    }
    private fun stopLive() {
        stopTransport(); live = false; mute(); play.isEnabled = true; sound.isEnabled = false
        progress.visibility = View.GONE; play.visibility = View.VISIBLE
        play.setImageResource(R.drawable.ic_play_outline); play.contentDescription = context.getString(R.string.ha_watch_live)
        setState(R.string.ha_image)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(watchScroll); viewTreeObserver.addOnGlobalLayoutListener(watchLayout)
        handler.post { syncVisibility() }
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(watchScroll); viewTreeObserver.removeOnGlobalLayoutListener(watchLayout)
        menu?.dismiss(); information?.dismiss(); stopLive(); super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
