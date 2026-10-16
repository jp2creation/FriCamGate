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

/** Direct live video only. Hidden/background cards never retain a player or audio. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OnvifLiveCard(
    context: Context,
    private var camera: OnvifSavedCamera,
    private val fullscreen: () -> Unit,
    private val manage: () -> Unit,
    private val onAudible: (OnvifLiveCard) -> Unit = {}
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
    private val sound: ImageButton
    private val retry: ImageButton
    private val profiles: ImageButton
    private val watchScroll = ViewTreeObserver.OnScrollChangedListener { syncVisibility() }
    private val watchLayout = ViewTreeObserver.OnGlobalLayoutListener { syncVisibility() }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(8).toFloat() }
        clipToOutline = true
        val frame = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) * 9 / 16, MeasureSpec.EXACTLY))
            }
        }
        preview = LayoutInflater.from(context).inflate(R.layout.onvif_live_preview, frame, false) as PlayerView
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        status = TextView(context).apply {
            text = context.getString(R.string.connecting); setTextColor(Color.WHITE); textSize = 12f
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        frame.addView(status, FrameLayout.LayoutParams(-1, -1))
        addView(frame, LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            addView(TextView(context).apply {
                text = camera.name; setTextColor(Palette.text); textSize = 14f; maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LayoutParams(0, dp(44), 1f))
            addView(TextView(context).apply { text = "ONVIF"; textSize = 9f; setTextColor(Palette.muted) })
        }, LayoutParams(-1, dp(44)))
        val controls = LinearLayout(context).apply { gravity = Gravity.END }
        sound = icon(R.drawable.ic_volume_off, context.getString(R.string.playback_sound)) {
            muted = !muted
            if (!muted) onAudible(this)
            player?.volume = if (muted) 0f else 1f
            updateSound()
        }
        retry = icon(R.drawable.ic_refresh, context.getString(R.string.retry)) {
            stop(); retries = 0; failed = false; syncVisibility()
        }
        profiles = icon(R.drawable.ic_list, context.getString(R.string.onvif_profile)) { showProfiles() }.apply { isEnabled = false }
        controls.addView(sound)
        controls.addView(profiles)
        controls.addView(retry)
        controls.addView(icon(R.drawable.ic_fullscreen, context.getString(R.string.onvif_fullscreen)) { fullscreen() })
        controls.addView(icon(R.drawable.ic_settings, context.getString(R.string.settings)) { manage() })
        addView(controls, LayoutParams(-1, dp(44)))
    }

    private fun icon(resource: Int, description: String, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(resource); setColorFilter(Palette.text); setBackgroundColor(Color.TRANSPARENT)
        contentDescription = description; androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
        setPadding(dp(11), dp(11), dp(11), dp(11)); layoutParams = LayoutParams(dp(44), dp(44))
        setOnClickListener { click() }
    }
    private fun updateSound() {
        sound.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        sound.isSelected = !muted
    }
    fun mute() { muted = true; player?.volume = 0f; updateSound() }
    fun setActive(value: Boolean) {
        if (value && !active) { retries = 0; failed = false }
        active = value
        if (!value) { mute(); stop() } else syncVisibility()
    }
    private fun visible() = active && isAttachedToWindow && isShown && getGlobalVisibleRect(Rect())
    private fun syncVisibility() {
        if (!visible()) {
            if (connecting || player != null) { mute(); stop() }
        } else if (!connecting && player == null && !failed) start()
    }
    private fun start() {
        connecting = true; status.visibility = View.VISIBLE; status.text = context.getString(R.string.connecting)
        val request = ++generation
        val executor = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
        executor.submit {
            val result = runCatching {
                val connected = client.connect(camera.endpoint, camera.credentials)
                val profile = preferredOnvifProfile(connected.profiles, camera.profileToken)
                connected to client.stream(connected, profile, camera.credentials).toString()
            }
            handler.post {
                if (request != generation || !visible()) return@post
                connecting = false
                result.onSuccess { (connected, uri) ->
                    connection = connected; profiles.isEnabled = true
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
        current.volume = if (muted) 0f else 1f
        current.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== current) return
                handler.removeCallbacks(bufferingTimeout)
                if (state == Player.STATE_BUFFERING) handler.postDelayed(bufferingTimeout, 25_000)
            }
            override fun onRenderedFirstFrame() {
                if (player === current) { status.visibility = View.GONE; retries = 0 }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === current) fail(error.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
            }
        })
        current.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(uri)))
        current.prepare(); current.playWhenReady = true
    }
    private fun fail(retryable: Boolean = true) {
        stop(); status.visibility = View.VISIBLE; status.text = context.getString(R.string.onvif_connection_failed)
        failed = true
        if (retryable && retries < 3 && visible()) {
            val delay = longArrayOf(2000, 5000, 10000)[retries++]
            val request = generation
            handler.postDelayed({ if (request == generation && visible()) { failed = false; syncVisibility() } }, delay)
        }
    }
    private fun showProfiles() {
        val connected = connection ?: return
        PopupMenu(context, profiles).apply {
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
        connection = null; profiles.isEnabled = false
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
        stop(); super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        profiles.visibility = if (available < dp(240)) View.GONE else View.VISIBLE
        retry.visibility = if (available < dp(240)) View.GONE else View.VISIBLE
        sound.visibility = if (available < dp(140)) View.GONE else View.VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

internal fun preferredOnvifProfile(profiles: List<OnvifProfile>, token: String?): OnvifProfile =
    profiles.firstOrNull { it.token == token } ?: profiles.last()
