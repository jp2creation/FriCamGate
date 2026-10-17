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

/** Connection and playback screen; physical-device settings open in their dedicated activity. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnvifActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OnvifReadOnlyClient()
    private val discovery = OnvifDiscovery()
    private var discoveryLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var pendingDiscovery = false
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
    private var stopped = false
    private var pendingCamera: OnvifSavedCamera? = null
    private var pendingEdit: OnvifSavedCamera? = null
    private var editing = false
    private var passwordInput: EditText? = null
    private val fullscreen get() = intent.getBooleanExtra("fullscreen", false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val appearance = getSharedPreferences("camgate-settings", MODE_PRIVATE)
        Appearance.loadPalette(appearance, resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark; isAppearanceLightNavigationBars = !Palette.dark
        }
        appLock = BiometricAppLock(this) { resumePendingCamera() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) finish() else if (editing || playingCamera != null) list() else finish() }
        })
        store = OnvifCameraStore(this)
        try {
            saved = store.load()
            val initialCamera = saved.firstOrNull { it.id == intent.getStringExtra("cameraId") }
            when {
                intent.getBooleanExtra("discover", false) -> { pendingDiscovery = true; list() }
                initialCamera == null -> list()
                intent.getBooleanExtra("manage", false) -> { pendingEdit = initialCamera; list() }
                else -> connect(initialCamera)
            }
        } catch (_: Exception) {
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
                val keyboard = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                view.setPadding(dp(12) + system.left, dp(12) + system.top, dp(12) + system.right,
                    dp(12) + maxOf(system.bottom, keyboard.bottom))
                insets
            }
        }
    }

    private fun list() {
        passwordInput?.text?.clear(); passwordInput = null; editing = false
        stopDiscovery()
        generation++; job?.cancel(true); client.cancel(); player?.release(); player = null; playingCamera = null
        val root = root(getString(R.string.onvif_cameras)) { finish() }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saved.forEach { camera ->
            rows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(camera.name, 17).apply { setOnClickListener { connect(camera) } }, LinearLayout.LayoutParams(0, dp(68), 1f))
                addView(icon(R.drawable.ic_settings, getString(R.string.settings)) {
                    startActivity(android.content.Intent(this@OnvifActivity, OnvifCameraSettingsActivity::class.java).putExtra("cameraId", camera.id))
                })
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
        root.addView(action(R.string.onvif_search, true) { showDiscovery() })
        root.addView(action(R.string.onvif_manual, false) { add() })
        setContentView(root)
    }

    private fun persist(cameras: List<OnvifSavedCamera>) {
        try { store.save(cameras); saved = cameras; list() } catch (_: Exception) { error(R.string.onvif_storage_error) }
    }

    private fun add(existing: OnvifSavedCamera? = null, discovered: OnvifDiscoveredCamera? = null) {
        editing = true
        val ui = ConnectionFormUi(this)
        val root = root(existing?.name ?: getString(R.string.onvif_add)) { list() }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24))
        }
        val name = ui.field(form, getString(R.string.onvif_name), getString(R.string.onvif_name),
            existing?.name ?: discovered?.name.orEmpty(), key = "onvif-name")
        val address = ui.field(form, getString(R.string.onvif_address), "http://192.168.1.100:8000",
            existing?.endpoint ?: discovered?.endpoint.orEmpty(), key = "onvif-server")
        val username = ui.field(form, getString(R.string.username), getString(R.string.username),
            existing?.credentials?.username.orEmpty(), key = "onvif-username")
        val password = ui.field(form, getString(R.string.password),
            getString(if (existing != null) R.string.onvif_keep_password else R.string.password),
            secret = true, key = "onvif-password")
        passwordInput = password
        if (existing != null) {
            password.hint = ""
            form.addView(ui.text(getString(R.string.onvif_keep_password)).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        form.addView(ui.action(getString(R.string.onvif_connect)) {
                val endpoint = runCatching { onvifEndpoint(address.text.toString()).toASCIIString() }.getOrNull()
                if (name.text.isBlank()) { name.error = getString(R.string.onvif_name); name.requestFocus(); return@action }
                if (endpoint == null) { address.error = getString(R.string.onvif_invalid_address); address.requestFocus(); return@action }
                val camera = OnvifSavedCamera(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(), endpoint,
                    OnvifCredentials(username.text.toString(), password.text.toString().ifEmpty { existing?.credentials?.password.orEmpty() }),
                    existing?.profileToken)
                password.text.clear(); passwordInput = null; editing = false
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(password.windowToken, 0)
                connect(camera, adding = true)
        })
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; addView(form)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(root)
    }

    private fun connect(camera: OnvifSavedCamera, adding: Boolean = false, autoProfile: Boolean = false) {
        if (appLock.locked) { pendingCamera = camera; list(); return }
        stopDiscovery()
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
                        try { saved = store.load().filterNot { it.id == camera.id } + camera; store.save(saved) } catch (_: Exception) { error(R.string.onvif_storage_error); list(); return@onSuccess }
                    }
                    if (fullscreen || autoProfile) stream(camera, connection, preferredOnvifProfile(connection.profiles, camera.profileToken))
                    else choose(camera, connection)
                }.onFailure { error(R.string.onvif_connection_failed); list() }
            }
        }
    }

    private fun choose(camera: OnvifSavedCamera, connection: OnvifConnection) {
        dialog = AlertDialog.Builder(this).setTitle(R.string.onvif_profile)
            .setItems(connection.profiles.map { "${it.name} · ${it.encoding}" }.toTypedArray()) { _, index ->
                stream(camera, connection, connection.profiles[index])
            }.setOnCancelListener { list() }.show()
    }

    private fun stream(camera: OnvifSavedCamera, connection: OnvifConnection, profile: OnvifProfile) {
        val request = ++generation
        job = worker.submit {
            val result = runCatching { client.stream(connection, profile, camera.credentials) }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { uri ->
                    val updated = OnvifSavedCamera(camera.id, camera.name, camera.endpoint, camera.credentials, profile.token)
                    try { saved = store.load().map { if (it.id == camera.id) updated else it }; store.save(saved) }
                    catch (_: Exception) { error(R.string.onvif_storage_error); list(); return@onSuccess }
                    play(updated, uri.toString())
                }.onFailure { error(R.string.onvif_connection_failed); list() }
            }
        }
    }

    private fun play(camera: OnvifSavedCamera, uri: String) {
        player?.release()
        playingCamera = camera
        val root = root(camera.name) { list() }
        val view = (layoutInflater.inflate(R.layout.onvif_live_preview, root, false) as PlayerView).apply {
            keepScreenOn = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val sound = icon(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume, getString(R.string.playback_sound)) {
            muted = !muted; player?.volume = if (muted) 0f else 1f
            (it as ImageButton).setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        }
        root.addView(LinearLayout(this).apply {
            addView(sound)
            addView(icon(R.drawable.ic_refresh, getString(R.string.retry)) { connect(camera) })
        })
        if (fullscreen) {
            val full = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                (view.parent as ViewGroup).removeView(view)
                addView(view, FrameLayout.LayoutParams(-1, -1))
                addView(icon(R.drawable.ic_arrow_back, getString(R.string.onvif_back)) { finish() }.apply { setColorFilter(android.graphics.Color.WHITE) },
                    FrameLayout.LayoutParams(dp(48), dp(52), Gravity.TOP or Gravity.START).apply { topMargin = dp(24); leftMargin = dp(16) })
                addView(icon(R.drawable.ic_refresh, getString(R.string.retry)) { connect(camera, autoProfile = true) }.apply { setColorFilter(android.graphics.Color.WHITE) },
                    FrameLayout.LayoutParams(dp(48), dp(52), Gravity.TOP or Gravity.END).apply { topMargin = dp(24); rightMargin = dp(16) })
                (sound.parent as ViewGroup).removeView(sound)
                sound.setColorFilter(android.graphics.Color.WHITE)
                addView(sound, FrameLayout.LayoutParams(dp(48), dp(52), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(24); rightMargin = dp(16) })
            }
            setContentView(full)
            androidx.core.view.WindowCompat.getInsetsController(window, full).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else setContentView(root)
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
    private fun action(resource: Int, primary: Boolean, click: () -> Unit) = Button(this).apply {
        setText(resource); isAllCaps = false; textSize = 15f
        setTextColor(if (primary) android.graphics.Color.WHITE else Palette.text)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (primary) Palette.accent else Palette.surface); cornerRadius = dp(6).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) }
        setOnClickListener { click() }
    }
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
        passwordInput?.text?.clear()
        stopDiscovery()
        stopped = true
        resumedPlayback = player?.playWhenReady == true
        if (resumedPlayback) pendingCamera = playingCamera
        player?.release(); player = null
        generation++; job?.cancel(true); client.cancel(); dialog?.dismiss(); dialog = null
        if (::appLock.isInitialized) appLock.onBackground()
        super.onStop()
    }
    override fun onStart() {
        super.onStart()
        if (::appLock.isInitialized) {
            if (stopped && player == null && ::store.isInitialized) list()
            stopped = false
            appLock.onForeground()
            if (!appLock.locked) resumePendingCamera()
        }
    }
    private fun resumePendingCamera() {
        if (pendingDiscovery) { pendingDiscovery = false; showDiscovery(); return }
        pendingEdit?.let { pendingEdit = null; add(it); return }
        pendingCamera?.let { pendingCamera = null; connect(it, autoProfile = true) }
    }
    private fun showDiscovery() {
        if (appLock.locked) { pendingDiscovery = true; return }
        list()
        val root = root(getString(R.string.onvif_search)) { list() }
        lateinit var results: OnvifDiscoveryView
        results = OnvifDiscoveryView(this,
            select = { camera -> stopDiscovery(); generation++; job?.cancel(true); results.finish(); add(discovered = camera) },
            search = { search(results) }, cancel = { list() })
        root.addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        search(results)
    }

    private fun search(results: OnvifDiscoveryView) {
        stopDiscovery(); generation++; job?.cancel(true); client.cancel()
        results.begin()
        val local = onvifDiscoveryNetwork(this)
        if (local == null) {
            results.finish(R.string.onvif_search_network); return
        }
        if (local.wifi) {
            try {
                val wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                discoveryLock = wifi.createMulticastLock("Camnory.ONVIF").apply { setReferenceCounted(false); acquire() }
            } catch (_: Exception) { stopDiscovery(); results.finish(R.string.onvif_search_failed); return }
        }
        val request = generation
        val lock = discoveryLock
        job = worker.submit {
            val result = runCatching {
                discovery.search(local.address, bindNetwork = { local.network.bindSocket(it) }) { camera ->
                    runOnUiThread {
                        if (request == generation && !isFinishing && !isDestroyed && results.isAttachedToWindow && !appLock.locked) {
                            val alreadySaved = saved.any { runCatching { onvifEndpoint(it.endpoint).host == camera.host }.getOrDefault(false) }
                            results.add(camera, alreadySaved)
                        }
                    }
                }
            }
            runOnUiThread {
                if (request == generation && discoveryLock === lock) stopDiscovery()
                if (request == generation && !isFinishing && !isDestroyed && results.isAttachedToWindow && !appLock.locked)
                    results.finish(if (result.isFailure) R.string.onvif_search_failed else null)
            }
        }
    }

    private fun stopDiscovery() {
        discovery.cancel()
        discoveryLock?.let { if (it.isHeld) it.release() }
        discoveryLock = null
    }

    override fun onDestroy() { generation++; stopDiscovery(); client.cancel(); worker.shutdownNow(); player?.release(); super.onDestroy() }
}
