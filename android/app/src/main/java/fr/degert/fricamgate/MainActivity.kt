package fr.degert.fricamgate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.drawable.GradientDrawable
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences("fricamgate-native", MODE_PRIVATE) }
    private var session: NativeSession? = null
    private var api: FrigateNativeApi? = null
    private var cameras: List<NativeCamera> = emptyList()
    private var events: List<NativeEvent> = emptyList()
    private var currentPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.black
        session = loadSession()
        api = session?.let { FrigateNativeApi(it.baseUrl, httpClient) }
        if (session == null) {
            renderLogin()
        } else {
            loadDashboard("Session restauree pour ${session?.username}.")
        }
    }

    override fun onStop() {
        super.onStop()
        currentPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun renderLogin() {
        releasePlayer()
        val scroll = ScrollView(this).apply {
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
        root.addView(label("Serveur Frigate", 12, Palette.muted).apply { setPadding(0, dp(28), 0, dp(8)) })
        val serverInput = editText("http://cam.example.fr", prefs.getString("baseUrl", "") ?: "")
        root.addView(serverInput, matchWrap())
        root.addView(label("Utilisateur", 12, Palette.muted).apply { setPadding(0, dp(18), 0, dp(8)) })
        val usernameInput = editText("admin", prefs.getString("username", "") ?: "")
        root.addView(usernameInput, matchWrap())
        root.addView(label("Mot de passe", 12, Palette.muted).apply { setPadding(0, dp(18), 0, dp(8)) })
        val passwordInput = editText("mot de passe", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passwordInput, matchWrap())
        val button = primaryButton("Connexion").apply {
            setOnClickListener {
                connect(serverInput.text.toString(), usernameInput.text.toString(), passwordInput.text.toString())
            }
        }
        root.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(24) })
        root.addView(label("Chaque utilisateur renseigne son propre serveur. Le HTTP est accepte pour les installations Frigate exposees ainsi.", 14, Palette.muted).apply {
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(scroll)
    }

    private fun connect(baseUrlValue: String, username: String, password: String) {
        val normalizedUrl = normalizeBaseUrl(baseUrlValue)
        if (normalizedUrl.isBlank() || username.isBlank() || password.isBlank()) {
            toast("Serveur, utilisateur et mot de passe obligatoires.")
            return
        }

        prefs.edit()
            .putString("baseUrl", normalizedUrl)
            .putString("username", username)
            .apply()
        renderLoading("Connexion a Frigate...")
        val nextApi = FrigateNativeApi(normalizedUrl, httpClient)
        nextApi.login(username, password) { result ->
            runOnUiThread {
                result.onSuccess { login ->
                    val nextSession = NativeSession(normalizedUrl, login.token, login.username)
                    saveSession(nextSession)
                    session = nextSession
                    api = nextApi
                    loadDashboard("Connecte a $normalizedUrl")
                }.onFailure {
                    renderLogin()
                    toast(it.message ?: "Connexion impossible")
                }
            }
        }
    }

    private fun loadDashboard(message: String? = null) {
        releasePlayer()
        val activeSession = session
        val activeApi = api
        if (activeSession == null || activeApi == null) {
            renderLogin()
            return
        }
        renderLoading("Chargement des cameras...")
        activeApi.loadDashboard(activeSession.token) { result ->
            runOnUiThread {
                result.onSuccess { data ->
                    cameras = data.cameras
                    events = data.events
                    renderHome(message)
                }.onFailure {
                    renderHome(it.message ?: "Frigate indisponible")
                }
            }
        }
    }

    private fun renderHome(message: String? = null) {
        releasePlayer()
        val content = ScrollView(this).apply {
            setBackgroundColor(Palette.background)
            clipToPadding = false
            setPadding(0, 0, 0, dp(78))
        }
        val column = vertical().apply { setPadding(dp(12), dp(12), dp(12), dp(24)) }
        content.addView(column, responsiveScrollParams(720))
        column.addView(eventStrip())
        val titleRow = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(14))
        }
        titleRow.addView(label("Accueil", 30, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(label(message ?: "", 13, Palette.muted))
        column.addView(titleRow)

        if (cameras.isEmpty()) {
            column.addView(emptyMessage("Aucune camera Frigate trouvee."))
        } else {
            cameras.filter { it.enabled }.forEach { camera ->
                column.addView(cameraCard(camera))
            }
        }
        setShell(content, Tab.Home)
    }

    private fun renderCameraDetail(camera: NativeCamera) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        releasePlayer()

        val body = vertical().apply { setBackgroundColor(Palette.background) }
        val playerFrame = FrameLayout(this).apply { setBackgroundColor(Palette.black) }
        val fallback = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        playerFrame.addView(fallback, frameMatch())
        activeApi.loadBitmap(activeSession.token, activeApi.latestFrameUrl(camera.name)) { bitmap ->
            runOnUiThread { bitmap?.let { fallback.setImageBitmap(it) } }
        }

        val playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
        }
        playerFrame.addView(playerView, frameMatch())
        val back = roundButton("<").apply { setOnClickListener { renderHome() } }
        playerFrame.addView(back, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(18)
            topMargin = dp(18)
        })
        playerFrame.addView(chip(camera.friendlyName), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(18)
            bottomMargin = dp(16)
        })
        playerFrame.addView(chip("live"), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.END or Gravity.TOP).apply {
            rightMargin = dp(18)
            topMargin = dp(18)
        })
        body.addView(playerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        body.addView(cameraToolbar(camera))
        body.addView(timeline(camera), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setShell(body, Tab.Cameras)
        startLivePlayer(activeApi, activeSession.token, camera, playerView)
    }

    private fun renderEventDetail(event: NativeEvent) {
        val activeSession = session ?: return renderLogin()
        val activeApi = api ?: return renderLogin()
        releasePlayer()

        val body = vertical().apply { setBackgroundColor(Palette.background) }
        val playerFrame = FrameLayout(this).apply { setBackgroundColor(Palette.black) }
        val fallback = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        playerFrame.addView(fallback, frameMatch())
        event.thumbnail?.let { fallback.setImageBitmap(it) }
        activeApi.loadBitmap(activeSession.token, activeApi.eventSnapshotUrl(event.id)) { bitmap ->
            runOnUiThread { bitmap?.let { fallback.setImageBitmap(it) } }
        }
        val playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.TRANSPARENT)
            setShutterBackgroundColor(Color.TRANSPARENT)
        }
        playerFrame.addView(playerView, frameMatch())
        playerFrame.addView(roundButton("<").apply { setOnClickListener { renderHome() } }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.TOP).apply {
            leftMargin = dp(18)
            topMargin = dp(18)
        })
        body.addView(playerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))

        val scroll = ScrollView(this)
        val column = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(120)) }
        scroll.addView(column)
        column.addView(label("LECTURE EVENEMENT", 12, Palette.muted))
        column.addView(label(event.label, 28, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        column.addView(label("${event.camera} - ${event.time}", 14, Palette.muted).apply { setPadding(0, dp(4), 0, dp(16)) })
        column.addView(label("Cameras au meme moment", 18, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        column.addView(syncCameraGrid(event))
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setShell(body, Tab.Events)
        startClipPlayer(activeApi, activeSession.token, event, playerView)
    }

    private fun startLivePlayer(api: FrigateNativeApi, token: String, camera: NativeCamera, playerView: PlayerView) {
        val player = ExoPlayer.Builder(this).build()
        currentPlayer = player
        playerView.player = player
        api.requestHlsUrl(token, camera.streamName) { result ->
            runOnUiThread {
                if (currentPlayer !== player) return@runOnUiThread
                result.onSuccess { url ->
                    val source = HlsMediaSource.Factory(authDataSource(token)).createMediaSource(MediaItem.fromUri(url))
                    player.setMediaSource(source)
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.prepare()
                    player.playWhenReady = true
                }.onFailure {
                    toast("Live indisponible: ${it.message ?: "flux HLS"}")
                }
            }
        }
    }

    private fun startClipPlayer(api: FrigateNativeApi, token: String, event: NativeEvent, playerView: PlayerView) {
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(authDataSource(token)))
            .build()
        currentPlayer = player
        playerView.player = player
        player.setMediaItem(MediaItem.fromUri(api.eventClipUrl(event.id)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun authDataSource(token: String): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token", "User-Agent" to "FriCamGate"))

    private fun eventStrip(): View {
        if (events.isEmpty()) return emptyMessage("Aucun evenement recent.")
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
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
            loadEventPreview(event, thumb)
            thumbFrame.addView(thumb, frameMatch())
            card.addView(thumbFrame, LinearLayout.LayoutParams(dp(140), dp(78)))
            card.addView(label(event.time, 16, Palette.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            row.addView(card, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
        }
        return scroll
    }

    private fun cameraCard(camera: NativeCamera): View {
        val card = FrameLayout(this).apply {
            background = rounded(Palette.card, 8)
            applyRoundedClip(8)
            setOnClickListener { renderCameraDetail(camera) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.surface)
        }
        card.addView(image, frameMatch())
        session?.let { activeSession ->
            api?.loadBitmap(activeSession.token, api?.latestFrameUrl(camera.name).orEmpty()) { bitmap ->
                runOnUiThread { bitmap?.let { image.setImageBitmap(it) } }
            }
        }
        card.addView(chip(camera.friendlyName), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.START or Gravity.BOTTOM).apply {
            leftMargin = dp(16)
            bottomMargin = dp(16)
        })
        val controls = horizontal().apply {
            gravity = Gravity.CENTER
            addView(iconBubble(R.drawable.ic_refresh))
            addView(iconBubble(R.drawable.ic_videocam))
            addView(iconBubble(R.drawable.ic_settings))
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

    private fun cameraToolbar(camera: NativeCamera): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            setBackgroundColor(Palette.backgroundAlt)
            addView(label(camera.friendlyName, 24, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(chip("son off"))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72))
        }
    }

    private fun timeline(camera: NativeCamera): View {
        val scroll = ScrollView(this)
        val column = vertical().apply { setPadding(dp(18), dp(20), dp(18), dp(120)) }
        scroll.addView(column)
        val cameraEvents = events.filter { it.camera == camera.name }
        if (cameraEvents.isEmpty()) {
            column.addView(emptyMessage("Aucune video recente pour cette camera."))
        } else {
            cameraEvents.forEach { event ->
                val row = horizontal().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { renderEventDetail(event) }
                }
                val marker = TextView(this).apply {
                    text = "|"
                    textSize = 42f
                    setTextColor(Palette.accent)
                    gravity = Gravity.CENTER
                }
                row.addView(marker, LinearLayout.LayoutParams(dp(42), dp(82)))
                val thumb = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Palette.surface)
                }
                val thumbFrame = FrameLayout(this).apply {
                    background = rounded(Palette.card, 8)
                    applyRoundedClip(8)
                }
                loadEventPreview(event, thumb)
                thumbFrame.addView(thumb, frameMatch())
                row.addView(thumbFrame, LinearLayout.LayoutParams(dp(150), dp(82)))
                row.addView(vertical().apply {
                    setPadding(dp(14), 0, 0, 0)
                    addView(label(event.label, 17, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
                    addView(label(event.time, 15, Palette.muted))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                column.addView(row)
            }
        }
        return scroll
    }

    private fun syncCameraGrid(event: NativeEvent): View {
        val grid = vertical()
        cameras.filter { it.name != event.camera && it.enabled }.forEach { camera ->
            val row = horizontal().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { renderCameraDetail(camera) }
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

    private fun setShell(content: View, activeTab: Tab) {
        val root = vertical().apply { setBackgroundColor(Palette.background) }
        root.addView(appHeader())
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav(activeTab), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
        keepAboveSystemNavigation(root)
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun appHeader(): View {
        return FrameLayout(this).apply {
            setBackgroundColor(Palette.background)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.fricamgate_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)) }
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
                contentDescription = tab.label
                setOnClickListener {
                    when (tab) {
                        Tab.Home -> renderHome()
                        Tab.Cameras -> cameras.firstOrNull()?.let { renderCameraDetail(it) } ?: renderHome()
                        Tab.Grid -> renderHome()
                        Tab.Events -> events.firstOrNull()?.let { renderEventDetail(it) } ?: renderHome()
                        Tab.Settings -> renderSettings()
                    }
                }
            }
            val iconHolder = FrameLayout(this).apply {
                if (tab == activeTab) {
                    background = rounded(Palette.navActive, 12)
                }
            }
            iconHolder.addView(ImageView(this).apply {
                setImageResource(tab.iconRes)
                setColorFilter(if (tab == activeTab) Palette.accent else Palette.text)
                alpha = if (tab == activeTab) 1f else 0.92f
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
            item.addView(iconHolder, FrameLayout.LayoutParams(dp(64), dp(56), Gravity.CENTER))
            row.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return row
    }

    private fun renderSettings() {
        releasePlayer()
        val scroll = ScrollView(this).apply { setBackgroundColor(Palette.background) }
        val column = vertical().apply { setPadding(dp(16), dp(12), dp(16), dp(120)) }
        scroll.addView(column, centeredScrollParams(620))
        column.addView(label("Reglages", 30, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
        column.addView(settingsRow("Serveur", session?.baseUrl ?: "-"))
        column.addView(settingsRow("Utilisateur", session?.username ?: "-"))
        column.addView(primaryButton("Recharger").apply { setOnClickListener { loadDashboard("Recharge.") } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(18) })
        column.addView(primaryButton("Deconnexion").apply {
            setOnClickListener {
                prefs.edit().clear().apply()
                session = null
                api = null
                renderLogin()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) })
        setShell(scroll, Tab.Settings)
    }

    private fun settingsRow(title: String, value: String): View {
        return vertical().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Palette.card, 8)
            addView(label(title, 16, Palette.text).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) })
            addView(label(value, 14, Palette.muted).apply { setPadding(0, dp(4), 0, 0) })
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }
    }

    private fun renderLoading(text: String) {
        releasePlayer()
        val root = vertical().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.background)
        }
        root.addView(ProgressBar(this))
        root.addView(label(text, 16, Palette.muted).apply { setPadding(0, dp(18), 0, 0) })
        setContentView(root)
    }

    private fun saveSession(value: NativeSession) {
        prefs.edit()
            .putString("baseUrl", value.baseUrl)
            .putString("token", value.token)
            .putString("username", value.username)
            .apply()
    }

    private fun loadSession(): NativeSession? {
        val baseUrl = prefs.getString("baseUrl", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val username = prefs.getString("username", "admin") ?: "admin"
        return NativeSession(baseUrl, token, username)
    }

    private fun releasePlayer() {
        currentPlayer?.release()
        currentPlayer = null
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
            textSize = 17f
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
            textSize = 16f
            setTextColor(Palette.text)
            background = rounded(Palette.accent, 6)
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
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Palette.text)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(Palette.overlay, 20)
        }
    }

    private fun keepAboveSystemNavigation(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun iconBubble(iconRes: Int): View {
        return FrameLayout(this).apply {
            background = rounded(Palette.overlay, 22)
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Palette.text)
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) }
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
            textSize = size.toFloat()
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

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
    private fun frameMatch() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
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
                    }.onSuccess {
                        callback(Result.success(it))
                    }.onFailure {
                        callback(Result.failure(IOException("Tableau de bord Frigate invalide.", it)))
                    }
                }.onFailure { callback(Result.failure(it)) }
            }
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

    fun latestFrameUrl(cameraName: String): String = endpoint("/api/${Uri.encode(cameraName)}/latest.jpg?h=360")
    fun eventSnapshotUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/snapshot.jpg?h=360")
    fun eventClipUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/clip.mp4")
    fun recordingSnapshotUrl(cameraName: String, frameTime: Double): String =
        endpoint("/api/${Uri.encode(cameraName)}/recordings/${frameTime.toLong()}/snapshot.webp")

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
                streamName = selectStreamName(name, camera)
            )
        }.toList()
    }

    private fun parseEvents(events: JSONArray): List<NativeEvent> {
        return (0 until events.length()).mapNotNull { index ->
            val event = events.optJSONObject(index) ?: return@mapNotNull null
            val id = event.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val thumbnail = event.optString("thumbnail").takeIf { it.isNotBlank() }?.let { decodeThumbnail(it) }
            val startTime = if (event.has("start_time")) event.optDouble("start_time") else null
            NativeEvent(
                id = id,
                camera = cleanEventText(event, "camera"),
                label = cleanEventText(event, "sub_label").ifBlank { cleanEventText(event, "label").ifBlank { "motion" } },
                time = formatTime(startTime),
                startTime = startTime,
                endTime = if (event.has("end_time") && !event.isNull("end_time")) event.optDouble("end_time") else null,
                thumbnail = thumbnail
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
    }
}

private data class NativeSession(val baseUrl: String, val token: String, val username: String)
private data class NativeLogin(val token: String, val username: String)
private data class DashboardData(val cameras: List<NativeCamera>, val events: List<NativeEvent>)
private data class NativeCamera(val name: String, val friendlyName: String, val enabled: Boolean, val streamName: String)
private data class NativeEvent(
    val id: String,
    val camera: String,
    val label: String,
    val time: String,
    val startTime: Double?,
    val endTime: Double?,
    val thumbnail: Bitmap?
)

private enum class Tab(val label: String, val iconRes: Int) {
    Home("Accueil", R.drawable.ic_home),
    Cameras("Cameras", R.drawable.ic_videocam),
    Grid("Grille", R.drawable.ic_grid),
    Events("Events", R.drawable.ic_image),
    Settings("Reglages", R.drawable.ic_settings)
}

private object Palette {
    const val background = 0xff09090a.toInt()
    const val backgroundAlt = 0xff151518.toInt()
    const val black = 0xff000000.toInt()
    const val card = 0xff17171c.toInt()
    const val surface = 0xff22222a.toInt()
    const val overlay = 0xcc050507.toInt()
    const val navActive = 0xff3b0d1b.toInt()
    const val text = 0xfff7f7f9.toInt()
    const val muted = 0xff8d8d96.toInt()
    const val accent = 0xffff2d63.toInt()
}

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
