package fr.degert.fricamgate

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.app.DatePickerDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import android.graphics.drawable.GradientDrawable
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import pl.droidsonroids.gif.GifDrawable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity() {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences("fricamgate-native", MODE_PRIVATE) }
    private val appearancePrefs by lazy { getSharedPreferences("camgate-settings", MODE_PRIVATE) }
    private val reducedMotion get() = appearancePrefs.getBoolean("reducedMotion", false)
    private var session: NativeSession? = null
    private var api: FrigateNativeApi? = null
    private var cameras: List<NativeCamera> = emptyList()
    private var events: List<NativeEvent> = emptyList()
    private var currentPlayer: ExoPlayer? = null
    private var currentPlaybackKey: String? = null
    private var timelinePlaybackGeneration = 0
    private var timelineFrameGeneration = 0
    private var cameraPickerPopup: PopupWindow? = null
    private var alertsPopup: PopupWindow? = null
    private var nvrVersion: String? = null
    private var updateResult: NativeUpdateResult? = null
    private var updateChecking = false
    private var updateCardView: View? = null
    private var soundEnabled = false
    private val homeStreams = mutableListOf<WebSocket>()
    private val streamRestarts = mutableMapOf<ImageView, () -> Unit>()
    private var streamsSuspended = false
    private val playbackResume = PlaybackResumeGate<ExoPlayer>()
    private val homeSoundIcons = mutableMapOf<String, ImageView>()
    private var homeAudioWebView: WebView? = null
    private var resumeHomeAudioKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationHandler = Handler(Looper.getMainLooper())
    private var homeStreamGeneration = 0
    private var homeAudioGeneration = 0
    private val seenAlertIdsMemory = mutableSetOf<String>()
    private var dashboardErrorMessage: String? = null
    private val loadingAnimators = mutableListOf<ValueAnimator>()
    private lateinit var appLock: BiometricAppLock
    private var activeTab = Tab.Home
    private var settingsPage = "main"
    private var restoredSettingsPage: String? = null
    private var appearanceScrollY = 0
    private var eventSearch = EventSearch()
    private var globalResults: List<NativeEvent>? = null
    private var globalResultsContainer: LinearLayout? = null
    private var globalLoading = false
    private var globalHasMore = false
    private var globalError = false
    private var globalQueryGeneration = 0
    private var globalQueryAnchor = 0.0
    private var pendingEventId: String? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CameraNotifications.configure(this, granted)
        getSharedPreferences("camgate-settings", MODE_PRIVATE).edit().putBoolean("notificationEnablePending", false).apply()
        if (activeTab == Tab.Settings && settingsPage == "notifications") renderNotificationSettings()
    }
    private val notificationPoll = object : Runnable {
        override fun run() {
            if (session != null) CameraNotifications.checkNow(this@MainActivity)
            notificationHandler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        delegate.localNightMode = when (appearancePrefs.getString("theme", "system")) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        Palette.backgroundStyle = BackgroundStyle.fromPreference(appearancePrefs.getString("backgroundStyle", null))
        Palette.dark = Appearance.isDark(appearancePrefs.getString("theme", "system"),
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        Palette.accent = Appearance.accent(appearancePrefs.getString("accent", null))
        getSharedPreferences("camgate-settings", MODE_PRIVATE).edit()
            .putString("language", AppCompatDelegate.getApplicationLocales().toLanguageTags()).apply()
        appLock = BiometricAppLock(this) {
            resumeAfterBackground()
            homeAudioWebView?.onResume()
            openPendingNotification()
        }
        restoredSettingsPage = savedInstanceState?.getString("settingsPage")
        appearanceScrollY = savedInstanceState?.getInt("appearanceScrollY") ?: 0
        pendingEventId = intent?.getStringExtra(CameraNotifications.EVENT_ID)
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.black
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark
            isAppearanceLightNavigationBars = !Palette.dark
        }
        soundEnabled = prefs.getBoolean("soundEnabled", false)
        session = loadSession()
        api = session?.let { FrigateNativeApi(it.baseUrl, httpClient) }
        if (session == null) {
            renderLogin()
        } else {
            loadDashboard(getString(R.string.restored, session?.username.orEmpty()))
        }
    }

    override fun onStop() {
        super.onStop()
        notificationHandler.removeCallbacks(notificationPoll)
        if (!isChangingConfigurations) appLock.onBackground()
        playbackResume.remember(currentPlayer, currentPlayer?.playWhenReady == true)
        currentPlayer?.playWhenReady = false
        resumeHomeAudioKey = currentPlaybackKey.takeIf { soundEnabled && homeAudioWebView != null }
        if (homeAudioWebView != null) {
            homeAudioGeneration++
            releaseHomeAudioWebView()
        }
        streamsSuspended = true
        homeStreamGeneration++
        homeStreams.forEach { it.cancel() }
        homeStreams.clear()
    }

    override fun onPause() {
        if (::appLock.isInitialized && appLock.enabled && !isChangingConfigurations) {
            dismissTransientPopups()
            appLock.onBackground()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        appLock.onForeground()
        if (!appLock.locked) {
            resumeAfterBackground()
            homeAudioWebView?.onResume()
        }
        val settingsPrefs = getSharedPreferences("camgate-settings", MODE_PRIVATE)
        if (settingsPrefs.getBoolean("notificationEnablePending", false) && CameraNotifications.permitted(this)) {
            settingsPrefs.edit().putBoolean("notificationEnablePending", false).apply()
            CameraNotifications.configure(this, true)
        }
        CameraNotifications.schedule(this)
        notificationHandler.removeCallbacks(notificationPoll)
        notificationHandler.post(notificationPoll)
        if (activeTab == Tab.Settings && settingsPage == "notifications") renderNotificationSettings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (activeTab == Tab.Settings) outState.putString("settingsPage", settingsPage)
        outState.putInt("appearanceScrollY", appearanceScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEventId = intent.getStringExtra(CameraNotifications.EVENT_ID)
        openPendingNotification()
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        if (::appLock.isInitialized) appLock.protectContent()
    }

    private fun openPendingNotification() {
        if (appLock.locked || cameras.isEmpty()) return
        val id = pendingEventId ?: return
        pendingEventId = null
        events.firstOrNull { it.id == id }?.let { renderEventDetail(it) } ?: renderGlobalEvents()
    }

    override fun onDestroy() {
        notificationHandler.removeCallbacks(notificationPoll)
        releasePlayer()
        super.onDestroy()
    }

    private fun resumeAfterBackground() {
        if (playbackResume.consume(currentPlayer)) currentPlayer?.playWhenReady = true
        if (streamsSuspended) {
            streamsSuspended = false
            streamRestarts.values.toList().forEach { it() }
            val key = currentPlaybackKey
            val camera = cameras.firstOrNull { homePlaybackKey(it) == key }
            if (soundEnabled && resumeHomeAudioKey == key && key != null && camera != null) {
                startHomeLiveAudio(camera, key, ++homeAudioGeneration)
            }
            resumeHomeAudioKey = null
        }
    }

    private fun renderLogin() {
        releasePlayer()
        releaseLoadingAnimations()
        val scroll = appScrollView().apply {
            setBackgroundColor(Palette.background)
            isFillViewport = true
        }
        val root = vertical().apply {
            setPadding(dp(24), dp(32), dp(24), dp(24))
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Palette.background)
        }
        scroll.addView(root, centeredScrollParams(560))
        root.addView(logoHeader())
        root.addView(label(getString(R.string.frigate_server), 12, Palette.muted).apply { setPadding(0, dp(28), 0, dp(8)) })
        val serverInput = editText("http://cam.example.fr", prefs.getString("baseUrl", "") ?: "")
        root.addView(serverInput, matchWrap())
        root.addView(label(getString(R.string.username), 12, Palette.muted).apply { setPadding(0, dp(18), 0, dp(8)) })
        val usernameInput = editText("admin", prefs.getString("username", "") ?: "")
        root.addView(usernameInput, matchWrap())
        root.addView(label(getString(R.string.password), 12, Palette.muted).apply { setPadding(0, dp(18), 0, dp(8)) })
        val passwordInput = editText(getString(R.string.password_hint), "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passwordInput, matchWrap())
        val button = primaryButton(getString(R.string.login)).apply {
            setOnClickListener {
                connect(serverInput.text.toString(), usernameInput.text.toString(), passwordInput.text.toString())
            }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(24) })
        root.addView(label(getString(R.string.login_hint), 14, Palette.muted).apply {
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(scroll)
    }

    private fun connect(baseUrlValue: String, username: String, password: String) {
        val normalizedUrl = normalizeBaseUrl(baseUrlValue)
        if (normalizedUrl.isBlank() || username.isBlank() || password.isBlank()) {
            toast(getString(R.string.required_login))
            return
        }

        prefs.edit()
            .putString("baseUrl", normalizedUrl)
            .putString("username", username)
            .apply()
        renderLoading(getString(R.string.connecting))
        val nextApi = FrigateNativeApi(normalizedUrl, httpClient)
        nextApi.login(username, password) { result ->
            runOnUiThread {
                result.onSuccess { login ->
                    val nextSession = NativeSession(normalizedUrl, login.token, login.username)
                    saveSession(nextSession)
                    session = nextSession
                    api = nextApi
                    dashboardErrorMessage = null
                    loadDashboard(getString(R.string.connected, normalizedUrl))
                }.onFailure {
                    renderLogin()
                    toast(it.message ?: getString(R.string.login_failed))
                }
            }
        }
    }

    private fun loadDashboard(message: String? = null) {
        globalQueryGeneration++
        globalResults = null
        globalLoading = false
        releasePlayer()
        val activeSession = session
        val activeApi = api
        if (activeSession == null || activeApi == null) {
            renderLogin()
            return
        }
        renderLoading(getString(R.string.loading_cameras))
        activeApi.loadDashboard(activeSession.token) { result ->
            runOnUiThread {
                result.onSuccess { data ->
                    cameras = data.cameras
                    events = data.events
                    dashboardErrorMessage = null
                    val restoredPage = restoredSettingsPage
                    restoredSettingsPage = null
                    when (restoredPage) {
                        "language" -> renderLanguageSettings()
                        "appearance" -> renderAppearanceSettings()
                        "notifications" -> renderNotificationSettings()
                        "server" -> renderServerSettings()
                        "cameras" -> renderCameraSettings()
                        "about" -> renderAboutSettings()
                        "main" -> renderSettings()
                        else -> renderHome(message)
                    }
                    CameraNotifications.schedule(this)
                    openPendingNotification()
                }.onFailure { error ->
                    val errorMessage = error.message ?: getString(R.string.frigate_unavailable)
                    if (isAuthFailure(errorMessage)) {
                        prefs.edit().remove("token").apply()
                        session = null
                        api = null
                        cameras = emptyList()
                        events = emptyList()
                        dashboardErrorMessage = null
                        renderLogin()
                        toast(getString(R.string.session_expired))
                    } else {
                        dashboardErrorMessage = errorMessage
                        renderHome(errorMessage)
                        toast(errorMessage)
                    }
                }
            }
        }
    }

    private fun renderHome(message: String? = null) {
        releasePlayer()
        val content = appScrollView().apply {
            setBackgroundColor(Palette.background)
            clipToPadding = false
            setPadding(0, 0, 0, dp(78))
        }
        val column = vertical().apply { setPadding(dp(12), dp(10), dp(12), dp(24)) }
        content.addView(column, responsiveScrollParams(720))
        column.addView(eventStrip())
        recentActivitySummary()?.let { column.addView(it) }
        column.addView(homeTitleRow(message))

        val homeCameras = visibleCameras()
        val dashboardError = dashboardErrorMessage
        if (!dashboardError.isNullOrBlank()) {
            column.addView(dashboardErrorCard(dashboardError))
        } else if (cameras.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.no_cameras)))
        } else if (homeCameras.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.all_hidden)))
        } else {
            val layout = homeCameraLayout()
            if (layout == HomeCameraLayout.Column) {
                homeCameras.forEach { camera -> column.addView(cameraCard(camera)) }
            } else {
                homeCameras.chunked(layout.columns).forEach { cameraRow ->
                    column.addView(horizontal().apply {
                        gravity = Gravity.TOP
                        repeat(layout.columns) { index ->
                            val tile = cameraRow.getOrNull(index)?.let { cameraGridTile(it) } ?: View(context)
                            addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (index > 0) leftMargin = dp(8)
                            })
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    })
                }
            }
        }
        setShell(content, Tab.Home, animateIn = true)
    }

    private fun isAuthFailure(message: String): Boolean =
        message.contains("HTTP 401") || message.contains("HTTP 403")

    private fun renderLiaChat() {
        releasePlayer()
        val activeSession = session ?: run { renderLogin(); return }
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setShell(NativeChatView(this, activeSession.baseUrl, activeSession.token, activeSession.username, httpClient), Tab.Home)
    }

    private fun renderCameraDetail(
        camera: NativeCamera,
        timelineDayMillis: Long = System.currentTimeMillis(),
        timelineEvents: List<NativeEvent>? = null,
        timelineFocusSec: Double? = null,
        showEventsList: Boolean = false
    ) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        releasePlayer()
        var currentFocusSec: Double? = timelineFocusSec
        var currentFocusIsLive = timelineFocusSec == null && isSameLocalDay(timelineDayMillis, System.currentTimeMillis())

        val body = vertical().apply { setBackgroundColor(Palette.background) }
        val playerFrame = FrameLayout(this).apply { setBackgroundColor(Palette.black) }
        val liveImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.surface)
        }
        playerFrame.addView(liveImage, frameMatch())
        activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
            runOnUiThread { bitmap?.let { liveImage.setImageBitmap(it) } }
        }
        val playerView = PlayerView(this).apply {
            useController = false
            visibility = View.GONE
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
        }
        playerFrame.addView(playerView, frameMatch())
        val live = liveBadge().apply { visibility = View.GONE }
        playerFrame.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(18)
            topMargin = dp(18)
        })
        body.addView(playerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cameraVideoHeight()))
        body.addView(
            cameraToolbar(
                camera = camera,
                showEventsList = showEventsList,
                onRefresh = { renderCameraDetail(camera) },
                onSnapshot = { saveCameraSnapshot(camera, currentFocusSec, currentFocusIsLive) },
                onToggleEvents = { renderCameraDetail(camera, timelineDayMillis, timelineEvents, currentFocusSec, !showEventsList) },
                onSettings = { anchor -> showCameraOptionsPopup(camera, anchor) }
            )
        )
        val detailContent = if (showEventsList) {
            cameraEventList(camera, timelineEvents ?: events)
        } else {
            cameraTimeline(camera, liveImage, playerView, live, timelineDayMillis, timelineEvents, timelineFocusSec) { focusSec, isLive ->
                currentFocusSec = focusSec
                currentFocusIsLive = isLive
            }
        }
        body.addView(detailContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setShell(body, Tab.Cameras, showHeader = false)
        startMjpegStream(activeApi, activeSession.token, camera, liveImage, live)
    }

    private fun renderEventDetail(event: NativeEvent, displayedCameraName: String = event.camera) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        releasePlayer()
        val displayedCamera = cameras.firstOrNull { it.name == displayedCameraName }
            ?: cameras.firstOrNull { it.name == event.camera }
        val activeCameraName = displayedCamera?.name ?: event.camera
        val activeCameraLabel = displayedCamera?.friendlyName ?: activeCameraName
        val showingEventCamera = activeCameraName == event.camera

        val body = vertical().apply { setBackgroundColor(Palette.background) }
        val playerFrame = FrameLayout(this).apply { setBackgroundColor(Palette.black) }
        val fallback = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        playerFrame.addView(fallback, frameMatch())
        if (showingEventCamera) {
            event.thumbnail?.let { fallback.setImageBitmap(it) }
            activeApi.loadBitmap(activeSession.token, activeApi.eventSnapshotUrl(event.id)) { bitmap ->
                runOnUiThread { bitmap?.let { fallback.setImageBitmap(it) } }
            }
        } else {
            activeApi.loadBitmap(activeSession.token, activeApi.recordingSnapshotUrl(activeCameraName, event.startTime ?: 0.0)) { bitmap ->
                runOnUiThread {
                    if (bitmap != null) {
                        fallback.setImageBitmap(bitmap)
                    } else {
                        activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(activeCameraName)) { latest ->
                            runOnUiThread { latest?.let { fallback.setImageBitmap(it) } }
                        }
                    }
                }
            }
        }
        val playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
        }
        playerFrame.addView(playerView, frameMatch())
        playerFrame.addView(soundToggleButton(), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(18)
            bottomMargin = dp(14)
        })
        body.addView(playerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))

        val scroll = appScrollView()
        val column = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(120)) }
        scroll.addView(column)
        column.addView(label(getString(R.string.event_playback), 12, Palette.muted))
        column.addView(label(eventDisplayText(event), 28, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        column.addView(label("$activeCameraLabel - ${event.time}", 14, Palette.muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        column.addView(label(eventSentence(event), 14, Palette.text).apply { setPadding(0, 0, 0, dp(12)) })
        event.description?.let { description ->
            column.addView(sectionHeading(getString(R.string.frigate_description)))
            column.addView(label(description, 14, Palette.muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        }
        column.addView(label(getString(R.string.same_moment), 18, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        column.addView(syncCameraGrid(event, activeCameraName))
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setShell(body, Tab.Events)
        if (showingEventCamera || displayedCamera == null) {
            startClipPlayer(activeApi, activeSession.token, event, playerView, fallback)
        } else {
            startTimelineRecordingPlayer(activeApi, activeSession.token, displayedCamera, playerView, fallback, event.startTime ?: 0.0)
        }
    }

    private fun startClipPlayer(api: FrigateNativeApi, token: String, event: NativeEvent, playerView: PlayerView, fallback: ImageView) {
        releaseCurrentPlayer()
        val player = buildExoPlayer(token)
        currentPlayer = player
        val key = "event:${event.id}"
        currentPlaybackKey = key
        playerView.player = player
        playerView.visibility = View.VISIBLE
        attachFallbackHider(player, key, fallback, playerView)
        player.setMediaItem(MediaItem.fromUri(api.eventVodUrl(event.id)))
        applyPlayerVolume(player)
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    private fun buildExoPlayer(token: String): ExoPlayer =
        ExoPlayer.Builder(
            this,
            DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        )
            .setMediaSourceFactory(DefaultMediaSourceFactory(authDataSource(token)))
            .build()

    private fun startTimelineEventPlayer(
        api: FrigateNativeApi,
        token: String,
        event: NativeEvent,
        playerView: PlayerView,
        fallback: ImageView,
        startPositionMs: Long
    ) {
        val key = "event:${event.id}"
        if (currentPlaybackKey == key && currentPlayer != null) {
            fallback.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            currentPlayer?.seekTo(startPositionMs)
            currentPlayer?.playWhenReady = true
            currentPlayer?.play()
            return
        }

        releaseCurrentPlayer()
        val player = buildExoPlayer(token)
        currentPlayer = player
        currentPlaybackKey = key
        playerView.player = player
        playerView.visibility = View.VISIBLE
        fallback.visibility = View.VISIBLE
        attachFallbackHider(player, key, fallback, playerView)
        player.setMediaItem(MediaItem.fromUri(api.eventVodUrl(event.id)), startPositionMs)
        applyPlayerVolume(player)
        player.prepare()
        player.playWhenReady = true
        player.play()
    }

    private fun attachFallbackHider(player: ExoPlayer, playbackKey: String, fallback: ImageView, playerView: PlayerView) {
        fun showVideoOnly() {
            if (currentPlaybackKey == playbackKey && currentPlayer === player) {
                fallback.visibility = View.GONE
                playerView.visibility = View.VISIBLE
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) showVideoOnly()
            }

            override fun onRenderedFirstFrame() {
                showVideoOnly()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (currentPlaybackKey == playbackKey && currentPlayer === player) {
                    fallback.visibility = View.VISIBLE
                    playerView.visibility = View.GONE
                }
            }
        })
    }

    private fun startTimelineRecordingPlayer(
        api: FrigateNativeApi,
        token: String,
        camera: NativeCamera,
        playerView: PlayerView,
        fallback: ImageView,
        focusSec: Double,
        onPlaybackClock: (Double) -> Unit = {}
    ) {
        val safeFocus = focusSec.coerceAtLeast(0.0)
        val window = timelineRecordingWindow(safeFocus)
        val windowStart = window.startSec
        val windowEnd = window.endSec
        val seekMs = window.seekMs
        val key = "recording:${camera.name}:$windowStart:$windowEnd"
        val current = currentPlayer

        fun refreshFallbackFrame() {
            val frameGeneration = ++timelineFrameGeneration
            fallback.visibility = View.VISIBLE
            api.loadBitmap(token, api.recordingSnapshotUrl(camera.name, safeFocus)) { bitmap ->
                runOnUiThread {
                    if (frameGeneration == timelineFrameGeneration && bitmap != null) {
                        fallback.setImageBitmap(bitmap)
                    }
                }
            }
        }

        if (currentPlaybackKey == key && current != null && current.playerError == null) {
            timelineFrameGeneration += 1
            fallback.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            current.seekTo(seekMs)
            current.playWhenReady = true
            current.play()
            startTimelineClock(key, windowStart, playerView, current, onPlaybackClock)
            return
        }

        releaseCurrentPlayer()
        refreshFallbackFrame()
        val player = buildExoPlayer(token)
        currentPlayer = player
        currentPlaybackKey = key
        playerView.player = player
        playerView.visibility = View.VISIBLE
        var firstReadySeekDone = false
        var usingDefaultVod = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && currentPlayer === player && currentPlaybackKey == key) {
                    mainHandler.post {
                        if (currentPlayer === player && currentPlaybackKey == key) {
                            startTimelineRecordingPlayer(api, token, camera, playerView, fallback,
                                windowEnd.toDouble(), onPlaybackClock)
                        }
                    }
                }
                if (playbackState == Player.STATE_READY && currentPlaybackKey == key) {
                    if (!firstReadySeekDone) {
                        firstReadySeekDone = true
                        player.seekTo(seekMs)
                    }
                    player.playWhenReady = true
                    player.play()
                }
            }

            override fun onRenderedFirstFrame() {
                if (currentPlaybackKey == key) {
                    fallback.visibility = View.GONE
                    playerView.visibility = View.VISIBLE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (currentPlaybackKey == key) {
                    if (!usingDefaultVod) {
                        usingDefaultVod = true
                        firstReadySeekDone = false
                        player.setMediaItem(MediaItem.fromUri(api.recordingClipUrl(camera.name, windowStart, windowEnd)))
                        player.prepare()
                        player.playWhenReady = true
                        player.play()
                        return
                    }
                    currentPlaybackKey = null
                    playerView.visibility = View.GONE
                    fallback.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, R.string.no_recent_video, Toast.LENGTH_LONG).show()
                }
            }
        })
        player.setMediaItem(MediaItem.fromUri(api.recordingVodUrl(camera.name, windowStart, windowEnd)))
        applyPlayerVolume(player)
        player.prepare()
        player.seekTo(seekMs)
        player.playWhenReady = true
        player.play()
        startTimelineClock(key, windowStart, playerView, player, onPlaybackClock)
    }

    private fun startTimelineClock(
        playbackKey: String,
        windowStartSec: Long,
        playerView: PlayerView,
        player: ExoPlayer,
        onPlaybackClock: (Double) -> Unit
    ) {
        val playbackGeneration = ++timelinePlaybackGeneration
        fun tick() {
            if (timelinePlaybackGeneration != playbackGeneration || currentPlaybackKey != playbackKey || currentPlayer !== player) return
            if (playerView.visibility == View.VISIBLE && player.playbackState != Player.STATE_IDLE) {
                onPlaybackClock(windowStartSec + (player.currentPosition / 1000.0))
            }
            if (player.playbackState != Player.STATE_ENDED) {
                mainHandler.postDelayed({ tick() }, TIMELINE_PLAYBACK_CLOCK_MS)
            }
        }
        mainHandler.postDelayed({ tick() }, TIMELINE_PLAYBACK_CLOCK_MS)
    }

    private fun startMjpegStream(
        api: FrigateNativeApi,
        token: String,
        camera: NativeCamera,
        image: ImageView,
        badge: View
    ) {
        streamRestarts[image] = { startMjpegStream(api, token, camera, image, badge) }
        badge.visibility = View.GONE
        if (streamsSuspended) return
        val generation = homeStreamGeneration
        fun connect(attempt: Int) {
            if (generation != homeStreamGeneration || streamsSuspended) return
            lateinit var stream: WebSocket
            var receivedFrame = false
            var lastFrameAt = android.os.SystemClock.elapsedRealtime()
            lateinit var watchdog: Runnable
            fun recover() {
                if (generation != homeStreamGeneration || !homeStreams.remove(stream)) return
                mainHandler.removeCallbacks(watchdog)
                stream.cancel()
                badge.visibility = View.GONE
                val next = if (receivedFrame) 0 else (attempt + 1).coerceAtMost(6)
                mainHandler.postDelayed({ connect(next) }, streamRetryDelay(next))
            }
            watchdog = object : Runnable {
                override fun run() {
                    if (generation != homeStreamGeneration || !homeStreams.contains(stream)) return
                    val limit = if (receivedFrame) 15_000L else HOME_MJPEG_STARTUP_TIMEOUT_MS
                    if (android.os.SystemClock.elapsedRealtime() - lastFrameAt >= limit) recover()
                    else mainHandler.postDelayed(this, 1000L)
                }
            }
            stream = api.openMjpegStream(
                token = token,
                streamName = camera.streamName,
                onFrame = { bitmap ->
                    runOnUiThread {
                        if (!homeStreams.contains(stream) || generation != homeStreamGeneration) return@runOnUiThread
                        receivedFrame = true
                        lastFrameAt = android.os.SystemClock.elapsedRealtime()
                        image.setImageBitmap(bitmap)
                        badge.visibility = View.VISIBLE
                    }
                },
                onError = { runOnUiThread { recover() } }
            )
            homeStreams.add(stream)
            mainHandler.postDelayed(watchdog, 1000L)
        }
        connect(0)
    }

    private fun authDataSource(token: String): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token", "User-Agent" to "FriCamGate"))

    private fun applyPlayerVolume(player: ExoPlayer) {
        player.volume = if (soundEnabled) 1f else 0f
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !soundEnabled)
            .build()
    }

    private fun toggleSound(): Boolean {
        soundEnabled = !soundEnabled
        prefs.edit().putBoolean("soundEnabled", soundEnabled).apply()
        currentPlayer?.let { applyPlayerVolume(it) }
        return soundEnabled
    }

    private fun homePlaybackKey(camera: NativeCamera): String = "home:${camera.name}"

    private fun isHomeSoundActive(camera: NativeCamera): Boolean =
        soundEnabled && currentPlaybackKey == homePlaybackKey(camera)

    private fun refreshHomeSoundIcons() {
        homeSoundIcons.forEach { (cameraName, icon) ->
            val active = soundEnabled && currentPlaybackKey == "home:$cameraName"
            icon.setImageResource(if (active) R.drawable.ic_volume else R.drawable.ic_volume_off)
            icon.alpha = if (active) 1f else 0.72f
        }
    }

    private fun toggleHomeSound(camera: NativeCamera): Boolean {
        val key = homePlaybackKey(camera)
        if (isHomeSoundActive(camera)) {
            homeAudioGeneration += 1
            soundEnabled = false
            prefs.edit().putBoolean("soundEnabled", false).apply()
            releaseHomeAudioWebView()
            releaseCurrentPlayer()
            refreshHomeSoundIcons()
            return false
        }

        soundEnabled = true
        prefs.edit().putBoolean("soundEnabled", true).apply()
        releaseHomeAudioWebView()
        releaseCurrentPlayer()
        currentPlaybackKey = key
        refreshHomeSoundIcons()
        startHomeLiveAudio(camera, key, ++homeAudioGeneration)
        return true
    }

    private fun startHomeLiveAudio(camera: NativeCamera, playbackKey: String, generation: Int) {
        val activeSession = session
        val activeApi = api
        if (activeSession == null || activeApi == null) {
            failHomeLiveAudio(playbackKey, getString(R.string.sound_unavailable))
            return
        }

        if (generation != homeAudioGeneration || currentPlaybackKey != playbackKey || !soundEnabled) {
            return
        }

        val webView = WebView(this).apply {
            alpha = 0.01f
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun failed() {
                    runOnUiThread {
                        if (generation == homeAudioGeneration && currentPlaybackKey == playbackKey) {
                            failHomeLiveAudio(playbackKey, getString(R.string.sound_unavailable))
                        }
                    }
                }
            }, "CamGateAudio")
        }
        homeAudioWebView = webView
        addContentView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP))
        syncWebViewAuthCookie(webView, activeSession)
        webView.loadDataWithBaseURL(
            activeSession.baseUrl,
            liveAudioHtml(activeApi.liveMseWebSocketUrl(camera.streamName)),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun failHomeLiveAudio(playbackKey: String, message: String) {
        if (currentPlaybackKey != playbackKey) return
        releaseHomeAudioWebView()
        releaseCurrentPlayer()
        soundEnabled = false
        prefs.edit().putBoolean("soundEnabled", false).apply()
        refreshHomeSoundIcons()
        toast(message)
    }

    private fun liveAudioHtml(wsUrl: String): String {
        val wsLiteral = JSONObject.quote(wsUrl)
        val codecsLiteral = JSONObject.quote("mp4a.40.2,mp4a.40.5,flac,opus")
        return """
            <!doctype html>
            <html>
            <body style="margin:0;background:transparent">
            <video id="video" autoplay playsinline style="width:1px;height:1px"></video>
            <script>
            const video = document.getElementById('video');
            video.muted = false;
            video.volume = 1;
            const queue = [];
            let sourceBuffer;
            let socket;
            const startup = setTimeout(() => CamGateAudio.failed(), 12000);
            video.addEventListener('playing', () => clearTimeout(startup));
            function pump() {
              if (video.error) return;
              if (!sourceBuffer || sourceBuffer.updating || queue.length === 0) return;
              try {
                if (sourceBuffer.buffered.length && video.currentTime - sourceBuffer.buffered.start(0) > 30) {
                  sourceBuffer.remove(0, video.currentTime - 15);
                  return;
                }
                sourceBuffer.appendBuffer(queue.shift());
              } catch (_) { CamGateAudio.failed(); }
            }
            video.addEventListener('error', () => {
              try { if (socket) socket.close(); } catch (_) {}
              CamGateAudio.failed();
            });
            const mediaSource = new MediaSource();
            video.src = URL.createObjectURL(mediaSource);
            mediaSource.addEventListener('sourceopen', () => {
              socket = new WebSocket($wsLiteral);
              socket.binaryType = 'arraybuffer';
              socket.onopen = () => {
                socket.send(JSON.stringify({ type: 'mse', value: $codecsLiteral }));
              };
              socket.onerror = () => CamGateAudio.failed();
              socket.onclose = () => CamGateAudio.failed();
              socket.onmessage = (event) => {
                if (typeof event.data === 'string') {
                  const message = JSON.parse(event.data);
                  if (message.type === 'mse' && !sourceBuffer) {
                    sourceBuffer = mediaSource.addSourceBuffer(message.value);
                    sourceBuffer.mode = 'segments';
                    sourceBuffer.addEventListener('updateend', pump);
                    video.play().catch(() => {});
                  }
                  return;
                }
                if (queue.length > 120) { CamGateAudio.failed(); return; }
                queue.push(event.data);
                pump();
              };
            });
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun eventStrip(): View {
        if (events.isEmpty()) return emptyMessage(getString(R.string.no_recent_events))
        val scroll = appHorizontalScrollView()
        val row = horizontal().apply { setPadding(0, 0, 0, dp(4)) }
        scroll.addView(row)
        events.take(12).forEach { event ->
            val card = vertical().apply {
                setPadding(0, 0, 0, dp(8))
                setOnClickListener { renderEventDetail(event) }
            }
            val thumbFrame = FrameLayout(this).apply {
                background = rounded(Palette.card, 8)
                applyRoundedClip(8)
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.surface)
            }
            loadEventPreviewAnimated(event, thumb)
            thumbFrame.addView(thumb, frameMatch())
            card.addView(thumbFrame, LinearLayout.LayoutParams(dp(140), dp(78)))
            card.addView(label(event.time, 13, Palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            row.addView(card, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        }
        return scroll
    }

    private fun cameraCard(camera: NativeCamera): View {
        val card = FrameLayout(this).apply {
            background = rounded(Palette.card, 8)
            applyRoundedClip(8)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.surface)
        }
        card.addView(image, frameMatch())
        val live = liveBadge()
        live.visibility = View.GONE
        session?.let { activeSession ->
            val activeApi = api
            activeApi?.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
                runOnUiThread { bitmap?.let { image.setImageBitmap(it) } }
            }
            activeApi?.let { startMjpegStream(it, activeSession.token, camera, image, live) }
        }
        card.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(10)
            topMargin = dp(10)
        })
        card.addView(chip(camera.friendlyName), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(14)
            bottomMargin = dp(14)
        })
        val controls = horizontal().apply {
            gravity = Gravity.CENTER
            addView(homeSoundBubble(camera))
            if (camera.audioEnabled == true) {
                addView(iconBubble(R.drawable.ic_mic, alphaValue = 0.85f) {
                    toast(getString(R.string.micro_unavailable))
                })
            }
            addView(iconBubble(R.drawable.ic_fullscreen) { renderCameraFullscreen(camera) })
            addView(iconBubble(R.drawable.ic_settings) { anchor -> showCameraOptionsPopup(camera, anchor) })
            addView(iconBubble(R.drawable.ic_list) { renderCameraDetail(camera) })
        }
        card.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(12)
            bottomMargin = dp(12)
        })
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(218)).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun cameraGridTile(camera: NativeCamera): View {
        val tile = vertical().apply {
            background = rounded(Palette.card, 8)
            applyRoundedClip(8)
        }
        val preview = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = View.MeasureSpec.getSize(widthMeasureSpec) * 9 / 16
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.surface)
        }
        preview.addView(image, frameMatch())
        val live = liveBadge().apply { visibility = View.GONE }
        preview.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(5)
            rightMargin = dp(5)
        })
        tile.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tile.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(label(camera.friendlyName, 12, Palette.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = camera.friendlyName
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, camera.friendlyName)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toolbarIcon(R.drawable.ic_more_vertical, tint = Palette.text) { anchor ->
                showGridCameraActions(camera, anchor)
            }.apply {
                val description = getString(R.string.camera_options, camera.friendlyName)
                contentDescription = description
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        session?.let { activeSession ->
            api?.let { activeApi ->
                activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
                    runOnUiThread { bitmap?.let { image.setImageBitmap(it) } }
                }
                startMjpegStream(activeApi, activeSession.token, camera, image, live)
            }
        }
        return tile
    }

    private fun showGridCameraActions(camera: NativeCamera, anchor: View) {
        val soundActive = soundEnabled && currentPlaybackKey == homePlaybackKey(camera)
        val actions = listOf(
            Triple(if (soundActive) R.drawable.ic_volume else R.drawable.ic_volume_off,
                getString(if (soundActive) R.string.camera_disable_audio else R.string.camera_enable_audio), {
                    val enabled = toggleHomeSound(camera)
                    toast(getString(if (enabled) R.string.sound_on else R.string.sound_off))
                }),
            Triple(R.drawable.ic_fullscreen, getString(R.string.camera_fullscreen), { renderCameraFullscreen(camera) }),
            Triple(R.drawable.ic_settings, getString(R.string.settings), { showCameraOptionsPopup(camera, anchor) }),
            Triple(R.drawable.ic_list, getString(R.string.events_tab), { renderCameraDetail(camera, showEventsList = true) })
        )
        showHomeViewMenu(anchor, actions.map { (icon, title, action) ->
            homeMenuRow(icon, title, false) { action() }
        })
    }

    private fun renderCameraFullscreen(camera: NativeCamera) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        releasePlayer()
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.black) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Palette.black)
        }
        root.addView(image, frameMatch())
        activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
            runOnUiThread { bitmap?.let { image.setImageBitmap(it) } }
        }
        val live = liveBadge().apply { visibility = View.GONE }
        root.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(18)
            topMargin = dp(38)
        })
        root.addView(roundButton("<").apply {
            textSize = 26f
            setOnClickListener { renderHome() }
        }, FrameLayout.LayoutParams(dp(54), dp(54), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(16)
            topMargin = dp(28)
        })
        root.addView(chip(camera.friendlyName), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32), Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(18)
            bottomMargin = dp(20)
        })
        root.addView(horizontal().apply {
            gravity = Gravity.CENTER
            addView(homeSoundBubble(camera))
            addView(iconBubble(R.drawable.ic_settings) { anchor -> showCameraOptionsPopup(camera, anchor) })
            addView(iconBubble(R.drawable.ic_list) { renderCameraDetail(camera) })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(14)
            bottomMargin = dp(18)
        })
        setContentView(root)
        startMjpegStream(activeApi, activeSession.token, camera, image, live)
    }

    private fun cameraToolbar(
        camera: NativeCamera,
        showEventsList: Boolean,
        onRefresh: () -> Unit,
        onSnapshot: () -> Unit,
        onToggleEvents: () -> Unit,
        onSettings: (View) -> Unit
    ): View {
        return FrameLayout(this).apply {
            setBackgroundColor(Palette.backgroundAlt)
            addView(View(context).apply {
                background = rounded(0xff3d3d42.toInt(), 3)
            }, FrameLayout.LayoutParams(dp(54), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10)
            })
            addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(16), 0)
                addView(View(context).apply {
                    background = rounded(Palette.liveBlue, 5)
                }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) })
                addView(label(camera.friendlyName, 18, Palette.text).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(toolbarIcon(R.drawable.ic_refresh, onClick = { onRefresh() }))
                addView(soundToolbarIcon())
                addView(toolbarIcon(R.drawable.ic_image, onClick = { onSnapshot() }))
                addView(toolbarIcon(R.drawable.ic_grid, tint = if (showEventsList) Palette.accent else Palette.muted, onClick = { onToggleEvents() }))
                addView(toolbarIcon(R.drawable.ic_settings, onClick = { anchor -> onSettings(anchor) }))
            }, frameMatch())
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
        }
    }

    private fun cameraEventList(camera: NativeCamera, sourceEvents: List<NativeEvent>): View {
        val cameraEvents = sourceEvents
            .filter { it.camera == camera.name }
            .filter { it.startTime != null }
            .sortedByDescending { it.startTime ?: 0.0 }
        val scroll = appScrollView().apply {
            setBackgroundColor(Palette.backgroundAlt)
        }
        val column = vertical().apply { setPadding(dp(18), dp(18), dp(18), dp(120)) }
        scroll.addView(column)
        column.addView(label(getString(R.string.events_heading), 12, Palette.muted).apply {
            setPadding(0, 0, 0, dp(10))
        })
        if (cameraEvents.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.no_camera_events)))
            return scroll
        }
        cameraEvents.forEach { event ->
            val row = horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(12), dp(10))
                background = rounded(Palette.card, 8)
                setOnClickListener { renderEventDetail(event) }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.surface)
            }
            row.addView(FrameLayout(this).apply {
                background = rounded(Palette.surface, 8)
                applyRoundedClip(8)
                addView(thumb, frameMatch())
                loadEventPreviewAnimated(event, thumb)
            }, LinearLayout.LayoutParams(dp(132), dp(76)).apply {
                rightMargin = dp(14)
            })
            row.addView(vertical().apply {
                addView(label(eventDisplayText(event), 17, Palette.text).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(label(eventSentence(event), 13, Palette.muted).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(eventObjectIcon(event))
                setColorFilter(Palette.accent)
                alpha = 0.9f
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            row.minimumHeight = dp(100)
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        return scroll
    }

    private fun renderGlobalEvents() {
        releasePlayer()
        setShell(globalEventList(globalResults.orEmpty()), Tab.Grid)
        if (globalResults == null && !globalLoading) loadGlobalEventPage(reset = true)
    }

    private fun loadGlobalEventPage(reset: Boolean) {
        val activeSession = session ?: return
        val activeApi = api ?: return
        if (globalLoading) return
        globalLoading = true
        globalError = false
        if (reset) {
            globalQueryAnchor = System.currentTimeMillis() / 1000.0
            globalResults = emptyList()
            globalHasMore = false
        }
        val before = if (reset) null else globalResults?.mapNotNull { it.startTime }?.minOrNull()
        val generation = ++globalQueryGeneration
        val search = eventSearch
        refreshGlobalEventResults()
        activeApi.searchEvents(activeSession.token, search, globalQueryAnchor, before) { result ->
            runOnUiThread {
                if (generation != globalQueryGeneration || session?.token != activeSession.token || search != eventSearch) return@runOnUiThread
                globalLoading = false
                result.onSuccess { page ->
                    globalResults = (globalResults.orEmpty() + page).distinctBy { it.id }.sortedByDescending { it.startTime ?: 0.0 }
                    globalHasMore = page.size >= 100
                }.onFailure { globalError = true }
                if (activeTab == Tab.Grid) refreshGlobalEventResults()
            }
        }
    }

    private fun changeEventSearch(next: EventSearch) {
        eventSearch = next
        globalQueryGeneration++
        globalLoading = false
        globalResults = null
        renderGlobalEvents()
    }

    private fun eventFilterBar(): View = appHorizontalScrollView().apply {
        val row = horizontal().apply { setPadding(0, 0, 0, dp(10)) }
        addView(row)
        fun menu(title: String, values: List<String>, checked: Int, select: (Int) -> Unit) {
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity).setTitle(title)
                .setSingleChoiceItems(values.toTypedArray(), checked) { dialog, index -> dialog.dismiss(); select(index) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        fun filter(title: String, action: () -> Unit) {
            row.addView(secondaryButton(title).apply {
                isAllCaps = false
                textSize = 12f * Appearance.textScale(appearancePrefs.getString("textSize", null))
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { rightMargin = dp(8) })
        }
        val options = visibleCameras()
        filter(options.firstOrNull { it.name == eventSearch.camera }?.friendlyName ?: getString(R.string.all_cameras)) {
            menu(getString(R.string.cameras), listOf(getString(R.string.all_cameras)) + options.map { it.friendlyName },
                options.indexOfFirst { it.name == eventSearch.camera } + 1) { index ->
                changeEventSearch(eventSearch.copy(camera = options.getOrNull(index - 1)?.name))
            }
        }
        filter(getString(eventSearch.kind.titleRes)) {
            val kinds = EventKind.values()
            menu(getString(R.string.filter_type), kinds.map { getString(it.titleRes) }, kinds.indexOf(eventSearch.kind)) {
                changeEventSearch(eventSearch.copy(kind = kinds[it]))
            }
        }
        filter(eventSearch.name ?: getString(R.string.filter_name)) {
            val input = editText(getString(R.string.filter_name), eventSearch.name.orEmpty())
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            val body = vertical().apply {
                setPadding(dp(20), dp(8), dp(20), dp(8))
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            }
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity).setTitle(R.string.filter_name)
                .setView(body).setPositiveButton(android.R.string.ok) { _, _ ->
                    changeEventSearch(eventSearch.copy(name = input.text.toString().trim().takeIf { it.isNotEmpty() }))
                }.setNeutralButton(R.string.clear) { _, _ -> changeEventSearch(eventSearch.copy(name = null)) }
                .setNegativeButton(android.R.string.cancel, null).show().also { input.requestFocus() }
        }
        val periods = listOf(null, 24, 168, 720)
        val titles = listOf(R.string.filter_any_time, R.string.filter_day, R.string.filter_week, R.string.filter_month)
        filter(getString(titles[periods.indexOf(eventSearch.hours).coerceAtLeast(0)])) {
            menu(getString(R.string.filter_period), titles.map { getString(it) }, periods.indexOf(eventSearch.hours)) {
                changeEventSearch(eventSearch.copy(hours = periods[it]))
            }
        }
        if (eventSearch != EventSearch()) filter(getString(R.string.filter_reset)) { changeEventSearch(EventSearch()) }
    }

    private fun globalEventList(sourceEvents: List<NativeEvent>): View {
        val scroll = appScrollView().apply {
            setBackgroundColor(Palette.background)
            clipToPadding = false
            setPadding(0, 0, 0, dp(78))
        }
        val column = vertical().apply { setPadding(dp(18), dp(8), dp(18), dp(120)) }
        scroll.addView(column, responsiveScrollParams(720))
        column.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_grid)
                setColorFilter(Palette.accent)
                alpha = 0.96f
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
            addView(vertical().apply {
                addView(label(getString(R.string.notifications), 25, Palette.text).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                addView(label(getString(R.string.all_cameras), 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toolbarIcon(R.drawable.ic_refresh) { loadGlobalEventPage(reset = true) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(eventFilterBar())
        val results = vertical()
        globalResultsContainer = results
        column.addView(results)
        populateGlobalEventResults(results, sourceEvents)
        return scroll
    }

    private fun refreshGlobalEventResults() {
        globalResultsContainer?.let { column ->
            column.removeAllViews()
            populateGlobalEventResults(column, globalResults.orEmpty())
        }
    }

    private fun populateGlobalEventResults(column: LinearLayout, sourceEvents: List<NativeEvent>) {
        val visibleNames = visibleCameras().map { it.name }.toSet()
        val globalEvents = sourceEvents.filter { it.camera in visibleNames && it.startTime != null }
            .sortedByDescending { it.startTime ?: 0.0 }
        if (globalError) {
            column.addView(secondaryButton(getString(R.string.filter_retry)).apply { setOnClickListener { loadGlobalEventPage(reset = globalResults.isNullOrEmpty()) } })
        }
        if (globalLoading && globalEvents.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.filter_loading)))
            return
        }
        if (globalEvents.isEmpty() && !globalError) {
            column.addView(emptyMessage(getString(R.string.no_notifications)))
            if (!globalHasMore) return
        }
        globalEvents.forEach { event ->
            column.addView(globalEventRow(event).apply { minimumHeight = dp(100) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        if (globalHasMore || globalLoading) column.addView(secondaryButton(getString(if (globalLoading) R.string.filter_loading else R.string.filter_more)).apply {
            isEnabled = !globalLoading
            setOnClickListener { loadGlobalEventPage(reset = false) }
        })
    }

    private fun globalEventRow(event: NativeEvent): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(12), dp(10))
            background = rounded(Palette.card, 8)
            setOnClickListener { renderEventDetail(event) }
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.surface)
            }
            addView(FrameLayout(context).apply {
                background = rounded(Palette.surface, 8)
                applyRoundedClip(8)
                addView(thumb, frameMatch())
            }, LinearLayout.LayoutParams(dp(132), dp(76)).apply { rightMargin = dp(14) })
            loadEventPreviewAnimated(event, thumb)
            addView(vertical().apply {
                addView(label(eventDisplayText(event), 17, Palette.text).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                addView(label(eventSentence(event), 13, Palette.muted).apply {
                    setPadding(0, dp(6), 0, 0)
                })
                event.description?.let { description ->
                    addView(label(getString(R.string.frigate_description_inline, description), 12, Palette.muted).apply {
                        maxLines = 3
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(6), 0, 0)
                    })
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(eventObjectIcon(event))
                setColorFilter(Palette.accent)
                alpha = 0.9f
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
    }

    private fun cameraTimeline(
        camera: NativeCamera,
        preview: ImageView,
        playerView: PlayerView,
        liveBadge: View,
        timelineDayMillis: Long,
        timelineEvents: List<NativeEvent>?,
        timelineFocusSec: Double?,
        onFocusChanged: (Double?, Boolean) -> Unit
    ): View {
        val activeSession = session
        val activeApi = api
        val cameraEvents = (timelineEvents ?: events)
            .filter { it.camera == camera.name }
            .filter { it.startTime != null }
            .sortedByDescending { it.startTime ?: 0.0 }
        val selectedToday = isSameLocalDay(timelineDayMillis, System.currentTimeMillis())
        val selectedDayStart = startOfLocalDayMillis(timelineDayMillis)
        val selectedDayEnd = selectedDayStart + TimeUnit.DAYS.toMillis(1) - 1000L
        val zoomLevels = intArrayOf(18, 34, 62, 100)
        var zoomIndex = prefs.getInt("timelineZoomIndex", 1).coerceIn(0, zoomLevels.lastIndex)
        val frame = FrameLayout(this).apply { setBackgroundColor(Palette.backgroundAlt) }
        val scroll = appScrollView().apply {
            clipToPadding = false
        }
        val column = FrameLayout(this)
        val estimatedTimelineHeight = maxOf(dp(420), resources.displayMetrics.heightPixels - cameraVideoHeight() - dp(168))
        val topSpacer = timelinePlayheadOffset(estimatedTimelineHeight).coerceIn(dp(120), dp(230))
        val bottomSpacer = dp(220)
        val railX = dp(164)
        val pxPerMinute = dp(zoomLevels[zoomIndex])
        val anchorSec = timelineAnchorSeconds(cameraEvents, selectedToday, selectedDayEnd)
        val oldestEventSec = cameraEvents.mapNotNull { it.startTime }.minOrNull() ?: (anchorSec - 1800.0)
        val spanMinutes = maxOf(18.0, (anchorSec - oldestEventSec) / 60.0 + 6.0)
        val timelineThumbWidth = dp(148)
        val timelineThumbHeight = dp(82)
        val stackedThumbHeight = cameraEvents.size * (timelineThumbHeight + dp(12))
        val timelineHeight = maxOf(
            topSpacer + bottomSpacer + (spanMinutes * pxPerMinute).toInt(),
            topSpacer + bottomSpacer + stackedThumbHeight
        )
        scroll.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, timelineHeight))

        fun yForTime(timeSec: Double): Int {
            val minutes = ((anchorSec - timeSec) / 60.0).coerceAtLeast(0.0)
            return topSpacer + (minutes * pxPerMinute).toInt()
        }

        val tickStepMinutes = when (zoomIndex) {
            0 -> 5
            1 -> 2
            else -> 1
        }
        val tickCount = (spanMinutes / tickStepMinutes).toInt() + 1
        repeat(tickCount) { tick ->
            val minutesFromAnchor = tick * tickStepMinutes
            val y = topSpacer + minutesFromAnchor * pxPerMinute
            val timeSec = anchorSec - minutesFromAnchor * 60.0
            val isHour = minutesFromAnchor % 60 == 0
            val isMajor = isHour || tickStepMinutes <= 2 || minutesFromAnchor % (tickStepMinutes * 3) == 0
            column.addView(View(this).apply {
                setBackgroundColor(if (isMajor) Palette.tick else Palette.tickMinor)
            }, FrameLayout.LayoutParams(dp(if (isMajor) 22 else 8), dp(1), Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(4)
                topMargin = y
            })
            if (isMajor) {
                column.addView(label(formatTimelineTime(timeSec), 12, Palette.muted), FrameLayout.LayoutParams(dp(76), dp(24), Gravity.START or Gravity.TOP).apply {
                    leftMargin = dp(28)
                    topMargin = y - dp(11)
                })
            }
            if (zoomIndex >= 1) {
                val minorStep = if (zoomIndex >= 2) 30 else 60
                var offsetSeconds = minorStep
                while (offsetSeconds < tickStepMinutes * 60) {
                    val minorY = y + (offsetSeconds / 60.0 * pxPerMinute).toInt()
                    if (minorY < timelineHeight - bottomSpacer) {
                        column.addView(View(this).apply {
                            setBackgroundColor(Palette.tickMinor)
                        }, FrameLayout.LayoutParams(dp(8), dp(1), Gravity.START or Gravity.TOP).apply {
                            leftMargin = dp(4)
                            topMargin = minorY
                        })
                    }
                    offsetSeconds += minorStep
                }
            }
        }
        column.addView(View(this).apply {
            background = rounded(0x36ff2d63, 4)
        }, FrameLayout.LayoutParams(dp(5), timelineHeight - topSpacer, Gravity.TOP or Gravity.START).apply {
            leftMargin = railX - dp(2)
            topMargin = topSpacer
        })

        if (cameraEvents.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.no_recent_video)).apply {
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                topMargin = topSpacer
            })
        } else {
            var lastThumbBottom = topSpacer - dp(18)
            cameraEvents.forEach { event ->
                val start = event.startTime ?: return@forEach
                val end = event.endTime ?: (start + 30.0)
                val eventTop = yForTime(end).coerceIn(topSpacer, timelineHeight - bottomSpacer)
                val eventBottom = yForTime(start).coerceAtLeast(eventTop + dp(26))
                val eventHeight = (eventBottom - eventTop).coerceAtLeast(dp(36))
                column.addView(View(this).apply {
                    background = rounded(Palette.accent, 8)
                }, FrameLayout.LayoutParams(dp(7), eventHeight, Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX - dp(3)
                    topMargin = eventTop
                })
                val eventCenter = eventTop + eventHeight / 2
                column.addView(View(this).apply {
                    background = rounded(Palette.backgroundAlt, 9, Palette.border, 2)
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX - dp(9)
                    topMargin = eventCenter - dp(9)
                })
                column.addView(View(this).apply {
                    setBackgroundColor(Palette.tick)
                }, FrameLayout.LayoutParams(dp(84), dp(1), Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX + dp(10)
                    topMargin = eventCenter
                })
                val icon = eventObjectIcon(event)
                val eventText = eventDisplayText(event)
                column.addView(vertical().apply {
                    gravity = Gravity.CENTER
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        setColorFilter(Palette.muted)
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(label(eventText, 11, Palette.muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(3), 0, 0)
                    }, LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT))
                    setOnClickListener { renderEventDetail(event) }
                }, FrameLayout.LayoutParams(dp(92), dp(54), Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX + dp(34)
                    topMargin = eventCenter - dp(27)
                })
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Palette.surface)
                }
                val thumbFrame = FrameLayout(this).apply {
                    background = rounded(Palette.card, 8)
                    applyRoundedClip(8)
                }
                loadEventPreviewAnimated(event, thumb)
                thumbFrame.addView(thumb, frameMatch())
                thumbFrame.addView(label(eventText, 12, Palette.text).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setShadowLayer(5f, 0f, 1f, Palette.black)
                    setPadding(dp(8), 0, dp(8), dp(6))
                    gravity = Gravity.BOTTOM or Gravity.START
                }, frameMatch())
                thumbFrame.setOnClickListener { renderEventDetail(event) }
                val thumbTop = maxOf(eventCenter - timelineThumbHeight / 2, lastThumbBottom + dp(12))
                lastThumbBottom = thumbTop + timelineThumbHeight
                column.addView(thumbFrame, FrameLayout.LayoutParams(timelineThumbWidth, timelineThumbHeight, Gravity.END or Gravity.TOP).apply {
                    rightMargin = dp(34)
                    topMargin = thumbTop
                })
            }
        }
        frame.addView(scroll, frameMatch())
        frame.addView(View(this).apply { setBackgroundColor(Palette.liveBlue) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.TOP).apply {
            topMargin = topSpacer
        })
        val currentTime = label(if (selectedToday) getString(R.string.live) else formatTimelineClock(anchorSec), 14, Palette.text).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(Palette.liveBlue, 14)
            setPadding(dp(10), 0, dp(10), 0)
        }
        frame.addView(currentTime, FrameLayout.LayoutParams(dp(106), dp(34), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(28)
            topMargin = topSpacer - dp(16)
        })
        val dateRow = FrameLayout(this).apply {
            addView(timelineDateButton(formatShortDate(selectedDayStart)).apply {
                setOnClickListener { showTimelineDatePicker(camera, timelineDayMillis) }
            }, FrameLayout.LayoutParams(dp(130), dp(46), Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(32)
            })
        }
        frame.addView(dateRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP))
        val returnLive = timelineReturnLiveButton().apply {
            visibility = if (selectedToday) View.GONE else View.VISIBLE
        }
        frame.addView(returnLive, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(16)
        })
        fun centerSecondForScroll(scrollY: Int): Double {
            val centerOffset = scrollY + topSpacer
            return anchorSec - ((centerOffset - topSpacer).toDouble() / pxPerMinute.toDouble()) * 60.0
        }

        fun scrollForSecond(timeSec: Double): Int {
            val target = yForTime(timeSec) - topSpacer
            return target.coerceIn(0, maxOf(0, timelineHeight - frame.height))
        }

        frame.addView(vertical().apply {
            gravity = Gravity.CENTER
            addView(timelineFloatButton(R.drawable.ic_zoom_out).apply {
                setOnClickListener {
                    if (zoomIndex > 0) {
                        val focusSec = centerSecondForScroll(scroll.scrollY)
                        prefs.edit().putInt("timelineZoomIndex", zoomIndex - 1).apply()
                        renderCameraDetail(camera, timelineDayMillis, cameraEvents, focusSec)
                    }
                }
            })
            addView(timelineFloatButton(R.drawable.ic_zoom_in).apply {
                setOnClickListener {
                    if (zoomIndex < zoomLevels.lastIndex) {
                        val focusSec = centerSecondForScroll(scroll.scrollY)
                        prefs.edit().putInt("timelineZoomIndex", zoomIndex + 1).apply()
                        renderCameraDetail(camera, timelineDayMillis, cameraEvents, focusSec)
                    }
                }
            })
            addView(timelineFloatButton(R.drawable.ic_list).apply {
                setOnClickListener {
                    renderCameraDetail(camera, timelineDayMillis, cameraEvents, centerSecondForScroll(scroll.scrollY), true)
                }
            })
        }, FrameLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(12)
        })
        var lastPlaybackSec: Double? = null
        var pendingScrollPlayback: Runnable? = null
        val interaction = TimelineInteractionGate()
        var timelineMode = false
        fun resumeLive() {
            if (activeSession == null || activeApi == null) return
            timelineMode = false
            interaction.commitScroll()
            lastPlaybackSec = null
            pendingScrollPlayback?.let { mainHandler.removeCallbacks(it) }
            pendingScrollPlayback = null
            currentTime.text = getString(R.string.live)
            onFocusChanged(null, true)
            returnLive.visibility = View.GONE
            releaseCurrentPlayer()
            playerView.visibility = View.GONE
            preview.visibility = View.VISIBLE
            liveBadge.visibility = View.GONE
            activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
                runOnUiThread { bitmap?.let { preview.setImageBitmap(it) } }
            }
            startMjpegStream(activeApi, activeSession.token, camera, preview, liveBadge)
        }
        fun returnToLive() {
            if (!selectedToday) {
                renderCameraDetail(camera)
                return
            }
            if (reducedMotion) {
                scroll.scrollTo(0, 0)
                resumeLive()
                return
            }
            val current = scroll.scrollY
            if (current > frame.height * 2) {
                scroll.smoothScrollTo(0, current / 2)
                mainHandler.postDelayed({ scroll.smoothScrollTo(0, 0) }, 180L)
            } else {
                scroll.smoothScrollTo(0, 0)
            }
            mainHandler.postDelayed({ resumeLive() }, 460L)
        }
        returnLive.setOnClickListener { returnToLive() }
        var syncingPlaybackScroll = false
        fun updatePlaybackClock(playbackSec: Double) {
            if (!timelineMode || !interaction.followsPlayback || frame.height <= 0) return
            currentTime.text = formatTimelineClock(playbackSec)
            onFocusChanged(playbackSec, false)
            lastPlaybackSec = playbackSec
            val target = scrollForSecond(playbackSec)
            if (kotlin.math.abs(scroll.scrollY - target) > dp(1)) {
                syncingPlaybackScroll = true
                scroll.scrollTo(0, target)
                syncingPlaybackScroll = false
            }
        }
        fun updatePreviewForScroll(scrollY: Int) {
            if (activeSession == null || activeApi == null || frame.height <= 0) return
            val centerSec = centerSecondForScroll(scrollY)
            if (selectedToday && centerSec >= anchorSec - 3.0) {
                pendingScrollPlayback?.let { mainHandler.removeCallbacks(it) }
                pendingScrollPlayback = null
                if (timelineMode) resumeLive()
                currentTime.text = getString(R.string.live)
                onFocusChanged(null, true)
                returnLive.visibility = View.GONE
                return
            }
            currentTime.text = formatTimelineClock(centerSec)
            onFocusChanged(centerSec, false)
            val previousPlaybackSec = lastPlaybackSec
            if (previousPlaybackSec != null && kotlin.math.abs(previousPlaybackSec - centerSec) < 1.0) return
            lastPlaybackSec = centerSec
            interaction.beginScroll()
            timelinePlaybackGeneration += 1
            if (!timelineMode) {
                timelineMode = true
                stopMjpegStreams()
                liveBadge.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                returnLive.visibility = View.VISIBLE
            }
            pendingScrollPlayback?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!timelineMode) return@Runnable
                pendingScrollPlayback = null
                interaction.commitScroll()
                startTimelineRecordingPlayer(activeApi, activeSession.token, camera, playerView, preview, centerSec, ::updatePlaybackClock)
            }
            pendingScrollPlayback = runnable
            mainHandler.postDelayed(runnable, 160L)
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (scrollY != oldScrollY && !syncingPlaybackScroll) updatePreviewForScroll(scrollY)
        }
        frame.post {
            val focus = timelineFocusSec
            if (focus != null) {
                val target = scrollForSecond(focus)
                scroll.scrollTo(0, target)
                updatePreviewForScroll(target)
            } else if (selectedToday) {
                scroll.scrollTo(0, 0)
                currentTime.text = getString(R.string.live)
                onFocusChanged(null, true)
                returnLive.visibility = View.GONE
            }
        }
        return frame
    }

    private fun eventDurationMs(event: NativeEvent): Long {
        val start = event.startTime ?: return 0L
        val end = event.endTime ?: return 0L
        return maxOf(0L, ((end - start) * 1000).toLong())
    }

    private fun timelineAnchorSeconds(events: List<NativeEvent>, selectedToday: Boolean, selectedDayEnd: Long): Double {
        if (selectedToday) return System.currentTimeMillis() / 1000.0
        val newest = events.mapNotNull { it.endTime ?: it.startTime }.maxOrNull()
        return (newest ?: (selectedDayEnd / 1000.0)) + 120.0
    }

    private fun nearestEvent(events: List<NativeEvent>, centerSec: Double): NativeEvent? =
        events.minByOrNull { event ->
            val start = event.startTime ?: return@minByOrNull Double.MAX_VALUE
            val end = event.endTime ?: (start + 30.0)
            when {
                centerSec in start..end -> 0.0
                centerSec < start -> start - centerSec
                else -> centerSec - end
            }
        }

    private fun eventObjectIcon(event: NativeEvent): Int {
        val label = event.objectLabel.lowercase(Locale.ROOT)
        return when {
            listOf("car", "vehicle", "truck", "bus", "motorcycle", "bicycle").any { label.contains(it) } -> R.drawable.ic_vehicle
            listOf("dog", "cat", "bird", "animal").any { label.contains(it) } -> R.drawable.ic_animal
            else -> R.drawable.ic_person
        }
    }

    private fun eventDisplayText(event: NativeEvent): String =
        event.subLabel?.takeIf { it.isNotBlank() } ?: localizedAlertLabel(this, event.objectLabel.ifBlank { event.label })

    private fun eventSentence(event: NativeEvent): String = eventNarrative(this,
        EventFacts(event.objectLabel, event.subLabel, event.description),
        cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera.replace('_', ' '), event.startTime)

    private fun recentActivitySummary(): View? {
        val activeSession = session ?: return null
        val allowed = visibleCameras().map { it.name }.toSet()
        val now = System.currentTimeMillis() / 1000.0
        val recent = events.filter { it.camera in allowed && it.startTime?.let { time -> time in (now - 86400)..now } == true }
        val key = "activityAcknowledged:${activeSession.baseUrl}:${activeSession.username}"
        val acknowledged = prefs.getStringSet(key, emptySet()).orEmpty()
        val unread = unreadActivities(recent.map { ActivityItem(it.id, it.camera, it.startTime!!,
            EventFacts(it.objectLabel, it.subLabel, it.description)) }, acknowledged)
        val groups = groupActivities(unread).take(3)
        if (groups.isEmpty()) return null
        val summary = groups.joinToString(" · ") { group ->
            val event = recent.first { it.id == group.latest.id }
            val camera = cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera.replace('_', ' ')
            getString(R.string.activity_compact_item, eventDisplayText(event), camera, group.count)
        }
        return vertical().apply {
            val card = this
            setPadding(dp(14), dp(12), dp(14), dp(4))
            background = rounded(Palette.card, 8, Palette.border, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(4)
            }
            addView(label(getString(R.string.recent_activity), 14, Palette.text).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(label(summary, 12, Palette.muted).apply {
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, dp(4))
            })
            addView(label(resources.getQuantityString(R.plurals.activity_detections, unread.size, unread.size), 11, Palette.muted))
            addView(horizontal().apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                fun action(titleRes: Int, run: () -> Unit): View = label(getString(titleRes), 13, Palette.accent).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(14), 0, dp(14), 0)
                    minimumHeight = dp(48)
                    isFocusable = true
                    setOnClickListener { run() }
                }
                addView(action(R.string.activity_understood) {
                    prefs.edit().putStringSet(key, (unread.map { it.id } + acknowledged).take(1000).toSet()).apply()
                    card.visibility = View.GONE
                })
                addView(action(R.string.activity_review) { changeEventSearch(EventSearch()) })
            })
        }
    }

    private fun showTimelineDatePicker(camera: NativeCamera, timelineDayMillis: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = timelineDayMillis }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                loadCameraTimelineDay(camera, selected.timeInMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun loadCameraTimelineDay(camera: NativeCamera, dayMillis: Long) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        val dayStart = startOfLocalDayMillis(dayMillis)
        val dayEnd = dayStart + TimeUnit.DAYS.toMillis(1) - 1000L
        renderLoading(getString(R.string.loading_day, formatShortDate(dayStart)))
        activeApi.loadEvents(activeSession.token, camera.name, dayStart / 1000.0, dayEnd / 1000.0) { result ->
            runOnUiThread {
                result.onSuccess { loadedEvents ->
                    renderCameraDetail(camera, dayStart, loadedEvents)
                }.onFailure {
                    toast(it.message ?: getString(R.string.videos_unavailable))
                    renderCameraDetail(camera, dayStart, emptyList())
                }
            }
        }
    }

    private fun isSameLocalDay(leftMillis: Long, rightMillis: Long): Boolean {
        val left = Calendar.getInstance().apply { timeInMillis = leftMillis }
        val right = Calendar.getInstance().apply { timeInMillis = rightMillis }
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
            left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)
    }

    private fun startOfLocalDayMillis(value: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = value
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun formatTimelineTime(timeSec: Double): String =
        SimpleDateFormat("HH:mm", resources.configuration.locales[0]).format(Date((timeSec * 1000).toLong()))

    private fun formatTimelineClock(timeSec: Double): String =
        SimpleDateFormat("HH:mm:ss", resources.configuration.locales[0]).format(Date((timeSec * 1000).toLong()))

    private fun formatShortDate(value: Long): String =
        SimpleDateFormat("d MMM", resources.configuration.locales[0]).format(Date(value))

    private fun formatLongDate(value: Long): String =
        SimpleDateFormat("EEE d MMM yy", resources.configuration.locales[0]).format(Date(value)).replaceFirstChar { it.lowercase(resources.configuration.locales[0]) }

    private fun syncCameraGrid(event: NativeEvent, displayedCameraName: String): View {
        val grid = vertical()
        val visibleNames = visibleCameras().map { it.name }.toSet()
        val cameraByName = cameras.associateBy { it.name }
        sameMomentCameraNames(cameras.map { it.name }, displayedCameraName, visibleNames)
            .mapNotNull { cameraByName[it] }
            .forEach { camera ->
                val row = horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { renderEventDetail(event, camera.name) }
                }
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Palette.surface)
                }
                row.addView(image, LinearLayout.LayoutParams(dp(160), dp(92)))
                session?.let { activeSession ->
                    api?.loadBitmap(activeSession.token, api?.recordingSnapshotUrl(camera.name, event.startTime ?: 0.0).orEmpty()) { bitmap ->
                        runOnUiThread {
                            if (bitmap != null) {
                                image.setImageBitmap(bitmap)
                            } else {
                                api?.loadBitmap(activeSession.token, api?.latestFrameUrl(camera.name).orEmpty()) { latest ->
                                    runOnUiThread { latest?.let { image.setImageBitmap(it) } }
                                }
                            }
                        }
                    }
                }
                row.addView(vertical().apply {
                    setPadding(dp(14), 0, 0, 0)
                    addView(label(camera.friendlyName, 18, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
                    addView(label(event.time, 15, Palette.muted))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                grid.addView(row)
            }
        return grid
    }

    private fun setShell(content: View, activeTab: Tab, showHeader: Boolean = true, animateIn: Boolean = false) {
        this.activeTab = activeTab
        releaseLoadingAnimations()
        dismissTransientPopups()
        val root = vertical().apply { setBackgroundColor(Palette.background) }
        if (showHeader) root.addView(appHeader())
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navigation = bottomNav(activeTab)
        root.addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        keepAboveSystemNavigation(root, if (content is NativeChatView) navigation else null)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        if (animateIn) {
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(if (reducedMotion) 0L else 220L).start()
        }
    }

    private fun appHeader(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
            addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.fricamgate_logo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(50), dp(50)))
                addView(vertical().apply {
                    setPadding(dp(10), 0, 0, 0)
                    addView(label("CamGate", 24, Palette.text).apply {
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    }, LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(label("Frigate Client", 12, Palette.muted).apply {
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        gravity = Gravity.START
                        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        letterSpacing = 0.01f
                        textScaleX = 1.1f
                    }, LinearLayout.LayoutParams(dp(118), ViewGroup.LayoutParams.WRAP_CONTENT))
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(58), Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(18)
                topMargin = dp(42)
            })
            addView(alertsButton(), FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.TOP).apply {
                rightMargin = dp(18)
                topMargin = dp(52)
            })
            addView(chatButton(), FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.TOP).apply {
                rightMargin = dp(74)
                topMargin = dp(52)
            })
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)) }
    }

    private fun alertsButton(): View {
        val alertCount = alertEvents().size
        return FrameLayout(this).apply {
            isClickable = true
            contentDescription = getString(R.string.alerts)
            background = rounded(Palette.card, 23, 0x1eff2d63, 1)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_bell)
                setColorFilter(Palette.text)
                alpha = 0.94f
            }, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
            if (alertCount > 0) {
                addView(label(alertCount.coerceAtMost(99).toString(), 9, Palette.text).apply {
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = rounded(Palette.accent, 9)
                }, FrameLayout.LayoutParams(dp(if (alertCount > 9) 20 else 16), dp(16), Gravity.END or Gravity.TOP).apply {
                    topMargin = dp(2)
                    rightMargin = dp(2)
                })
            }
            setOnClickListener { showAlertsPopup(it) }
        }
    }

    private fun homeTitleRow(message: String?): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(12))
            addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_videocam)
                        setColorFilter(Palette.accent)
                        rotation = -10f
                    }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                }, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    rightMargin = dp(7)
                })
                addView(label(getString(R.string.cameras), 25, Palette.text).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!message.isNullOrBlank() && resources.configuration.screenWidthDp >= 700) {
                addView(label(message, 12, Palette.muted).apply {
                    setPadding(0, 0, dp(12), 0)
                })
            }
            val layout = homeCameraLayout()
            addView(toolbarIcon(R.drawable.ic_layout_selector, tint = Palette.muted) { anchor -> showCameraLayoutMenu(anchor) }.apply {
                val description = getString(R.string.camera_layout)
                contentDescription = description
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun homeCameraLayout(): HomeCameraLayout = HomeCameraLayout.fromPreference(
        getSharedPreferences("camgate-settings", MODE_PRIVATE).getString("homeCameraLayout", null)
    )

    private fun showCameraLayoutMenu(anchor: View) {
        val selected = homeCameraLayout()
        showHomeViewMenu(anchor, HomeCameraLayout.values().map { layout ->
            homeMenuRow(layout.iconRes, getString(layout.titleRes), selected == layout) {
                getSharedPreferences("camgate-settings", MODE_PRIVATE).edit()
                    .putString("homeCameraLayout", layout.preference).apply()
                if (selected != layout) renderHome()
            }
        })
    }

    private fun homeMenuRow(icon: Int, title: String, selected: Boolean, onClick: () -> Unit): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        isFocusable = true
        isSelected = selected
        contentDescription = title
        addView(ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(if (selected) Palette.accent else Palette.text)
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) })
        addView(label(title, 14, if (selected) Palette.accent else Palette.text),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) addView(android.widget.RadioButton(context).apply {
            isChecked = true
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        setOnClickListener {
            dismissCameraPicker()
            onClick()
        }
    }

    private fun showHomeViewMenu(anchor: View, rows: List<View>) {
        dismissTransientPopups()
        val menu = vertical().apply {
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = rounded(Palette.card, 8, Palette.divider, 1)
            rows.forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))) }
        }
        val popup = PopupWindow(menu, minOf(dp(260), resources.displayMetrics.widthPixels - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(12).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { if (cameraPickerPopup === this) cameraPickerPopup = null }
        }
        cameraPickerPopup = popup
        popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    }

    private fun chatButton(): View {
        return FrameLayout(this).apply {
            isClickable = true
            contentDescription = getString(R.string.ai_chat)
            background = rounded(Palette.card, 22, Palette.divider, 1)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chat)
                setColorFilter(Palette.text)
                alpha = 0.96f
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { renderLiaChat() }
        }
    }

    private fun syncWebViewAuthCookie(webView: WebView, activeSession: NativeSession) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }
        val secure = if (normalizeBaseUrl(activeSession.baseUrl).startsWith("https://", ignoreCase = true)) "; Secure" else ""
        cookieManager.setCookie(activeSession.baseUrl, "frigate_token=${activeSession.token}; Path=/$secure")
        cookieManager.flush()
    }

    private fun logoHeader(): View = ImageView(this).apply {
        setImageResource(R.drawable.fricamgate_logo)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)) }

    private fun bottomNav(activeTab: Tab): View {
        val row = horizontal().apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.black, 0)
        }
        Tab.values().forEach { tab ->
            val item = FrameLayout(this).apply {
                isClickable = true
                contentDescription = getString(when (tab) {
                    Tab.Home -> R.string.home
                    Tab.Cameras -> R.string.cameras
                    Tab.Grid -> R.string.grid_tab
                    Tab.Events -> R.string.events_tab
                    Tab.Settings -> R.string.settings
                })
            }
            item.setOnClickListener {
                when (tab) {
                    Tab.Home -> renderHome()
                    Tab.Cameras -> showCameraPickerPopup(item)
                    Tab.Grid -> renderGlobalEvents()
                    Tab.Events -> events.firstOrNull()?.let { renderEventDetail(it) } ?: renderHome()
                    Tab.Settings -> renderSettings()
                }
            }
            val iconHolder = FrameLayout(this).apply {
                background = rounded(Color.TRANSPARENT, 12)
            }
            iconHolder.addView(ImageView(this).apply {
                setImageResource(tab.iconRes)
                setColorFilter(if (tab == activeTab) Palette.accent else Palette.text)
                alpha = if (tab == activeTab) 1f else 0.86f
            }, FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            item.addView(iconHolder, FrameLayout.LayoutParams(dp(58), dp(54), Gravity.CENTER))
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    private fun showCameraPickerPopup(anchor: View) {
        dismissTransientPopups()
        val choices = visibleCameras()
        if (choices.isEmpty()) {
            toast(getString(R.string.no_visible))
            return
        }
        val card = vertical().apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Palette.card, 16, Palette.border, 1)
        }
        card.addView(label(getString(R.string.pick_camera), 13, Palette.muted).apply {
            setPadding(dp(8), dp(4), dp(8), dp(10))
        })
        val activeSession = session
        val activeApi = api
        choices.forEach { camera ->
            val row = horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = rounded(Color.TRANSPARENT, 10)
                setOnClickListener {
                    dismissCameraPicker()
                    renderCameraDetail(camera)
                }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.surface)
            }
            row.addView(FrameLayout(this).apply {
                background = rounded(Palette.surface, 7)
                applyRoundedClip(7)
                addView(thumb, frameMatch())
            }, LinearLayout.LayoutParams(dp(72), dp(44)).apply {
                rightMargin = dp(12)
            })
            row.addView(vertical().apply {
                addView(label(camera.friendlyName, 15, Palette.text))
                addView(label(getString(R.string.open_live), 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_videocam)
                setColorFilter(Palette.accent)
                alpha = 0.95f
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
                topMargin = dp(2)
            })
            if (activeSession != null && activeApi != null) {
                activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
                    runOnUiThread { bitmap?.let { thumb.setImageBitmap(it) } }
                }
            }
        }
        val content = appScrollView().apply {
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popupWidth = cameraPickerPopupWidth(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
            dp(360),
            dp(32)
        )
        val popupHeight = minOf(dp(390), dp(56) + choices.size * dp(62))
        val popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            elevation = dp(18).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                if (cameraPickerPopup === this) cameraPickerPopup = null
            }
        }
        cameraPickerPopup = popup
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(86))
    }

    private fun dismissCameraPicker() {
        cameraPickerPopup?.dismiss()
        cameraPickerPopup = null
    }

    private fun dismissAlertsPopup() {
        alertsPopup?.dismiss()
        alertsPopup = null
    }

    private fun dismissTransientPopups() {
        dismissCameraPicker()
        dismissAlertsPopup()
    }

    private fun alertEvents(): List<NativeEvent> {
        val visibleNames = visibleCameras().map { it.name }.toSet()
        val seenIds = seenAlertIds()
        val seenCutoffSec = seenAlertsCutoffMillis() / 1000.0
        val nowSec = System.currentTimeMillis() / 1000.0
        return events
            .asSequence()
            .filter { it.camera in visibleNames }
            .filter { it.id !in seenIds }
            .filter { event ->
                val started = event.startTime ?: return@filter true
                started > seenCutoffSec
            }
            .filter { event ->
                val started = event.startTime ?: return@filter true
                nowSec - started <= 24 * 60 * 60
            }
            .take(30)
            .toList()
    }

    private fun showAlertsPopup(anchor: View) {
        dismissTransientPopups()
        val alerts = alertEvents()
        val card = vertical().apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Palette.card, 16, Palette.border, 1)
        }
        card.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_bell)
                setColorFilter(Palette.accent)
                alpha = 0.95f
            }, LinearLayout.LayoutParams(dp(21), dp(21)).apply { rightMargin = dp(9) })
            addView(vertical().apply {
                addView(label(getString(R.string.alerts), 16, Palette.text).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                addView(label(if (alerts.isEmpty()) getString(R.string.no_events_short) else getString(R.string.alert_count, alerts.size), 12, Palette.muted).apply {
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (alerts.isNotEmpty()) {
                addView(label(getString(R.string.clear), 12, Palette.accent).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(13), 0, dp(13), 0)
                    background = rounded(Palette.surface, 14)
                    setOnClickListener {
                        markAlertsSeen(alerts, advanceCutoff = true)
                        dismissAlertsPopup()
                        renderHome(getString(R.string.cleared))
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        if (alerts.isEmpty()) {
            card.addView(label(getString(R.string.no_alerts), 14, Palette.muted).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
                background = rounded(Palette.card, 12)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            alerts.take(12).forEach { event ->
                card.addView(alertRow(event).apply { minimumHeight = dp(72) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            }
        }

        val content = appScrollView().apply {
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popupWidth = minOf(resources.displayMetrics.widthPixels - dp(28), dp(410))
        val popupHeight = minOf(dp(520), dp(86) + maxOf(1, alerts.take(12).size) * dp(76))
        val popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            elevation = dp(18).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                if (alertsPopup === this) alertsPopup = null
            }
        }
        alertsPopup = popup
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.END, dp(12), dp(104))
    }

    private fun alertRow(event: NativeEvent): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Palette.card, 12)
            setOnClickListener {
                markAlertSeen(event)
                dismissAlertsPopup()
                renderEventDetail(event)
            }
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.surface)
            }
            addView(FrameLayout(context).apply {
                background = rounded(Palette.surface, 8)
                applyRoundedClip(8)
                addView(thumb, frameMatch())
            }, LinearLayout.LayoutParams(dp(82), dp(54)).apply { rightMargin = dp(12) })
            loadEventPreviewAnimated(event, thumb)
            addView(vertical().apply {
                addView(label(eventDisplayText(event), 15, Palette.text).apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                })
                addView(label(eventSentence(event), 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(eventObjectIcon(event))
                setColorFilter(Palette.accent)
                alpha = 0.95f
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
        }
    }

    private fun markAlertSeen(event: NativeEvent) {
        markAlertsSeen(listOf(event), advanceCutoff = false)
    }

    private fun markAlertsSeen(items: List<NativeEvent>, advanceCutoff: Boolean) {
        val currentEventIds = events.map { it.id }.toSet()
        val ids = seenAlertIds()
            .filter { it in currentEventIds }
            .toMutableSet()
        ids.addAll(items.map { it.id }.filter { it.isNotBlank() })
        seenAlertIdsMemory.clear()
        seenAlertIdsMemory.addAll(ids)
        val editor = prefs.edit().putStringSet(seenAlertsKey(), ids)
        if (advanceCutoff) {
            val newestSeenMillis = items
                .mapNotNull { it.startTime }
                .maxOrNull()
                ?.times(1000)
                ?.toLong()
                ?: System.currentTimeMillis()
            editor.putLong(seenAlertsCutoffKey(), maxOf(seenAlertsCutoffMillis(), newestSeenMillis))
        }
        editor.commit()
    }

    private fun showCameraOptionsPopup(camera: NativeCamera, anchor: View) {
        dismissTransientPopups()
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        val card = vertical().apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Palette.card, 16, Palette.border, 1)
        }
        card.addView(label(camera.friendlyName, 16, Palette.text).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(4))
        })
        card.addView(localSoundRow())
        listOf(
            CameraFeatureControl(getString(R.string.detection), getString(R.string.object_analysis), "detect", camera.detectEnabled),
            CameraFeatureControl(getString(R.string.motion), getString(R.string.motion_detection), "motion", camera.motionEnabled),
            CameraFeatureControl(getString(R.string.recordings), getString(R.string.recordings_detail), "recordings", camera.recordingsEnabled),
            CameraFeatureControl(getString(R.string.snapshots), getString(R.string.event_images), "snapshots", camera.snapshotsEnabled),
            CameraFeatureControl(getString(R.string.audio), getString(R.string.audio_detection), "audio", camera.audioEnabled),
            CameraFeatureControl(getString(R.string.transcription), getString(R.string.audio_transcription), "audio_transcription", camera.audioTranscriptionEnabled),
            CameraFeatureControl(getString(R.string.notifications), getString(R.string.camera_notifications), "notifications", camera.notificationsEnabled)
        ).forEach { control ->
            card.addView(cameraFeatureToggleRow(activeApi, activeSession.token, camera, control))
        }
        card.addView(disabledCameraOptionRow(getString(R.string.microphone), getString(R.string.api_unavailable)))
        card.addView(disabledCameraOptionRow(getString(R.string.alarm), getString(R.string.api_unavailable)))

        val content = appScrollView().apply {
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popupWidth = minOf(resources.displayMetrics.widthPixels - dp(28), dp(390))
        val popupHeight = minOf(dp(540), dp(72) + dp(58) * 10)
        val popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            elevation = dp(18).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                if (cameraPickerPopup === this) cameraPickerPopup = null
            }
        }
        cameraPickerPopup = popup
        popup.showAtLocation(anchor, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(86))
    }

    private fun localSoundRow(): View {
        var internalChange = false
        val switchView = SwitchCompat(this).apply {
            isChecked = soundEnabled
        }
        switchView.setOnCheckedChangeListener { _, checked ->
            if (internalChange) return@setOnCheckedChangeListener
            if (checked != soundEnabled) toggleSound()
        }
        return settingsToggleRow(getString(R.string.playback_sound), getString(R.string.playback_sound_detail), switchView)
    }

    private fun cameraFeatureToggleRow(
        api: FrigateNativeApi,
        token: String,
        camera: NativeCamera,
        control: CameraFeatureControl
    ): View {
        var internalChange = false
        val switchView = SwitchCompat(this).apply {
            isChecked = control.enabled ?: true
        }
        switchView.setOnCheckedChangeListener { _, checked ->
            if (internalChange) return@setOnCheckedChangeListener
            api.setCameraFeature(token, camera.name, control.feature, checked) { result ->
                runOnUiThread {
                    result.onSuccess {
                        toast(getString(R.string.feature_changed, control.title, getString(if (checked) R.string.enabled else R.string.disabled)))
                    }.onFailure {
                        toast(getString(R.string.feature_unavailable, control.title))
                        internalChange = true
                        switchView.isChecked = !checked
                        internalChange = false
                    }
                }
            }
        }
        val detail = if (control.enabled == null) getString(R.string.unknown_state, control.detail) else control.detail
        return settingsToggleRow(control.title, detail, switchView)
    }

    private fun settingsToggleRow(title: String, detail: String, switchView: SwitchCompat): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(8))
            addView(vertical().apply {
                addView(label(title, 14, Palette.text))
                addView(label(detail, 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchView)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        }
    }

    private fun disabledCameraOptionRow(title: String, detail: String): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0.58f
            setPadding(dp(8), dp(8), dp(4), dp(8))
            addView(vertical().apply {
                addView(label(title, 14, Palette.text))
                addView(label(detail, 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("N/A", 12, Palette.muted).apply {
                gravity = Gravity.CENTER
                background = rounded(Palette.surface, 10)
                setPadding(dp(10), 0, dp(10), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58))
        }
    }

    private fun saveCameraSnapshot(camera: NativeCamera, focusSec: Double?, isLive: Boolean) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        val url = if (!isLive && focusSec != null) {
            activeApi.recordingSnapshotUrl(camera.name, focusSec)
        } else {
            activeApi.latestFrameUrl(camera.name)
        }
        toast(getString(R.string.capture_pending))
        activeApi.loadBitmap(activeSession.token, url) { bitmap ->
            runOnUiThread {
                if (bitmap == null) {
                    toast(getString(R.string.capture_failed))
                } else {
                    saveBitmapToGallery(bitmap, camera)
                }
            }
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, camera: NativeCamera) {
        val fileName = "FriCamGate_${camera.name}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FriCamGate")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast(getString(R.string.save_failed))
            return
        }
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw IOException("Compression impossible.")
                }
            } ?: throw IOException("Flux image indisponible.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        }.onSuccess {
            toast(getString(R.string.capture_saved))
        }.onFailure {
            resolver.delete(uri, null, null)
            toast(getString(R.string.save_failed))
        }
    }

    private fun renderSettings() {
        settingsPage = "main"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(120)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(pageTitle(getString(R.string.settings)))
        column.addView(sectionHeading(getString(R.string.general)))
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.server), session?.baseUrl ?: "-", { renderServerSettings() }),
            settingsNavRow(getString(R.string.cameras), getString(R.string.camera_count, visibleCameras().size, cameras.size), { renderCameraSettings() }),
            settingsNavRow(getString(R.string.about), "FriCamGate ${appVersionName()}", { renderAboutSettings() }),
            settingsNavRow(getString(R.string.language), currentLanguageLabel(), { renderLanguageSettings() }),
            settingsNavRow(getString(R.string.appearance), getString(R.string.appearance_detail), { renderAppearanceSettings() })
        ))
        column.addView(sectionHeading(getString(R.string.security)))
        val biometricSwitch = SwitchCompat(this).apply {
            isChecked = appLock.enabled
            contentDescription = getString(R.string.biometric_lock)
            setOnCheckedChangeListener { button, checked ->
                button.isEnabled = false
                appLock.changeEnabled(checked) { actual ->
                    button.isEnabled = true
                    if (actual != checked) renderSettings()
                }
            }
        }
        column.addView(preferenceToggleRow(getString(R.string.biometric_lock),
            getString(if (appLock.available() || appLock.enabled) R.string.biometric_detail else R.string.biometric_unavailable), biometricSwitch))
        column.addView(sectionHeading(getString(R.string.notifications)))
        column.addView(settingsGroup(settingsNavRow(getString(R.string.device_alerts), notificationStatus(), { renderNotificationSettings() })))
        column.addView(sectionHeading(getString(R.string.updates_heading)))
        column.addView(settingsUpdateCard())
        column.addView(sectionHeading(getString(R.string.actions)))
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.reload), getString(R.string.reload_detail), { loadDashboard(getString(R.string.reloaded)) }, showChevron = false)
        ))
        setShell(scroll, Tab.Settings)
        if (updateResult == null && !updateChecking) {
            checkForUpdates(renderSettingsWhenDone = true)
        }
    }

    private fun currentLanguageLabel(): String {
        val code = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
        return appLanguages.firstOrNull { it.first == code }?.second
            ?.takeIf { it.isNotEmpty() } ?: getString(R.string.system_language)
    }

    private fun renderAppearanceSettings() {
        settingsPage = "appearance"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(settingsPageHeader(getString(R.string.appearance)))
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> appearanceScrollY = y }
        fun choices(titleRes: Int, key: String, fallback: String, options: List<Pair<String, Int>>) {
            column.addView(sectionHeading(getString(titleRes)))
            var selected = appearancePrefs.getString(key, fallback)
            val radios = mutableMapOf<String, android.widget.RadioButton>()
            column.addView(settingsGroup(*options.map { (value, textRes) ->
                horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, dp(12), 0)
                    isFocusable = true
                    if (key == "accent" || key == "backgroundStyle") {
                        addView(View(context).apply {
                            background = rounded(if (key == "accent") Appearance.accent(value) else BackgroundStyle.fromPreference(value).background, 8, Palette.border, 1)
                        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(12) })
                    }
                    addView(settingsTitleLabel(getString(textRes)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(android.widget.RadioButton(context).apply {
                        radios[value] = this
                        isChecked = value == selected
                        isClickable = false
                        isFocusable = false
                        buttonTintList = android.content.res.ColorStateList.valueOf(if (isChecked) Palette.accent else Palette.muted)
                    })
                    setOnClickListener {
                        if (value != selected) {
                            appearanceScrollY = scroll.scrollY
                            val previous = selected
                            selected = value
                            appearancePrefs.edit().putString(key, value).apply()
                            radios.forEach { (option, radio) ->
                                radio.isChecked = option == value
                                radio.buttonTintList = android.content.res.ColorStateList.valueOf(if (radio.isChecked) Palette.accent else Palette.muted)
                            }
                            if (key == "theme") {
                                recreate()
                            } else if (key == "textSize") {
                                val ratio = Appearance.textScale(value) / Appearance.textScale(previous)
                                fun resize(view: View) {
                                    if (view is TextView) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, view.textSize * ratio)
                                    if (view is ViewGroup) for (index in 0 until view.childCount) resize(view.getChildAt(index))
                                }
                                resize(window.decorView)
                            } else if (key != "homeCameraLayout") {
                                Palette.accent = Appearance.accent(appearancePrefs.getString("accent", null))
                                Palette.backgroundStyle = BackgroundStyle.fromPreference(appearancePrefs.getString("backgroundStyle", null))
                                window.statusBarColor = Palette.background
                                window.navigationBarColor = Palette.black
                                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                                    isAppearanceLightStatusBars = !Palette.dark
                                    isAppearanceLightNavigationBars = !Palette.dark
                                }
                                renderAppearanceSettings()
                            }
                        }
                    }
                }
            }.toTypedArray()))
        }
        choices(R.string.theme, "theme", "system", listOf("system" to R.string.system_language, "dark" to R.string.theme_dark, "light" to R.string.theme_light))
        choices(R.string.background_style, "backgroundStyle", "automatic", BackgroundStyle.values().map { it.preference to it.titleRes })
        choices(R.string.accent_color, "accent", "red", listOf("red" to R.string.accent_red, "blue" to R.string.accent_blue, "green" to R.string.accent_green, "violet" to R.string.accent_violet))
        choices(R.string.text_size, "textSize", "standard", listOf("small" to R.string.size_small, "standard" to R.string.size_standard, "large" to R.string.size_large))
        choices(R.string.camera_layout, "homeCameraLayout", "column", HomeCameraLayout.values().map { it.preference to it.titleRes })
        column.addView(sectionHeading(getString(R.string.animations)))
        val motionSwitch = SwitchCompat(this).apply {
            isChecked = reducedMotion
            contentDescription = getString(R.string.reduce_motion)
            setOnCheckedChangeListener { _, checked ->
                appearancePrefs.edit().putBoolean("reducedMotion", checked).apply()
            }
        }
        column.addView(preferenceToggleRow(getString(R.string.reduce_motion), getString(R.string.reduce_motion_detail), motionSwitch))
        val restoreY = appearanceScrollY
        setShell(scroll, Tab.Settings)
        scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                scroll.scrollTo(0, restoreY)
                return true
            }
        })
    }

    private fun renderLanguageSettings() {
        settingsPage = "language"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(settingsPageHeader(getString(R.string.language)))
        column.addView(sectionHeading(getString(R.string.language_detail)))
        val selected = AppCompatDelegate.getApplicationLocales().get(0)?.language.orEmpty()
        column.addView(settingsGroup(*appLanguages.map { (code, nativeName) ->
            val title = if (code.isEmpty()) getString(R.string.system_language) else nativeName
            horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(12), 0)
                isFocusable = true
                contentDescription = title
                addView(settingsTitleLabel(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(android.widget.RadioButton(context).apply {
                    isChecked = code == selected
                    isClickable = false
                    isFocusable = false
                    buttonTintList = android.content.res.ColorStateList.valueOf(if (isChecked) Palette.accent else Palette.muted)
                })
                setOnClickListener {
                    if (code != selected) {
                        restoredSettingsPage = "language"
                        getSharedPreferences("camgate-settings", MODE_PRIVATE).edit().putString("language", code).apply()
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
                    }
                }
            }
        }.toTypedArray()))
        setShell(scroll, Tab.Settings)
    }

    private fun notificationStatus(): String = getString(when {
        !CameraNotifications.permitted(this) -> R.string.notifications_blocked
        CameraNotifications.enabled(this) -> R.string.notifications_enabled
        else -> R.string.notifications_disabled
    })

    private fun renderNotificationSettings() {
        settingsPage = "notifications"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(32)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(settingsPageHeader(getString(R.string.notifications)))
        val switch = SwitchCompat(this).apply {
            isChecked = CameraNotifications.enabled(context) && CameraNotifications.permitted(context)
            contentDescription = getString(R.string.device_alerts)
            setOnCheckedChangeListener { _, checked ->
                if (checked) enableDeviceNotifications() else {
                    CameraNotifications.configure(this@MainActivity, false)
                    renderNotificationSettings()
                }
            }
        }
        column.addView(preferenceToggleRow(getString(R.string.device_alerts), notificationStatus(), switch))
        column.addView(label(getString(R.string.notifications_background), 13, Palette.muted).apply {
            setPadding(dp(2), dp(16), dp(2), dp(16))
        })
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.android_settings), getString(R.string.device_alerts), { openAndroidNotificationSettings() }),
            settingsNavRow(getString(R.string.test_notification), getString(R.string.event_channel), {
                if (CameraNotifications.enabled(this) && CameraNotifications.permitted(this)) CameraNotifications.test(this)
                else enableDeviceNotifications()
            }, showChevron = false)
        ))
        setShell(scroll, Tab.Settings)
    }

    private fun enableDeviceNotifications() {
        val settingsPrefs = getSharedPreferences("camgate-settings", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val asked = settingsPrefs.getBoolean("notificationPermissionAsked", false)
            settingsPrefs.edit().putBoolean("notificationPermissionAsked", true).putBoolean("notificationEnablePending", true).apply()
            if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openAndroidNotificationSettings()
            else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!CameraNotifications.permitted(this)) {
            settingsPrefs.edit().putBoolean("notificationEnablePending", true).apply()
            openAndroidNotificationSettings()
        } else {
            CameraNotifications.configure(this, true)
            renderNotificationSettings()
        }
    }

    private fun openAndroidNotificationSettings() {
        val settingsIntent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(settingsIntent)
    }

    private fun preferenceToggleRow(title: String, detail: String, toggle: SwitchCompat): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(12), dp(16))
        background = rounded(Palette.card, settingsMenuCornerRadiusDp(), Palette.divider, 1)
        addView(vertical().apply {
            setPadding(0, 0, dp(12), 0)
            addView(settingsTitleLabel(title).apply { maxLines = 2 })
            addView(label(detail, 13, Palette.muted).apply { setPadding(0, dp(6), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle)
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun renderServerSettings() {
        settingsPage = "server"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(120)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(settingsPageHeader(getString(R.string.server)))
        column.addView(sectionHeading(getString(R.string.connection)))
        val serverInput = editText("http://cam.example.fr", session?.baseUrl ?: prefs.getString("baseUrl", "").orEmpty())
        val usernameInput = editText("admin", session?.username ?: prefs.getString("username", "").orEmpty())
        val passwordInput = editText(getString(R.string.password_hint), "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(label(getString(R.string.frigate_server), 12, Palette.muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        column.addView(serverInput, matchWrap())
        column.addView(label(getString(R.string.username), 12, Palette.muted).apply { setPadding(0, dp(16), 0, dp(8)) })
        column.addView(usernameInput, matchWrap())
        column.addView(label(getString(R.string.password), 12, Palette.muted).apply { setPadding(0, dp(16), 0, dp(8)) })
        column.addView(passwordInput, matchWrap())
        column.addView(primaryButton(getString(R.string.save_reconnect)).apply {
            setOnClickListener { connect(serverInput.text.toString(), usernameInput.text.toString(), passwordInput.text.toString()) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(22) })
        column.addView(settingsGroup(
            settingsRow(getString(R.string.current_server), session?.baseUrl ?: "-"),
            settingsRow(getString(R.string.current_user), session?.username ?: "-")
        ))
        column.addView(primaryButton(getString(R.string.logout)).apply {
            setOnClickListener {
                prefs.edit().remove("token").apply()
                session = null
                api = null
                CameraNotifications.schedule(this@MainActivity)
                renderLogin()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) })
        setShell(scroll, Tab.Settings)
    }

    private fun renderCameraSettings() {
        settingsPage = "cameras"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(120)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(settingsPageHeader(getString(R.string.cameras)))
        column.addView(sectionHeading(getString(R.string.visibility)))
        column.addView(settingsGroup(settingsRow(getString(R.string.home), getString(R.string.camera_count_detail, visibleCameras().size, cameras.size))))
        if (cameras.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.no_cameras)))
        } else {
            column.addView(settingsGroup(*cameras.map { cameraVisibilityRow(it) }.toTypedArray()))
        }
        setShell(scroll, Tab.Settings)
    }

    private fun renderAboutSettings() {
        settingsPage = "about"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(120)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(settingsPageHeader(getString(R.string.about)))
        column.addView(sectionHeading(getString(R.string.application)))
        column.addView(settingsGroup(
            settingsRow(getString(R.string.name), "FriCamGate"),
            settingsRow(getString(R.string.app_version), appVersionName())
        ))
        column.addView(sectionHeading("NVR"))
        column.addView(settingsGroup(
            settingsRow(getString(R.string.nvr_version), nvrVersion ?: getString(R.string.loading)),
            settingsRow(getString(R.string.server), session?.baseUrl ?: "-")
        ))
        setShell(scroll, Tab.Settings)
        if (nvrVersion == null) {
            loadNvrVersion(renderAboutWhenDone = true)
        }
    }

    private fun settingsRow(title: String, value: String): View {
        return FrameLayout(this).apply {
            addView(vertical().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(18), 0)
                addView(settingsTitleLabel(title))
                addView(settingsDetailLabel(value))
            }, frameMatch())
        }
    }

    private fun settingsNavRow(title: String, detail: String, onClick: () -> Unit, showChevron: Boolean = true): View {
        return FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(vertical().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), 0, dp(settingsMenuTextEndPaddingDp(showChevron)), 0)
                addView(settingsTitleLabel(title))
                addView(settingsDetailLabel(detail))
            }, frameMatch())
            if (showChevron) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(Palette.muted)
                    alpha = 0.7f
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    rightMargin = dp(18)
                })
            }
        }
    }

    private fun cameraVisibilityRow(camera: NativeCamera): View {
        val visible = isCameraVisible(camera)
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(12), 0)
            addView(vertical().apply {
                addView(settingsTitleLabel(camera.friendlyName))
                addView(settingsDetailLabel(if (camera.enabled) camera.name else getString(R.string.camera_disabled, camera.name)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(SwitchCompat(context).apply {
                isChecked = visible
                isEnabled = camera.enabled
                setOnCheckedChangeListener { _, checked ->
                    setCameraHidden(camera.name, !checked)
                    renderCameraSettings()
                }
            })
        }
    }

    private fun settingsUpdateCard(): View {
        val result = updateResult
        val downloadedVersion = prefs.getString("updateDownloadVersion", null)
        val downloadId = if (downloadedVersion != null && compareVersions(downloadedVersion, appVersionName()) > 0)
            prefs.getLong("updateDownloadId", -1L) else -1L
        val statusText = when {
            updateChecking -> getString(R.string.searching)
            result == null -> getString(R.string.search_available)
            result.available -> getString(R.string.update_new)
            result.latestVersion != null -> getString(R.string.up_to_date)
            else -> getString(R.string.update_failed)
        }
        return vertical().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Palette.card, settingsMenuCornerRadiusDp(), Palette.divider, 1)
            addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(settingsTitleLabel(getString(R.string.updates)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(toolbarIcon(R.drawable.ic_refresh, tint = Palette.muted) {
                    checkForUpdates(renderSettingsWhenDone = true)
                }.apply {
                    isEnabled = !updateChecking
                    alpha = if (updateChecking) 0.4f else 1f
                    contentDescription = getString(R.string.check)
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                })
            })
            addView(settingsDetailLabel(statusText).apply {
                if (result?.available == true) setTextColor(Palette.accent)
            })
            addView(settingsDetailLabel(getString(R.string.installed_latest, appVersionName(), result?.latestVersion ?: "-")))
            if (result?.available == true && !result.downloadUrl.isNullOrBlank() && downloadId == -1L) {
                addView(primaryButton(getString(R.string.update_available)).apply {
                    setOnClickListener { downloadUpdate(result) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(16) })
            }
            if (downloadId != -1L) {
                val downloadStatus = settingsDetailLabel("")
                val progress = android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    progressTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
                    max = 100
                }
                val install = primaryButton(getString(R.string.update_install)).apply {
                    visibility = View.GONE
                    setOnClickListener { installDownloadedUpdate(downloadId) }
                }
                addView(downloadStatus)
                addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10); bottomMargin = dp(12) })
                addView(install, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                val card = this
                fun poll() {
                    if (!card.isAttachedToWindow) return
                    val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val state = runCatching {
                        manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                            if (!cursor.moveToFirst()) return@use null
                            Triple(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        }
                    }.getOrNull()
                    when (state?.first) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloadStatus.text = getString(R.string.update_ready)
                            progress.visibility = View.GONE
                            install.visibility = View.VISIBLE
                        }
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                            val percent = updateDownloadPercent(state.second, state.third)
                            downloadStatus.text = if (percent == null) getString(R.string.update_downloading) else getString(R.string.update_progress, percent)
                            progress.isIndeterminate = percent == null
                            progress.progress = percent ?: 0
                            card.postDelayed({ poll() }, 700)
                        }
                        else -> {
                            downloadStatus.text = getString(R.string.update_download_failed)
                            progress.visibility = View.GONE
                            install.visibility = View.VISIBLE
                            install.text = getString(R.string.update_retry)
                            install.setOnClickListener {
                                manager.remove(downloadId)
                                prefs.edit().remove("updateDownloadId").apply()
                                if (result?.available == true) downloadUpdate(result) else refreshUpdateCard()
                            }
                        }
                    }
                }
                post { poll() }
            }
            if (updateChecking) addView(android.widget.ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(12) })
        }.also {
            updateCardView = it
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        }
    }

    private fun settingsPageHeader(title: String): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(settingsBackButton(), LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(12)
            })
            addView(pageTitle(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun settingsBackButton(): View {
        return FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = rounded(Palette.overlay, 22)
            setOnClickListener { renderSettings() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_back)
                setColorFilter(Palette.text)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
    }

    private fun settingsGroup(vararg rows: View): View {
        return vertical().apply {
            background = rounded(Palette.card, settingsMenuCornerRadiusDp(), Palette.divider, 1)
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    addView(View(context).apply {
                        setBackgroundColor(Palette.divider)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(18)
                        rightMargin = dp(14)
                    })
                }
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(settingsMenuRowHeightDp())))
            }
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
    }

    private fun settingsTitleLabel(textValue: String): TextView {
        return label(textValue, SETTINGS_MENU_TITLE_SP, Palette.text).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun settingsDetailLabel(textValue: String): TextView {
        return label(textValue, SETTINGS_MENU_DETAIL_SP, Palette.muted).apply {
            setPadding(0, dp(4), 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun pageTitle(textValue: String): TextView {
        return label(textValue, 28, Palette.text).apply {
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(18))
        }
    }

    private fun sectionHeading(textValue: String): TextView {
        return label(textValue, 12, Palette.muted).apply {
            setPadding(dp(2), dp(22), 0, dp(2))
        }
    }

    private fun renderLoading(text: String) {
        releasePlayer()
        releaseLoadingAnimations()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
        }
        val content = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        val logoFrame = FrameLayout(this)
        val ring = LoadingRingView(this)
        logoFrame.addView(ring, FrameLayout.LayoutParams(dp(128), dp(128), Gravity.CENTER))
        logoFrame.addView(ImageView(this).apply {
            setImageResource(R.drawable.fricamgate_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER))
        content.addView(logoFrame, LinearLayout.LayoutParams(dp(136), dp(136)))
        content.addView(label("CamGate", 24, Palette.text).apply {
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(18), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(label("Frigate Client", 12, Palette.muted).apply {
            gravity = Gravity.CENTER
            textScaleX = 1.1f
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val status = label(text.ifBlank { localizedLoadingStage(0) }, 14, Palette.muted).apply {
            gravity = Gravity.CENTER
            alpha = 0.86f
            setPadding(0, dp(28), 0, 0)
        }
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val progressTrack = FrameLayout(this).apply {
            background = rounded(Palette.surface, 2)
        }
        val progressFill = View(this).apply {
            background = rounded(Palette.accent, 2)
            pivotX = 0f
            scaleX = 0.18f
        }
        progressTrack.addView(progressFill, frameMatch())
        content.addView(progressTrack, LinearLayout.LayoutParams(dp(176), dp(3)).apply {
            topMargin = dp(20)
        })
        val slowPanel = vertical().apply {
            alpha = 0f
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
            background = rounded(Palette.card, 10, 0x25ff2d70, 1)
            addView(label(getString(R.string.slow_connection), 13, Palette.text).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(horizontal().apply {
                gravity = Gravity.CENTER
                addView(secondaryButton(getString(R.string.retry)).apply {
                    textSize = 13f
                    setOnClickListener {
                        if (session == null) renderLogin() else loadDashboard(getString(R.string.reloaded))
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f))
                addView(primaryButton(getString(R.string.server)).apply {
                    textSize = 13f
                    setOnClickListener {
                        if (session == null) renderLogin() else renderServerSettings()
                    }
                }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
                topMargin = dp(12)
            })
        }
        content.addView(slowPanel, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(34)
        })
        root.addView(content, FrameLayout.LayoutParams(contentWidth(420), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        keepAboveSystemNavigation(root)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        if (reducedMotion) {
            progressFill.scaleX = 1f
            mainHandler.postDelayed({
                if (root.isAttachedToWindow) slowPanel.visibility = View.VISIBLE
            }, LOADING_SLOW_ACTIONS_DELAY_MS)
            return
        }
        val ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation -> ring.progress = animation.animatedValue as Float }
            start()
        }
        val barAnimator = ValueAnimator.ofFloat(0.18f, 1f).apply {
            duration = 1900L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animation -> progressFill.scaleX = animation.animatedValue as Float }
            start()
        }
        val stageAnimator = ValueAnimator.ofInt(0, LOADING_STAGE_COUNT - 1).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animation -> status.text = localizedLoadingStage(animation.animatedValue as Int) }
            start()
        }
        loadingAnimators.addAll(listOf(ringAnimator, barAnimator, stageAnimator))
        mainHandler.postDelayed({
            if (root.isAttachedToWindow && loadingAnimators.isNotEmpty()) {
                slowPanel.visibility = View.VISIBLE
                slowPanel.animate().alpha(1f).setDuration(220L).start()
            }
        }, LOADING_SLOW_ACTIONS_DELAY_MS)
    }

    private fun saveSession(value: NativeSession) {
        prefs.edit()
            .putString("baseUrl", value.baseUrl)
            .putString("token", value.token)
            .putString("username", value.username)
            .apply()
    }

    private fun localizedLoadingStage(index: Int): String = getString(when (((index % 3) + 3) % 3) {
        0 -> R.string.connecting_nvr
        1 -> R.string.loading_cameras
        else -> R.string.preparing_live
    })

    private fun loadSession(): NativeSession? {
        val baseUrl = prefs.getString("baseUrl", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val username = prefs.getString("username", "admin") ?: "admin"
        return NativeSession(baseUrl, token, username)
    }

    private fun visibleCameras(): List<NativeCamera> = cameras.filter { isCameraVisible(it) }

    private fun isCameraVisible(camera: NativeCamera): Boolean =
        camera.enabled && !hiddenCameraNames().contains(camera.name)

    private fun hiddenCameraNames(): Set<String> =
        prefs.getStringSet(hiddenCameraKey(), emptySet())?.toSet() ?: emptySet()

    private fun setCameraHidden(cameraName: String, hidden: Boolean) {
        val names = hiddenCameraNames().toMutableSet()
        if (hidden) {
            names.add(cameraName)
        } else {
            names.remove(cameraName)
        }
        prefs.edit().putStringSet(hiddenCameraKey(), names).apply()
    }

    private fun hiddenCameraKey(): String = "hiddenCameras:${session?.baseUrl ?: "default"}"

    private fun seenAlertIds(): Set<String> {
        val saved = prefs.getStringSet(seenAlertsKey(), emptySet())?.toSet() ?: emptySet()
        if (saved.isNotEmpty()) seenAlertIdsMemory.addAll(saved)
        return saved + seenAlertIdsMemory
    }

    private fun seenAlertsKey(): String = "seenAlerts:${session?.baseUrl ?: "default"}"

    private fun seenAlertsCutoffMillis(): Long =
        prefs.getLong(seenAlertsCutoffKey(), 0L)

    private fun seenAlertsCutoffKey(): String = "seenAlertsCutoff:${session?.baseUrl ?: "default"}"

    private fun appVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }
            .getOrDefault("")
            .ifBlank { "0.2.16" }

    private fun loadNvrVersion(renderAboutWhenDone: Boolean) {
        val activeSession = session ?: return
        val activeApi = api ?: return
        nvrVersion = getString(R.string.loading)
        activeApi.loadNvrVersion(activeSession.token) { result ->
            runOnUiThread {
                nvrVersion = result.getOrElse { getString(R.string.unavailable) }
                if (renderAboutWhenDone) renderAboutSettings()
            }
        }
    }

    private fun checkForUpdates(renderSettingsWhenDone: Boolean) {
        if (updateChecking) return
        updateChecking = true
        if (renderSettingsWhenDone) refreshUpdateCard()
        AppUpdateService(httpClient).check(appVersionName()) { result ->
            runOnUiThread {
                updateChecking = false
                updateResult = result.getOrElse {
                    NativeUpdateResult(
                        available = false,
                        message = getString(R.string.update_failed),
                        latestVersion = null,
                        assetName = null,
                        downloadUrl = null
                    )
                }
                if (renderSettingsWhenDone && activeTab == Tab.Settings && settingsPage == "main") refreshUpdateCard()
            }
        }
    }

    private fun downloadUpdate(update: NativeUpdateResult) {
        val url = update.downloadUrl
        if (url.isNullOrBlank() || !trustedUpdateUrl(url)) {
            toast(getString(R.string.download_unavailable))
            return
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val previousId = prefs.getLong("updateDownloadId", -1L)
        if (previousId != -1L) {
            val active = runCatching {
                manager.query(DownloadManager.Query().setFilterById(previousId)).use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_FAILED
                }
            }.getOrDefault(false)
            if (active && prefs.getString("updateDownloadUrl", null) == url) {
                refreshUpdateCard()
                return
            }
            manager.remove(previousId)
        }
        val fileName = "camgate-update-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("FriCamGate ${update.latestVersion ?: ""}".trim())
            .setDescription(getString(R.string.download_description))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .addRequestHeader("User-Agent", "FriCamGate")
        runCatching { manager.enqueue(request) }.onSuccess { id ->
            prefs.edit().putLong("updateDownloadId", id).putString("updateDownloadUrl", url)
                .putString("updateDownloadVersion", update.latestVersion).apply()
            refreshUpdateCard()
        }.onFailure { toast(getString(R.string.update_download_failed)) }
    }

    private fun refreshUpdateCard() {
        val old = updateCardView ?: return
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        val replacement = settingsUpdateCard()
        parent.removeViewAt(index)
        parent.addView(replacement, index)
    }

    private fun installDownloadedUpdate(id: Long) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                toast(getString(R.string.update_install_permission))
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = manager.getUriForDownloadedFile(id) ?: error("Missing update")
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast(getString(R.string.update_install_failed)) }
    }

    private fun releasePlayer() {
        globalResultsContainer = null
        resumeHomeAudioKey = null
        dismissCameraPicker()
        homeStreamGeneration += 1
        homeAudioGeneration += 1
        timelinePlaybackGeneration += 1
        timelineFrameGeneration += 1
        mainHandler.removeCallbacksAndMessages(null)
        releaseLoadingAnimations()
        releaseHomeAudioWebView()
        releaseCurrentPlayer()
        homeStreams.forEach { it.close(1000, "released") }
        homeStreams.clear()
        streamRestarts.clear()
        homeSoundIcons.clear()
    }

    private fun releaseLoadingAnimations() {
        loadingAnimators.forEach { it.cancel() }
        loadingAnimators.clear()
    }

    private fun releaseCurrentPlayer() {
        currentPlayer?.release()
        currentPlayer = null
        currentPlaybackKey = null
    }

    private fun releaseHomeAudioWebView() {
        homeAudioWebView?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        homeAudioWebView = null
    }

    private fun stopMjpegStreams() {
        homeStreamGeneration += 1
        homeStreams.forEach { it.close(1000, "timeline") }
        homeStreams.clear()
        streamRestarts.clear()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun editText(hintValue: String, textValue: String): EditText {
        return EditText(this).apply {
            hint = hintValue
            setText(textValue)
            textSize = 17f * Appearance.textScale(appearancePrefs.getString("textSize", null))
            setTextColor(Palette.text)
            setHintTextColor(Palette.muted)
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Palette.card, 6)
        }
    }

    private fun primaryButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            textSize = 16f * Appearance.textScale(appearancePrefs.getString("textSize", null))
            setTextColor(Color.WHITE)
            background = rounded(Palette.accent, 6)
        }
    }

    private fun secondaryButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            textSize = 15f * Appearance.textScale(appearancePrefs.getString("textSize", null))
            setTextColor(Palette.text)
            background = rounded(Palette.surface, 6)
        }
    }

    private fun roundButton(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Palette.text)
            background = rounded(Palette.overlay, 28)
        }
    }

    private fun chip(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 12.5f * Appearance.textScale(appearancePrefs.getString("textSize", null))
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(Palette.text)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Palette.overlay, 16)
        }
    }

    private fun liveBadge(): View {
        return horizontal().apply {
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, dp(8), 0)
            background = rounded(Palette.overlay, 14)
            addView(View(context).apply {
                background = rounded(Palette.accent, 3)
            }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { rightMargin = dp(5) })
            addView(label(getString(R.string.live), 10, Palette.text).apply {
                alpha = 0.92f
            })
        }
    }

    private fun keepAboveSystemNavigation(root: View, chatNavigation: View? = null) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            chatNavigation?.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            view.setPadding(0, 0, 0, if (chatNavigation != null) maxOf(bars.bottom, ime.bottom) else bars.bottom)
            insets
        }
    }

    private fun iconBubble(
        iconRes: Int,
        tint: Int = Palette.text,
        alphaValue: Float = 1f,
        onClick: ((View) -> Unit)? = null
    ): View {
        return FrameLayout(this).apply {
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick(it) }
            background = rounded(Palette.overlay, 22)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
                alpha = alphaValue
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) }
        }
    }

    private fun homeSoundBubble(camera: NativeCamera): View {
        val icon = ImageView(this).apply { setColorFilter(Palette.text) }
        homeSoundIcons[camera.name] = icon
        return FrameLayout(this).apply {
            isClickable = true
            background = rounded(Palette.overlay, 22)
            addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                val enabled = toggleHomeSound(camera)
                refreshHomeSoundIcons()
                toast(if (enabled) getString(R.string.sound_on) else getString(R.string.sound_off))
            }
            refreshHomeSoundIcons()
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(6) }
        }
    }

    private fun toolbarIcon(iconRes: Int, tint: Int = Palette.muted, onClick: ((View) -> Unit)? = null): View {
        return FrameLayout(this).apply {
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick(it) }
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
                alpha = 0.9f
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(42), dp(52))
        }
    }

    private fun soundToolbarIcon(): View {
        val icon = ImageView(this).apply {
            alpha = 0.9f
        }
        fun refresh() {
            icon.setImageResource(if (soundEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off)
            icon.setColorFilter(if (soundEnabled) Palette.accent else Palette.muted)
        }
        return FrameLayout(this).apply {
            isClickable = true
            addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                toggleSound()
                refresh()
            }
            refresh()
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(42), dp(52))
        }
    }

    private fun timelineFloatButton(iconRes: Int): View {
        return FrameLayout(this).apply {
            background = rounded(Palette.card, 23)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Palette.text)
                alpha = 0.8f
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(12) }
        }
    }

    private fun timelineReturnLiveButton(): View {
        return FrameLayout(this).apply {
            background = rounded(Palette.accent, 22)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_up)
                setColorFilter(Palette.text)
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
    }

    private fun soundToggleButton(): View {
        val icon = ImageView(this).apply {
            setColorFilter(Palette.text)
            alpha = 0.95f
        }
        fun refresh() {
            icon.setImageResource(if (soundEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off)
        }
        return FrameLayout(this).apply {
            background = rounded(Palette.overlay, 22)
            addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                toggleSound()
                refresh()
            }
            refresh()
        }
    }

    private fun timelineDateButton(textValue: String): View {
        return horizontal().apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Palette.card, 10)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_calendar)
                setColorFilter(Palette.muted)
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) })
            addView(label(textValue, 13, Palette.muted).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    private fun loadEventPreview(event: NativeEvent, target: ImageView) {
        event.thumbnail?.let {
            target.setImageBitmap(it)
            return
        }
        val activeSession = session ?: return
        val activeApi = api ?: return
        activeApi.loadBitmap(activeSession.token, activeApi.eventSnapshotUrl(event.id)) { bitmap ->
            runOnUiThread { bitmap?.let { target.setImageBitmap(it) } }
        }
    }

    private fun loadEventPreviewAnimated(event: NativeEvent, target: ImageView) {
        event.thumbnail?.let { target.setImageBitmap(it) }
        val activeSession = session ?: return
        val activeApi = api ?: return
        activeApi.loadGif(activeSession.token, activeApi.eventPreviewUrl(event.id)) { drawable ->
            runOnUiThread {
                if (drawable != null) {
                    target.setImageDrawable(drawable)
                    (drawable as? GifDrawable)?.start()
                } else if (event.thumbnail == null) {
                    activeApi.loadBitmap(activeSession.token, activeApi.eventSnapshotUrl(event.id)) { bitmap ->
                        runOnUiThread { bitmap?.let { target.setImageBitmap(it) } }
                    }
                }
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }
    }

    private fun View.applyRoundedClip(radiusDp: Int) {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
            }
        }
        clipToOutline = true
    }

    private fun centeredScrollParams(maxWidthDp: Int): FrameLayout.LayoutParams {
        return FrameLayout.LayoutParams(contentWidth(maxWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun responsiveScrollParams(maxWidthDp: Int): FrameLayout.LayoutParams {
        val screenWidthDp = resources.configuration.screenWidthDp
        val width = if (screenWidthDp >= 700) contentWidth(maxWidthDp) else ViewGroup.LayoutParams.MATCH_PARENT
        return FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
    }

    private fun contentWidth(maxWidthDp: Int): Int {
        return minOf(resources.displayMetrics.widthPixels - dp(32), dp(maxWidthDp))
    }

    private fun label(textValue: String, size: Int, color: Int): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = size * Appearance.textScale(appearancePrefs.getString("textSize", null))
            setTextColor(color)
            includeFontPadding = false
        }
    }

    private fun emptyMessage(textValue: String): View {
        return label(textValue, 15, Palette.muted).apply {
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Palette.card)
        }
    }

    private fun dashboardErrorCard(message: String): View {
        return vertical().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Palette.card, 8)
            addView(label(getString(R.string.frigate_error), 16, Palette.text).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(label(message, 13, Palette.muted).apply {
                setPadding(0, dp(8), 0, 0)
            })
            addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(secondaryButton(getString(R.string.reload)).apply {
                    setOnClickListener { loadDashboard(getString(R.string.reloaded)) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(primaryButton(getString(R.string.server)).apply {
                    setOnClickListener { renderServerSettings() }
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    leftMargin = dp(10)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply {
                topMargin = dp(14)
            })
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
    private fun frameMatch() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun cameraVideoHeight(): Int = minOf(dp(340), maxOf(dp(220), resources.displayMetrics.widthPixels * 9 / 16))
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun appScrollView(): ScrollView =
        ScrollView(this).apply { hideScrollIndicators() }

    private fun appHorizontalScrollView(): HorizontalScrollView =
        HorizontalScrollView(this).apply { hideScrollIndicators() }

    private fun View.hideScrollIndicators() {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    companion object {
        private const val HOME_MJPEG_STARTUP_TIMEOUT_MS = 8000L
    }
}

private const val TIMELINE_RECORDING_BUCKET_SECONDS = 20L
private const val TIMELINE_RECORDING_PREROLL_SECONDS = 8L
private const val TIMELINE_RECORDING_WINDOW_SECONDS = 45L
private const val TIMELINE_PLAYBACK_CLOCK_MS = 500L
private const val TIMELINE_PLAYHEAD_FRACTION = 0.25f
private const val LOADING_STAGE_COUNT = 3
private const val LOADING_SLOW_ACTIONS_DELAY_MS = 6000L
private const val SETTINGS_MENU_ROW_HEIGHT_DP = 68
private const val SETTINGS_MENU_RADIUS_DP = 12
private const val SETTINGS_MENU_TITLE_SP = 15
private const val SETTINGS_MENU_DETAIL_SP = 12

internal fun settingsMenuRowHeightDp(): Int = SETTINGS_MENU_ROW_HEIGHT_DP

internal fun settingsMenuCornerRadiusDp(): Int = SETTINGS_MENU_RADIUS_DP

internal fun settingsMenuTextEndPaddingDp(showChevron: Boolean): Int =
    if (showChevron) 44 else 18

internal fun sameMomentCameraNames(
    cameraNames: List<String>,
    displayedCameraName: String,
    visibleCameraNames: Set<String> = cameraNames.toSet()
): List<String> =
    cameraNames.filter { cameraName ->
        cameraName != displayedCameraName && cameraName in visibleCameraNames
    }

fun loadingStageMessage(index: Int): String =
    when (((index % LOADING_STAGE_COUNT) + LOADING_STAGE_COUNT) % LOADING_STAGE_COUNT) {
        0 -> "Connexion au NVR..."
        1 -> "Chargement des cameras..."
        else -> "Preparation du live..."
    }

private class LoadingRingView(context: Context) : View(context) {
    var progress: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val bounds = RectF()
    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * density
        color = Palette.divider
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f * density
        color = Palette.accent
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6.5f * density
        color = 0x25ff2d70
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 10f * density
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, trackPaint)
        val start = progress * 360f - 90f
        canvas.drawArc(bounds, start, 112f, false, glowPaint)
        canvas.drawArc(bounds, start, 92f, false, accentPaint)
        canvas.drawArc(bounds, start + 178f, 34f, false, trackPaint)
    }
}

private class FrigateNativeApi(
    private val baseUrl: String,
    private val client: OkHttpClient
) {
    fun login(username: String, password: String, callback: (Result<NativeLogin>) -> Unit) {
        postLogin(username, password, listOf("/api/login", "/login"), callback)
    }

    private fun postLogin(username: String, password: String, paths: List<String>, callback: (Result<NativeLogin>) -> Unit) {
        val path = paths.firstOrNull() ?: "/api/login"
        val body = JSONObject()
            .put("user", username)
            .put("password", password)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint(path))
            .post(body)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                response.use { httpResponse ->
                    if (httpResponse.code == 404 && paths.size > 1) {
                        postLogin(username, password, paths.drop(1), callback)
                        return
                    }
                    if (!httpResponse.isSuccessful) {
                        callback(Result.failure(IOException("Frigate HTTP ${httpResponse.code}")))
                        return
                    }

                    val payload = httpResponse.body?.string().orEmpty()
                    val json = parseJsonObject(payload, path, allowEmpty = true).getOrElse {
                        callback(Result.failure(it))
                        return
                    }
                    val token = json.optString("token").ifBlank { json.optString("access_token") }
                        .ifBlank { bearerTokenFromHeader(httpResponse.header("Authorization")) }
                        .ifBlank { tokenFromCookies(httpResponse.headers("Set-Cookie")) }
                    if (token.isBlank()) {
                        callback(Result.failure(IOException("Jeton Frigate absent.")))
                        return
                    }
                    callback(Result.success(NativeLogin(token, json.optString("username").ifBlank { username })))
                }
            }
        })
    }

    fun loadDashboard(token: String, callback: (Result<DashboardData>) -> Unit) {
        getJson(token, "/api/config") { configResult ->
            configResult.onFailure { callback(Result.failure(it)); return@getJson }
            getJsonArray(token, "/api/events?limit=100&include_thumbnails=1") { eventsResult ->
                eventsResult.onSuccess { eventArray ->
                    runCatching {
                        val cameras = parseCameras(configResult.getOrThrow())
                        val events = parseEvents(eventArray)
                        DashboardData(cameras, events)
                    }.onSuccess { dashboard ->
                        if (dashboard.cameras.isNotEmpty()) {
                            callback(Result.success(dashboard))
                        } else {
                            loadCameraStats(token) { statsResult ->
                                val fallbackCameras = statsResult.getOrElse { emptyList() }
                                callback(Result.success(dashboard.copy(cameras = fallbackCameras)))
                            }
                        }
                    }.onFailure {
                        callback(Result.failure(IOException("Tableau de bord Frigate invalide.", it)))
                    }
                }.onFailure { callback(Result.failure(it)) }
            }
        }
    }

    fun loadEvents(
        token: String,
        cameraName: String,
        afterSec: Double,
        beforeSec: Double,
        callback: (Result<List<NativeEvent>>) -> Unit
    ) {
        val path = "/api/events?camera=${Uri.encode(cameraName)}&after=${afterSec.toLong()}&before=${beforeSec.toLong()}&limit=500&include_thumbnails=1"
        getJsonArray(token, path) { result ->
            result
                .mapCatching { parseEvents(it) }
                .onSuccess { callback(Result.success(it)) }
                .onFailure { callback(Result.failure(it)) }
        }
    }

    fun searchEvents(token: String, search: EventSearch, now: Double, before: Double?, callback: (Result<List<NativeEvent>>) -> Unit) {
        getJsonArray(token, search.query(now, before)) { result -> callback(result.mapCatching { parseEvents(it) }) }
    }

    fun loadNvrVersion(token: String, callback: (Result<String>) -> Unit) {
        val request = authRequest(token, "/api/version")
            .header("Accept", "text/plain,application/json")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        callback(Result.failure(IOException("Frigate HTTP ${httpResponse.code}")))
                        return
                    }
                    val version = parseVersionPayload(httpResponse.body?.string().orEmpty())
                    if (version.isBlank()) {
                        callback(Result.failure(IOException("Version NVR introuvable.")))
                    } else {
                        callback(Result.success(version))
                    }
                }
            }
        })
    }

    private fun loadCameraStats(token: String, callback: (Result<List<NativeCamera>>) -> Unit) {
        getJson(token, "/api/stats") { statsResult ->
            statsResult
                .mapCatching { parseCamerasFromStats(it) }
                .onSuccess { callback(Result.success(it)) }
                .onFailure { callback(Result.failure(it)) }
        }
    }

    fun loadBitmap(token: String, url: String, callback: (Bitmap?) -> Unit) {
        if (url.isBlank()) {
            callback(null)
            return
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(null)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(null)
                        return
                    }
                    callback(BitmapFactory.decodeStream(it.body?.byteStream()))
                }
            }
        })
    }

    fun loadGif(token: String, url: String, callback: (Drawable?) -> Unit) {
        if (url.isBlank()) {
            callback(null)
            return
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/gif,image/*")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(null)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(null)
                        return
                    }
                    val bytes = it.body?.bytes()
                    val drawable = runCatching {
                        if (bytes == null || bytes.isEmpty()) null else GifDrawable(bytes).apply { setLoopCount(0) }
                    }.getOrNull()
                    callback(drawable)
                }
            }
        })
    }

    fun setCameraFeature(
        token: String,
        cameraName: String,
        feature: String,
        enabled: Boolean,
        callback: (Result<Unit>) -> Unit
    ) {
        val body = JSONObject()
            .put("value", if (enabled) "ON" else "OFF")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = authRequest(token, cameraSetFeaturePath(cameraName, feature))
            .put(body)
            .header("Content-Type", "application/json")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                response.use { httpResponse ->
                    if (httpResponse.isSuccessful) {
                        callback(Result.success(Unit))
                    } else {
                        callback(Result.failure(IOException("Frigate HTTP ${httpResponse.code}")))
                    }
                }
            }
        })
    }

    fun openMjpegStream(
        token: String,
        streamName: String,
        onFrame: (Bitmap) -> Unit,
        onError: () -> Unit
    ): WebSocket {
        val request = Request.Builder()
            .url(liveWebSocketUrl(mjpegStreamName(streamName)))
            .header("Authorization", "Bearer $token")
            .header("Origin", baseUrl)
            .build()
        var lastFrameAt = 0L
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "mjpeg").toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json?.optString("type") == "error") {
                    onError()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < MIN_MJPEG_FRAME_INTERVAL_MS) return
                val data = bytes.toByteArray()
                if (!isJpeg(data)) return
                val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
                lastFrameAt = now
                onFrame(bitmap)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                onError()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onError()
            }
        })
    }

    fun requestHlsUrl(token: String, streamName: String, callback: (Result<String>) -> Unit) {
        val request = Request.Builder()
            .url(liveWebSocketUrl(streamName))
            .header("Authorization", "Bearer $token")
            .header("Origin", baseUrl)
            .build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "hls").put("value", HLS_CODECS).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json?.optString("type") == "hls") {
                    val hlsUrl = extractHlsUrl(json.optString("value"))
                    if (hlsUrl != null) {
                        webSocket.close(1000, "ready")
                        callback(Result.success(hlsUrl))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                callback(Result.failure(IOException(t.message ?: "HLS indisponible")))
            }
        })
    }

    fun latestFrameUrl(cameraName: String): String = endpoint("/api/${Uri.encode(cameraName)}/latest.jpg?h=360&_=${System.currentTimeMillis()}")
    fun eventSnapshotUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/snapshot.jpg?h=360")
    fun eventPreviewUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/preview.gif")
    fun eventClipUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/clip.mp4")
    fun eventVodUrl(eventId: String): String = endpoint("/vod/event/${Uri.encode(eventId)}/master.m3u8")
    fun recordingClipUrl(cameraName: String, startSec: Long, endSec: Long): String =
        endpoint("/api/${Uri.encode(cameraName)}/start/$startSec/end/$endSec/clip.mp4")
    fun recordingVodUrl(cameraName: String, startSec: Long, endSec: Long, stream: String? = null): String =
        endpoint(recordingVodPath(cameraName, startSec, endSec, stream))
    fun recordingSnapshotUrl(cameraName: String, frameTime: Double): String =
        endpoint("/api/${Uri.encode(cameraName)}/recordings/${frameTime.toLong()}/snapshot.webp")
    fun liveMseWebSocketUrl(streamName: String): String = liveWebSocketUrl(streamName)

    private fun getJson(token: String, path: String, callback: (Result<JSONObject>) -> Unit) {
        val request = authRequest(token, path).build()
        client.newCall(request).enqueue(object : JsonCallback(callback) {
            override fun onJson(json: JSONObject, response: Response) = callback(Result.success(json))
        })
    }

    private fun getJsonArray(token: String, path: String, callback: (Result<JSONArray>) -> Unit) {
        val request = authRequest(token, path).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
            override fun onResponse(call: Call, response: Response) {
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        callback(Result.failure(IOException("Frigate HTTP ${httpResponse.code}")))
                        return
                    }
                    parseJsonArray(httpResponse.body?.string().orEmpty(), path)
                        .onSuccess { callback(Result.success(it)) }
                        .onFailure { callback(Result.failure(it)) }
                }
            }
        })
    }

    private fun authRequest(token: String, path: String): Request.Builder =
        Request.Builder()
            .url(endpoint(path))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")

    private fun parseCameras(config: JSONObject): List<NativeCamera> {
        val cameras = config.optJSONObject("cameras") ?: return emptyList()
        return cameras.keys().asSequence().map { name ->
            val camera = cameras.optJSONObject(name) ?: JSONObject()
            NativeCamera(
                name = name,
                friendlyName = camera.optString("friendly_name").ifBlank { titleFromName(name) },
                enabled = !camera.has("enabled") || camera.optBoolean("enabled", true),
                streamName = selectStreamName(name, camera),
                detectEnabled = cameraFeatureEnabled(camera, "detect"),
                motionEnabled = cameraFeatureEnabled(camera, "motion"),
                recordingsEnabled = cameraFeatureEnabled(camera, "record"),
                snapshotsEnabled = cameraFeatureEnabled(camera, "snapshots"),
                audioEnabled = cameraFeatureEnabled(camera, "audio"),
                notificationsEnabled = cameraFeatureEnabled(camera, "notifications"),
                audioTranscriptionEnabled = cameraFeatureEnabled(camera, "audio_transcription")
            )
        }.toList()
    }

    private fun parseCamerasFromStats(stats: JSONObject): List<NativeCamera> {
        val cameras = stats.optJSONObject("cameras") ?: return emptyList()
        return cameras.keys().asSequence().map { name ->
            NativeCamera(
                name = name,
                friendlyName = titleFromName(name),
                enabled = true,
                streamName = name,
                detectEnabled = null,
                motionEnabled = null,
                recordingsEnabled = null,
                snapshotsEnabled = null,
                audioEnabled = null,
                notificationsEnabled = null,
                audioTranscriptionEnabled = null
            )
        }.toList()
    }

    private fun cameraFeatureEnabled(camera: JSONObject, key: String): Boolean? {
        val value = camera.opt(key)
        return when (value) {
            is Boolean -> value
            is JSONObject -> if (value.has("enabled") && !value.isNull("enabled")) value.optBoolean("enabled") else null
            else -> null
        }
    }

    private fun parseEvents(events: JSONArray): List<NativeEvent> {
        return (0 until events.length()).mapNotNull { index ->
            val event = events.optJSONObject(index) ?: return@mapNotNull null
            val id = event.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val thumbnail = event.optString("thumbnail").takeIf { it.isNotBlank() }?.let { decodeThumbnail(it) }
            val startTime = if (event.has("start_time")) event.optDouble("start_time") else null
            val objectLabel = cleanEventText(event, "label").ifBlank { "motion" }
            val facts = EventFacts.fromJson(event)
            val subLabel = facts.name
            NativeEvent(
                id = id,
                camera = cleanEventText(event, "camera"),
                label = subLabel ?: objectLabel,
                objectLabel = objectLabel,
                subLabel = subLabel,
                time = formatTime(startTime),
                startTime = startTime,
                endTime = if (event.has("end_time") && !event.isNull("end_time")) event.optDouble("end_time") else null,
                thumbnail = thumbnail,
                description = facts.description
            )
        }
    }

    private fun cleanEventText(event: JSONObject, key: String): String {
        if (!event.has(key) || event.isNull(key)) return ""
        val value = event.optString(key).trim()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun selectStreamName(name: String, camera: JSONObject): String {
        val streams = camera.optJSONObject("live")?.optJSONObject("streams") ?: return name
        val values = streams.keys().asSequence().mapNotNull { key ->
            val value = streams.optString(key)
            if (value.isBlank()) null else key to value
        }.toList()
        val preferred = values.firstOrNull { (label, value) ->
            val searchable = "$label $value".lowercase(Locale.ROOT)
            listOf("grid", "sub", "low", "detect", "sd", "mobile").any { searchable.contains(it) }
        }
        return preferred?.second ?: values.firstOrNull()?.second ?: name
    }

    private fun decodeThumbnail(thumbnail: String): Bitmap? {
        return runCatching {
            val bytes = Base64.decode(thumbnail, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun extractHlsUrl(playlist: String): String? {
        val path = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("hls/playlist.m3u8") && !it.startsWith("#") }
            ?: return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return endpoint("/live/mse/api/${path.trimStart('/')}")
    }

    private fun liveWebSocketUrl(streamName: String): String {
        val uri = Uri.parse(baseUrl)
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        val basePath = uri.encodedPath?.trimEnd('/') ?: ""
        return uri.buildUpon()
            .scheme(scheme)
            .encodedPath("$basePath/live/mse/api/ws")
            .clearQuery()
            .appendQueryParameter("src", streamName)
            .build()
            .toString()
    }

    private fun mjpegStreamName(streamName: String): String {
        val source = if (streamName.startsWith("ffmpeg:", ignoreCase = true)) streamName else "ffmpeg:$streamName"
        return if (source.contains("#video=mjpeg")) source else "$source#video=mjpeg#width=640"
    }

    private fun isJpeg(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte()

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    private fun parseJsonObject(payload: String, path: String, allowEmpty: Boolean = false): Result<JSONObject> {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) {
            return if (allowEmpty) {
                Result.success(JSONObject())
            } else {
                Result.failure(IOException("Reponse Frigate vide pour $path."))
            }
        }

        return runCatching { JSONObject(trimmed) }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(IOException("Reponse Frigate invalide pour $path.", it)) }
        )
    }

    private fun parseJsonArray(payload: String, path: String): Result<JSONArray> {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IOException("Reponse Frigate vide pour $path."))
        }

        return runCatching { JSONArray(trimmed) }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(IOException("Reponse Frigate invalide pour $path.", it)) }
        )
    }

    private fun parseVersionPayload(payload: String): String {
        val trimmed = payload.trim().trim('"')
        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull()
            return json?.optString("version").orEmpty()
                .ifBlank { json?.optString("frigate_version").orEmpty() }
                .ifBlank { json?.optString("nvr_version").orEmpty() }
        }
        return trimmed
    }

    private fun bearerTokenFromHeader(header: String?): String {
        val value = header?.trim().orEmpty()
        return if (value.startsWith("Bearer ", ignoreCase = true)) {
            value.substringAfter(' ').trim()
        } else {
            value
        }
    }

    private fun tokenFromCookies(cookies: List<String>): String {
        return cookies.asSequence()
            .mapNotNull { cookie -> Regex("""(?:^|;\s*)frigate_token=([^;]+)""").find(cookie)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            .orEmpty()
    }

    private inner abstract class JsonCallback(private val callback: (Result<JSONObject>) -> Unit) : Callback {
        override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))
        override fun onResponse(call: Call, response: Response) {
            response.use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    callback(Result.failure(IOException("Frigate HTTP ${httpResponse.code}")))
                    return
                }
                parseJsonObject(httpResponse.body?.string().orEmpty(), httpResponse.request.url.encodedPath)
                    .onSuccess { onJson(it, httpResponse) }
                    .onFailure { callback(Result.failure(it)) }
            }
        }

        abstract fun onJson(json: JSONObject, response: Response)
    }

    companion object {
        private const val HLS_CODECS = "avc1.640029,avc1.64002A,avc1.640033,hvc1.1.6.L153.B0,mp4a.40.2,mp4a.40.5,flac,opus"
        private const val MIN_MJPEG_FRAME_INTERVAL_MS = 110L
    }
}

private class AppUpdateService(private val client: OkHttpClient) {
    fun check(currentVersion: String, callback: (Result<NativeUpdateResult>) -> Unit) {
        val request = Request.Builder()
            .url(RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "FriCamGate")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = callback(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                response.use { httpResponse ->
                    if (httpResponse.code == 403 || httpResponse.code == 404) {
                        callback(Result.success(NativeUpdateResult(false, "Releases GitHub non accessibles depuis l'app.", null, null, null)))
                        return
                    }
                    if (!httpResponse.isSuccessful) {
                        callback(Result.failure(IOException("GitHub HTTP ${httpResponse.code}")))
                        return
                    }
                    val release = latestApkRelease(httpResponse.body?.string().orEmpty())
                    if (release == null) {
                        callback(Result.success(NativeUpdateResult(false, "Version GitHub introuvable.", null, null, null)))
                        return
                    }
                    val available = compareVersions(release.version, currentVersion) > 0
                    callback(Result.success(
                        NativeUpdateResult(
                            available = available,
                            message = if (available) "Mise a jour ${release.version} disponible." else "Application a jour.",
                            latestVersion = release.version,
                            assetName = release.assetName,
                            downloadUrl = release.downloadUrl
                        )
                    ))
                }
            }
        })
    }

    private fun latestApkRelease(payload: String): ApkRelease? {
        val releases = runCatching { JSONArray(payload) }.getOrNull() ?: return null
        var latest: ApkRelease? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false)) continue
            val version = extractVersion(release.optString("tag_name").ifBlank { release.optString("name") })
            if (version.isBlank()) continue
            val asset = findApkAsset(release.optJSONArray("assets")) ?: continue
            val candidate = ApkRelease(version, asset.first, asset.second)
            if (latest == null || compareVersions(candidate.version, latest.version) > 0) {
                latest = candidate
            }
        }
        return latest
    }

    private fun findApkAsset(assets: JSONArray?): Pair<String, String>? {
        if (assets == null) return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val downloadUrl = asset.optString("browser_download_url")
            if (name.lowercase(Locale.ROOT).endsWith(".apk") && downloadUrl.isNotBlank()) {
                return name to downloadUrl
            }
        }
        return null
    }

    companion object {
        private const val RELEASES_API_URL = "https://api.github.com/repos/jp2creation/FriCamGate/releases?per_page=20"
    }
}

private data class ApkRelease(val version: String, val assetName: String, val downloadUrl: String)
private data class NativeUpdateResult(
    val available: Boolean,
    val message: String,
    val latestVersion: String?,
    val assetName: String?,
    val downloadUrl: String?
)
private data class NativeSession(val baseUrl: String, val token: String, val username: String)
private data class NativeLogin(val token: String, val username: String)
private data class DashboardData(val cameras: List<NativeCamera>, val events: List<NativeEvent>)
private data class NativeCamera(
    val name: String,
    val friendlyName: String,
    val enabled: Boolean,
    val streamName: String,
    val detectEnabled: Boolean?,
    val motionEnabled: Boolean?,
    val recordingsEnabled: Boolean?,
    val snapshotsEnabled: Boolean?,
    val audioEnabled: Boolean?,
    val notificationsEnabled: Boolean?,
    val audioTranscriptionEnabled: Boolean?
)
private data class CameraFeatureControl(
    val title: String,
    val detail: String,
    val feature: String,
    val enabled: Boolean?
)
private data class NativeEvent(
    val id: String,
    val camera: String,
    val label: String,
    val objectLabel: String,
    val subLabel: String?,
    val time: String,
    val startTime: Double?,
    val endTime: Double?,
    val thumbnail: Bitmap?,
    val description: String? = null
)

private enum class Tab(val label: String, val iconRes: Int) {
    Home("Accueil", R.drawable.ic_home),
    Cameras("Cameras", R.drawable.ic_videocam),
    Grid("Grille", R.drawable.ic_grid),
    Events("Events", R.drawable.ic_image),
    Settings("Reglages", R.drawable.ic_settings)
}

internal object Palette {
    private var themeDark = true
    var backgroundStyle = BackgroundStyle.Automatic
    var dark: Boolean
        get() = backgroundStyle != BackgroundStyle.Automatic || themeDark
        set(value) { themeDark = value }
    var accent = 0xffff2d63.toInt()
    val background get() = if (dark) backgroundStyle.background else 0xfff5f6f8.toInt()
    val backgroundAlt get() = if (backgroundStyle != BackgroundStyle.Automatic) backgroundStyle.background else if (dark) 0xff151518.toInt() else 0xfff0f1f4.toInt()
    val black get() = if (backgroundStyle != BackgroundStyle.Automatic) backgroundStyle.background else if (dark) 0xff000000.toInt() else 0xffffffff.toInt()
    val card get() = if (dark) backgroundStyle.card else 0xffffffff.toInt()
    val surface get() = if (dark) backgroundStyle.surface else 0xffe5e7ec.toInt()
    val overlay get() = if (dark) 0xcc050507.toInt() else 0xe6ffffff.toInt()
    val text get() = if (dark) 0xfff7f7f9.toInt() else 0xff202127.toInt()
    val muted get() = if (backgroundStyle != BackgroundStyle.Automatic) 0xffb0b3be.toInt() else if (dark) 0xff8d8d96.toInt() else 0xff60636e.toInt()
    val border get() = if (dark) 0xff2c2c34.toInt() else 0xffd7d9e0.toInt()
    val divider get() = if (dark) 0x14ffffff else 0x18000000
    val tick get() = if (dark) 0xff585860.toInt() else 0xff858995.toInt()
    val tickMinor get() = if (dark) 0xff303037.toInt() else 0xffc9cdd5.toInt()
    const val liveBlue = 0xff07b8ff.toInt()
}

internal fun extractVersion(input: String): String {
    val normalized = input.trim().removePrefix("v").removePrefix("V")
    val version = normalized.substringBefore("-")
    return if (Regex("""^\d+(?:\.\d+){0,3}$""").matches(version)) version else ""
}

internal fun compareVersions(left: String, right: String): Int {
    val leftParts = extractVersion(left).split(".").filter { it.isNotBlank() }.map { it.toInt() }
    val rightParts = extractVersion(right).split(".").filter { it.isNotBlank() }.map { it.toInt() }
    val length = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until length) {
        val leftValue = leftParts.getOrNull(index) ?: 0
        val rightValue = rightParts.getOrNull(index) ?: 0
        if (leftValue > rightValue) return 1
        if (leftValue < rightValue) return -1
    }
    return 0
}

internal data class TimelineRecordingWindow(val startSec: Long, val endSec: Long, val seekMs: Long)

internal fun timelinePlayheadOffset(containerHeightPx: Int): Int =
    (containerHeightPx.coerceAtLeast(0) * TIMELINE_PLAYHEAD_FRACTION).toInt()

internal fun timelineRecordingWindow(focusSec: Double): TimelineRecordingWindow {
    val safeFocus = focusSec.coerceAtLeast(0.0)
    val bucketStart = ((safeFocus.toLong() - TIMELINE_RECORDING_PREROLL_SECONDS)
        .coerceAtLeast(0L) / TIMELINE_RECORDING_BUCKET_SECONDS) * TIMELINE_RECORDING_BUCKET_SECONDS
    val startSec = bucketStart.coerceAtLeast(0L)
    val endSec = startSec + TIMELINE_RECORDING_WINDOW_SECONDS
    val seekMs = ((safeFocus - startSec.toDouble()) * 1000.0)
        .coerceIn(0.0, (TIMELINE_RECORDING_WINDOW_SECONDS * 1000L).toDouble())
        .toLong()
    return TimelineRecordingWindow(startSec, endSec, seekMs)
}

internal fun cameraPickerPopupWidth(
    screenWidthPx: Int,
    screenHeightPx: Int,
    floatingMaxWidthPx: Int,
    floatingHorizontalMarginPx: Int
): Int {
    val safeWidth = screenWidthPx.coerceAtLeast(0)
    if (screenHeightPx >= safeWidth) return safeWidth
    return minOf(safeWidth - floatingHorizontalMarginPx, floatingMaxWidthPx)
        .coerceAtLeast(0)
}

internal fun cameraSetFeaturePath(cameraName: String, feature: String): String =
    "/api/${urlPathSegment(cameraName)}/set/${urlPathSegment(feature)}"

internal fun recordingVodPath(cameraName: String, startSec: Long, endSec: Long, stream: String? = null): String {
    val streamPath = stream?.takeIf { it.isNotBlank() }?.let { "/${urlPathSegment(it)}" }.orEmpty()
    return "/vod/${urlPathSegment(cameraName)}$streamPath/start/$startSec/end/$endSec/master.m3u8"
}

internal fun liaChatUrl(baseUrl: String): String =
    "${normalizeBaseUrl(baseUrl)}/chat"

internal fun isSameOriginUrl(baseUrl: String, targetUrl: String): Boolean {
    return try {
        val base = URI(normalizeBaseUrl(baseUrl))
        val target = URI(targetUrl)
        base.scheme.equals(target.scheme, ignoreCase = true) &&
            base.host.equals(target.host, ignoreCase = true) &&
            (base.port.takeIf { it != -1 } ?: defaultPort(base.scheme)) ==
            (target.port.takeIf { it != -1 } ?: defaultPort(target.scheme))
    } catch (_: Exception) {
        false
    }
}

private fun defaultPort(scheme: String?): Int =
    when (scheme?.lowercase(Locale.ROOT)) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }

private fun urlPathSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    var normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    listOf("/api/login", "/login", "/api").forEach { suffix ->
        if (normalized.endsWith(suffix, ignoreCase = true)) {
            normalized = normalized.dropLast(suffix.length).trimEnd('/')
        }
    }
    return normalized
}

private fun titleFromName(name: String): String =
    name.split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.FRANCE) } }

private fun formatTime(epochSeconds: Double?): String {
    if (epochSeconds == null || epochSeconds <= 0.0) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date((epochSeconds * 1000).toLong()))
}
