package com.nvr.camnory

import android.widget.CheckBox

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.core.view.doOnLayout
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
    private val cameraArrangement by lazy { CameraArrangementStore(this) }
    private val reducedMotion get() = appearancePrefs.getBoolean("reducedMotion", false)
    private var session: NativeSession? = null
    private var api: FrigateNativeApi? = null
    private val frigateServers by lazy { FrigateServerStore(this) }
    private val frigateManager = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        settingsPage = "connections"
        if (result.resultCode == RESULT_OK) {
            session = loadSession()
            api = createFrigateApi()
            cameras = emptyList(); events = emptyList(); globalResults = null
            seenAlertIdsMemory.clear()
            restoredSettingsPage = "connections"
            loadDashboard()
        } else renderConnectionsSettings()
    }

    private fun createFrigateApi(): FrigateNativeApi? = runCatching {
        frigateServers.load().takeIf { it.isNotEmpty() }?.let { MultiFrigateApi(it, httpClient, this) }
    }.getOrElse { toast(getString(R.string.frigate_storage_error)); null }
    private var cameras: List<NativeCamera> = emptyList()
    private var events: List<NativeEvent> = emptyList()
    private var currentPlayer: ExoPlayer? = null
    private var eventFullscreenDialog: android.app.Dialog? = null
    private var loginCancellation: (() -> Unit)? = null
    private var currentPlaybackKey: String? = null
    private var timelinePlaybackGeneration = 0
    private var timelineFrameGeneration = 0
    private var cameraPickerPopup: PopupWindow? = null
    private var cameraPickerSheet: SelectionBottomSheet? = null
    private var activityBriefingSheet: ActivityBriefingSheet? = null
    private var timelineDateSheet: TimelineDateSheet? = null
    private var cameraButtonsSheet: CameraButtonsSheet? = null
    private var appearanceResetSheet: ConfirmationSheet? = null
    private var homeSourceSheet: AppearanceChoiceSheet? = null
    private var eventFilterSheet: EventFilterSheet? = null
    private var chatDialog: NativeChatDialog? = null
    private var accessorySheet: CameraControlsSheet? = null
    private var chatServerSheet: SelectionBottomSheet? = null
    private var alertsPopup: NotificationsSheet? = null
    private var nvrVersion: String? = null
    private var updateResult: NativeUpdateResult? = null
    private var updateChecking = false
    private var updateCardView: View? = null
    private var soundEnabled = false
    private var detailSoundRefresh: (() -> Unit)? = null
    private var eventPlaybackControls: androidx.media3.ui.PlayerControlView? = null
    private var tvEventSelectedId: String? = null
    private var tvNotificationSelectedId: String? = null
    private var tvUnreadOnly = false
    private var tvHistoryOrigin = Tab.Events
    private val homeStreams = mutableListOf<WebSocket>()
    private val streamRestarts = mutableMapOf<ImageView, () -> Unit>()
    private var streamsSuspended = false
    private val onvifCards = mutableListOf<OnvifLiveCard>()
    private val homeAssistantCards = mutableListOf<HomeAssistantCameraView>()
    private var homeAssistantFullscreen: android.app.Dialog? = null
    private var homeVisible = false
    private var reflowShell: (() -> Unit)? = null
    private fun tabletLayout(): TabletLayoutPolicy = resources.configuration.let {
        tabletLayoutPolicy(it.screenWidthDp, it.screenHeightDp, it.smallestScreenWidthDp, TelevisionMode.enabled(this))
    }
    private val onvifManager = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val returnPage = onvifSettingsReturnPage
        onvifSettingsReturnPage = null
        if (returnPage != null) {
            if (session == null && !hasStandaloneCameras()) renderLogin() else renderSettingsDestination(returnPage)
        } else if (homeVisible || session == null) {
            if (session == null && !hasStandaloneCameras()) renderLogin() else renderHome()
        }
    }
    private val homeAssistantManager = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val returnPage = haSettingsReturnPage
        haSettingsReturnPage = null
        if (result.resultCode == RESULT_OK) {
            if (session == null && !hasStandaloneCameras()) renderLogin()
            else if (returnPage != null) renderSettingsDestination(returnPage) else renderHome()
        }
    }
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
    private var loadingProgress: View? = null
    private var loadingCompletion: ValueAnimator? = null
    private lateinit var appLock: BiometricAppLock
    private var activeTab = Tab.Home
    private var settingsPage = "main"
    private var restoredSettingsPage: String? = null
    private var settingsScrollY = 0
    private var settingsBackCallback: androidx.activity.OnBackPressedCallback? = null
    private var expandedLiveBack: androidx.activity.OnBackPressedCallback? = null
    private var expandedLiveReflow: (() -> Unit)? = null
    private var expandedLiveResume: (() -> Unit)? = null
    private var expandedLiveImmersive = false
    private var onvifSettingsReturnPage: String? = null
    private var haSettingsReturnPage: String? = null
    private var appearanceScrollY = 0
    private var appearanceChoiceSheet: AppearanceChoiceSheet? = null
    private var appearancePreviewBitmap: Bitmap? = null
    private var appearancePreviewKey: String? = null
    private var notificationScrollY = 0
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
        TelevisionMode.adoptAppearance(this)
        // Android still creates a launch window; hand it off without a second logo animation.
        installSplashScreen().setOnExitAnimationListener { it.remove() }
        delegate.localNightMode = when (appearancePrefs.getString("theme", "system")) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        if (TelevisionMode.enabled(this)) volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        TvRemoteSupport.start(application)
        if (TelevisionMode.enabled(this)) onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activeTab != Tab.Home || expandedLiveReflow != null || expandedLiveImmersive) renderHome() else finish()
            }
        })
        Appearance.loadPalette(appearancePrefs, resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        getSharedPreferences("camgate-settings", MODE_PRIVATE).edit()
            .putString("language", AppCompatDelegate.getApplicationLocales().toLanguageTags()).apply()
        appLock = BiometricAppLock(this) {
            resumeAfterBackground()
            homeAudioWebView?.onResume()
            openPendingNotification()
        }
        restoredSettingsPage = savedInstanceState?.getString("settingsPage")
        settingsScrollY = savedInstanceState?.getInt("settingsScrollY") ?: 0
        onvifSettingsReturnPage = savedInstanceState?.getString("onvifSettingsReturnPage")
        haSettingsReturnPage = savedInstanceState?.getString("haSettingsReturnPage")
        settingsBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { if (!appLock.locked) navigateSettingsBack() }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
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
        api = createFrigateApi()
        if (TelevisionMode.enabled(this) && intent.action == "com.nvr.camnory.TV_SETTINGS") {
            renderSettings()
        } else if (session == null) {
            if (hasStandaloneCameras()) renderRestoredSettingsOrHome() else renderLogin()
        } else {
            loadDashboard(getString(R.string.restored, session?.username.orEmpty()))
        }
    }

    override fun onStop() {
        appearanceResetSheet?.dismiss(); appearanceResetSheet = null
        timelineDateSheet?.dismiss(); timelineDateSheet = null
        cameraButtonsSheet?.dismiss(); cameraButtonsSheet = null
        dismissCameraPicker()
        accessorySheet?.dismiss(); accessorySheet = null
        chatDialog?.dismiss(); chatDialog = null
        chatServerSheet?.dismiss(); chatServerSheet = null
        eventFilterSheet?.dismiss(); eventFilterSheet = null
        homeSourceSheet?.dismiss(); homeSourceSheet = null
        activityBriefingSheet?.dismiss(); activityBriefingSheet = null
        super.onStop()
        notificationHandler.removeCallbacks(notificationPoll)
        if (!isChangingConfigurations) appLock.onBackground()
        playbackResume.remember(currentPlayer, currentPlayer?.playWhenReady == true)
        currentPlayer?.playWhenReady = false
        onvifCards.forEach { it.setActive(false) }
        homeAssistantFullscreen?.dismiss()
        homeAssistantCards.forEach { it.setActive(false) }
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val mode = appearancePrefs.getString("theme", "system")
        if (mode != "dark" && mode != "light") {
            val systemDark = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (Palette.dark != systemDark) {
                Appearance.loadPalette(appearancePrefs, systemDark)
                recreate()
                return
            }
        }
        if (homeVisible) renderHome() else (expandedLiveReflow ?: reflowShell)?.invoke()
    }

    override fun onPause() {
        loginCancellation?.invoke()
        onvifCards.forEach { it.setActive(false) }
        homeAssistantFullscreen?.dismiss()
        homeAssistantCards.forEach { it.setActive(false) }
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
        outState.putInt("settingsScrollY", settingsScrollY)
        outState.putString("onvifSettingsReturnPage", onvifSettingsReturnPage)
        outState.putString("haSettingsReturnPage", haSettingsReturnPage)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (TelevisionMode.enabled(this) && intent.action == "com.nvr.camnory.TV_SETTINGS") {
            renderSettings()
            return
        }
        pendingEventId = intent.getStringExtra(CameraNotifications.EVENT_ID)
        openPendingNotification()
    }

    override fun setContentView(view: View) {
        reflowShell = null
        super.setContentView(view)
        TvRemoteSupport.install(window.decorView)
        if (::appLock.isInitialized) appLock.protectContent()
    }

    private fun openPendingNotification() {
        if (appLock.locked || cameras.isEmpty()) return
        val id = pendingEventId ?: return
        pendingEventId = null
        events.firstOrNull { it.id == id }?.let { renderEventDetail(it) } ?: renderGlobalEvents()
    }

    override fun onDestroy() {
        accessorySheet?.dismiss(); accessorySheet = null
        chatDialog?.dismiss(); chatDialog = null
        chatServerSheet?.dismiss(); chatServerSheet = null
        eventFilterSheet?.dismiss(); eventFilterSheet = null
        homeSourceSheet?.dismiss(); homeSourceSheet = null
        activityBriefingSheet?.dismiss(); activityBriefingSheet = null
        dismissAlertsPopup()
        dismissCameraPicker()
        appearanceChoiceSheet?.dismiss()
        notificationHandler.removeCallbacks(notificationPoll)
        releasePlayer()
        super.onDestroy()
    }

    private fun resumeAfterBackground() {
        onvifCards.forEach { it.setActive(!appLock.locked) }
        homeAssistantCards.forEach { it.setActive(!appLock.locked) }
        if (!appLock.locked) expandedLiveResume?.invoke()
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
            setPadding(dp(24), dp(24), dp(24), dp(24))
            gravity = Gravity.START
            isFocusableInTouchMode = true
            setBackgroundColor(Palette.background)
        }
        scroll.addView(root, responsiveScrollParams(560))
        val ui = ConnectionFormUi(this)
        root.addView(brandWordmark(190), LinearLayout.LayoutParams(dp(190), dp(59)))
        root.addView(label(getString(R.string.connection_heading), 24, Palette.text).apply {
            setPadding(0, dp(24), 0, dp(22))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        root.addView(label(getString(R.string.connection_source), 12, Palette.muted).apply { setPadding(0, 0, 0, dp(8)) })
        val sourceNames = listOf("Frigate", "ONVIF", "Home Assistant")
        val sourceIcons = listOf(R.drawable.ic_grid, R.drawable.ic_videocam, R.drawable.ic_home)
        val selector = android.widget.Spinner(this, android.widget.Spinner.MODE_DROPDOWN).apply {
            contentDescription = getString(R.string.connection_source)
            background = ui.outline()
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = sourceNames.size
                override fun getItem(position: Int) = sourceNames[position]
                override fun getItemId(position: Int) = position.toLong()
                private fun row(position: Int, dropdown: Boolean): View = horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0)
                    if (dropdown) setBackgroundColor(Palette.card)
                    addView(ImageView(context).apply { setImageResource(sourceIcons[position]); setColorFilter(Palette.text) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
                    addView(label(sourceNames[position], 15, Palette.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (!dropdown) addView(ImageView(context).apply { setImageResource(R.drawable.ic_connection_expand); setColorFilter(Palette.muted) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                    layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup) = row(position, false)
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) = row(position, true)
            }
        }
        root.addView(selector, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val frigateForm = vertical().apply { tag = "connection-frigate-form" }
        val formRoot = root
        frigateForm.apply {
        val root = this
        val serverInput = ui.field(root, getString(R.string.frigate_server), "https://cam.example.fr", prefs.getString("baseUrl", "").orEmpty(), key = "connection-frigate-server")
        val usernameInput = ui.field(root, getString(R.string.username), "admin", prefs.getString("username", "").orEmpty(), key = "connection-frigate-user")
        val passwordInput = ui.field(root, getString(R.string.password), getString(R.string.password_hint), secret = true, key = "connection-frigate-password")
        ui.advanced(root, "connection-frigate-advanced").addView(label(getString(R.string.login_hint), 12, Palette.muted))
        ui.spacer(root)
        root.addView(ui.action(getString(R.string.login)) {
            connect(serverInput.text.toString(), usernameInput.text.toString(), passwordInput.text.toString())
        })
        }
        val onvifForm = vertical().apply { visibility = View.GONE; tag = "connection-onvif-form" }
        val cameraName = ui.field(onvifForm, getString(R.string.onvif_name), getString(R.string.onvif_name), key = "connection-onvif-name")
        val cameraAddress = ui.field(onvifForm, getString(R.string.connection_camera_address), "http://192.168.1.100:8000", key = "connection-onvif-server")
        val cameraUser = ui.field(onvifForm, getString(R.string.username), "admin", key = "connection-onvif-user")
        val cameraPassword = ui.field(onvifForm, getString(R.string.password), getString(R.string.password_hint), secret = true, key = "connection-onvif-password")
        val onvifAdvanced = ui.advanced(onvifForm, "connection-onvif-advanced")
        ui.spacer(onvifForm)
        val cameraConnect = ui.action(getString(R.string.onvif_connect)) {}
        onvifForm.addView(cameraConnect)
        val cameraStatus = label("", 14, Palette.muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        onvifForm.addView(cameraStatus)
        onvifAdvanced.addView(ui.action(getString(R.string.onvif_search), false) { openOnvifManager(discover = true) })
        onvifAdvanced.addView(ui.action(getString(R.string.connection_saved_cameras), false) { openOnvifManager() })
        var onvifGeneration = 0
        var onvifClient: OnvifReadOnlyClient? = null
        cameraConnect.setOnClickListener {
            val endpoint = runCatching { onvifEndpoint(cameraAddress.text.toString()).toASCIIString() }.getOrNull()
            if (endpoint == null || cameraName.text.isBlank()) {
                cameraAddress.error = getString(R.string.onvif_invalid_address)
                return@setOnClickListener
            }
            val camera = OnvifSavedCamera(java.util.UUID.randomUUID().toString(), cameraName.text.toString().trim(), endpoint,
                OnvifCredentials(cameraUser.text.toString(), cameraPassword.text.toString()))
            cameraConnect.isEnabled = false
            selector.isEnabled = false
            cameraStatus.text = getString(R.string.connecting)
            val client = OnvifReadOnlyClient()
            onvifClient = client
            val request = ++onvifGeneration
            Thread {
                val result = runCatching { client.connect(camera.endpoint, camera.credentials) }
                runOnUiThread {
                    if (request != onvifGeneration || !formRoot.isAttachedToWindow || !onvifForm.isShown || isFinishing || isDestroyed || appLock.locked) return@runOnUiThread
                    cameraConnect.isEnabled = true
                    selector.isEnabled = true
                    if (result.isSuccess) {
                        val saved = runCatching {
                            val store = OnvifCameraStore(this)
                            store.save(store.load() + camera)
                        }
                        if (saved.isFailure) { cameraStatus.text = getString(R.string.onvif_storage_error); return@runOnUiThread }
                        cameraPassword.text.clear()
                        cameraStatus.text = ""
                        renderHome()
                    } else cameraStatus.text = getString(R.string.onvif_connection_failed)
                }
                client.cancel()
            }.start()
        }
        formRoot.addView(frigateForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        formRoot.addView(onvifForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val haForm = HomeAssistantSetupView(this, savedHomeAssistant(), { !appLock.locked }, { renderHome() }, { selector.isEnabled = !it }).apply { visibility = View.GONE }
        formRoot.addView(haForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cancel = {
            onvifGeneration++; onvifClient?.cancel(); onvifClient = null
            cameraConnect.isEnabled = true; cameraStatus.text = ""
            haForm.cancel(); selector.isEnabled = true
        }
        loginCancellation = cancel
        selector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                cancel()
                frigateForm.visibility = if (position == 0) View.VISIBLE else View.GONE
                onvifForm.visibility = if (position == 1) View.VISIBLE else View.GONE
                haForm.visibility = if (position == 2) View.VISIBLE else View.GONE
                root.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(selector.windowToken, 0)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val location = IntArray(2).also { view.getLocationOnScreen(it) }
            val safeTop = (maxOf(bars.top, cutout.top) - location[1]).coerceAtLeast(0)
            view.setPadding(maxOf(bars.left, cutout.left), safeTop, maxOf(bars.right, cutout.right), maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        scroll.doOnLayout { ViewCompat.getRootWindowInsets(window.decorView)?.let { ViewCompat.dispatchApplyWindowInsets(scroll, it) } }
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
        val connector: NvrConnector = nextApi
        connector.login(username, password) { result ->
            runOnUiThread {
                result.onSuccess { login ->
                    val nextSession = NativeSession(normalizedUrl, login.token, login.username)
                    if (!runCatching { saveSession(nextSession) }.isSuccess) {
                        renderLogin(); toast(getString(R.string.frigate_storage_error)); return@onSuccess
                    }
                    session = loadSession()
                    api = createFrigateApi()
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
        val activeApi: NvrConnector? = api
        if (activeSession == null || activeApi == null) {
            if (hasStandaloneCameras()) renderRestoredSettingsOrHome() else renderLogin()
            return
        }
        renderLoading(getString(R.string.loading_cameras))
        activeApi.loadDashboard(activeSession.token) { result ->
            runOnUiThread {
                if (api !== activeApi) return@runOnUiThread
                result.onSuccess { data ->
                    cameras = data.cameras
                    events = data.events
                    dashboardErrorMessage = null
                    finishLoading {
                        if (api !== activeApi) return@finishLoading
                        renderRestoredSettingsOrHome(if ((activeApi as? MultiFrigateApi)?.unavailable?.isNotEmpty() == true) getString(R.string.frigate_partial) else message)
                        CameraNotifications.schedule(this)
                        openPendingNotification()
                    }
                }.onFailure { error ->
                    val errorMessage = error.message ?: getString(R.string.frigate_unavailable)
                    if (isAuthFailure(errorMessage) && activeApi !is MultiFrigateApi) {
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
        homeVisible = true
        val dashboardCameras = dashboardCameras()
        val sourceFilter = homeSourceFilter()
        val shownCameras = dashboardCameras.filter { dashboardCameraVisible(it) && sourceFilter.includes(homeSourceKey(it)) }
        val tablet = tabletLayout()
        val showHomeEvents = showHomeEvents()
        val activitySidebar = showHomeEvents && tablet.activityWidthDp > 0 && session != null
        val content = (if (TelevisionMode.enabled(this)) TvCameraScrollView(this) else appScrollView()).apply {
            setBackgroundColor(Palette.background)
            clipToPadding = false
            tag = "home-camera-scroll"
            setPadding(0, 0, 0, dp(if (tablet.tablet) 12 else 78))
        }
        val column = vertical().apply { setPadding(dp(12), dp(10), dp(12), dp(24)) }
        content.addView(column, if (tablet.tablet) FrameLayout.LayoutParams(-1, -2) else responsiveScrollParams(720))
        if (showHomeEvents && session != null && !activitySidebar) {
            column.addView(eventStrip().apply { tag = "home-recent-events" })
        }
        if (activityBriefingStyle() == ActivityBriefingStyle.Banner) recentActivitySummary()?.let {
            column.addView(it, LinearLayout.LayoutParams(-1, -2))
        }
        if (showHomeToolbar()) column.addView(homeTitleRow(message).apply { tag = "home-camera-toolbar" })

        val dashboardError = dashboardErrorMessage
        if (!dashboardError.isNullOrBlank()) {
            column.addView(dashboardErrorCard(dashboardError))
        }
        if (dashboardCameras.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.no_cameras)))
        } else if (shownCameras.isEmpty()) {
            column.addView(emptyMessage(getString(if (sourceFilter.selected != null) R.string.home_source_empty else R.string.all_hidden)))
        } else {
            val layout = homeCameraLayout()
            if (TelevisionMode.enabled(this)) {
                val grid = TvCameraGrid(this, 2, shownCameras.map { item ->
                    dashboardCameraView(item, layout).apply { tag = "dashboard-camera:${item.key}" }
                }, onSelection = { index -> selectTelevisionCamera(index?.let { shownCameras[it] }) })
                column.addView(grid, LinearLayout.LayoutParams(-1, -2))
                grid.attachToViewport(content, column)
            } else shownCameras.chunked(layout.columns).forEach { cameraRow ->
                column.addView(horizontal().apply {
                    gravity = Gravity.TOP
                    repeat(layout.columns) { index ->
                        val item = cameraRow.getOrNull(index)
                        val tile = item?.let { dashboardCameraView(it, layout) } ?: View(context)
                        tile.tag = item?.let { "dashboard-camera:${it.key}" }
                        val height = if (layout == HomeCameraLayout.Column && !tablet.tablet) dp(218) else ViewGroup.LayoutParams.WRAP_CONTENT
                        addView(tile, LinearLayout.LayoutParams(0, height, 1f).apply {
                            if (index > 0) leftMargin = dp(8)
                        })
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        val homeContent = FrameLayout(this).apply {
            addView(horizontal().apply {
                addView(PullRefreshLayout(this@MainActivity, content,
                    allowed = { homeVisible && !appLock.locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) },
                    refresh = { done -> refreshHomeDashboard(done) }), LinearLayout.LayoutParams(0, -1, 1f))
                if (activitySidebar) {
                    addView(View(context).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(dp(1), -1))
                    addView(tabletActivitySidebar(), LinearLayout.LayoutParams(dp(tablet.activityWidthDp), -1))
                }
            }, FrameLayout.LayoutParams(-1, -1))
            if (activityBriefingStyle() == ActivityBriefingStyle.Floating) recentActivitySummary()?.let { entry ->
                addView(entry, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
                    marginEnd = dp(16); bottomMargin = dp(16)
                })
            }
        }
        setShell(homeContent, Tab.Home, animateIn = true)
        onvifCards.forEach { it.setActive(!appLock.locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
        homeAssistantCards.forEach { it.setActive(!appLock.locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    }

    private var homeRefreshGeneration = 0
    private val tvCameraSound = mutableMapOf<String, (Boolean) -> Unit>()

    private fun selectTelevisionCamera(item: DashboardCamera?) {
        if (!homeVisible || appLock.locked) return
        tvCameraSound.values.forEach { it(false) }
        releaseCurrentPlayer()
        homeAudioGeneration++
        releaseHomeAudioWebView()
        if (item == null) return
        soundEnabled = true
        tvCameraSound[item.key]?.let { it(true); return }
        val camera = item.frigate ?: return
        val transport = api ?: return
        val token = session?.token ?: return
        currentPlaybackKey = "tv-home:${item.key}"
        val playback = buildExoPlayer(token)
        currentPlayer = playback
        applyPlayerVolume(playback)
        playback.setMediaSource(frigateLiveMediaSource(transport, token, camera.tvStreamName, audioOnly = true))
        playback.prepare(); playback.playWhenReady = true
    }

    private fun refreshHomeDashboard(done: () -> Unit) {
        val refreshGeneration = ++homeRefreshGeneration
        val activeSession = session
        val activeApi = api
        val generation = homeStreamGeneration
        if (activeSession == null || activeApi == null) {
            done(); if (homeVisible && !appLock.locked) renderHome()
            return
        }
        activeApi.loadDashboard(activeSession.token) { result -> runOnUiThread {
            done()
            if (!homeVisible || appLock.locked || refreshGeneration != homeRefreshGeneration || generation != homeStreamGeneration || api !== activeApi || session != activeSession) return@runOnUiThread
            result.onSuccess { data ->
                cameras = data.cameras; events = data.events; dashboardErrorMessage = null
                globalQueryGeneration++; globalResults = null; globalLoading = false
                renderHome(if ((activeApi as? MultiFrigateApi)?.unavailable?.isNotEmpty() == true) getString(R.string.frigate_partial) else null)
            }.onFailure { toast(it.message ?: getString(R.string.frigate_unavailable)) }
        } }
    }

    private data class DashboardCamera(
        val key: String, val name: String, val source: CameraSource,
        val frigate: NativeCamera? = null, val onvif: OnvifSavedCamera? = null,
        val haCamera: HomeAssistantCamera? = null, val haConnection: HomeAssistantConnection? = null
    ) {
        val enabled get() = frigate?.enabled ?: true
    }

    private fun dashboardCameras(): List<DashboardCamera> {
        val items = cameras.map { camera ->
            DashboardCamera(frigateCameraKey(camera.name),
                camera.friendlyName, CameraSource.Frigate, frigate = camera)
        } + savedOnvifCameras().map { camera ->
            DashboardCamera(CameraArrangement.key("onvif", "", camera.id), camera.name, CameraSource.Onvif, onvif = camera)
        } + savedHomeAssistant()?.let { connection ->
            connection.cameras.map { camera ->
                DashboardCamera(CameraArrangement.key("ha", connection.baseUrl, camera.entityId), camera.name,
                    CameraSource.HomeAssistant, haCamera = camera, haConnection = connection)
            }
        }.orEmpty()
        val byKey = items.associateBy { it.key }
        return CameraArrangement.ordered(items.map { it.key }, cameraArrangement.savedOrder()).mapNotNull { byKey[it] }
    }

    private fun dashboardCameraVisible(camera: DashboardCamera): Boolean =
        camera.enabled && !cameraArrangement.hidden(camera.key) && (camera.frigate?.let { isCameraVisible(it) } ?: true)

    private fun dashboardCameraView(item: DashboardCamera, layout: HomeCameraLayout): View {
        if (TelevisionMode.enabled(this)) return televisionCameraTile(item)
        val overlay = layout == HomeCameraLayout.Column || tabletLayout().tablet && layout == HomeCameraLayout.TwoColumns
        item.frigate?.let { return if (overlay) cameraCard(it) else cameraGridTile(it) }
        item.onvif?.let { camera ->
            return OnvifLiveCard(this, camera,
                fullscreen = { renderExpandedLive(item) },
                manage = { openOnvifManager(camera.id, manage = true) },
                columnMode = overlay,
                accessories = { showAccessories(item.key, item.name) },
                onAudible = { selected ->
                    homeAssistantCards.forEach { it.mute() }
                    onvifCards.filter { it !== selected }.forEach { it.mute() }
                    homeAudioGeneration++; soundEnabled = false
                    prefs.edit().putBoolean("soundEnabled", false).apply()
                    releaseHomeAudioWebView(); currentPlaybackKey = null; refreshHomeSoundIcons()
                }).also { onvifCards.add(it) }
        }
        val connection = requireNotNull(item.haConnection)
        val camera = requireNotNull(item.haCamera)
        return HomeAssistantCameraView(this, connection, camera,
            fullscreen = { openHomeAssistantCamera(connection, camera) },
            columnMode = overlay,
            accessories = { showAccessories(item.key, item.name) },
            onAudible = { silenceOtherSources(it) }).also { homeAssistantCards.add(it) }
    }

    private fun televisionCameraTile(item: DashboardCamera): View {
        val video: View = when {
            item.frigate != null -> return cameraCard(item.frigate)
            item.onvif != null -> OnvifLiveCard(this, item.onvif, fullscreen = {}, manage = {}, playerOnly = true, fillPreview = true, preloadLive = true)
                .also { card -> onvifCards.add(card); tvCameraSound[item.key] = { card.restorePlaybackSound(it) } }
            else -> HomeAssistantCameraView(this, requireNotNull(item.haConnection), requireNotNull(item.haCamera),
                fullscreen = {}, onAudible = { silenceOtherSources(it) }, fullscreenMode = true, playerOnly = true, fillPreview = true, preloadLive = true)
                .also { card -> homeAssistantCards.add(card); tvCameraSound[item.key] = { enabled ->
                    card.restorePlaybackSound(enabled)
                    if (enabled) card.startLive()
                } }
        }
        fun presentationOnly(view: View) {
            view.isClickable = false; view.isFocusable = false
            if (view is android.widget.ImageButton && view.visibility != View.GONE) view.visibility = View.GONE
            if (view is ViewGroup) repeat(view.childCount) { presentationOnly(view.getChildAt(it)) }
        }
        presentationOnly(video)
        video.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> presentationOnly(video) }
        return object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY)
                    heightMeasureSpec else View.MeasureSpec.makeMeasureSpec(
                    View.MeasureSpec.getSize(widthMeasureSpec) * 9 / 16, View.MeasureSpec.EXACTLY))
            }
        }.apply {
            tag = "tv-camera-tile"
            setTag(R.id.tv_camera_tile, true)
            contentDescription = item.name
            background = rounded(Color.BLACK, 8); applyRoundedClip(8)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(video, frameMatch())
            addView(CameraColumnOverlay(context, item.name, item.source, emptyList()), frameMatch())
            setOnClickListener { renderExpandedLive(item, immersive = true, audible = true) }
        }
    }

    private fun showHomeEvents() = appearancePrefs.getBoolean("showHomeEvents", !TelevisionMode.enabled(this))
    private fun showHomeToolbar() = appearancePrefs.getBoolean("showHomeCameraToolbar", !TelevisionMode.enabled(this))
    private fun showHeaderChat() = appearancePrefs.getBoolean("showHeaderChat", !TelevisionMode.enabled(this))
    private fun showHeaderAlerts() = appearancePrefs.getBoolean("showHeaderAlerts", !TelevisionMode.enabled(this))

    private fun savedHomeAssistant(): HomeAssistantConnection? = runCatching { HomeAssistantStore(this).load() }
        .getOrElse { toast(getString(R.string.ha_storage_failed)); null }

    private fun cameraControlTarget(camera: DashboardCamera): CameraControlTarget {
        camera.onvif?.let { return CameraControlTarget(camera.name, onvif = it) }
        camera.haCamera?.let { return CameraControlTarget(camera.name, homeAssistant = camera.haConnection, haEntity = it.entityId) }
        val native = camera.frigate?.name ?: return CameraControlTarget(camera.name)
        val router = api as? MultiFrigateApi
        val server = runCatching { router?.serverFor(native) ?: frigateServers.load().firstOrNull() }.getOrNull()
        return CameraControlTarget(camera.name, frigate = server, frigateCamera = router?.nativeName(native) ?: native)
    }

    private fun showAccessories(key: String, name: String, respectAppearance: Boolean = true) {
        if (appLock.locked) return
        dismissTransientPopups()
        val item = dashboardCameras().firstOrNull { it.key == key } ?: return
        val sheet = CameraControlsSheet(this, cameraControlTarget(item).copy(name = name),
            allowed = { !appLock.locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) },
            respectAppearance = respectAppearance)
        accessorySheet = sheet
        sheet.setOnDismissListener { if (accessorySheet === sheet) accessorySheet = null }
        sheet.show()
    }

    private fun hasStandaloneCameras() = savedOnvifCameras().isNotEmpty() || savedHomeAssistant()?.cameras?.isNotEmpty() == true

    private fun openHomeAssistantManager() {
        haSettingsReturnPage = settingsPage.takeIf { activeTab == Tab.Settings }
        homeAssistantCards.forEach { it.setActive(false) }
        onvifCards.forEach { it.setActive(false) }
        homeAssistantManager.launch(Intent(this, HomeAssistantActivity::class.java))
    }

    private fun silenceOtherSources(selected: HomeAssistantCameraView) {
        homeAssistantCards.filter { it !== selected }.forEach { it.mute() }
        onvifCards.forEach { it.mute() }
        homeAudioGeneration++; soundEnabled = false
        prefs.edit().putBoolean("soundEnabled", false).apply()
        releaseHomeAudioWebView(); currentPlayer?.volume = 0f
        refreshHomeSoundIcons()
    }

    private fun openHomeAssistantCamera(connection: HomeAssistantConnection, camera: HomeAssistantCamera) {
        renderExpandedLive(DashboardCamera(CameraArrangement.key("ha", connection.baseUrl, camera.entityId),
            camera.name, CameraSource.HomeAssistant, haCamera = camera, haConnection = connection))
    }

    private fun savedOnvifCameras(): List<OnvifSavedCamera> = runCatching { OnvifCameraStore(this).load() }
        .getOrElse { toast(getString(R.string.onvif_storage_error)); emptyList() }

    private fun openOnvifManager(cameraId: String? = null, fullscreen: Boolean = false, manage: Boolean = false, discover: Boolean = false) {
        onvifSettingsReturnPage = settingsPage.takeIf { activeTab == Tab.Settings }
        onvifCards.forEach { it.setActive(false) }
        onvifManager.launch(Intent(this, OnvifActivity::class.java).apply {
            putExtra("cameraId", cameraId)
            putExtra("fullscreen", fullscreen)
            putExtra("manage", manage)
            putExtra("discover", discover)
        })
    }

    private fun isAuthFailure(message: String): Boolean =
        message.contains("HTTP 401") || message.contains("HTTP 403")

    private fun renderLiaChat() {
        if (appLock.locked) return
        dismissTransientPopups()
        val servers = runCatching { frigateServers.load() }.getOrElse { toast(getString(R.string.frigate_storage_error)); return }
        fun open(server: FrigateServer) {
            if (appLock.locked || isFinishing || isDestroyed) return
            val dialog = NativeChatDialog(this, server, httpClient)
            chatDialog = dialog
            dialog.setOnDismissListener { if (chatDialog === dialog) chatDialog = null }
            dialog.show()
        }
        if (servers.isEmpty()) { renderLogin(); return }
        if (servers.size == 1) open(servers.first()) else {
            val list = vertical()
            lateinit var sheet: SelectionBottomSheet
            servers.forEach { server -> list.addView(settingsNavRow(server.name, server.baseUrl, { sheet.dismiss(); open(server) })) }
            sheet = SelectionBottomSheet(this, getString(R.string.frigate_chat_server), list)
            chatServerSheet = sheet
            sheet.setOnDismissListener { if (chatServerSheet === sheet) chatServerSheet = null }
            sheet.show()
        }
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
            tintVideoBuffering()
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
                onSound = { if (currentFocusIsLive) toggleHomeSound(camera) else toggleSound() },
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
        if (currentFocusIsLive) ensureLiveAudio(camera)
    }

    private fun renderEventDetail(event: NativeEvent, displayedCameraName: String = event.camera) {
        if (TelevisionMode.enabled(this) && findViewById<ViewGroup>(android.R.id.content)
                .findViewWithTag<View>("tv-notifications-browser") != null) tvHistoryOrigin = Tab.Grid
        else if (TelevisionMode.enabled(this) && findViewById<ViewGroup>(android.R.id.content)
                .findViewWithTag<View>("event-player-frame") == null) tvHistoryOrigin = Tab.Events
        renderEventDetailAt(event, displayedCameraName, event.startTime ?: 0.0, true)
    }

    private fun renderEventDetailAt(event: NativeEvent, displayedCameraName: String, focusSec: Double, playing: Boolean) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        val scrollTag = "event-scroll:${event.id}"
        val previousScrollY = findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<android.widget.ScrollView>(scrollTag)?.scrollY ?: 0
        releasePlayer()
        val displayedCamera = cameras.firstOrNull { it.name == displayedCameraName }
            ?: cameras.firstOrNull { it.name == event.camera }
        val activeCameraName = displayedCamera?.name ?: event.camera
        val activeCameraLabel = displayedCamera?.friendlyName ?: activeCameraName
        val showingEventCamera = activeCameraName == event.camera

        val body = vertical().apply { setBackgroundColor(Palette.background) }
        val playerFrame = ExpandedVideoFrame(this).apply {
            tag = "event-player-frame"
            setBackgroundColor(Palette.black)
        }
        val fallback = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
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
        val playerView = (if (TelevisionMode.enabled(this))
            layoutInflater.inflate(R.layout.tv_live_player, playerFrame, false) as PlayerView
            else PlayerView(this)).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            tintVideoBuffering()
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
        }
        playerFrame.addView(playerView, frameMatch())
        playerFrame.addPlaybackActions(soundToggleButton().apply {
                contentDescription = getString(R.string.playback_sound)
            }, iconBubble(R.drawable.ic_fullscreen) {
                val dialog = eventFullscreenDialog
                if (dialog != null) dialog.dismiss()
                else displayedCamera?.let { openEventCameraFullscreen(event, it) }
            }.apply {
                contentDescription = getString(R.string.camera_fullscreen)
            }, "event-player-actions")
        body.addView(playerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        val controls = layoutInflater.inflate(R.layout.event_playback_controls, body, false) as androidx.media3.ui.PlayerControlView
        controls.setBackgroundColor(Palette.background)
        for (id in listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_ffwd)) {
            controls.findViewById<ImageView>(id).setColorFilter(Palette.text)
        }
        for (id in listOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)) {
            controls.findViewById<TextView>(id).setTextColor(Palette.muted)
        }
        controls.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress).apply {
            setPlayedColor(Palette.accent)
            setScrubberColor(Palette.accent)
            setBufferedColor(Palette.tick)
            setUnplayedColor(Palette.tickMinor)
        }
        body.addView(controls)

        val scroll = appScrollView()
        scroll.tag = scrollTag
        val column = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(120)) }
        scroll.addView(column)
        column.addView(label(getString(R.string.event_playback), 12, Palette.muted))
        column.addView(label(eventDisplayText(event), if (TelevisionMode.enabled(this)) 20 else 28, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val playbackTime = if (TelevisionMode.enabled(this)) event.startTime?.let {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM)
                .format(java.util.Date((it * 1000).toLong()))
        } ?: event.time else event.time
        column.addView(label("$activeCameraLabel\n$playbackTime".takeIf { TelevisionMode.enabled(this) }
            ?: "$activeCameraLabel - $playbackTime", 14, Palette.muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        if (!TelevisionMode.enabled(this)) column.addView(label(eventSentence(event), 14, Palette.text).apply { setPadding(0, 0, 0, dp(12)) })
        event.description?.let { description ->
            column.addView(sectionHeading(getString(R.string.frigate_description)))
            column.addView(label(description, if (TelevisionMode.enabled(this)) 12 else 14, Palette.muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        }
        column.addView(label(getString(R.string.same_moment), if (TelevisionMode.enabled(this)) 14 else 18, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        column.addView(syncCameraGrid(event, activeCameraName))
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (TelevisionMode.enabled(this)) {
            body.removeView(playerFrame)
            body.removeView(controls)
            body.removeView(scroll)
            body.orientation = LinearLayout.HORIZONTAL
            val playback = vertical().apply {
                addView(playerFrame, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(controls, LinearLayout.LayoutParams(-1, -2))
            }
            body.addView(playback, LinearLayout.LayoutParams(0, -1, 3.3f))
            column.setPadding(dp(14), dp(12), dp(8), dp(16))
            body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }
        setShell(body, if (TelevisionMode.enabled(this)) tvHistoryOrigin else Tab.Events)
        if (TelevisionMode.enabled(this)) {
            expandedLiveBack = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (tvHistoryOrigin == Tab.Grid) renderTvHistory(true, restoreFocus = true)
                    else renderTvHistory(false, restoreFocus = true)
                }
            }.also { onBackPressedDispatcher.addCallback(this, it) }
        }
        scroll.post { scroll.scrollTo(0, previousScrollY) }
        eventPlaybackControls = controls
        if (showingEventCamera || displayedCamera == null) {
            startClipPlayer(activeApi, activeSession.token, event, playerView, fallback)
            currentPlayer?.seekTo(((focusSec - (event.startTime ?: 0.0)).coerceAtLeast(0.0) * 1000).toLong())
        } else {
            startTimelineRecordingPlayer(activeApi, activeSession.token, displayedCamera, playerView, fallback, focusSec)
        }
        currentPlayer?.playWhenReady = playing
        controls.show()
        if (TelevisionMode.enabled(this)) {
            installTvPlaybackChrome(playerFrame, controls)
        }
    }

    private fun installTvPlaybackChrome(frame: ViewGroup, controls: androidx.media3.ui.PlayerControlView) {
        val actions = frame.findViewWithTag<View>("event-player-actions")
        fun reveal() { controls.visibility = View.VISIBLE; controls.show(); actions?.visibility = View.VISIBLE }
        val hide = object : Runnable {
            override fun run() {
                if (!frame.isAttachedToWindow) return
                val focused = currentFocus
                fun contains(parent: ViewGroup): Boolean = focused != null && (focused === parent || (0 until parent.childCount).any {
                    val child = parent.getChildAt(it)
                    child === focused || child is ViewGroup && contains(child)
                })
                if (currentPlayer?.isPlaying == true && !contains(controls) && (actions !is ViewGroup || !contains(actions))) {
                    controls.visibility = View.INVISIBLE; actions?.visibility = View.GONE
                }
                frame.postDelayed(this, 3500)
            }
        }
        frame.isFocusable = true
        frame.setTag(R.id.tv_remote_action, true)
        frame.setOnClickListener { reveal(); controls.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus() }
        frame.setOnKeyListener { _, key, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && key in listOf(android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER)) {
                reveal(); controls.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus(); true
            } else false
        }
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { frame.postDelayed(hide, 3500) }
            override fun onViewDetachedFromWindow(view: View) { frame.removeCallbacks(hide) }
        })
        frame.post { frame.requestFocus(); frame.postDelayed(hide, 3500) }
    }

    private fun openEventCameraFullscreen(event: NativeEvent, camera: NativeCamera) {
        if (eventFullscreenDialog != null) return
        val playing = currentPlayer?.playWhenReady ?: true
        val baseSec = currentPlaybackKey?.takeIf { it.startsWith("recording:") }
            ?.split(':')?.takeLast(2)?.firstOrNull()?.toDoubleOrNull() ?: (event.startTime ?: 0.0)
        val focusSec = baseSec + (currentPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L) / 1000.0
        renderEventDetailAt(event, camera.name, focusSec, playing)
        val frame = findViewById<ViewGroup>(android.R.id.content).findViewWithTag<FrameLayout>("event-player-frame") ?: return
        val controls = eventPlaybackControls ?: return
        val parent = frame.parent as LinearLayout
        val frameIndex = parent.indexOfChild(frame)
        val controlsIndex = parent.indexOfChild(controls)
        val frameParams = frame.layoutParams
        val controlsParams = controls.layoutParams
        val placeholder = View(this)
        parent.removeView(frame)
        parent.addView(placeholder, frameIndex, frameParams)
        parent.removeView(controls)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val full = vertical().apply { setBackgroundColor(Palette.black) }
        full.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        full.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val close = iconBubble(R.drawable.ic_fullscreen) { dialog.dismiss() }.apply {
            contentDescription = getString(android.R.string.cancel)
        }
        frame.addView(close, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(16); topMargin = dp(14)
        })
        dialog.setContentView(full)
        dialog.setOnDismissListener {
            frame.removeView(close)
            full.removeView(frame)
            full.removeView(controls)
            parent.removeView(placeholder)
            parent.addView(frame, frameIndex, frameParams)
            parent.addView(controls, controlsIndex, controlsParams)
            eventFullscreenDialog = null
        }
        eventFullscreenDialog = dialog
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun startClipPlayer(api: NvrRecordedMedia, token: String, event: NativeEvent, playerView: PlayerView, fallback: ImageView) {
        releaseCurrentPlayer()
        val player = buildExoPlayer(token)
        currentPlayer = player
        val key = "event:${event.id}"
        currentPlaybackKey = key
        playerView.player = player
        eventPlaybackControls?.player = player
        playerView.visibility = View.VISIBLE
        attachFallbackHider(player, key, fallback, playerView)
        player.setMediaItem(MediaItem.fromUri(if (TelevisionMode.enabled(this)) api.eventClipUrl(event.id) else api.eventVodUrl(event.id)))
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
            .build().also { playback ->
                if (TelevisionMode.enabled(this)) playback.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            }

    private fun startTimelineEventPlayer(
        api: NvrRecordedMedia,
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
        eventPlaybackControls?.player = player
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
        api: NvrPlaybackMedia,
        token: String,
        camera: NativeCamera,
        playerView: PlayerView,
        fallback: ImageView,
        focusSec: Double,
        onPlaybackClock: (Double) -> Unit = {}
    ) {
        val safeFocus = focusSec.coerceAtLeast(0.0)
        homeAudioGeneration++
        releaseHomeAudioWebView()
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
        eventPlaybackControls?.player = player
        playerView.visibility = View.VISIBLE
        var firstReadySeekDone = false
        var usingDefaultVod = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && player.playWhenReady && currentPlayer === player && currentPlaybackKey == key) {
                    val generation = timelinePlaybackGeneration
                    mainHandler.post {
                        if (generation == timelinePlaybackGeneration && currentPlayer === player && currentPlaybackKey == key) {
                            val now = System.currentTimeMillis() / 1000.0
                            val next = nextRecordingFocus(windowEnd, now)
                            if (next == null) onPlaybackClock(now)
                            else startTimelineRecordingPlayer(api, token, camera, playerView, fallback, next, onPlaybackClock)
                        }
                    }
                }
                if (playbackState == Player.STATE_READY && currentPlaybackKey == key) {
                    if (!firstReadySeekDone) {
                        firstReadySeekDone = true
                        player.seekTo(seekMs)
                    }
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
                        val resumePlayback = player.playWhenReady
                        player.setMediaItem(MediaItem.fromUri(api.recordingClipUrl(camera.name, windowStart, windowEnd)))
                        player.prepare()
                        player.playWhenReady = resumePlayback
                        if (resumePlayback) player.play()
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
            if (playerView.visibility == View.VISIBLE && player.isPlaying) {
                onPlaybackClock(windowStartSec + (player.currentPosition / 1000.0))
            }
            if (player.playbackState != Player.STATE_ENDED) {
                mainHandler.postDelayed({ tick() }, TIMELINE_PLAYBACK_CLOCK_MS)
            }
        }
        mainHandler.postDelayed({ tick() }, TIMELINE_PLAYBACK_CLOCK_MS)
    }

    private fun startMjpegStream(
        api: NvrLiveFrames,
        token: String,
        camera: NativeCamera,
        image: ImageView,
        badge: View
    ) {
        streamRestarts[image] = { startMjpegStream(api, token, camera, image, badge) }
        val frame = image.parent as? FrameLayout
        val connection = frame?.findViewWithTag<CameraConnectionOverlay>("camera-connection-overlay")
            ?: frame?.let { parent -> CameraConnectionOverlay(this).also {
                parent.addView(it, parent.indexOfChild(image) + 1, frameMatch())
            } }
        fun showConnection() { connection?.showConnection((image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap) }
        showConnection()
        badge.visibility = View.GONE
        if (streamsSuspended) return
        val generation = homeStreamGeneration
        fun connect(attempt: Int) {
            if (generation != homeStreamGeneration || streamsSuspended) return
            if (TelevisionMode.enabled(this) && !homeVisible && !image.isShown) {
                mainHandler.postDelayed({ connect(attempt) }, 1000L)
                return
            }
            lateinit var stream: WebSocket
            var receivedFrame = false
            var lastFrameAt = android.os.SystemClock.elapsedRealtime()
            lateinit var watchdog: Runnable
            fun recover() {
                if (generation != homeStreamGeneration || !homeStreams.remove(stream)) return
                mainHandler.removeCallbacks(watchdog)
                stream.cancel()
                badge.visibility = View.GONE
                showConnection()
                val next = if (receivedFrame) 0 else (attempt + 1).coerceAtMost(6)
                mainHandler.postDelayed({ connect(next) }, streamRetryDelay(next))
            }
            watchdog = object : Runnable {
                override fun run() {
                    if (generation != homeStreamGeneration || !homeStreams.contains(stream)) return
                    if (TelevisionMode.enabled(this@MainActivity) && !homeVisible && !image.isShown) { recover(); return }
                    val limit = if (receivedFrame) 15_000L else HOME_MJPEG_STARTUP_TIMEOUT_MS
                    if (android.os.SystemClock.elapsedRealtime() - lastFrameAt >= limit) recover()
                    else mainHandler.postDelayed(this, 1000L)
                }
            }
            stream = api.openMjpegStream(
                token = token,
                streamName = if (TelevisionMode.enabled(this)) camera.tvStreamName else camera.streamName,
                onFrame = { bitmap ->
                    runOnUiThread {
                        if (!homeStreams.contains(stream) || generation != homeStreamGeneration) return@runOnUiThread
                        receivedFrame = true
                        lastFrameAt = android.os.SystemClock.elapsedRealtime()
                        image.setImageBitmap(bitmap)
                        connection?.visibility = View.GONE
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

    private fun authDataSource(token: String): androidx.media3.datasource.DataSource.Factory {
        val router = api as? MultiFrigateApi ?: return DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token", "User-Agent" to "Camnory"))
        val mediaClient = httpClient.newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            val (server, canonical) = canonicalFrigateMediaSource(router.servers, chain.request().url.toString())
                ?: throw java.io.IOException("Unknown Frigate media source")
            chain.proceed(chain.request().newBuilder().url(canonical).header("Authorization", "Bearer ${server.token}").build())
        }.apply {
            router.servers.forEach { server -> server.localRoute?.let {
                addInterceptor(LocalRouteInterceptor.create(this@MainActivity, server.baseUrl, it, server.token, "/api/version"))
            } }
        }.build()
        return androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(mediaClient).setUserAgent("Camnory")
    }

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
        detailSoundRefresh?.invoke()
    }

    private fun ensureLiveAudio(camera: NativeCamera) {
        if (!soundEnabled) return
        val key = homePlaybackKey(camera)
        if (currentPlaybackKey == key && homeAudioWebView != null) return
        releaseHomeAudioWebView()
        currentPlaybackKey = key
        startHomeLiveAudio(camera, key, ++homeAudioGeneration)
    }

    private fun toggleHomeSound(camera: NativeCamera): Boolean {
        onvifCards.forEach { it.mute() }
        homeAssistantCards.forEach { it.mute() }
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
        val owner = (api as? MultiFrigateApi)?.serverFor(camera.name)
        val activeSession = owner?.let { NativeSession(it.baseUrl, it.token, it.username) } ?: session
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
            }, "CamnoryAudio")
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
            const startup = setTimeout(() => CamnoryAudio.failed(), 12000);
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
              } catch (_) { CamnoryAudio.failed(); }
            }
            video.addEventListener('error', () => {
              try { if (socket) socket.close(); } catch (_) {}
              CamnoryAudio.failed();
            });
            const mediaSource = new MediaSource();
            video.src = URL.createObjectURL(mediaSource);
            mediaSource.addEventListener('sourceopen', () => {
              socket = new WebSocket($wsLiteral);
              socket.binaryType = 'arraybuffer';
              socket.onopen = () => {
                socket.send(JSON.stringify({ type: 'mse', value: $codecsLiteral }));
              };
              socket.onerror = () => CamnoryAudio.failed();
              socket.onclose = () => CamnoryAudio.failed();
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
                if (queue.length > 120) { CamnoryAudio.failed(); return; }
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

    private fun tabletActivitySidebar(): View = vertical().apply {
        tag = "tablet-activity-sidebar"
        setPadding(dp(16), dp(10), dp(16), dp(16))
        addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(R.string.recent_activity), 16, Palette.text).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toolbarIcon(R.drawable.ic_chevron_right, Palette.accent) { renderGlobalEvents() }.apply {
                contentDescription = getString(R.string.notifications_history)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        val rows = vertical()
        if (events.isEmpty()) rows.addView(emptyMessage(getString(R.string.no_recent_events)))
        events.take(20).forEach { event ->
            rows.addView(horizontal().apply {
                tag = "tablet-activity:${event.id}"
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88)
                setPadding(0, dp(10), 0, dp(10)); isFocusable = true
                setOnClickListener { renderEventDetail(event) }
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(Palette.surface, 6); applyRoundedClip(6)
                }
                addView(image, LinearLayout.LayoutParams(dp(76), dp(50)).apply { marginEnd = dp(12) })
                if (event.thumbnail != null) image.setImageBitmap(event.thumbnail)
                else session?.let { activeSession -> api?.let { activeApi ->
                    activeApi.loadBitmap(activeSession.token, activeApi.eventSnapshotUrl(event.id)) { bitmap ->
                        runOnUiThread { if (image.isAttachedToWindow && bitmap != null) image.setImageBitmap(bitmap) }
                    }
                } }
                addView(vertical().apply {
                    addView(label(eventDisplayText(event), 13, Palette.text).apply {
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(label(cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera, 12, Palette.muted).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(label(event.time, 12, Palette.muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            rows.addView(View(context).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        addView(appScrollView().apply { addView(rows, FrameLayout.LayoutParams(-1, -2)) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun cameraCard(camera: NativeCamera): View {
        val card = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, if (TelevisionMode.enabled(this@MainActivity) && View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY)
                    heightMeasureSpec else if (tabletLayout().tablet)
                    View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec) * 9 / 16, View.MeasureSpec.EXACTLY) else heightMeasureSpec)
            }
        }.apply {
            background = rounded(Palette.card, 8)
            applyRoundedClip(8)
            if (tabletLayout().tablet) setOnClickListener { renderCameraFullscreen(camera) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.surface)
        }
        if (TelevisionMode.enabled(this)) {
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.setBackgroundColor(Color.BLACK)
            card.setBackgroundColor(Color.BLACK)
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
        if (TelevisionMode.enabled(this)) {
            card.tag = "tv-camera-tile"
            card.setTag(R.id.tv_camera_tile, true)
            card.contentDescription = camera.friendlyName
            card.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            card.addView(CameraColumnOverlay(this, camera.friendlyName, CameraSource.Frigate, emptyList()), frameMatch())
            return card
        }
        val controls = mutableListOf<View>().apply {
            add(homeSoundBubble(camera).cameraButton(CameraButton.Sound))
            if (camera.audioEnabled == true) {
                add(iconBubble(R.drawable.ic_mic, alphaValue = 0.85f) {
                    toast(getString(R.string.micro_unavailable))
                })
            }
            add(iconBubble(R.drawable.ic_fullscreen) { renderCameraFullscreen(camera) })
            add(iconBubble(R.drawable.ic_settings) { anchor -> showCameraOptionsPopup(camera, anchor) })
            add(iconBubble(R.drawable.ic_list) { renderCameraDetail(camera) })
        }
        card.addView(CameraColumnOverlay(this, camera.friendlyName, CameraSource.Frigate, controls), frameMatch())
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
            addView(CameraSource.Frigate.iconView(this@MainActivity), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
            addView(label(camera.friendlyName, 12, Palette.text).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                contentDescription = camera.friendlyName
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, camera.friendlyName)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toolbarIcon(R.drawable.ic_more_vertical, tint = Palette.text) { anchor ->
                showGridCameraActions(camera, anchor)
            }.apply {
                visibility = if (CameraButton.More.visible(context) && listOf(CameraButton.Sound, CameraButton.Fullscreen,
                    CameraButton.Settings, CameraButton.Events).any { it.visible(context) }) View.VISIBLE else View.GONE
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
        showHomeViewMenu(anchor, actions.filter { CameraButton.iconVisible(this, it.first) }.map { (icon, title, action) ->
            homeMenuRow(icon, title, false) { action() }
        })
    }

    private fun renderCameraFullscreen(camera: NativeCamera) {
        renderExpandedLive(DashboardCamera(frigateCameraKey(camera.name), camera.friendlyName,
            CameraSource.Frigate, frigate = camera), immersive = TelevisionMode.enabled(this), audible = TelevisionMode.enabled(this))
    }

    private fun renderExpandedLive(item: DashboardCamera, immersive: Boolean = false, audible: Boolean = false) {
        if (appLock.locked) return
        releasePlayer()
        if (TelevisionMode.enabled(this) && audible) soundEnabled = true
        val frame = ExpandedVideoFrame(this).apply { tag = "live-player-frame" }
        var readSound: () -> Boolean = { soundEnabled }
        var start: () -> Unit = {}
        val sound: View
        when {
            item.frigate != null -> {
                val camera = item.frigate
                val activeApi = api ?: return renderLogin()
                val token = session?.token ?: return renderLogin()
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                val live = liveBadge().apply { visibility = View.GONE }
                frame.addView(image, frameMatch())
                frame.addView(live, FrameLayout.LayoutParams(-2, dp(24), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(12); marginEnd = dp(12)
                })
                if (TelevisionMode.enabled(this)) {
                    sound = iconBubble(if (soundEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off) { button ->
                        val enabled = toggleSound()
                        ((button as ViewGroup).getChildAt(0) as ImageView).setImageResource(
                            if (enabled) R.drawable.ic_volume else R.drawable.ic_volume_off)
                    }.apply { contentDescription = getString(R.string.playback_sound) }
                    start = {
                        val key = "tv-live:${camera.name}"
                        currentPlaybackKey = key
                        startMjpegStream(activeApi, token, camera, image, live)
                        val stream = camera.tvStreamName
                        var fallbackRunning = true
                        val video = (layoutInflater.inflate(R.layout.tv_live_player, frame, false) as PlayerView)
                            .apply { visibility = View.GONE }
                        frame.addView(video, 1, frameMatch())
                        val playback = buildExoPlayer(token)
                        currentPlayer = playback
                        video.player = playback
                        applyPlayerVolume(playback)
                        playback.addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (currentPlayer === playback && playbackState == Player.STATE_READY) {
                                    homeStreamGeneration++
                                    homeStreams.forEach { it.cancel() }
                                    homeStreams.clear()
                                    streamRestarts.remove(image)
                                    fallbackRunning = false
                                    video.visibility = View.VISIBLE
                                    image.visibility = View.GONE
                                    frame.findViewWithTag<View>("camera-connection-overlay")?.visibility = View.GONE
                                }
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                if (currentPlayer === playback) {
                                    video.visibility = View.GONE
                                    image.visibility = View.VISIBLE
                                    if (!fallbackRunning) {
                                        startMjpegStream(activeApi, token, camera, image, live)
                                        fallbackRunning = true
                                    }
                                    toast(getString(R.string.tv_preview_quality))
                                }
                            }
                        })
                        playback.setMediaSource(frigateLiveMediaSource(activeApi, token, stream))
                        playback.prepare()
                        playback.playWhenReady = true
                    }
                } else {
                    sound = homeSoundBubble(camera)
                    start = {
                        startMjpegStream(activeApi, token, camera, image, live)
                        ensureLiveAudio(camera)
                    }
                }
            }
            item.onvif != null -> {
                val card = OnvifLiveCard(this, item.onvif, fullscreen = {}, manage = {}, playerOnly = true)
                card.restorePlaybackSound(audible)
                onvifCards.add(card)
                frame.addView(card, frameMatch())
                sound = card.expandedSoundControl()
                readSound = { card.playbackAudible() }
                start = { card.setActive(true) }
            }
            else -> {
                val card = HomeAssistantCameraView(this, requireNotNull(item.haConnection),
                    requireNotNull(item.haCamera), fullscreen = {}, onAudible = { silenceOtherSources(it) },
                    fullscreenMode = true, playerOnly = true)
                card.restorePlaybackSound(audible)
                homeAssistantCards.add(card)
                frame.addView(card, frameMatch())
                sound = card.expandedSoundControl()
                readSound = { card.playbackAudible() }
                start = { card.setActive(true); card.startLive() }
                expandedLiveResume = { card.startLive() }
            }
        }
        frame.addPlaybackActions(sound, iconBubble(if (TelevisionMode.enabled(this)) R.drawable.ic_arrow_back else R.drawable.ic_fullscreen) {
            if (TelevisionMode.enabled(this)) renderHome() else renderExpandedLive(item, !immersive, readSound())
        }.apply { contentDescription = getString(if (TelevisionMode.enabled(this@MainActivity)) R.string.onvif_back else R.string.camera_fullscreen) }, "live-player-actions")
        if (TelevisionMode.enabled(this)) {
            frame.findViewWithTag<LinearLayout>("live-player-actions").addView(iconBubble(R.drawable.ic_settings) {
                dismissTransientPopups()
                val sheet = CameraControlsSheet(this, cameraControlTarget(item),
                    allowed = { !appLock.locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) },
                    respectAppearance = false)
                accessorySheet = sheet
                sheet.setOnDismissListener { if (accessorySheet === sheet) accessorySheet = null }
                sheet.show()
            }.apply {
                tag = "tv-live-camera-options"
                contentDescription = getString(R.string.camera_all_settings)
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        }
        if (immersive) {
            setContentView(frame)
            if (TelevisionMode.enabled(this)) frame.post {
                frame.findViewWithTag<LinearLayout>("live-player-actions")?.getChildAt(0)?.requestFocus()
            }
            expandedLiveImmersive = true
            androidx.core.view.WindowCompat.getInsetsController(window, frame).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val body = vertical().apply {
                setBackgroundColor(Palette.background)
                addView(frame, LinearLayout.LayoutParams(-1, dp(260)))
                addView(horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(20), dp(16), dp(16))
                    addView(iconBubble(R.drawable.ic_arrow_back) { renderHome() }.apply {
                        contentDescription = getString(R.string.onvif_back)
                    }, LinearLayout.LayoutParams(dp(44), dp(44)))
                    addView(TextView(context).apply {
                        text = item.name; textSize = 24f; setTextColor(Palette.text)
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                })
            }
            setShell(body, Tab.Home)
        }
        expandedLiveBack = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (immersive && !TelevisionMode.enabled(this@MainActivity)) renderExpandedLive(item, false, readSound()) else renderHome()
            }
        }.also { onBackPressedDispatcher.addCallback(this, it) }
        expandedLiveReflow = { renderExpandedLive(item, immersive, readSound()) }
        frame.doOnLayout { if (!appLock.locked) start() }
    }

    private fun cameraToolbar(
        camera: NativeCamera,
        showEventsList: Boolean,
        onRefresh: () -> Unit,
        onSnapshot: () -> Unit,
        onSound: () -> Boolean,
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
                addView(toolbarIcon(R.drawable.ic_timeline, tint = if (!showEventsList) Palette.accent else Palette.muted, onClick = { onRefresh() }))
                addView(soundToolbarIcon(onSound))
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

    private fun renderTvEvents() = renderTvHistory(false)

    private fun renderTvHistory(inbox: Boolean, restoreFocus: Boolean = false) {
        val browserTag = if (inbox) "tv-notifications-browser" else "tv-events-browser"
        val previous = findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>(browserTag)
        val previousFocus = currentFocus?.tag as? String
        val restore = restoreFocus || previous != null && previousFocus?.startsWith(if (inbox) "tv-notification:" else "tv-event:") == true
        releasePlayer()
        val body = vertical().apply { setPadding(dp(18), dp(8), dp(18), dp(14)); tag = browserTag }
        val seen = seenAlertIds()
        val source = (globalResults ?: events).filter { event -> visibleCameras().any { it.name == event.camera } }
            .sortedByDescending { it.startTime ?: 0.0 }
        val unread = source.filter { it.id !in seen && (it.startTime == null || it.startTime > seenAlertsCutoffMillis(it) / 1000.0) }.map { it.id }.toSet()
        body.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(getString(if (inbox) R.string.notifications else R.string.events_tab), 23, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            if (inbox) {
                addView(FrameLayout(this@MainActivity).apply { addView(android.widget.CheckBox(this@MainActivity).apply {
                    text = getString(R.string.tv_unread_only)
                    textSize = 13f
                    buttonTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Palette.accent, Palette.muted))
                    tag = "tv-unread-filter"
                    isChecked = tvUnreadOnly
                    setTextColor(if (tvUnreadOnly) Palette.accent else Palette.text)
                    setOnClickListener { tvUnreadOnly = !tvUnreadOnly; tvNotificationSelectedId = null; renderTvHistory(true)
                        window.decorView.post { window.decorView.findViewWithTag<View>("tv-unread-filter")?.requestFocus() } }
                }, FrameLayout.LayoutParams(-2, -1)) }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(12) })
                addView(iconBubble(R.drawable.ic_selection_check) {
                    markAlertsSeen(source.filter { it.id in unread }, advanceCutoff = false)
                    renderTvHistory(true)
                    window.decorView.post { window.decorView.findViewWithTag<View>("tv-read-all")?.requestFocus() }
                }.apply {
                    tag = "tv-read-all"; contentDescription = getString(R.string.tv_read_loaded)
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                    isEnabled = unread.isNotEmpty(); alpha = if (isEnabled) 1f else 0.4f
                })
            }
        })
        body.addView(eventFilterBar())
        val selected = if (inbox) tvNotificationSelectedId else tvEventSelectedId
        val history = TvHistoryView(this, source.filter { !inbox || !tvUnreadOnly || it.id in unread }, inbox, unread, selected,
            title = ::eventDisplayText,
            camera = { event -> cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera },
            thumbnail = { event, image -> session?.let { active -> api?.let { transport ->
                transport.loadBitmap(active.token, transport.eventSnapshotUrl(event.id)) { bitmap ->
                    runOnUiThread { if (image.isAttachedToWindow && bitmap != null) image.setImageBitmap(bitmap) }
                }
            } } },
            onSelect = { if (inbox) tvNotificationSelectedId = it else tvEventSelectedId = it },
            onOpen = { event -> if (inbox) markAlertSeen(event); tvHistoryOrigin = if (inbox) Tab.Grid else Tab.Events; renderEventDetail(event) })
        body.addView(history, LinearLayout.LayoutParams(-1, 0, 1f))
        if (globalLoading) body.addView(label(getString(R.string.filter_loading), 12, Palette.muted))
        if (globalError || globalHasMore) body.addView(secondaryButton(getString(if (globalError) R.string.filter_retry else R.string.filter_more)).apply {
            isEnabled = !globalLoading
            setOnClickListener { loadGlobalEventPage(reset = globalError && globalResults.isNullOrEmpty()) }
        })
        setShell(body, if (inbox) Tab.Grid else Tab.Events)
        if (restore) history.post { history.focusSelection(selected) }
        if (globalResults == null && !globalLoading) loadGlobalEventPage(reset = true)
    }

    private fun renderGlobalEvents() {
        if (TelevisionMode.enabled(this)) return renderTvHistory(true)
        releasePlayer()
        setShell(PullRefreshLayout(this, globalEventList(globalResults.orEmpty()),
            allowed = { !appLock.locked && !globalLoading && activeTab == Tab.Grid },
            refresh = { done -> loadGlobalEventPage(reset = true, onComplete = done) }), Tab.Grid)
        if (globalResults == null && !globalLoading) loadGlobalEventPage(reset = true)
    }

    private fun loadGlobalEventPage(reset: Boolean, onComplete: () -> Unit = {}) {
        val activeSession = session ?: return onComplete()
        val activeApi = api ?: return onComplete()
        if (globalLoading) return onComplete()
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
                onComplete()
                if (generation != globalQueryGeneration || session?.token != activeSession.token || search != eventSearch) return@runOnUiThread
                globalLoading = false
                result.onSuccess { page ->
                    globalResults = (globalResults.orEmpty() + page).distinctBy { it.id }.sortedByDescending { it.startTime ?: 0.0 }
                    globalHasMore = page.size >= 100
                }.onFailure { globalError = true }
                if (activeTab == Tab.Grid) refreshGlobalEventResults()
                else if (activeTab == Tab.Events && findViewById<ViewGroup>(android.R.id.content)
                    .findViewWithTag<View>("tv-events-browser") != null) renderTvEvents()
            }
        }
    }

    private fun changeEventSearch(next: EventSearch) {
        eventSearch = next
        globalQueryGeneration++
        globalLoading = false
        globalResults = null
        tvEventSelectedId = null
        tvNotificationSelectedId = null
        if (TelevisionMode.enabled(this)) renderTvHistory(activeTab == Tab.Grid) else renderGlobalEvents()
    }

    private fun eventFilterBar(): View = horizontal().apply {
        tag = "event-filter-bar"; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(10))
        val criteria = listOfNotNull(
            eventSearch.camera?.let { camera -> visibleCameras().firstOrNull { it.name == camera }?.friendlyName ?: camera },
            eventSearch.kind.takeIf { it != EventKind.Any }?.let { getString(it.titleRes) }, eventSearch.name,
            eventSearch.hours?.let { getString(when (it) { 24 -> R.string.filter_day; 168 -> R.string.filter_week; else -> R.string.filter_month }) })
        addView(label(criteria.joinToString(" · ").ifBlank { getString(R.string.event_filters_none) }, 12, Palette.muted).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(FrameLayout(context).apply {
            tag = "event-filter-open"; isFocusable = true; isClickable = true
            contentDescription = getString(R.string.event_filters_title)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_event_filters)
                setColorFilter(if (eventSearch.activeFilterCount > 0) Palette.accent else Palette.text)
            }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            if (eventSearch.activeFilterCount > 0) addView(label(eventSearch.activeFilterCount.toString(), 9, Color.WHITE).apply {
                tag = "event-filter-count"; gravity = Gravity.CENTER; background = rounded(Palette.accent, 8)
            }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { topMargin = dp(3) })
            setOnClickListener {
                dismissTransientPopups()
                val sheet = EventFilterSheet(this@MainActivity, eventSearch, visibleCameras().map { it.name to it.friendlyName }) { next ->
                    if (next != eventSearch) changeEventSearch(next)
                }
                eventFilterSheet = sheet
                sheet.setOnDismissListener { if (eventFilterSheet === sheet) eventFilterSheet = null }
                sheet.show()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
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
                addView(label(visibleCameras().firstOrNull { it.name == eventSearch.camera }?.friendlyName ?: getString(R.string.all_cameras), 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(eventFilterBar())
        val results = vertical()
        globalResultsContainer = results
        column.addView(results)
        populateGlobalEventResults(results, sourceEvents)
        return scroll
    }

    private fun refreshGlobalEventResults() {
        if (TelevisionMode.enabled(this)) {
            val root = findViewById<ViewGroup>(android.R.id.content)
            if (root.findViewWithTag<View>("tv-notifications-browser") != null) renderTvHistory(true)
            else if (root.findViewWithTag<View>("tv-events-browser") != null) renderTvHistory(false)
            return
        }
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
        val zoomLevels = timelineZoomDensities
        val savedDensity = if (prefs.contains("timelineZoomDensity")) prefs.getInt("timelineZoomDensity", 34) else null
        val zoomIndex = timelineZoomIndex(savedDensity, prefs.getInt("timelineZoomIndex", 1))
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
        val spanMinutes = maxOf(18.0, (anchorSec - selectedDayStart / 1000.0) / 60.0,
            (anchorSec - oldestEventSec) / 60.0 + 6.0)
        val timelineThumbWidth = dp(148)
        val timelineThumbHeight = dp(82)
        val playheadColor = timelineColor(appearancePrefs.getString("timelineColor", "accent"), Palette.accent)
        val previewGroups = timelinePreviewGroups(cameraEvents.map { it.startTime!! },
            (timelineThumbHeight + dp(12)) * 60.0 / pxPerMinute)
        val previewCounts = previewGroups.associate { it.first() to it.size }
        val timelineHeight = topSpacer + bottomSpacer + (spanMinutes * pxPerMinute).toInt()
        scroll.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, timelineHeight))

        fun yForTime(timeSec: Double): Int {
            return timelineY(anchorSec, timeSec, topSpacer, pxPerMinute)
        }

        column.addView(TimelineRulerView(this, anchorSec, topSpacer, pxPerMinute, zoomLevels[zoomIndex], ::formatTimelineTime),
            FrameLayout.LayoutParams(dp(110), timelineHeight - bottomSpacer))
        column.addView(View(this).apply {
            background = rounded(androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent, 48), 2)
        }, FrameLayout.LayoutParams(dp(2), timelineHeight - topSpacer, Gravity.TOP or Gravity.START).apply {
            leftMargin = railX - dp(1)
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
            cameraEvents.forEachIndexed { index, event ->
                val start = event.startTime ?: return@forEachIndexed
                val end = event.endTime ?: (start + 30.0)
                val eventTop = yForTime(end).coerceIn(topSpacer, timelineHeight - bottomSpacer)
                val eventBottom = yForTime(start)
                val eventHeight = (eventBottom - eventTop).coerceAtLeast(dp(3))
                column.addView(View(this).apply {
                    background = rounded(Palette.accent, 2)
                }, FrameLayout.LayoutParams(dp(4), eventHeight, Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX - dp(2)
                    topMargin = eventTop
                })
                val eventCenter = yForTime(start)
                column.addView(View(this).apply {
                    background = rounded(Palette.accent, 1)
                }, FrameLayout.LayoutParams(dp(10), dp(2), Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX - dp(5)
                    topMargin = eventCenter - dp(1)
                })
                val previewCount = previewCounts[index] ?: return@forEachIndexed
                column.addView(View(this).apply {
                    setBackgroundColor(Palette.tickMinor)
                }, FrameLayout.LayoutParams(dp(84), dp(1), Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX + dp(6)
                    topMargin = eventCenter
                })
                val icon = eventObjectIcon(event)
                val eventText = eventDisplayText(event)
                column.addView(vertical().apply {
                    gravity = Gravity.CENTER
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        setColorFilter(Palette.muted)
                    }, LinearLayout.LayoutParams(dp(16), dp(16)))
                    addView(label(eventText, 9, Palette.muted).apply {
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(3), 0, 0)
                    }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
                    setOnClickListener { renderEventDetail(event) }
                }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.TOP).apply {
                    leftMargin = railX + dp(8)
                    topMargin = eventCenter - dp(24)
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
                thumbFrame.addView(label(formatTimelineClock(start) + if (previewCount > 1) "  +${previewCount - 1}" else "", 10, Color.WHITE).apply {
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    background = rounded(0x99000000.toInt(), 3)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    rightMargin = dp(4)
                })
                thumbFrame.addView(label(eventText, 12, Palette.text).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setShadowLayer(5f, 0f, 1f, Palette.black)
                    setPadding(dp(8), 0, dp(8), dp(6))
                    gravity = Gravity.BOTTOM or Gravity.START
                }, frameMatch())
                thumbFrame.setOnClickListener {
                    if (previewCount > 1) renderCameraDetail(camera, timelineDayMillis, cameraEvents, start, true)
                    else renderEventDetail(event)
                }
                val thumbTop = eventCenter - timelineThumbHeight / 2
                column.addView(thumbFrame, FrameLayout.LayoutParams(timelineThumbWidth, timelineThumbHeight, Gravity.END or Gravity.TOP).apply {
                    rightMargin = dp(34)
                    topMargin = thumbTop
                })
            }
        }
        frame.addView(scroll, frameMatch())
        frame.addView(View(this).apply { setBackgroundColor(playheadColor) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP).apply {
            topMargin = topSpacer
        })
        val currentTime = label(if (selectedToday) getString(R.string.live) else formatTimelineClock(anchorSec), 14, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(playheadColor, 10)
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
                        prefs.edit().putInt("timelineZoomDensity", zoomLevels[zoomIndex - 1]).apply()
                        renderCameraDetail(camera, timelineDayMillis, cameraEvents, focusSec)
                    }
                }
            })
            addView(timelineFloatButton(R.drawable.ic_zoom_in).apply {
                setOnClickListener {
                    if (zoomIndex < zoomLevels.lastIndex) {
                        val focusSec = centerSecondForScroll(scroll.scrollY)
                        prefs.edit().putInt("timelineZoomDensity", zoomLevels[zoomIndex + 1]).apply()
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
        var syncingPlaybackScroll = false
        var returnLiveAnimator: ValueAnimator? = null
        fun resumeLive() {
            if (activeSession == null || activeApi == null || !frame.isAttachedToWindow) return
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
            ensureLiveAudio(camera)
        }
        fun returnToLive() {
            returnLiveAnimator?.cancel()
            pendingScrollPlayback?.let { mainHandler.removeCallbacks(it) }
            pendingScrollPlayback = null
            currentPlayer?.pause()
            timelinePlaybackGeneration++
            fun finishReturn() { if (selectedToday) resumeLive() else renderCameraDetail(camera) }
            if (reducedMotion) {
                syncingPlaybackScroll = true
                scroll.scrollTo(0, 0)
                syncingPlaybackScroll = false
                finishReturn()
                return
            }
            returnLiveAnimator = ValueAnimator.ofInt(scroll.scrollY, 0).apply {
                duration = timelineReturnDuration(scroll.scrollY, frame.height)
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    if (!frame.isAttachedToWindow) return@addUpdateListener
                    syncingPlaybackScroll = true
                    scroll.scrollTo(0, it.animatedValue as Int)
                    syncingPlaybackScroll = false
                    currentTime.text = formatTimelineClock(centerSecondForScroll(scroll.scrollY))
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        returnLiveAnimator = null
                        if (!cancelled) finishReturn()
                    }
                })
                start()
            }
        }
        returnLive.setOnClickListener { returnToLive() }
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) returnLiveAnimator?.cancel()
            false
        }
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                returnLiveAnimator?.cancel()
                pendingScrollPlayback?.let { mainHandler.removeCallbacks(it) }
                pendingScrollPlayback = null
            }
        })
        fun updatePlaybackClock(playbackSec: Double) {
            if (!timelineMode || !interaction.followsPlayback || frame.height <= 0) return
            if (selectedToday && playbackSec >= System.currentTimeMillis() / 1000.0 - 3.0) {
                syncingPlaybackScroll = true
                scroll.scrollTo(0, 0)
                syncingPlaybackScroll = false
                resumeLive()
                return
            }
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
            if (activeSession == null || activeApi == null || frame.height <= 0 || !frame.isAttachedToWindow) return
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
            currentPlayer?.pause()
            if (!timelineMode) {
                timelineMode = true
                stopMjpegStreams()
                homeAudioGeneration++
                releaseHomeAudioWebView()
                liveBadge.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                returnLive.visibility = View.VISIBLE
            }
            pendingScrollPlayback?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!timelineMode || !frame.isAttachedToWindow) return@Runnable
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

    private fun activityBriefingStyle(): ActivityBriefingStyle {
        val legacyVisible = if (appearancePrefs.contains("showActivityBriefing")) appearancePrefs.getBoolean("showActivityBriefing", true) else null
        val style = ActivityBriefingStyle.fromPreference(appearancePrefs.getString("activityBriefingStyle", null), legacyVisible)
        if (legacyVisible != null) appearancePrefs.edit().putString("activityBriefingStyle", style.preference)
            .remove("showActivityBriefing").apply()
        return style
    }

    private fun recentActivitySummary(): View? {
        if (activityBriefingStyle() == ActivityBriefingStyle.Hidden) return null
        val activeSession = session ?: return null
        val allowed = visibleCameras().map { it.name }.toSet()
        val now = System.currentTimeMillis() / 1000.0
        val recent = events.filter { it.camera in allowed && it.startTime?.let { time -> time in (now - 86400)..now } == true }
        val key = "activityAcknowledged:${activeSession.baseUrl}:${activeSession.username}"
        val acknowledged = prefs.getStringSet(key, emptySet()).orEmpty()
        val unread = unreadActivities(recent.map { ActivityItem(it.id, it.camera, it.startTime!!,
            EventFacts(it.objectLabel, it.subLabel, it.description)) }, acknowledged)
        val groups = groupActivities(unread)
        val summary = if (groups.isEmpty()) getString(R.string.activity_up_to_date) else groups.take(3).joinToString(" · ") { group ->
            val event = recent.first { it.id == group.latest.id }
            val camera = cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera.replace('_', ' ')
            getString(R.string.activity_compact_item, eventDisplayText(event), camera, group.count)
        }
        return FrameLayout(this).apply {
            val entry = this
            tag = "activity-briefing-entry"
            isFocusable = true
            isClickable = true
            contentDescription = getString(R.string.recent_activity) + ", " + resources.getQuantityString(R.plurals.activity_detections, unread.size, unread.size)
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
            val style = activityBriefingStyle()
            if (style == ActivityBriefingStyle.Banner) {
                minimumHeight = dp(48)
                addView(horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_activity_wave); setColorFilter(Palette.accent)
                    }, LinearLayout.LayoutParams(dp(22), dp(22)))
                    addView(label(getString(R.string.recent_activity) + " · " +
                        resources.getQuantityString(R.plurals.activity_detections, unread.size, unread.size), 12, Palette.text).apply {
                        maxLines = 2
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
                    addView(label("›", 22, Palette.muted), LinearLayout.LayoutParams(dp(20), -2))
                }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            } else addView(FrameLayout(context).apply {
                if (style == ActivityBriefingStyle.Floating) {
                    background = rounded(Palette.card, 28, Palette.border, 1)
                    elevation = dp(6).toFloat()
                }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_activity_wave); setColorFilter(Palette.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }, FrameLayout.LayoutParams(dp(if (style == ActivityBriefingStyle.Floating) 56 else 44), dp(if (style == ActivityBriefingStyle.Floating) 56 else 44), Gravity.CENTER))
            if (unread.isNotEmpty() && style != ActivityBriefingStyle.Banner) addView(label(if (unread.size > 99) "99+" else unread.size.toString(), 10, Color.WHITE).apply {
                tag = "activity-briefing-badge"
                elevation = dp(8).toFloat()
                gravity = Gravity.CENTER
                background = rounded(Palette.accent, 10)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(if (unread.size > 99) 28 else 22), dp(20), Gravity.TOP or Gravity.END))
            setOnClickListener {
                if (appLock.locked) return@setOnClickListener
                activityBriefingSheet?.dismiss()
                val rows = vertical()
                val byId = recent.associateBy { it.id }
                groups.forEach { group ->
                    val event = byId.getValue(group.latest.id)
                    rows.addView(horizontal().apply {
                        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(80)
                        setPadding(0, dp(10), 0, dp(10)); isFocusable = true
                        tag = "activity-group:${event.id}"
                        addView(ImageView(context).apply {
                            setImageResource(eventObjectIcon(event)); setColorFilter(Palette.muted)
                        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
                        addView(vertical().apply {
                            val camera = cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera.replace('_', ' ')
                            addView(label(getString(R.string.activity_compact_item, eventDisplayText(event), camera, group.count), 14, Palette.text))
                            addView(label(eventSentence(event), 12, Palette.muted).apply { setPadding(0, dp(4), dp(8), 0) })
                            event.description?.takeIf { it.isNotBlank() }?.let {
                                addView(label(it, 12, Palette.muted).apply { setPadding(0, dp(4), dp(8), 0) })
                            }
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        val thumbnail = ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            background = rounded(Palette.surface, 6); applyRoundedClip(6)
                        }
                        addView(thumbnail, LinearLayout.LayoutParams(dp(88), dp(52)))
                        loadEventPreview(event, thumbnail)
                        setOnClickListener { activityBriefingSheet?.dismiss(); renderEventDetail(event) }
                    }, LinearLayout.LayoutParams(-1, -2))
                    rows.addView(View(context).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
                }
                val speech = listOf(getString(R.string.recent_activity), summary) + groups.map {
                    val event = byId.getValue(it.latest.id)
                    eventSentence(event) + event.description?.takeIf { description -> description.isNotBlank() }?.let { description -> " $description" }.orEmpty()
                }
                activityBriefingSheet = ActivityBriefingSheet(this@MainActivity, summary, rows, speech,
                    acknowledge = {
                        val current = prefs.getStringSet(key, emptySet()).orEmpty()
                        prefs.edit().putStringSet(key, (unread.map { it.id } + current).take(1000).toSet()).apply()
                        val currentEntry = window.decorView.findViewWithTag<View>("activity-briefing-entry") ?: entry
                        (currentEntry.parent as? ViewGroup)?.let { parent ->
                            val position = parent.indexOfChild(currentEntry)
                            val params = currentEntry.layoutParams
                            parent.removeView(currentEntry)
                            recentActivitySummary()?.let { parent.addView(it, position, params) }
                        }
                    }, review = { changeEventSearch(EventSearch()) }).also { it.show() }
            }
        }
    }

    private fun showTimelineDatePicker(camera: NativeCamera, timelineDayMillis: Long) {
        if (appLock.locked) return
        dismissTransientPopups()
        timelineDateSheet?.dismiss()
        val sheet = TimelineDateSheet(this, timelineDayMillis) { selected ->
            if (!appLock.locked) loadCameraTimelineDay(camera, selected)
        }
        timelineDateSheet = sheet
        sheet.setOnDismissListener { if (timelineDateSheet === sheet) timelineDateSheet = null }
        sheet.show()
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
            .chunked(if (TelevisionMode.enabled(this)) 1 else 2).forEach { camerasInRow ->
                val gridRow = horizontal().apply { setPadding(0, dp(12), 0, 0) }
                camerasInRow.forEachIndexed { index, camera ->
                val row = vertical().apply {
                    tag = "event-related-camera:${camera.name}"
                    setOnClickListener { renderEventDetail(event, camera.name) }
                    contentDescription = "${camera.friendlyName}, ${event.time}"
                }
                val image = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Palette.surface)
                    applyRoundedClip(6)
                }
                val preview = object : FrameLayout(this) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val width = View.MeasureSpec.getSize(widthMeasureSpec)
                        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY))
                    }
                }
                preview.addView(image, frameMatch())
                if (!TelevisionMode.enabled(this)) preview.addView(discreetFullscreenButton {
                    openEventCameraFullscreen(event, camera)
                }.apply {
                    contentDescription = "${getString(R.string.camera_fullscreen)} : ${camera.friendlyName}"
                }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.BOTTOM))
                row.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                    setPadding(0, dp(6), 0, 0)
                    addView(label(camera.friendlyName, 14, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
                    addView(label(event.time, 12, Palette.muted))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                gridRow.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) marginEnd = dp(10)
                })
                }
                if (camerasInRow.size == 1 && !TelevisionMode.enabled(this)) gridRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                grid.addView(gridRow)
            }
        return grid
    }

    private fun setShell(content: View, activeTab: Tab, showHeader: Boolean = true, animateIn: Boolean = false) {
        this.activeTab = activeTab
        settingsBackCallback?.isEnabled = activeTab == Tab.Settings && settingsPage != "main"
        releaseLoadingAnimations()
        dismissTransientPopups()
        val root = vertical().apply { setBackgroundColor(Palette.background) }
        if (showHeader) root.addView(appHeader())
        val rail = tabletLayout().navigationRail
        val navigation = bottomNav(activeTab, rail)
        if (rail) root.addView(horizontal().apply {
            tag = "tablet-landscape-shell"
            addView(navigation, LinearLayout.LayoutParams(dp(if (TelevisionMode.enabled(this@MainActivity)) 104 else 88), -1))
            addView(View(context).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(dp(1), -1))
            addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        }, LinearLayout.LayoutParams(-1, 0, 1f)) else {
            root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(navigation, LinearLayout.LayoutParams(-1, dp(72)))
        }
        keepAboveSystemNavigation(root, if (content is NativeChatView) navigation else null)
        setContentView(root)
        if (TelevisionMode.enabled(this)) root.post {
            navigation.findViewWithTag<View>("navigation-${activeTab.name}")?.requestFocus()
        }
        reflowShell = {
            (content.parent as? ViewGroup)?.removeView(content)
            setShell(content, activeTab, showHeader)
        }
        ViewCompat.requestApplyInsets(root)
        if (animateIn) {
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(if (reducedMotion) 0L else 220L).start()
        }
    }

    private fun appHeader(): View {
        if (tabletLayout().tablet) return FrameLayout(this).apply {
            tag = "tablet-header"
            setBackgroundColor(Palette.background)
            addView(brandWordmark(160), FrameLayout.LayoutParams(dp(160), dp(50), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })
            addView(label(java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date()), 12, Palette.muted).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
            }, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(196); marginEnd = dp(126) })
            if (showHeaderAlerts()) addView(alertsButton().apply { tag = "header-alerts"; if (session == null) visibility = View.INVISIBLE }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(14) })
            if (showHeaderChat()) addView(chatButton().apply { tag = "header-chat"; if (session == null) visibility = View.INVISIBLE }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(if (showHeaderAlerts()) 64 else 14) })
        }.also { it.layoutParams = LinearLayout.LayoutParams(-1, dp(64)) }
        return FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
            addView(horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(brandWordmark(230).apply {
                    scaleX = 0.65f
                    scaleY = 0.65f
                    pivotX = 0f
                    pivotY = dp(35).toFloat()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70), Gravity.START or Gravity.TOP).apply {
                leftMargin = dp(10)
                rightMargin = dp(118)
                topMargin = dp(26)
            })
            if (showHeaderAlerts()) addView(alertsButton().apply { tag = "header-alerts"; if (session == null) visibility = View.INVISIBLE }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.TOP).apply {
                rightMargin = dp(14)
                topMargin = dp(38)
            })
            if (showHeaderChat()) addView(chatButton().apply { tag = "header-chat"; if (session == null) visibility = View.INVISIBLE }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.END or Gravity.TOP).apply {
                rightMargin = dp(if (showHeaderAlerts()) 64 else 14)
                topMargin = dp(38)
            })
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)) }
    }

    private fun brandWordmark(widthDp: Int): View = BrandWordmarkView(this, widthDp)

    private fun alertsButton(): View {
        val alertCount = alertEvents().size
        return FrameLayout(this).apply {
            isClickable = true
            contentDescription = getString(R.string.alerts)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_bell_header)
                setColorFilter(Palette.text)
                alpha = 0.94f
            }, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
            if (alertCount > 0) {
                addView(label(alertCount.coerceAtMost(99).toString(), 9, Palette.text).apply {
                    tag = "alerts-badge"
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = rounded(Palette.accent, 9)
                }, FrameLayout.LayoutParams(dp(if (alertCount > 9) 20 else 16), dp(16), Gravity.END or Gravity.TOP).apply {
                    topMargin = dp(2)
                    rightMargin = dp(2)
                })
            }
            setOnClickListener { anchor ->
                val briefing = if (activityBriefingStyle() == ActivityBriefingStyle.Bell) recentActivitySummary() else null
                if (briefing == null) showAlertsPopup(anchor)
                else showHomeViewMenu(anchor, listOf(
                    homeMenuRow(R.drawable.ic_bell_header, getString(R.string.notifications), false) { showAlertsPopup(anchor) },
                    homeMenuRow(R.drawable.ic_activity_wave, getString(R.string.recent_activity), false) { briefing.performClick() }
                ))
            }
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
                addView(vertical().apply {
                    addView(label(getString(R.string.cameras), 25, Palette.text).apply {
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    })
                    val selected = homeSourceFilter().selected
                    if (selected != null) homeSourceOptions().firstOrNull { it.value == selected }?.let {
                        addView(label(it.title, 11, Palette.accent).apply {
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        })
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!message.isNullOrBlank() && resources.configuration.screenWidthDp >= 700) {
                addView(label(message, 12, Palette.muted).apply {
                    setPadding(0, 0, dp(12), 0)
                })
            }
            val layout = homeCameraLayout()
            if (TelevisionMode.enabled(this@MainActivity)) {
                addView(toolbarIcon(R.drawable.ic_refresh, tint = Palette.muted) { loadDashboard() }.apply {
                    contentDescription = getString(R.string.reload)
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            if (activityBriefingStyle() == ActivityBriefingStyle.Icon) recentActivitySummary()?.let {
                addView(it, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            addView(toolbarIcon(R.drawable.ic_layout_selector, tint = Palette.muted) { anchor -> showCameraLayoutMenu(anchor) }.apply {
                val description = getString(R.string.camera_layout)
                contentDescription = description
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            val filtered = homeSourceFilter().selected != null
            addView(toolbarIcon(R.drawable.ic_server_filter, tint = if (filtered) Palette.accent else Palette.muted) {
                showHomeSourceFilter()
            }.apply {
                tag = "home-server-filter"
                isSelected = filtered
                contentDescription = getString(R.string.home_source_filter)
                androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
    }

    private fun homeLayoutPreference() = if (tabletLayout().tablet) "tabletCameraLayout" else "homeCameraLayout"
    private fun homeCameraLayout(): HomeCameraLayout = getSharedPreferences("camgate-settings", MODE_PRIVATE).let {
        effectiveCameraLayout(it.getString("homeCameraLayout", null), it.getString("tabletCameraLayout", null), tabletLayout().tablet)
    }

    private fun homeSourceKey(camera: DashboardCamera): String = when (camera.source) {
        CameraSource.Frigate -> "frigate:${camera.frigate?.let { (api as? MultiFrigateApi)?.serverFor(it.name)?.id } ?: "legacy"}"
        CameraSource.HomeAssistant -> "ha"
        CameraSource.Onvif -> "onvif"
    }

    private fun homeSourceOptions(): List<AppearanceSheetOption> {
        val servers = (api as? MultiFrigateApi)?.servers ?: runCatching { frigateServers.load() }.getOrDefault(emptyList())
        return servers.map { AppearanceSheetOption("frigate:${it.id}", "Frigate · ${it.name}") } +
            savedHomeAssistant()?.let {
                listOf(AppearanceSheetOption("ha", "Home Assistant · ${android.net.Uri.parse(it.baseUrl).host.orEmpty()}"))
            }.orEmpty() + if (savedOnvifCameras().isNotEmpty())
                listOf(AppearanceSheetOption("onvif", getString(R.string.home_source_direct))) else emptyList()
    }

    private fun homeSourceFilter() = HomeSourceFilter(
        getSharedPreferences("camgate-settings", MODE_PRIVATE).getString("homeCameraServer", null),
        homeSourceOptions().map { it.value }.toSet())

    private fun showHomeSourceFilter() {
        dismissTransientPopups()
        val options = listOf(AppearanceSheetOption("all", getString(R.string.home_source_all))) + homeSourceOptions()
        val sheet = AppearanceChoiceSheet(this, getString(R.string.home_source_filter), "home-server",
            homeSourceFilter().selected ?: "all", options) { value ->
            getSharedPreferences("camgate-settings", MODE_PRIVATE).edit().apply {
                if (value == "all") remove("homeCameraServer") else putString("homeCameraServer", value)
            }.apply()
            renderHome()
        }
        homeSourceSheet = sheet
        sheet.setOnDismissListener { if (homeSourceSheet === sheet) homeSourceSheet = null }
        sheet.show()
    }

    private fun showCameraLayoutMenu(anchor: View) {
        val selected = homeCameraLayout()
        showHomeViewMenu(anchor, HomeCameraLayout.values().map { layout ->
            homeMenuRow(layout.iconRes, getString(layout.titleRes), selected == layout) {
                getSharedPreferences("camgate-settings", MODE_PRIVATE).edit()
                    .putString(homeLayoutPreference(), layout.preference).apply()
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
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chat_header)
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
        val cookiePath = android.net.Uri.parse(activeSession.baseUrl).encodedPath.orEmpty().trimEnd('/').ifEmpty { "/" }
        cookieManager.setCookie(activeSession.baseUrl, "frigate_token=${activeSession.token}; Path=$cookiePath$secure")
        cookieManager.flush()
    }

    private fun logoHeader(): View = ImageView(this).apply {
        setImageResource(R.drawable.camnory_logo)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)) }

    private fun bottomNav(activeTab: Tab, rail: Boolean = false): View {
        val tablet = tabletLayout().tablet
        val row = (if (rail) vertical() else horizontal()).apply {
            tag = if (rail) "tablet-navigation-rail" else "bottom-navigation"
            gravity = if (rail) Gravity.TOP else Gravity.CENTER
            background = rounded(Palette.black, 0)
        }
        Tab.values().forEach { tab ->
            if (TelevisionMode.enabled(this) && tab == Tab.Cameras) return@forEach
            if (session == null && tab in listOf(Tab.Grid, Tab.Events)) return@forEach
            val item = FrameLayout(this).apply {
                tag = "navigation-${tab.name}"
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
                    Tab.Events -> if (TelevisionMode.enabled(this)) renderTvEvents() else events.firstOrNull()?.let { renderEventDetail(it) } ?: renderHome()
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
            if (tablet) item.addView(vertical().apply {
                gravity = Gravity.CENTER
                addView(iconHolder, LinearLayout.LayoutParams(dp(48), dp(38)))
                addView(label(if (tab == Tab.Grid) getString(R.string.notifications) else item.contentDescription.toString(), if (TelevisionMode.enabled(this@MainActivity)) 12 else 10, if (tab == activeTab) Palette.accent else Palette.muted).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(-1, -2))
            }, frameMatch()) else item.addView(iconHolder, FrameLayout.LayoutParams(dp(58), dp(54), Gravity.CENTER))
            row.addView(item, if (rail) LinearLayout.LayoutParams(-1, dp(if (TelevisionMode.enabled(this)) 82 else 76)) else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    private fun showCameraPickerPopup(anchor: View) {
        dismissTransientPopups()
        val choices = dashboardCameras().filter { dashboardCameraVisible(it) }
        if (choices.isEmpty()) {
            toast(getString(R.string.no_visible))
            return
        }
        val card = vertical().apply {
            setPadding(dp(20), 0, dp(20), dp(12))
        }
        val activeSession = session
        val activeApi = api
        choices.forEach { camera ->
            val row = horizontal().apply {
                tag = "camera-picker-row:${camera.key}"
                isFocusable = true
                contentDescription = camera.name
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = rounded(Color.TRANSPARENT, 8)
                setOnClickListener {
                    dismissCameraPicker()
                    when {
                        camera.frigate != null -> renderCameraDetail(camera.frigate)
                        camera.onvif != null -> renderExpandedLive(camera)
                        else -> openHomeAssistantCamera(requireNotNull(camera.haConnection), requireNotNull(camera.haCamera))
                    }
                }
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Palette.surface)
                tag = "camera-picker-image"
                visibility = View.GONE
            }
            val placeholder = ImageView(this).apply { setImageResource(R.drawable.ic_image); setColorFilter(Palette.muted) }
            row.addView(FrameLayout(this).apply {
                tag = "camera-picker-thumbnail"
                background = rounded(Palette.surface, 7)
                applyRoundedClip(7)
                addView(placeholder, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
                addView(thumb, frameMatch())
            }, LinearLayout.LayoutParams(dp(72), dp(44)).apply {
                rightMargin = dp(12)
            })
            row.addView(vertical().apply {
                addView(horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(camera.source.iconView(context), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
                    addView(label(camera.name, 15, Palette.text).apply {
                        tag = "camera-picker-name"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        contentDescription = camera.name
                        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, camera.name)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
                addView(label(getString(if (camera.source == CameraSource.HomeAssistant) R.string.camera_fullscreen else R.string.open_live), 12, Palette.muted).apply {
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_videocam)
                setColorFilter(Palette.accent)
                alpha = 0.95f
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) })
            row.minimumHeight = dp(64)
            row.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // Tooltips make child labels long-clickable and can swallow taps meant for the row.
            fun mergeTapTarget(view: View) {
                androidx.appcompat.widget.TooltipCompat.setTooltipText(view, null)
                view.isClickable = false; view.isLongClickable = false; view.isFocusable = false
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                if (view is ViewGroup) repeat(view.childCount) { mergeTapTarget(view.getChildAt(it)) }
            }
            repeat(row.childCount) { mergeTapTarget(row.getChildAt(it)) }
            row.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent, 35)),
                rounded(Color.TRANSPARENT, 8), rounded(Color.WHITE, 8))
            card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            })
            fun showThumbnail(bitmap: Bitmap?) {
                if (bitmap != null && !bitmap.isRecycled) {
                    thumb.setImageBitmap(bitmap); thumb.visibility = View.VISIBLE; placeholder.visibility = View.GONE
                }
            }
            val cached = when {
                camera.haCamera != null -> homeAssistantCards.firstOrNull { it.camera.entityId == camera.haCamera.entityId }?.cachedPreview()
                camera.onvif != null -> runCatching { onvifCards.firstOrNull { it.cameraId() == camera.onvif.id }?.cachedPreview() }.getOrNull()
                else -> null
            }
            showThumbnail(cached)
            if (camera.frigate != null && activeSession != null && activeApi != null) {
                activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.frigate.name)) { bitmap ->
                    runOnUiThread { if (thumb.isAttachedToWindow) showThumbnail(bitmap) }
                }
            }
        }
        val sheet = SelectionBottomSheet(this, getString(R.string.pick_camera), card).apply {
            setOnDismissListener { if (cameraPickerSheet === this) cameraPickerSheet = null }
        }
        cameraPickerSheet = sheet
        sheet.show()
    }

    private fun dismissCameraPicker() {
        cameraPickerSheet?.dismiss()
        cameraPickerSheet = null
        cameraPickerPopup?.dismiss()
        cameraPickerPopup = null
    }

    private fun dismissAlertsPopup() {
        alertsPopup?.dismiss()
        alertsPopup = null
    }

    private fun dismissTransientPopups() {
        appearanceResetSheet?.dismiss(); appearanceResetSheet = null
        timelineDateSheet?.dismiss(); timelineDateSheet = null
        cameraButtonsSheet?.dismiss(); cameraButtonsSheet = null
        accessorySheet?.dismiss(); accessorySheet = null
        chatDialog?.dismiss(); chatDialog = null
        chatServerSheet?.dismiss(); chatServerSheet = null
        eventFilterSheet?.dismiss(); eventFilterSheet = null
        homeSourceSheet?.dismiss(); homeSourceSheet = null
        appearanceChoiceSheet?.dismiss()
        appearanceChoiceSheet = null
        dismissCameraPicker()
        dismissAlertsPopup()
    }

    private fun alertEvents(): List<NativeEvent> {
        val visibleNames = visibleCameras().map { it.name }.toSet()
        val seenIds = seenAlertIds()
        val nowSec = System.currentTimeMillis() / 1000.0
        return events
            .asSequence()
            .filter { it.camera in visibleNames }
            .filter { it.id !in seenIds }
            .filter { event ->
                val started = event.startTime ?: return@filter true
                started > seenAlertsCutoffMillis(event) / 1000.0
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
        val recent = recentNotifications(events, visibleCameras().map { it.name }.toSet(), System.currentTimeMillis() / 1000.0)
        val seen = seenAlertIds()
        val unread = recent.filter { it.id !in seen && (it.startTime == null || it.startTime > seenAlertsCutoffMillis(it) / 1000.0) }.map { it.id }.toSet()
        val sheet = NotificationsSheet(this, recent, unread,
            title = { event ->
                if (!event.subLabel.isNullOrBlank()) eventDisplayText(event)
                else getString(when {
                    notificationMatchesKind(event, EventKind.Person) -> R.string.notifications_person_detected
                    notificationMatchesKind(event, EventKind.Vehicle) -> R.string.notifications_vehicle_detected
                    notificationMatchesKind(event, EventKind.Animal) -> R.string.notifications_animal_detected
                    else -> R.string.notifications_other_detected
                })
            },
            cameraName = { event -> cameras.firstOrNull { it.name == event.camera }?.friendlyName ?: event.camera.replace('_', ' ') },
            narrative = ::eventSentence,
            thumbnail = { event, image ->
                val cached = event.thumbnail
                if (cached != null) image.setImageBitmap(cached)
                else {
                    val activeApi = api
                    val activeSession = session
                    if (activeApi != null && activeSession != null) activeApi.loadBitmap(activeSession.token, activeApi.eventSnapshotUrl(event.id)) { bitmap ->
                        runOnUiThread { if (image.isAttachedToWindow && bitmap != null) image.setImageBitmap(bitmap) }
                    }
                }
            },
            onReadAll = { items -> markAlertsSeen(items, advanceCutoff = true); refreshAlertsBadge() },
            onOpen = { event -> markAlertSeen(event); renderEventDetail(event) },
            onHistory = { eventSearch = EventSearch(); renderGlobalEvents() },
            onSettings = { renderNotificationSettings() }
        ).apply {
            setOnDismissListener { if (alertsPopup === this) alertsPopup = null }
        }
        alertsPopup = sheet
        sheet.show()
    }

    private fun refreshAlertsBadge() {
        val badge = findViewById<ViewGroup>(android.R.id.content).findViewWithTag<TextView>("alerts-badge") ?: return
        val count = alertEvents().size
        badge.text = count.coerceAtMost(99).toString()
        badge.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun markAlertSeen(event: NativeEvent) {
        markAlertsSeen(listOf(event), advanceCutoff = false)
    }

    private fun markAlertsSeen(items: List<NativeEvent>, advanceCutoff: Boolean) {
        val editor = prefs.edit()
        items.groupBy { eventServerUrl(it) }.forEach { (url, group) ->
            val ids = prefs.getStringSet("seenAlerts:$url", emptySet()).orEmpty() + group.map { it.id }
            editor.putStringSet("seenAlerts:$url", ids.toList().takeLast(1000).toSet())
            seenAlertIdsMemory.addAll(group.map { it.id })
            if (advanceCutoff) {
                val newest = group.mapNotNull { it.startTime }.maxOrNull()?.times(1000)?.toLong() ?: 0L
                editor.putLong("seenAlertsCutoff:$url", maxOf(prefs.getLong("seenAlertsCutoff:$url", 0), newest))
            }
        }
        editor.commit()
    }

    private fun showCameraOptionsPopup(camera: NativeCamera, anchor: View) {
        dismissTransientPopups()
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        val card = vertical().apply {
            tag = "camera-options-content"
            setPadding(dp(16), 0, dp(16), dp(20))
        }
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
            if (control.feature in setOf("detect", "recordings", "audio", "notifications")) {
                card.addView(View(this).apply { setBackgroundColor(Palette.border) },
                    LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            }
            card.addView(cameraFeatureToggleRow(activeApi, activeSession.token, camera, control))
        }
        card.addView(disabledCameraOptionRow(getString(R.string.microphone), getString(R.string.api_unavailable)))
        dashboardCameras().firstOrNull { it.frigate?.name == camera.name }?.let { item ->
            card.addView(CameraControlsView(this, cameraControlTarget(item), respectAppearance = activeTab != Tab.Settings) {
                !appLock.locked && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            })
        }

        val sheet = SelectionBottomSheet(this, camera.friendlyName, card)
        cameraPickerSheet = sheet
        sheet.setOnDismissListener { if (cameraPickerSheet === sheet) cameraPickerSheet = null }
        sheet.show()
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
        switchView.applyAccentTint()
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
        val fileName = "Camnory_${camera.name}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Camnory")
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
        val column = vertical().apply { setPadding(dp(12), dp(8), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
        column.addView(pageTitle(getString(R.string.settings)))
        val cameraSettingsItems = dashboardCameras()
        column.addView(sectionHeading(getString(R.string.settings_installation)).apply { tag = "settings-section-installation" })
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.settings_connections), getString(R.string.settings_connections_detail), { renderConnectionsSettings() }, icon = R.drawable.ic_settings_link, key = "connections"),
            settingsNavRow(getString(R.string.cameras), getString(R.string.camera_count, cameraSettingsItems.count { dashboardCameraVisible(it) }, cameraSettingsItems.size), { renderCameraSettings() }, icon = R.drawable.ic_videocam, key = "cameras")
        ))
        column.addView(sectionHeading(getString(R.string.settings_preferences)).apply { tag = "settings-section-preferences" })
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.notifications), getString(R.string.settings_notifications_detail), { renderNotificationSettings() }, icon = R.drawable.ic_bell_header, key = "notifications"),
            settingsNavRow(getString(R.string.appearance), getString(R.string.appearance_detail), { renderAppearanceSettings() }, icon = R.drawable.ic_settings_palette, key = "appearance"),
            settingsNavRow(getString(R.string.language), currentLanguageLabel(), { renderLanguageSettings() }, icon = R.drawable.ic_settings_globe, key = "language")
        ))
        column.addView(sectionHeading(getString(R.string.application)).apply { tag = "settings-section-application" })
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.security), getString(R.string.biometric_lock), { renderSecuritySettings() }, icon = R.drawable.ic_settings_lock, key = "security"),
            settingsNavRow(getString(R.string.updates), "Camnory ${appVersionName()}", { renderUpdatesSettings() }, icon = R.drawable.ic_refresh, key = "updates"),
            settingsNavRow(getString(R.string.about), getString(R.string.settings_about_detail), { renderAboutSettings() }, icon = R.drawable.ic_info, key = "about")
        ))
        val restoreY = settingsScrollY
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> settingsScrollY = y }
        setShell(scroll, Tab.Settings)
        scroll.doOnLayout { scroll.scrollTo(0, restoreY) }
    }

    private fun renderRestoredSettingsOrHome(message: String? = null) {
        val page = restoredSettingsPage
        restoredSettingsPage = null
        if (page == null) renderHome(message) else renderSettingsDestination(page)
    }

    private fun renderSettingsDestination(page: String) {
        when (page) {
            "connections" -> renderConnectionsSettings()
            "security" -> renderSecuritySettings()
            "updates" -> renderUpdatesSettings()
            "language" -> renderLanguageSettings()
            "appearance" -> renderAppearanceSettings()
            "notifications" -> renderNotificationSettings()
            "server" -> renderServerSettings()
            "cameras" -> renderCameraSettings()
            "about" -> renderAboutSettings()
            "licenses" -> renderLicensesSettings()
            else -> renderSettings()
        }
    }

    private fun navigateSettingsBack() {
        when (settingsPage) {
            "server" -> renderConnectionsSettings()
            "licenses" -> renderAboutSettings()
            else -> renderSettings()
        }
    }

    private fun newSettingsPage(page: String, title: String): Pair<ScrollView, LinearLayout> {
        settingsPage = page
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
        column.addView(settingsPageHeader(title))
        return scroll to column
    }

    private fun renderConnectionsSettings() {
        val (scroll, column) = newSettingsPage("connections", getString(R.string.settings_connections))
        column.addView(sectionHeading(getString(R.string.settings_sources)))
        column.addView(settingsGroup(
            settingsNavRow("Frigate", getString(R.string.frigate_servers_detail), { renderServerSettings() }, icon = R.drawable.ic_source_frigate, key = "frigate"),
            settingsNavRow(getString(R.string.onvif_cameras), getString(R.string.settings_onvif_detail), { openOnvifManager() }, icon = R.drawable.ic_source_onvif, key = "onvif"),
            settingsNavRow(getString(R.string.ha_cameras), getString(R.string.settings_ha_detail), { openHomeAssistantManager() }, icon = R.drawable.ic_source_home_assistant, key = "ha")
        ))
        column.addView(sectionHeading(getString(R.string.actions)))
        column.addView(settingsGroup(settingsNavRow(getString(if (TelevisionMode.enabled(this)) R.string.tv_pair else R.string.tv_share_title), getString(R.string.tv_transfer_detail), {
            if (!appLock.locked) startActivity(if (TelevisionMode.enabled(this)) Intent(this, TvActivity::class.java).setAction("com.nvr.camnory.TV_PAIR") else Intent(this, TvTransferActivity::class.java))
        }, icon = R.drawable.ic_settings_link, key = "tv-transfer")))
        column.addView(settingsGroup(settingsNavRow(getString(R.string.reload), getString(R.string.reload_detail), {
            restoredSettingsPage = "connections"
            loadDashboard(getString(R.string.reloaded))
        }, showChevron = false, icon = R.drawable.ic_refresh)))
        setShell(scroll, Tab.Settings)
    }

    private fun renderSecuritySettings() {
        val (scroll, column) = newSettingsPage("security", getString(R.string.security))
        column.addView(sectionHeading(getString(R.string.security)))
        val biometricSwitch = SwitchCompat(this).apply {
            isChecked = appLock.enabled
            contentDescription = getString(R.string.biometric_lock)
            setOnCheckedChangeListener { button, checked ->
                button.isEnabled = false
                appLock.changeEnabled(checked) { actual ->
                    button.isEnabled = true
                    if (actual != checked) renderSecuritySettings()
                }
            }
        }
        column.addView(preferenceToggleRow(getString(R.string.biometric_lock),
            getString(if (appLock.available() || appLock.enabled) R.string.biometric_detail else R.string.biometric_unavailable), biometricSwitch))
        setShell(scroll, Tab.Settings)
    }

    private fun renderUpdatesSettings() {
        val (scroll, column) = newSettingsPage("updates", getString(R.string.updates))
        column.addView(settingsGroup(settingsRow(getString(R.string.app_version), appVersionName())))
        column.addView(settingsUpdateCard())
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
        activityBriefingStyle()
        settingsPage = "appearance"
        releasePlayer()
        val scroll = appScrollView().apply {
            tag = "appearance-scroll"
            setBackgroundColor(Palette.background)
        }
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
        column.addView(settingsPageHeader(getString(R.string.appearance)))
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> appearanceScrollY = y }
        val previewEvent = events.firstOrNull { event -> cameras.any { it.name == event.camera && it.friendlyName.equals("Devant", true) } }
            ?: events.firstOrNull()
        val previewKey = "${session?.baseUrl}:${previewEvent?.id}"
        if (appearancePreviewKey != previewKey) {
            appearancePreviewKey = previewKey
            appearancePreviewBitmap = previewEvent?.thumbnail
        }
        val appearancePreview = AppearancePreviewView(this, appearancePreviewBitmap).apply {
            tag = "appearance-preview"
        }
        column.addView(appearancePreview, LinearLayout.LayoutParams(-1, dp(166)).apply { bottomMargin = dp(12) })
        if (appearancePreviewBitmap == null && previewEvent != null) {
            val previewApi = api
            val previewSession = session
            if (previewApi != null && previewSession != null) {
                previewApi.loadBitmap(previewSession.token, previewApi.eventSnapshotUrl(previewEvent.id)) { bitmap ->
                    runOnUiThread {
                        if (bitmap != null && appearancePreviewKey == previewKey) {
                            appearancePreviewBitmap = bitmap
                            appearancePreview.showThumbnail(bitmap)
                        }
                    }
                }
            }
        }

        fun refreshAppearance() {
            appearanceScrollY = scroll.scrollY
            delegate.localNightMode = when (appearancePrefs.getString("theme", "system")) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            Appearance.loadPalette(appearancePrefs, resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            window.statusBarColor = Palette.background
            window.navigationBarColor = Palette.black
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !Palette.dark
                isAppearanceLightNavigationBars = !Palette.dark
            }
            renderAppearanceSettings()
        }
        fun choice(title: Int, key: String, fallback: String, options: List<Pair<String, Int>>): View {
            val selected = appearancePrefs.getString(key, fallback)
            val selectedTitle = options.firstOrNull { it.first == selected }?.second ?: options.first().second
            return settingsNavRow(getString(title), getString(selectedTitle), {
                AppearanceChoiceSheet(this, getString(title), key, selected.orEmpty(),
                    options.map { (value, textRes) ->
                        AppearanceSheetOption(value, getString(textRes),
                            if (key == "backgroundStyle") BackgroundStyle.fromPreference(value).let { if (Palette.dark) it.background else it.lightBackground } else null,
                            if (key == "textSize") Appearance.textScale(value) else 1f)
                    }) { value ->
                        if (appearancePrefs.getString(key, fallback) != value) {
                            appearancePrefs.edit().putString(key, value).apply()
                            refreshAppearance()
                        }
                    }.also { sheet ->
                        appearanceChoiceSheet?.dismiss()
                        appearanceChoiceSheet = sheet
                        sheet.setOnDismissListener { if (appearanceChoiceSheet === sheet) appearanceChoiceSheet = null }
                    }.show()
            }, icon = when (key) {
                "theme" -> R.drawable.ic_settings_palette
                "backgroundStyle" -> R.drawable.ic_image
                "textSize" -> R.drawable.ic_text_size
                "activityBriefingStyle" -> R.drawable.ic_activity_wave
                "homeCameraLayout", "tabletCameraLayout" -> R.drawable.ic_layout_selector
                else -> R.drawable.ic_settings
            }).apply { tag = "appearance-$key" }
        }
        column.addView(sectionHeading(getString(R.string.appearance_ambience)))
        column.addView(settingsGroup(
            choice(R.string.display_mode, "theme", "system", listOf("system" to R.string.system_language, "dark" to R.string.theme_dark, "light" to R.string.theme_light)),
            choice(R.string.background_palette, "backgroundStyle", "automatic", BackgroundStyle.values().filter { it != BackgroundStyle.Oled }.map {
                it.preference to when (it) {
                    BackgroundStyle.Automatic -> R.string.background_neutral
                    BackgroundStyle.Midnight -> R.string.background_blue
                    BackgroundStyle.Forest -> R.string.background_green
                    else -> it.titleRes
                }
            })
        ))
        column.addView(preferenceToggleRow(getString(R.string.background_oled), getString(R.string.oled_dark_only), SwitchCompat(this).apply {
            tag = "appearance-oled-black"
            contentDescription = getString(R.string.background_oled)
            isChecked = appearancePrefs.getBoolean("oledBlack", false)
            isEnabled = Palette.dark
            setOnCheckedChangeListener { _, checked ->
                appearancePrefs.edit().putBoolean("oledBlack", checked).apply()
                refreshAppearance()
            }
        }))
        column.addView(settingsDetailLabel(getString(R.string.accent_color)))
        column.addView(horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            val selected = appearancePrefs.getString("accent", "red")
            listOf("red" to R.string.accent_red, "blue" to R.string.accent_blue, "green" to R.string.accent_green, "violet" to R.string.accent_violet, "ochre" to R.string.accent_ochre).forEach { (value, title) ->
                addView(FrameLayout(context).apply {
                    tag = "appearance-accent-$value"
                    contentDescription = getString(title)
                    isSelected = selected == value
                    isFocusable = true
                    setOnClickListener {
                        appearancePrefs.edit().putString("accent", value).apply()
                        refreshAppearance()
                    }
                    addView(View(context).apply {
                        background = rounded(Appearance.accent(value), 15, if (selected == value) Palette.text else Color.TRANSPARENT, if (selected == value) 2 else 0)
                    }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, dp(52), 1f))
            }
        })
        column.addView(sectionHeading(getString(R.string.appearance_readability)))
        column.addView(settingsGroup(choice(R.string.text_size, "textSize", "standard",
            listOf("small" to R.string.size_small, "standard" to R.string.size_standard, "large" to R.string.size_large))))
        column.addView(preferenceToggleRow(getString(R.string.reduce_motion), getString(R.string.reduce_motion_detail), SwitchCompat(this).apply {
            tag = "appearance-reduced-motion"
            isChecked = reducedMotion
            contentDescription = getString(R.string.reduce_motion)
            setOnCheckedChangeListener { _, checked -> appearancePrefs.edit().putBoolean("reducedMotion", checked).apply() }
        }))
        column.addView(sectionHeading(getString(R.string.appearance_details)))
        listOf(Triple("showHeaderChat", R.string.appearance_header_chat, showHeaderChat()),
            Triple("showHeaderAlerts", R.string.appearance_header_alerts, showHeaderAlerts())).forEach { (key, title, enabled) ->
            column.addView(preferenceToggleRow(getString(title), "", SwitchCompat(this).apply {
                tag = "appearance-$key"
                contentDescription = getString(title)
                isChecked = enabled
                setOnCheckedChangeListener { _, checked ->
                    appearancePrefs.edit().putBoolean(key, checked).apply()
                    reflowShell?.invoke()
                }
            }))
        }
        column.addView(preferenceToggleRow(getString(R.string.home_recent_events), getString(R.string.home_recent_events_detail), SwitchCompat(this).apply {
            tag = "appearance-home-events"
            contentDescription = getString(R.string.home_recent_events)
            isChecked = showHomeEvents()
            setOnCheckedChangeListener { _, checked ->
                appearancePrefs.edit().putBoolean("showHomeEvents", checked).apply()
            }
        }))
        column.addView(preferenceToggleRow(getString(R.string.home_camera_toolbar), "", SwitchCompat(this).apply {
            tag = "appearance-home-camera-toolbar"
            contentDescription = getString(R.string.home_camera_toolbar)
            isChecked = showHomeToolbar()
            setOnCheckedChangeListener { _, checked ->
                appearancePrefs.edit().putBoolean("showHomeCameraToolbar", checked).apply()
            }
        }))
        column.addView(settingsGroup(settingsNavRow(getString(R.string.camera_buttons_title),
            getString(R.string.playback_sound) + " · " + getString(R.string.camera_fullscreen), {
                dismissTransientPopups()
                val sheet = CameraButtonsSheet(this)
                cameraButtonsSheet = sheet
                sheet.setOnDismissListener { if (cameraButtonsSheet === sheet) cameraButtonsSheet = null }
                sheet.show()
            }, icon = R.drawable.ic_videocam).apply { tag = "appearance-camera-buttons" }))
        column.addView(settingsGroup(choice(R.string.activity_style, "activityBriefingStyle", "floating",
            ActivityBriefingStyle.values().map { it.preference to it.titleRes })))
        column.addView(settingsGroup(choice(R.string.camera_layout, homeLayoutPreference(), if (tabletLayout().tablet) "two_columns" else "column", HomeCameraLayout.values().map { it.preference to it.titleRes })))
        column.addView(preferenceToggleRow(getString(R.string.camera_source_icons), getString(R.string.camera_source_icons_detail), SwitchCompat(this).apply {
            tag = "show-camera-source-icons"
            contentDescription = getString(R.string.camera_source_icons)
            isChecked = cameraArrangement.showSourceIcons
            setOnCheckedChangeListener { _, checked ->
                appearancePrefs.edit().putBoolean("showCameraSourceIcons", checked).apply()
                column.findViewWithTag<AppearancePreviewView>("appearance-preview").invalidate()
            }
        }))
        column.addView(secondaryButton(getString(R.string.appearance_reset)).apply { setOnClickListener {
            dismissTransientPopups()
            val sheet = ConfirmationSheet(this@MainActivity, getString(R.string.appearance_reset),
                getString(R.string.appearance_reset_confirm)) {
                    val edit = appearancePrefs.edit()
                    CameraButton.values().forEach { edit.remove("cameraButton:${it.preference}") }
                    listOf("theme", "backgroundStyle", "oledBlack", "accent", "timelineColor", "textSize", "homeCameraLayout", "showCameraSourceIcons", "showActivityBriefing", "activityBriefingStyle", "reducedMotion", "showHomeEvents", "showHomeCameraToolbar", "showHeaderChat", "showHeaderAlerts").forEach { edit.remove(it) }
                    edit.apply()
                    refreshAppearance()
                }
            appearanceResetSheet = sheet
            sheet.setOnDismissListener { if (appearanceResetSheet === sheet) appearanceResetSheet = null }
            sheet.show()
        }; tag = "appearance-reset" })
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
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
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
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
        column.addView(settingsPageHeader(getString(R.string.notifications)))
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> notificationScrollY = y }
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
        fun option(title: Int, key: String, default: Boolean, detail: String = "", changed: (() -> Unit)? = null) {
            val toggle = SwitchCompat(this).apply {
                isChecked = appearancePrefs.getBoolean(key, default)
                contentDescription = getString(title)
                setOnCheckedChangeListener { _, checked ->
                    appearancePrefs.edit().putBoolean(key, checked).apply()
                    changed?.invoke()
                }
            }
            column.addView(preferenceToggleRow(getString(title), detail, toggle))
        }
        column.addView(sectionHeading(getString(R.string.alert_delivery)))
        option(R.string.alert_sound, "alertSound", true, getString(R.string.alert_android_priority)) { CameraNotifications.createChannel(this) }
        option(R.string.alert_vibration, "alertVibration", true, getString(R.string.alert_android_priority)) { CameraNotifications.createChannel(this) }
        column.addView(sectionHeading(getString(R.string.alert_types)))
        option(R.string.person, "alertType:person", true)
        option(R.string.animal, "alertType:animal", true)
        option(R.string.vehicle, "alertType:vehicle", true)
        option(R.string.alert_other, "alertType:other", true)
        column.addView(sectionHeading(getString(R.string.cameras)))
        option(R.string.alert_all_cameras, "alertAllCameras", true) { renderNotificationSettings() }
        if (!appearancePrefs.getBoolean("alertAllCameras", true)) {
            val selected = appearancePrefs.getStringSet("alertCameras", emptySet()).orEmpty()
            column.addView(settingsGroup(settingsNavRow(getString(R.string.alert_choose_cameras),
                "${selected.size} / ${visibleCameras().size}", {
                    val available = visibleCameras()
                    val chosen = selected.toMutableSet()
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(R.string.alert_choose_cameras)
                        .setMultiChoiceItems(available.map { it.friendlyName }.toTypedArray(),
                            available.map { it.name in chosen }.toBooleanArray()) { _, index, checked ->
                            if (checked) chosen.add(available[index].name) else chosen.remove(available[index].name)
                        }.setPositiveButton(android.R.string.ok) { _, _ ->
                            appearancePrefs.edit().putStringSet("alertCameras", chosen).apply()
                            renderNotificationSettings()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                })))
        }
        fun clockRow(title: Int, key: String, fallback: Int): View {
            val value = appearancePrefs.getInt(key, fallback)
            return settingsNavRow(getString(title), String.format(Locale.getDefault(), "%02d:%02d", value / 60, value % 60), {
                android.app.TimePickerDialog(this, { _, hour, minute ->
                    appearancePrefs.edit().putInt(key, hour * 60 + minute).apply()
                    renderNotificationSettings()
                }, value / 60, value % 60, true).show()
            })
        }
        column.addView(sectionHeading(getString(R.string.alert_schedule)))
        option(R.string.alert_active_hours, "alertActiveHours", false, getString(R.string.alert_active_detail))
        column.addView(settingsGroup(clockRow(R.string.alert_from, "alertActiveStart", 0), clockRow(R.string.alert_until, "alertActiveEnd", 0)))
        option(R.string.alert_quiet_hours, "alertQuietHours", false, getString(R.string.alert_quiet_detail))
        column.addView(settingsGroup(clockRow(R.string.alert_from, "alertQuietStart", 1320), clockRow(R.string.alert_until, "alertQuietEnd", 420)))
        val cooldowns = longArrayOf(0, 30, 60, 300, 900)
        fun cooldownLabel(seconds: Long) = if (seconds == 0L) getString(R.string.alert_no_delay) else getString(R.string.alert_seconds, seconds)
        column.addView(settingsGroup(settingsNavRow(getString(R.string.alert_cooldown),
            cooldownLabel(appearancePrefs.getLong("alertCooldown", 0)), {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.alert_cooldown)
                    .setSingleChoiceItems(cooldowns.map { cooldownLabel(it) }.toTypedArray(),
                        cooldowns.indexOf(appearancePrefs.getLong("alertCooldown", 0))) { dialog, index ->
                        appearancePrefs.edit().putLong("alertCooldown", cooldowns[index]).apply()
                        dialog.dismiss()
                        renderNotificationSettings()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            })))
        column.addView(sectionHeading(getString(R.string.alert_background)))
        val monitoring = SwitchCompat(this).apply {
            isChecked = CameraAlertMonitorService.running
            contentDescription = getString(R.string.alert_monitor)
            setOnCheckedChangeListener { button, checked ->
                if (checked) {
                    if (!CameraNotifications.enabled(this@MainActivity) || !CameraNotifications.permitted(this@MainActivity)) {
                        button.isChecked = false
                        enableDeviceNotifications()
                    } else if (!CameraAlertMonitorService.start(this@MainActivity)) {
                        button.isChecked = false
                        toast(getString(R.string.alert_monitor_failed))
                    }
                } else stopService(Intent(this@MainActivity, CameraAlertMonitorService::class.java))
            }
        }
        column.addView(preferenceToggleRow(getString(R.string.alert_monitor), getString(R.string.alert_monitor_detail), monitoring))
        column.addView(settingsGroup(settingsNavRow(getString(R.string.alert_battery), getString(R.string.alert_battery_detail), {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { toast(getString(R.string.alert_monitor_failed)) }
        })))
        column.addView(sectionHeading(getString(R.string.alert_diagnostics)))
        val lastCheck = appearancePrefs.getLong("alertLastCheck", 0)
        val lastDelivery = appearancePrefs.getLong("alertLastDelivery", 0)
        fun timestamp(value: Long) = if (value == 0L) "—" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM).format(java.util.Date(value))
        column.addView(settingsDetailLabel(getString(R.string.alert_last_check, timestamp(lastCheck), appearancePrefs.getString("alertLastCheckStatus", "—"))))
        column.addView(settingsDetailLabel(getString(R.string.alert_last_delivery, timestamp(lastDelivery))))
        column.addView(settingsGroup(
            settingsNavRow(getString(R.string.android_settings), getString(R.string.device_alerts), { openAndroidNotificationSettings() }),
            settingsNavRow(getString(R.string.alert_channel_settings), getString(R.string.alert_android_priority), {
                CameraNotifications.createChannel(this)
                startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CameraNotifications.selectedChannel(this)))
            }),
            settingsNavRow(getString(R.string.test_notification), getString(R.string.event_channel), {
                if (CameraNotifications.enabled(this) && CameraNotifications.permitted(this)) {
                    toast(getString(if (CameraNotifications.test(this)) R.string.alert_test_sent else R.string.notifications_blocked))
                    renderNotificationSettings()
                }
                else enableDeviceNotifications()
            }, showChevron = false)
        ))
        setShell(scroll, Tab.Settings)
        val restoreY = notificationScrollY
        scroll.post { scroll.scrollTo(0, restoreY) }
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
        toggle.applyAccentTint()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(16), dp(12), dp(16))
        background = rounded(Palette.card, settingsMenuCornerRadiusDp(), Palette.divider, 1)
        addView(ImageView(context).apply {
            tag = "settings-row-icon"
            setImageResource(when (toggle.tag) {
                "appearance-oled-black" -> R.drawable.ic_image
                "appearance-reduced-motion" -> R.drawable.ic_pause_outline
                "appearance-home-events" -> R.drawable.ic_image
                "appearance-showHeaderChat" -> R.drawable.ic_chat_header
                "appearance-showHeaderAlerts" -> R.drawable.ic_bell_header
                "appearance-home-camera-toolbar" -> R.drawable.ic_server_filter
                "show-camera-source-icons" -> R.drawable.ic_settings_link
                else -> R.drawable.ic_settings
            })
            setColorFilter(Palette.text)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        addView(vertical().apply {
            setPadding(0, 0, dp(12), 0)
            addView(settingsTitleLabel(title).apply { maxLines = 2 })
            if (detail.isNotBlank()) addView(label(detail, 13, Palette.muted).apply { setPadding(0, dp(6), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle)
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun renderServerSettings() {
        settingsPage = "server"
        releasePlayer()
        homeAssistantCards.forEach { it.setActive(false) }
        onvifCards.forEach { it.setActive(false) }
        frigateManager.launch(Intent(this, FrigateServersActivity::class.java))
    }

    private fun renderCameraSettings() {
        settingsPage = "cameras"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
        column.addView(settingsPageHeader(getString(R.string.cameras)))
        column.addView(sectionHeading(getString(R.string.camera_order_visibility)))
        val all = dashboardCameras()
        val summary = settingsDetailLabel(getString(R.string.camera_count_detail, all.count { dashboardCameraVisible(it) }, all.size))
        column.addView(summary)
        if (all.isEmpty()) {
            column.addView(emptyMessage(getString(R.string.no_cameras)))
        } else {
            column.addView(CameraArrangementView(this, all.map {
                CameraArrangementRow(it.key, it.name, it.source, it.enabled, dashboardCameraVisible(it))
            }, onOrder = { cameraArrangement.saveOrder(it) }, onVisibility = { key, visible ->
                cameraArrangement.setHidden(key, !visible)
                all.firstOrNull { it.key == key }?.frigate?.let { setCameraHidden(it.name, !visible) }
                summary.text = getString(R.string.camera_count_detail, all.count { dashboardCameraVisible(it) }, all.size)
            }, onOpen = { key, anchor ->
                all.firstOrNull { it.key == key }?.let { camera ->
                    when {
                        camera.frigate != null -> showCameraOptionsPopup(camera.frigate, anchor)
                        camera.onvif != null -> startActivity(Intent(this, OnvifCameraSettingsActivity::class.java).putExtra("cameraId", camera.onvif.id))
                        else -> showAccessories(camera.key, camera.name, respectAppearance = false)
                    }
                }
            }))
        }
        setShell(scroll, Tab.Settings)
    }

    private fun renderAboutSettings() {
        settingsPage = "about"
        releasePlayer()
        val scroll = appScrollView().apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(32)) }
        scroll.addView(column, responsiveScrollParams(760))
        column.addView(settingsPageHeader(getString(R.string.about)))
        column.addView(sectionHeading(getString(R.string.application)))
        column.addView(settingsGroup(
            settingsRow(getString(R.string.name), "Camnory"),
            settingsRow(getString(R.string.app_version), appVersionName())
        ))
        column.addView(settingsGroup(settingsNavRow(getString(R.string.settings_licenses), getString(R.string.settings_licenses_detail), { renderLicensesSettings() }, key = "licenses")))
        if (session != null) {
            column.addView(sectionHeading("NVR"))
            column.addView(settingsGroup(
                settingsRow(getString(R.string.nvr_version), nvrVersion ?: getString(R.string.loading)),
                settingsRow(getString(R.string.server), session?.baseUrl ?: "-")
            ))
        }
        setShell(scroll, Tab.Settings)
        if (session != null && nvrVersion == null) {
            loadNvrVersion(renderAboutWhenDone = true)
        }
    }

    private fun renderLicensesSettings() {
        val (scroll, column) = newSettingsPage("licenses", getString(R.string.settings_licenses))
        val rows = assets.list("licenses").orEmpty().sorted().map { file ->
            settingsNavRow(file.removeSuffix(".txt").replace('-', ' '), "", {
                val notice = assets.open("licenses/$file").bufferedReader().use { it.readText() }
                val text = TextView(this).apply {
                    this.text = notice
                    setTextColor(Palette.text)
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    textSize = 14f
                }
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle(file.removeSuffix(".txt"))
                    .setView(ScrollView(this).apply { addView(text) })
                    .setPositiveButton(android.R.string.ok, null).show()
            })
        }
        column.addView(settingsGroup(*rows.toTypedArray()))
        setShell(scroll, Tab.Settings)
    }

    private fun settingsRow(title: String, value: String): View {
        return FrameLayout(this).apply {
            addView(vertical().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(settingsTitleLabel(title))
                addView(settingsDetailLabel(value))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        }
    }

    private fun settingsNavRow(title: String, detail: String, onClick: () -> Unit, showChevron: Boolean = true, icon: Int? = R.drawable.ic_settings, key: String? = null): View {
        return FrameLayout(this).apply {
            if (key != null) tag = "settings-entry-$key"
            isClickable = true
            isFocusable = true
            contentDescription = if (detail.isBlank()) title else "$title. $detail"
            setOnClickListener { onClick() }
            addView(vertical().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (icon != null) 48 else 14), dp(12), dp(settingsMenuTextEndPaddingDp(showChevron)), dp(12))
                addView(settingsTitleLabel(title))
                if (detail.isNotBlank()) addView(settingsDetailLabel(detail))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            if (icon != null) addView(ImageView(context).apply {
                tag = "settings-row-icon"
                setImageResource(icon); setColorFilter(Palette.text)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(14) })
            if (showChevron) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(Palette.muted)
                    alpha = 0.7f
                }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                    marginEnd = dp(14)
                })
            }
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
            tag = "settings-back"
            contentDescription = getString(R.string.onvif_back)
            setOnClickListener { navigateSettingsBack() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_back)
                setColorFilter(Palette.text)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
    }

    private fun settingsGroup(vararg rows: View): View {
        return vertical().apply {
            background = rounded(Palette.card, settingsMenuCornerRadiusDp())
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    addView(View(context).apply {
                        setBackgroundColor(Palette.divider)
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        leftMargin = dp(18)
                        rightMargin = dp(14)
                    })
                }
                row.minimumHeight = dp(settingsMenuRowHeightDp())
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
            maxLines = 2
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
        content.addView(BrandWordmarkView(this, 190, portrait = true).apply { tag = "loading-brand" },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, minOf(dp(243), (resources.displayMetrics.heightPixels * 0.32f).toInt())))
        val status = label(text.ifBlank { localizedLoadingStage(0) }, 14, Palette.muted).apply {
            gravity = Gravity.CENTER
            alpha = 0.86f
            setPadding(0, dp(20), 0, 0)
        }
        content.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val progressTrack = FrameLayout(this).apply {
            tag = "loading-progress"
            background = rounded(Palette.surface, 2)
        }
        val progressFill = View(this).apply {
            tag = "loading-progress-fill"
            background = rounded(Palette.accent, 2)
            pivotX = 0f
            scaleX = 0.18f
        }
        progressTrack.addView(progressFill, frameMatch())
        loadingProgress = progressFill
        content.addView(progressTrack, LinearLayout.LayoutParams(dp(140), maxOf(1, dp(1))).apply {
            topMargin = dp(16)
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
                        loadDashboard(getString(R.string.reloaded))
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
        loadingAnimators.addAll(listOf(barAnimator, stageAnimator))
        mainHandler.postDelayed({
            if (root.isAttachedToWindow && loadingAnimators.isNotEmpty()) {
                slowPanel.visibility = View.VISIBLE
                slowPanel.animate().alpha(1f).setDuration(220L).start()
            }
        }, LOADING_SLOW_ACTIONS_DELAY_MS)
    }

    private fun finishLoading(onReady: () -> Unit) {
        val fill = loadingProgress?.takeIf { it.isAttachedToWindow } ?: return onReady()
        loadingAnimators.forEach { it.cancel() }
        loadingAnimators.clear()
        fun openWhenComplete() {
            fill.postOnAnimation { if (loadingProgress === fill && fill.isAttachedToWindow) onReady() }
        }
        if (reducedMotion) {
            fill.scaleX = 1f
            openWhenComplete()
            return
        }
        loadingCompletion = ValueAnimator.ofFloat(fill.scaleX, 1f).apply {
            duration = 220L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { fill.scaleX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    loadingCompletion = null
                    openWhenComplete()
                }
            })
            start()
        }
    }

    private fun saveSession(value: NativeSession) {
        val all = frigateServers.load()
        val existing = all.firstOrNull { it.baseUrl == value.baseUrl }
        frigateServers.upsert(FrigateServer(existing?.id ?: FrigateServerStore.newId(), existing?.name ?: android.net.Uri.parse(value.baseUrl).host.orEmpty(),
            value.baseUrl, value.username, value.token, existing?.legacy ?: all.isEmpty()))
    }

    private fun localizedLoadingStage(index: Int): String = getString(when (((index % 3) + 3) % 3) {
        0 -> R.string.connecting_nvr
        1 -> R.string.loading_cameras
        else -> R.string.preparing_live
    })

    private fun loadSession(): NativeSession? {
        return runCatching { frigateServers.load().firstOrNull()?.let { NativeSession(it.baseUrl, it.token, it.username) } }
            .getOrElse { toast(getString(R.string.frigate_storage_error)); null }
    }

    private fun frigateCameraKey(name: String) = (api as? MultiFrigateApi)?.cameraKey(name)
        ?: CameraArrangement.key("frigate", session?.baseUrl.orEmpty(), name)

    private fun visibleCameras(): List<NativeCamera> {
        val byKey = cameras.filter { isCameraVisible(it) }.associateBy { frigateCameraKey(it.name) }
        return CameraArrangement.ordered(byKey.keys.toList(), cameraArrangement.savedOrder()).mapNotNull { byKey[it] }
    }

    private fun isCameraVisible(camera: NativeCamera): Boolean =
        camera.enabled && !hiddenCameraNames(camera.name).contains((api as? MultiFrigateApi)?.nativeName(camera.name) ?: camera.name) &&
            !cameraArrangement.hidden(frigateCameraKey(camera.name))

    private fun hiddenCameraNames(cameraName: String): Set<String> =
        prefs.getStringSet(hiddenCameraKey(cameraName), emptySet())?.toSet() ?: emptySet()

    private fun setCameraHidden(cameraName: String, hidden: Boolean) {
        val names = hiddenCameraNames(cameraName).toMutableSet()
        val nativeName = (api as? MultiFrigateApi)?.nativeName(cameraName) ?: cameraName
        if (hidden) {
            names.add(nativeName)
        } else {
            names.remove(nativeName)
        }
        prefs.edit().putStringSet(hiddenCameraKey(cameraName), names).apply()
    }

    private fun hiddenCameraKey(cameraName: String): String = "hiddenCameras:${(api as? MultiFrigateApi)?.serverFor(cameraName)?.baseUrl ?: session?.baseUrl ?: "default"}"

    private fun seenAlertIds(): Set<String> {
        val urls = (api as? MultiFrigateApi)?.servers?.map { it.baseUrl } ?: listOf(session?.baseUrl ?: "default")
        val saved = urls.flatMap { prefs.getStringSet("seenAlerts:$it", emptySet()).orEmpty() }.toSet()
        if (saved.isNotEmpty()) seenAlertIdsMemory.addAll(saved)
        return saved + seenAlertIdsMemory
    }

    private fun eventServerUrl(event: NativeEvent) = (api as? MultiFrigateApi)?.serverFor(event.id)?.baseUrl ?: session?.baseUrl ?: "default"
    private fun seenAlertsCutoffMillis(event: NativeEvent): Long = prefs.getLong("seenAlertsCutoff:${eventServerUrl(event)}", 0L)

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
                if (renderAboutWhenDone && activeTab == Tab.Settings && settingsPage == "about") renderAboutSettings()
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
                if (renderSettingsWhenDone && activeTab == Tab.Settings && settingsPage == "updates") refreshUpdateCard()
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
        val fileName = "camnory-update-${System.currentTimeMillis()}.apk"
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Camnory ${update.latestVersion ?: ""}".trim())
            .setDescription(getString(R.string.download_description))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
            .addRequestHeader("User-Agent", "Camnory")
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
        timelineDateSheet?.dismiss(); timelineDateSheet = null
        if (expandedLiveImmersive) {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            expandedLiveImmersive = false
        }
        expandedLiveBack?.remove(); expandedLiveBack = null
        expandedLiveReflow = null
        expandedLiveResume = null
        loginCancellation?.invoke(); loginCancellation = null
        homeVisible = false
        homeAssistantFullscreen?.dismiss()
        homeAssistantCards.forEach { it.setActive(false) }
        homeAssistantCards.clear()
        tvCameraSound.clear()
        onvifCards.forEach { it.setActive(false) }
        onvifCards.clear()
        eventPlaybackControls?.player = null
        eventPlaybackControls = null
        detailSoundRefresh = null
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
        loadingCompletion?.removeAllListeners()
        loadingCompletion?.cancel()
        loadingCompletion = null
        loadingProgress = null
        loadingAnimators.forEach { it.cancel() }
        loadingAnimators.clear()
    }

    private fun releaseCurrentPlayer() {
        eventFullscreenDialog?.dismiss()
        eventPlaybackControls?.player = null
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
            val safe = if (TelevisionMode.enabled(this)) dp(18) else 0
            view.setPadding(safe, safe, safe, safe + if (chatNavigation != null) maxOf(bars.bottom, ime.bottom) else bars.bottom)
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
            CameraButton.forIcon(iconRes)?.let { cameraButton(it) }
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

    private fun soundToolbarIcon(onToggle: () -> Boolean = { toggleSound() }): View {
        val icon = ImageView(this).apply {
            alpha = 0.9f
        }
        fun refresh() {
            icon.setImageResource(if (soundEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off)
            icon.setColorFilter(if (soundEnabled) Palette.accent else Palette.muted)
        }
        return FrameLayout(this).apply {
            isClickable = true
            contentDescription = getString(R.string.playback_sound)
            addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener {
                onToggle()
                refresh()
            }
            refresh()
            detailSoundRefresh = { refresh() }
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
            contentDescription = getString(R.string.live)
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

    private fun discreetFullscreenButton(onClick: () -> Unit): View {
        return FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(FrameLayout(context).apply {
                background = rounded(0x66000000, 8)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_fullscreen)
                    setColorFilter(Color.WHITE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
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
private const val SETTINGS_MENU_RADIUS_DP = 8
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


private class AppUpdateService(private val client: OkHttpClient) {
    fun check(currentVersion: String, callback: (Result<NativeUpdateResult>) -> Unit) {
        val request = Request.Builder()
            .url(RELEASES_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Camnory")
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
private data class CameraFeatureControl(
    val title: String,
    val detail: String,
    val feature: String,
    val enabled: Boolean?
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
    var oledBlack = false
    var backgroundStyle = BackgroundStyle.Automatic
    var dark: Boolean
        get() = themeDark
        set(value) { themeDark = value }
    var accent = 0xffff2d63.toInt()
    private val pureBlack get() = dark && (oledBlack || backgroundStyle == BackgroundStyle.Oled)
    val background get() = if (pureBlack) 0xff000000.toInt() else if (dark) backgroundStyle.background else backgroundStyle.lightBackground
    val backgroundAlt get() = if (dark) if (backgroundStyle != BackgroundStyle.Automatic || pureBlack) background else 0xff151518.toInt()
        else if (backgroundStyle == BackgroundStyle.Automatic) 0xfff0f1f4.toInt() else backgroundStyle.lightBackground
    val black get() = if (dark) if (pureBlack || backgroundStyle == BackgroundStyle.Automatic) 0xff000000.toInt() else background else backgroundStyle.lightCard
    val card get() = if (dark) if (pureBlack && backgroundStyle == BackgroundStyle.Automatic) BackgroundStyle.Oled.card else backgroundStyle.card else backgroundStyle.lightCard
    val surface get() = if (dark) if (pureBlack && backgroundStyle == BackgroundStyle.Automatic) BackgroundStyle.Oled.surface else backgroundStyle.surface else backgroundStyle.lightSurface
    val overlay get() = if (dark) 0xcc050507.toInt() else 0xe6ffffff.toInt()
    val text get() = if (dark) 0xfff7f7f9.toInt() else 0xff202127.toInt()
    val muted get() = if (!dark) 0xff60636e.toInt() else if (backgroundStyle != BackgroundStyle.Automatic) 0xffb0b3be.toInt() else 0xff8d8d96.toInt()
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

internal fun titleFromName(name: String): String =
    name.split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.FRANCE) } }

internal fun formatTime(epochSeconds: Double?): String {
    if (epochSeconds == null || epochSeconds <= 0.0) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date((epochSeconds * 1000).toLong()))
}
