package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/** Keep offscreen cards paused and muted; navigation/background releases their sessions. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnvifLiveCard(
    context: Context,
    private var camera: OnvifSavedCamera,
    private val fullscreen: () -> Unit,
    private val manage: () -> Unit,
    private val onAudible: (OnvifLiveCard) -> Unit = {},
    private val columnMode: Boolean = false,
    private val accessories: (() -> Unit)? = null
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private val client = OnvifReadOnlyClient()
    private var generation = 0
    private var active = false
    private var connecting = false
    private var retries = 0
    private var failed = false
    private var muted = true
    private var player: ExoPlayer? = null
    private var connection: OnvifConnection? = null
    private val bufferingTimeout = Runnable { if (player != null && player?.isPlaying != true && visible()) fail() }
    private val preview: PlayerView
    private val status: TextView
    private val liveBadge: TextView
    private var menu: PopupWindow? = null
    private var overlaySound: ImageButton? = null
    private val textScale = Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
    private val watchScroll = ViewTreeObserver.OnScrollChangedListener { syncVisibility() }
    private val watchLayout = ViewTreeObserver.OnGlobalLayoutListener { syncVisibility() }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(8).toFloat() }
        clipToOutline = true
        val frame = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(if (columnMode) dp(218) else MeasureSpec.getSize(widthMeasureSpec) * 9 / 16, MeasureSpec.EXACTLY))
            }
        }
        preview = LayoutInflater.from(context).inflate(R.layout.onvif_live_preview, frame, false) as PlayerView
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        status = TextView(context).apply {
            text = context.getString(R.string.onvif_connecting); setTextColor(Color.WHITE); textSize = 12f
            tag = "onvif-camera-status"
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        frame.addView(status, FrameLayout.LayoutParams(-1, -1))
        liveBadge = TextView(context).apply {
            text = context.getString(R.string.live); textSize = 10f * textScale; setTextColor(if (columnMode) Palette.text else Color.WHITE)
            gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply { setColor(if (columnMode) Palette.overlay else 0xb3000000.toInt()); cornerRadius = dp(12).toFloat() }
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.accent); setBounds(0, 0, dp(5), dp(5)) }
            setCompoundDrawablesRelative(dot, null, null, null); compoundDrawablePadding = dp(4)
            visibility = View.GONE
            tag = "onvif-camera-live"
        }
        frame.addView(liveBadge, FrameLayout.LayoutParams(-2, dp(if (columnMode) 24 else 22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(if (columnMode) 10 else 5); rightMargin = topMargin
        })
        addView(frame, LayoutParams(-1, -2))
        if (columnMode) {
            fun action(icon: Int, description: Int, click: (View) -> Unit) = ImageButton(context).apply {
                setImageResource(icon); setColorFilter(Palette.text)
                contentDescription = context.getString(description)
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                setOnClickListener { click(it) }
            }
            overlaySound = action(R.drawable.ic_volume_off, R.string.camera_enable_audio) { toggleSound() }.apply { isEnabled = false }
            frame.addView(CameraColumnOverlay(context, camera.name, CameraSource.Onvif, listOf(
                requireNotNull(overlaySound),
                action(R.drawable.ic_fullscreen, R.string.camera_fullscreen) { fullscreen() },
                action(R.drawable.ic_settings, R.string.settings) { (accessories ?: manage)() },
                action(R.drawable.ic_list, R.string.settings) { showMenu(it) }.apply {
                    tag = "onvif-camera-overflow"; contentDescription = context.getString(R.string.camera_options, camera.name)
                }
            )), FrameLayout.LayoutParams(-1, -1))
        } else addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            tag = "onvif-camera-footer"
            setPadding(dp(8), 0, 0, 0)
            addView(CameraSource.Onvif.iconView(context), LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
            addView(TextView(context).apply {
                text = camera.name; setTextColor(Palette.text); textSize = 12f * textScale; maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = camera.name
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, camera.name)
            }, LayoutParams(0, -2, 1f))
            addView(ImageButton(context).apply {
                tag = "onvif-camera-overflow"
                setImageResource(R.drawable.ic_more_vertical); setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
                contentDescription = context.getString(R.string.camera_options, camera.name)
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                setPadding(dp(13), dp(13), dp(13), dp(13))
                setOnClickListener { showMenu(it) }
            }, LayoutParams(dp(48), dp(48)))
        }, LayoutParams(-1, dp(48)))
    }

    private fun showMenu(anchor: View) {
        menu?.dismiss()
        val actions: List<Triple<Int, Int, () -> Unit>> = listOf<Triple<Int, Int, () -> Unit>>(
            Triple(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume,
                if (muted) R.string.camera_enable_audio else R.string.camera_disable_audio, { toggleSound() }),
            Triple(R.drawable.ic_list, R.string.onvif_profile, { showProfiles(anchor) }),
            Triple(R.drawable.ic_refresh, R.string.retry, { stop(); retries = 0; failed = false; syncVisibility() }),
            Triple(R.drawable.ic_fullscreen, R.string.camera_fullscreen, fullscreen),
            Triple(R.drawable.ic_settings, R.string.settings, manage)
        ) + accessories?.let { listOf(Triple(R.drawable.ic_camera_light, R.string.accessory_title, it)) }.orEmpty()
        val rows = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(4), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Palette.divider) }
        }
        actions.forEachIndexed { index, (icon, title, action) ->
            rows.addView(LinearLayout(context).apply {
                tag = "onvif-camera-action-$index"
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                isFocusable = true
                isEnabled = when (index) { 0 -> player != null; 1 -> connection != null; else -> true }
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
    private fun toggleSound() {
        if (player == null) return
        muted = !muted
        if (!muted) onAudible(this)
        player?.volume = if (muted) 0f else 1f
        updateSound()
    }
    private fun updateSound() {
        overlaySound?.apply {
            setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
            contentDescription = context.getString(if (muted) R.string.camera_enable_audio else R.string.camera_disable_audio)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        }
    }
    fun cachedPreview(): android.graphics.Bitmap? = if (player != null && status.visibility == View.GONE)
        (preview.videoSurfaceView as? android.view.TextureView)?.takeIf { it.isAvailable }?.getBitmap(dp(72), dp(44)) else null

    fun cameraId(): String = camera.id

    fun mute() { muted = true; player?.volume = 0f; updateSound() }
    fun setActive(value: Boolean) {
        if (value && !active) { retries = 0; failed = false }
        active = value
        if (!value) { menu?.dismiss(); mute(); stop() } else syncVisibility()
    }
    private fun playbackVisibility() = onvifPlaybackVisibility(active, isAttachedToWindow, isShown, getGlobalVisibleRect(Rect()))
    private fun visible() = playbackVisibility() == OnvifPlaybackVisibility.Play
    private fun syncVisibility() {
        when (playbackVisibility()) {
            OnvifPlaybackVisibility.Release -> if (connecting || player != null) { mute(); stop() }
            OnvifPlaybackVisibility.Pause -> {
                menu?.dismiss(); mute()
                player?.playWhenReady = false
                handler.removeCallbacks(bufferingTimeout)
                liveBadge.visibility = View.GONE
            }
            OnvifPlaybackVisibility.Play -> {
                val current = player
                if (current != null) {
                    val resuming = !current.playWhenReady
                    current.playWhenReady = true
                    if (resuming && current.playbackState == Player.STATE_BUFFERING) {
                        handler.removeCallbacks(bufferingTimeout)
                        handler.postDelayed(bufferingTimeout, 25_000)
                    }
                }
                else if (!connecting && !failed) start()
            }
        }
    }
    private fun start() {
        connecting = true; status.visibility = View.VISIBLE; status.text = context.getString(R.string.onvif_connecting)
        val request = ++generation
        val executor = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
        executor.submit {
            val result = runCatching {
                val connected = client.connect(camera.endpoint, camera.credentials)
                val profile = preferredOnvifProfile(connected.profiles, camera.profileToken)
                connected to client.stream(connected, profile, camera.credentials).toString()
            }
            handler.post {
                if (request != generation || playbackVisibility() == OnvifPlaybackVisibility.Release) return@post
                connecting = false
                result.onSuccess { (connected, uri) ->
                    connection = connected
                    play(uri)
                }.onFailure { error ->
                    fail(error is java.net.SocketTimeoutException || error is java.net.ConnectException ||
                        error is java.net.UnknownHostException || (error is OnvifHttpException && error.statusCode >= 500))
                }
            }
        }
    }
    private fun play(uri: String) {
        val current = ExoPlayer.Builder(context).build()
        player = current; preview.player = current
        overlaySound?.isEnabled = true
        current.volume = if (muted) 0f else 1f
        current.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== current) return
                handler.removeCallbacks(bufferingTimeout)
                if (state == Player.STATE_BUFFERING && visible()) handler.postDelayed(bufferingTimeout, 25_000)
            }
            override fun onRenderedFirstFrame() {
                if (player === current) {
                    status.visibility = View.GONE
                    liveBadge.visibility = if (visible() && current.isPlaying) View.VISIBLE else View.GONE
                    retries = 0
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === current) liveBadge.visibility =
                    if (isPlaying && visible() && status.visibility == View.GONE) View.VISIBLE else View.GONE
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === current) fail(error.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
            }
        })
        current.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(uri)))
        current.prepare(); current.playWhenReady = visible()
    }
    private fun fail(retryable: Boolean = true) {
        stop(); status.visibility = View.VISIBLE; status.text = context.getString(R.string.onvif_connection_failed)
        failed = true
        if (retryable && retries < 3) {
            if (!visible()) { failed = false; return }
            val delay = longArrayOf(2000, 5000, 10000)[retries++]
            val request = generation
            handler.postDelayed({
                if (request == generation) {
                    failed = false
                    if (visible()) syncVisibility()
                }
            }, delay)
        }
    }
    private fun showProfiles(anchor: View) {
        val connected = connection ?: return
        PopupMenu(context, anchor).apply {
            connected.profiles.forEachIndexed { index, profile -> menu.add(0, index, index, profile.name) }
            setOnMenuItemClickListener { item ->
                val profile = connected.profiles[item.itemId]
                val updated = OnvifSavedCamera(camera.id, camera.name, camera.endpoint, camera.credentials, profile.token)
                runCatching {
                    val store = OnvifCameraStore(context)
                    store.save(store.load().map { if (it.id == camera.id) updated else it })
                }.onSuccess {
                    camera = updated; stop(); retries = 0; failed = false; syncVisibility()
                }.onFailure { Toast.makeText(context, R.string.onvif_storage_error, Toast.LENGTH_LONG).show() }
                true
            }
        }.show()
    }
    private fun stop() {
        generation++; handler.removeCallbacksAndMessages(null); client.cancel()
        worker?.shutdownNow(); worker = null
        connecting = false; preview.player = null; player?.release(); player = null
        connection = null; liveBadge.visibility = View.GONE
        overlaySound?.isEnabled = false
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(watchScroll)
        viewTreeObserver.addOnGlobalLayoutListener(watchLayout)
        syncVisibility()
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnScrollChangedListener(watchScroll)
            viewTreeObserver.removeOnGlobalLayoutListener(watchLayout)
        }
        menu?.dismiss(); stop(); super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

}

internal fun preferredOnvifProfile(profiles: List<OnvifProfile>, token: String?): OnvifProfile =
    profiles.firstOrNull { it.token == token } ?: profiles.last()
