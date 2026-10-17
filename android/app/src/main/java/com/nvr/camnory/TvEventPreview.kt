package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView

/** EN1 presentation. The activity retains playback, authentication and read-state ownership. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvEventPreview(
    context: Context,
    inbox: Boolean,
    private val title: (NativeEvent) -> String,
    private val camera: (NativeEvent) -> String,
    private val thumbnail: (NativeEvent, ImageView, () -> Boolean) -> Unit,
    sound: View,
    private val onStop: () -> Unit,
    private val onPlay: (NativeEvent, PlayerView, ImageView, PlayerControlView) -> Unit,
    onFullscreen: (NativeEvent) -> Unit
) : LinearLayout(context) {
    private var selected: NativeEvent? = null
    private var pendingThumbnail: Runnable? = null
    private val heading = label("", 20, Palette.text)
    private val cameraName = label("", 14, Palette.text)
    private val time = label("", 12, Palette.muted)
    private val duration = label("", 14, Palette.muted)
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val frame = ExpandedVideoFrame(context).apply { tag = "tv-history-preview-frame" }
    private val video = (android.view.LayoutInflater.from(context).inflate(R.layout.tv_live_player, frame, false) as PlayerView).apply {
        useController = false
        setShutterBackgroundColor(Color.TRANSPARENT)
        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        findViewById<android.widget.ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.indeterminateTintList =
            android.content.res.ColorStateList.valueOf(Palette.accent)
    }
    private val controls = android.view.LayoutInflater.from(context).inflate(R.layout.event_playback_controls, this, false) as PlayerControlView
    private val hideChrome = object : Runnable {
        override fun run() {
            if (!frame.isAttachedToWindow) return
            val actions = frame.findViewWithTag<View>("tv-history-preview-actions")
            if (video.player?.isPlaying == true && !controls.hasFocus() && actions?.hasFocus() != true) {
                controls.visibility = INVISIBLE; actions?.visibility = GONE
            }
            frame.postDelayed(this, 3500)
        }
    }

    init {
        tag = "tv-history-preview"
        orientation = if (inbox) VERTICAL else HORIZONTAL
        val playback = LinearLayout(context).apply {
            orientation = VERTICAL
            if (inbox) addView(cameraName, LayoutParams(-1, dp(24)))
            frame.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
            frame.addView(video, android.widget.FrameLayout.LayoutParams(-1, -1))
            video.visibility = GONE
            frame.addPlaybackActions(sound, ImageView(context).apply {
                setImageResource(R.drawable.ic_fullscreen); setColorFilter(Palette.text)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = context.getString(R.string.camera_fullscreen)
                setOnClickListener { selected?.let(onFullscreen) }
            }, "tv-history-preview-actions")
            (frame.findViewWithTag<View>("tv-history-preview-actions").layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin = dp(48)
            frame.setTag(R.id.tv_remote_action, true)
            frame.isFocusable = true
            frame.contentDescription = context.getString(R.string.event_playback)
            frame.setOnClickListener { play() }
            frame.setOnKeyListener { _, key, event ->
                if (event.action == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_DOWN && video.player != null) {
                    revealControls(); controls.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus(); true
                } else if (event.action == KeyEvent.ACTION_DOWN && key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
                    play(); true
                } else false
            }
            addView(frame, LayoutParams(-1, 0, 1f))
            for (id in listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_ffwd)) {
                controls.findViewById<ImageView>(id)?.setColorFilter(Palette.text)
            }
            for (id in listOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)) {
                controls.findViewById<TextView>(id)?.setTextColor(Palette.muted)
            }
            controls.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).apply {
                setPlayedColor(Palette.accent); setScrubberColor(Palette.accent)
                setBufferedColor(Palette.tick); setUnplayedColor(Palette.tickMinor)
            }
            frame.addView(controls, android.widget.FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM))
            controls.setBackgroundColor(0xaa000000.toInt())
            frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    frame.removeCallbacks(hideChrome)
                    pendingThumbnail?.let { image.removeCallbacks(it) }
                }
            })
        }
        addView(playback, if (inbox) LayoutParams(-1, -1) else LayoutParams(0, -1, 2f))
        if (!inbox) addView(LinearLayout(context).apply {
            tag = "tv-event-metadata"; orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(4), dp(8))
            addView(heading, LayoutParams(-1, -2))
            addView(cameraName, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(time, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(duration, LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(ImageView(context).apply {
                tag = "tv-preview-play"; setImageResource(android.R.drawable.ic_media_play); setColorFilter(Palette.text)
                contentDescription = context.getString(R.string.event_playback)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { play() }
            }, LayoutParams(dp(48), dp(48)).apply { topMargin = dp(10) })
        }, LayoutParams(0, -1, 1f))
    }

    fun select(event: NativeEvent) {
        if (selected?.id == event.id) return
        onStop()
        resetPlayback()
        selected = event
        heading.text = title(event)
        cameraName.text = "${camera(event)} · ${event.time}"
        time.text = event.startTime?.let {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                .format(java.util.Date((it * 1000).toLong()))
        }.orEmpty()
        duration.text = event.endTime?.let { end -> event.startTime?.let { start ->
            val seconds = (end - start).toLong().coerceAtLeast(0)
            String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        } }.orEmpty()
        image.setImageBitmap(event.thumbnail)
        pendingThumbnail?.let { image.removeCallbacks(it) }
        pendingThumbnail = Runnable {
            if (selected?.id == event.id && image.isAttachedToWindow)
                thumbnail(event, image) { selected?.id == event.id && image.isAttachedToWindow }
        }.also { image.postDelayed(it, 180) }
    }

    fun play() {
        val event = selected ?: return
        val starting = video.player == null || video.player?.playerError != null
        if (starting) onPlay(event, video, image, controls)
        else video.player?.play()
        revealControls()
        if (starting) frame.requestFocus() else controls.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
        frame.removeCallbacks(hideChrome); frame.postDelayed(hideChrome, 3500)
    }

    private fun revealControls() {
        controls.visibility = VISIBLE; controls.show()
        frame.findViewWithTag<View>("tv-history-preview-actions")?.visibility = VISIBLE
    }

    fun resetPlayback() {
        video.player = null; video.visibility = GONE
        image.visibility = VISIBLE
        controls.player = null
        frame.removeCallbacks(hideChrome)
        revealControls()
    }

    private fun label(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value; textSize = size * Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
        setTextColor(color); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
