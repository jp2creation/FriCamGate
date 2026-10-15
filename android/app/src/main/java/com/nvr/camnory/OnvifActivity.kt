package com.nvr.camnory

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import java.util.UUID
import java.util.concurrent.Executors

/** Camera-only screen: no Frigate API, camera configuration or alarm controls. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnvifActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OnvifReadOnlyClient()
    private lateinit var store: OnvifCameraStore
    private var saved = emptyList<OnvifSavedCamera>()
    private var player: ExoPlayer? = null
    private var generation = 0
    private var resumedPlayback = false
    private var muted = true
    private var playingCamera: OnvifSavedCamera? = null
    private lateinit var appLock: BiometricAppLock
    private var job: java.util.concurrent.Future<*>? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        appLock = BiometricAppLock(this) { if (resumedPlayback) player?.play() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (playingCamera != null) list() else finish() }
        })
        store = OnvifCameraStore(this)
        try { saved = store.load(); list() } catch (_: Exception) {
            Toast.makeText(this, R.string.onvif_storage_error, Toast.LENGTH_LONG).show(); finish()
        }
    }

    private fun root(title: String, back: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.background)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val bar = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(R.drawable.ic_arrow_back, getString(R.string.onvif_back)) { back() })
                addView(text(title, 20), LinearLayout.LayoutParams(0, dp(52), 1f))
            }
            addView(bar)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val system = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                view.setPadding(dp(12) + system.left, dp(12) + system.top, dp(12) + system.right, dp(12) + system.bottom)
                insets
            }
        }
    }

    private fun list() {
        generation++; job?.cancel(true); client.cancel(); player?.release(); player = null; playingCamera = null
        val root = root(getString(R.string.onvif_cameras)) { finish() }
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saved.forEach { camera ->
            rows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(camera.name, 17).apply { setOnClickListener { connect(camera) } }, LinearLayout.LayoutParams(0, dp(68), 1f))
                addView(icon(R.drawable.ic_delete, getString(R.string.onvif_delete)) {
                    AlertDialog.Builder(this@OnvifActivity).setMessage(getString(R.string.onvif_delete_question, camera.name))
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                            persist(saved.filterNot { it.id == camera.id })
                        }.show()
                })
            })
        }
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply { text = getString(R.string.onvif_add); setOnClickListener { add() } })
        setContentView(root)
    }

    private fun persist(cameras: List<OnvifSavedCamera>) {
        try { store.save(cameras); saved = cameras; list() } catch (_: Exception) { error(R.string.onvif_storage_error) }
    }

    private fun add() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        fun input(hint: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint; isSingleLine = true
            inputType = if (password) 129 else android.text.InputType.TYPE_CLASS_TEXT
            form.addView(this)
        }
        val name = input(getString(R.string.onvif_name))
        val address = input(getString(R.string.onvif_address))
        val username = input(getString(R.string.username))
        val password = input(getString(R.string.password), true)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.onvif_add).setView(form)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.onvif_connect, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val endpoint = runCatching { onvifEndpoint(address.text.toString()).toASCIIString() }.getOrNull()
                if (endpoint == null || name.text.isBlank()) { address.error = getString(R.string.onvif_invalid_address); return@setOnClickListener }
                val camera = OnvifSavedCamera(UUID.randomUUID().toString(), name.text.toString().trim(), endpoint,
                    OnvifCredentials(username.text.toString(), password.text.toString()))
                password.text.clear(); dialog.dismiss(); connect(camera, adding = true)
            }
        }
        this.dialog = dialog
        dialog.show()
    }

    private fun connect(camera: OnvifSavedCamera, adding: Boolean = false) {
        job?.cancel(true); client.cancel()
        player?.release(); player = null; playingCamera = null
        val root = root(camera.name) { list() }
        root.addView(ProgressBar(this))
        setContentView(root)
        val request = ++generation
        job?.cancel(true)
        job = worker.submit {
            val result = runCatching { client.connect(camera.endpoint, camera.credentials) }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { connection ->
                    if (adding) {
                        try { store.save(saved + camera); saved = saved + camera } catch (_: Exception) { error(R.string.onvif_storage_error); list(); return@onSuccess }
                    }
                    choose(camera, connection)
                }.onFailure { error(R.string.onvif_connection_failed); list() }
            }
        }
    }

    private fun choose(camera: OnvifSavedCamera, connection: OnvifConnection) {
        dialog = AlertDialog.Builder(this).setTitle(R.string.onvif_profile)
            .setItems(connection.profiles.map { "${it.name} · ${it.encoding}" }.toTypedArray()) { _, index ->
                val request = ++generation
                job = worker.submit {
                    val result = runCatching { client.stream(connection, connection.profiles[index], camera.credentials) }
                    runOnUiThread {
                        if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                        result.onSuccess { play(camera, it.toString()) }.onFailure { error(R.string.onvif_connection_failed); list() }
                    }
                }
            }.setOnCancelListener { list() }.show()
    }

    private fun play(camera: OnvifSavedCamera, uri: String) {
        player?.release()
        playingCamera = camera
        val root = root(camera.name) { list() }
        val view = PlayerView(this).apply { useController = false; keepScreenOn = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val sound = icon(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume, getString(R.string.playback_sound)) {
            muted = !muted; player?.volume = if (muted) 0f else 1f
            (it as ImageButton).setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        }
        root.addView(LinearLayout(this).apply {
            addView(sound)
            addView(icon(R.drawable.ic_refresh, getString(R.string.retry)) { connect(camera) })
        })
        setContentView(root)
        val current = ExoPlayer.Builder(this).build()
        player = current; view.player = current
        current.volume = if (muted) 0f else 1f
        current.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player === current) error(R.string.onvif_playback_failed)
            }
        })
        current.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(uri)))
        current.prepare(); current.playWhenReady = true
    }

    private fun text(value: String, size: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(Palette.text); gravity = Gravity.CENTER_VERTICAL }
    private fun icon(resource: Int, description: String, click: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(resource); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
        contentDescription = description; layoutParams = LinearLayout.LayoutParams(dp(48), dp(52)); setOnClickListener(click)
    }
    private fun error(message: Int) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun setContentView(view: View) {
        super.setContentView(view)
        androidx.core.view.ViewCompat.requestApplyInsets(view)
        if (::appLock.isInitialized) appLock.protectContent()
    }
    override fun onStop() {
        resumedPlayback = player?.playWhenReady == true; player?.pause()
        generation++; job?.cancel(true); client.cancel(); dialog?.dismiss(); dialog = null
        if (::appLock.isInitialized) appLock.onBackground()
        super.onStop()
    }
    override fun onStart() {
        super.onStart()
        if (::appLock.isInitialized) {
            if (player == null && ::store.isInitialized) list()
            appLock.onForeground()
            if (resumedPlayback && !appLock.locked) player?.play()
        }
    }
    override fun onDestroy() { generation++; client.cancel(); worker.shutdownNow(); player?.release(); super.onDestroy() }
}
