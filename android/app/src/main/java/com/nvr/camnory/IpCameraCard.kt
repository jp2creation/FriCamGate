package com.nvr.camnory

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.*
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class IpCameraCard(context: Context, private val camera: IpCamera,
    private val fullscreen: () -> Unit, private val manage: () -> Unit,
    private val onAudible: (IpCameraCard) -> Unit = {}, private val playerOnly: Boolean = false,
    private val fillPreview: Boolean = false, private val preload: Boolean = false,
    private val compact: Boolean = false) : FrameLayout(context) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val image = ImageView(context).apply { scaleType = if (fillPreview) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER }
    private val video = LayoutInflater.from(context).inflate(R.layout.tv_live_player, this, false) as PlayerView
    private val overlay = CameraConnectionOverlay(context)
    private var player: ExoPlayer? = null
    private var call: Call? = null
    private var worker: java.util.concurrent.ExecutorService? = null
    @Volatile private var active = false
    private var running = false
    @Volatile private var generation = 0
    private var muted = true
    private var failed = false
    private val sound = ImageButton(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setColorFilter(Palette.text); setPadding(dp(10), dp(10), dp(10), dp(10))
        isEnabled = false
        setOnClickListener { restorePlaybackSound(muted); if (!muted) onAudible(this@IpCameraCard) }
    }
    private val watch = ViewTreeObserver.OnGlobalLayoutListener { sync() }
    private val scroll = ViewTreeObserver.OnScrollChangedListener { sync() }
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val request = chain.request()
            val original = camera.url.takeIf { it.startsWith("http") }?.toHttpUrl()
            val url = request.url
            val builder = request.newBuilder().removeHeader("Authorization")
            if (original != null && url.scheme == original.scheme && url.host == original.host && url.port == original.port && camera.username.isNotEmpty())
                builder.header("Authorization", Credentials.basic(camera.username, camera.password))
            chain.proceed(builder.build())
        }.build()

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(image, LayoutParams(-1, -1))
        video.useController = false
        video.tintVideoBuffering()
        if (fillPreview) video.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        addView(video, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
        restorePlaybackSound(false)
        if (!playerOnly && compact) {
            for (view in listOf(image, video, overlay)) (view.layoutParams as LayoutParams).bottomMargin = dp(36)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Palette.card); setPadding(dp(6), 0, 0, 0)
                addView(CameraSource.Ip.iconView(context), LinearLayout.LayoutParams(dp(16), dp(16)))
                addView(TextView(context).apply {
                    text = camera.name; setTextColor(Palette.text); textSize = 12f
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = camera.name
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_settings); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setPadding(dp(9), dp(9), dp(9), dp(9)); contentDescription = context.getString(R.string.settings)
                    setOnClickListener { manage() }
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
            }, LayoutParams(-1, dp(36), Gravity.BOTTOM))
            setOnClickListener { fullscreen() }
        } else if (!playerOnly) {
            fun button(icon: Int, title: Int, action: () -> Unit) = ImageButton(context).apply {
                setImageResource(icon); setColorFilter(Palette.text); contentDescription = context.getString(title)
                CameraButton.forIcon(icon)?.let { cameraButton(it) }
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                setOnClickListener { action() }
            }
            sound.cameraButton(CameraButton.Sound)
            addView(CameraColumnOverlay(context, camera.name, CameraSource.Ip, listOf(sound,
                button(R.drawable.ic_fullscreen, R.string.camera_fullscreen, fullscreen),
                button(R.drawable.ic_settings, R.string.settings, manage))), LayoutParams(-1, -1))
            setOnClickListener { fullscreen() }
        }
        overlay.setOnClickListener { stop(); failed = false; sync() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, if (playerOnly) heightMeasureSpec else MeasureSpec.makeMeasureSpec(
            MeasureSpec.getSize(widthMeasureSpec) * 9 / 16 + if (compact) dp(36) else 0, MeasureSpec.EXACTLY))
    }
    fun setActive(value: Boolean) { if (value && !active) failed = false; active = value; if (!value) { mute(); stop() } else sync() }
    fun mute() = restorePlaybackSound(false)
    fun restorePlaybackSound(enabled: Boolean) {
        muted = !enabled; player?.volume = if (enabled) 1f else 0f
        sound.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        sound.contentDescription = context.getString(R.string.playback_sound)
    }
    fun playbackAudible() = !muted
    fun expandedSoundControl(): View = sound
    private fun visible() = isShown && getGlobalVisibleRect(Rect())
    private fun sync() {
        if (!active || !isAttachedToWindow) return
        if (!visible() && !preload) { stop(); return }
        player?.playWhenReady = visible()
        if (!running && !failed) start()
    }
    private fun start() {
        running = true; overlay.showConnection(null)
        val request = ++generation
        if (camera.url.startsWith("rtsp://")) { playVideo(request); return }
        worker = java.util.concurrent.Executors.newSingleThreadExecutor().also { executor -> executor.submit {
            try {
                do {
                    val current = client.newCall(Request.Builder().url(camera.url).build())
                    call = current
                    if (request != generation || !active) { current.cancel(); return@submit }
                    current.execute().use { response ->
                        require(response.isSuccessful)
                        val type = response.header("Content-Type").orEmpty().lowercase(java.util.Locale.ROOT)
                        if (!type.startsWith("multipart/") && !type.startsWith("image/jpeg")) {
                            handler.post { if (request == generation && active) playVideo(request, type.substringBefore(';')) }
                            return@submit
                        }
                        val source = response.body?.byteStream()?.buffered() ?: error("Empty stream")
                        do {
                            val bytes = readStreamJpeg(source) ?: break
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong() * bounds.outHeight <= 32_000_000)
                            val options = BitmapFactory.Options().apply {
                                inSampleSize = 1
                                while (bounds.outWidth / inSampleSize > 1920 || bounds.outHeight / inSampleSize > 1080) inSampleSize *= 2
                            }
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Invalid image")
                            val shown = java.util.concurrent.CountDownLatch(1)
                            handler.post {
                                if (request == generation && active) { image.setImageBitmap(bitmap); video.visibility = GONE; overlay.visibility = GONE }
                                shown.countDown()
                            }
                            if (!shown.await(2, TimeUnit.SECONDS)) return@submit
                            if (!type.startsWith("multipart/")) break
                        } while (request == generation && active && !Thread.currentThread().isInterrupted)
                        if (type.startsWith("multipart/")) error("Stream ended")
                    }
                    Thread.sleep(1000)
                } while (request == generation && active && !Thread.currentThread().isInterrupted)
            } catch (_: Exception) { handler.post { if (request == generation && active) unavailable() } }
        } }
    }
    private fun playVideo(request: Int, mime: String? = null) {
        if (request != generation || !active) return
        val current = ExoPlayer.Builder(context).build()
        player = current; video.player = current; video.visibility = VISIBLE
        current.volume = if (muted) 0f else 1f
        sound.isEnabled = true
        current.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { if (player === current) overlay.visibility = GONE }
            override fun onPlayerError(error: PlaybackException) { if (player === current) unavailable() }
        })
        val source = if (camera.url.startsWith("rtsp://")) {
            val uri = android.net.Uri.parse(camera.url)
            val authenticated = if (camera.username.isEmpty()) camera.url else uri.buildUpon().encodedAuthority(
                android.net.Uri.encode(camera.username) + ":" + android.net.Uri.encode(camera.password) + "@" + uri.encodedAuthority).build().toString()
            RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(authenticated))
        } else DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)).createMediaSource(
            MediaItem.Builder().setUri(camera.url).setMimeType(mime?.takeIf { it in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "video/mp4") }).build())
        current.setMediaSource(source); current.prepare(); current.playWhenReady = visible()
    }
    private fun unavailable() { stop(); failed = true; overlay.showUnavailable(null) }
    private fun stop() {
        generation++; call?.cancel(); call = null; worker?.shutdownNow(); worker = null
        video.player = null; player?.release(); player = null; running = false; sound.isEnabled = false
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); viewTreeObserver.addOnGlobalLayoutListener(watch); viewTreeObserver.addOnScrollChangedListener(scroll); sync()
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) { viewTreeObserver.removeOnGlobalLayoutListener(watch); viewTreeObserver.removeOnScrollChangedListener(scroll) }
        stop(); super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
