package com.nvr.camnory

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** First TV surface: remote navigation, local pairing and source-owned playback. No camera mutations. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvActivity : AppCompatActivity() {
    private val tv by lazy { TvUi(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private var generation = 0
    private var receiver: TvPairingReceiver? = null
    private var pendingBytes: ByteArray? = null
    private var configuration: TvConfiguration? = null
    private var api: MultiFrigateApi? = null
    private val onvifCards = mutableListOf<OnvifLiveCard>()
    private val haCards = mutableListOf<HomeAssistantCameraView>()
    private var stream: WebSocket? = null
    private var mediaHttp: OkHttpClient? = null
    private var player: ExoPlayer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mode = "home"
    private var homePage = 0
    private var selectedKey: String? = null
    private var knownCameras = emptyList<NativeCamera>()
    private var knownEvents = emptyList<NativeEvent>()
    private var allItems = emptyList<Item>()
    private var sidePanel: android.app.Dialog? = null
    private var adminSection = 0
    private var metadataAttempted = false
    private var manualProvider = "frigate"
    private val manualSetup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val imported = runCatching {
            TvConfiguration.fromPhone(this).takeIf { it.frigate.isNotEmpty() || it.onvif.isNotEmpty() || it.ha != null }
                ?.let { TvWorkspace.merge(configuration, it, manualProvider) }
                ?.also { TvConfigurationStore(this).save(it) }
        }
        if (imported.isSuccess) {
            configuration = imported.getOrNull() ?: configuration
            resetCatalog()
            showHome()
        } else showHome(getString(R.string.tv_pair_failed))
    }
    private data class Item(val key: String, val name: String, val source: CameraSource,
        val frigate: NativeCamera? = null, val onvif: OnvifSavedCamera? = null, val ha: HomeAssistantCamera? = null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == "com.nvr.camnory.TV_SETTINGS") mode = "settings"
        configuration = runCatching { TvConfigurationStore(this).load() }.getOrNull()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (mode == "home") finish() else if (mode == "event") showTvEvents() else showHome() }
        })
    }
    override fun onResume() {
        super.onResume()
        Palette.dark = true; Palette.accent = tv.accent; Palette.oledBlack = tv.prefs.getBoolean("black", true)
        configuration = runCatching { TvConfigurationStore(this).load() }.getOrNull()
        if (mode == "settings") showTvSettings() else showHome()
    }
    private fun clear() {
        generation++
        sidePanel?.dismiss(); sidePanel = null
        receiver?.close(); receiver = null
        pendingBytes?.fill(0); pendingBytes = null
        onvifCards.forEach { it.setActive(false) }; onvifCards.clear()
        haCards.forEach { it.setActive(false) }; haCards.clear()
        stream?.cancel(); stream = null
        player?.release(); player = null
        mediaHttp?.dispatcher?.cancelAll(); mediaHttp?.connectionPool?.evictAll(); mediaHttp = null
        handler.removeCallbacksAndMessages(null)
        http.dispatcher.cancelAll()
    }
    private fun root(title: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(tv.background)
            setPadding(dp(32), dp(18), dp(32), dp(18))
        }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(BrandWordmarkView(this@TvActivity, 180, darkBackground = true), LinearLayout.LayoutParams(dp(180), dp(60)))
            addView(text(title, 22), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(24) })
        })
        setContentView(body)
        return body
    }
    private fun text(value: String, size: Int = 16) = tv.text(value, size)
    private fun focus(view: View) {
        tv.focus(view)
    }
    private fun action(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE)
        isAllCaps = false; minimumHeight = dp(48)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        focus(this); setOnClickListener { click() }
    }
    private fun resetCatalog() {
        knownCameras = emptyList(); knownEvents = emptyList(); allItems = emptyList(); metadataAttempted = false
    }
    private fun showHome(message: String? = null, reload: Boolean = false) {
        clear(); mode = "home"
        api = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val config = configuration
        if (config == null) {
            setContentView(TvWelcomeView(this, message, ::startPairing, ::showManualSources))
            return
        }
        if (reload) resetCatalog()
        val items = config.onvif.map { Item(CameraArrangement.key("onvif", "", it.id), it.name, CameraSource.Onvif, onvif = it) } +
            config.ha?.cameras.orEmpty().map { Item(CameraArrangement.key("ha", config.ha!!.baseUrl, it.entityId), it.name, CameraSource.HomeAssistant, ha = it) }
        fun display() {
            populate(knownCameras.filter { it.enabled }.map { Item(requireNotNull(api).cameraKey(it.name), it.friendlyName, CameraSource.Frigate, frigate = it) } + items)
            message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }
        if (config.frigate.isNotEmpty()) {
            val transport = MultiFrigateApi(config.frigate, http, this).also { api = it }
            if (knownCameras.isNotEmpty()) { display(); return }
            val loading = root(getString(R.string.cameras))
            loading.addView(text(getString(R.string.loading_cameras)))
            loading.addView(action(getString(R.string.tv_admin)) { showTvSettings() })
            val request = generation
            metadataAttempted = true
            transport.loadDashboard(config.frigate.first().token) { result -> runOnUiThread {
                if (request != generation || mode != "home") return@runOnUiThread
                result.onSuccess { data ->
                    knownCameras = data.cameras; knownEvents = data.events; display()
                }.onFailure { display(); Toast.makeText(this, R.string.frigate_unavailable, Toast.LENGTH_LONG).show() }
            } }
        } else display()
    }
    private fun showTvSettings(section: Int = adminSection) {
        clear(); mode = "settings"
        adminSection = section.coerceIn(0, 4)
        val page = TvAdminView(this, adminSection, { showTvSettings(it) }, { showHome() })
        setContentView(page)
        val body = page.content
        val config = configuration
        when (adminSection) {
            0 -> {
                config?.frigate.orEmpty().forEach { server ->
                    body.addView(tv.row(server.name, "Frigate") { editFrigate(server) })
                    body.addView(ServerRouteStatusView(this, server.baseUrl, "/api/version", server.localRoute))
                    body.addView(tv.action(getString(R.string.tv_test_connection)) { testServer(server, body) })
                }
                config?.ha?.let { server ->
                    body.addView(tv.row("Home Assistant", server.baseUrl) { editHa(server) })
                    body.addView(ServerRouteStatusView(this, server.baseUrl, "/api/", server.localRoute))
                    body.addView(tv.action(getString(R.string.tv_test_connection)) { testHa(server, body) })
                }
                body.addView(tv.action(getString(R.string.tv_pair)) { startPairing() })
                body.addView(tv.action(getString(R.string.tv_setup_manual)) { showManualSources() })
            }
            1 -> {
                if (allItems.isEmpty() && config != null) {
                    allItems = config.onvif.map { Item(CameraArrangement.key("onvif", "", it.id), it.name, CameraSource.Onvif, onvif = it) } +
                        config.ha?.cameras.orEmpty().map { Item(CameraArrangement.key("ha", config.ha!!.baseUrl, it.entityId), it.name, CameraSource.HomeAssistant, ha = it) }
                }
                if (!metadataAttempted && config?.frigate?.isNotEmpty() == true) {
                    metadataAttempted = true
                    val transport = MultiFrigateApi(config.frigate, http, this).also { api = it }
                    val request = generation
                    body.addView(text(getString(R.string.loading_cameras)))
                    transport.loadDashboard(config.frigate.first().token) { result -> runOnUiThread {
                        if (request == generation && mode == "settings") {
                            result.onSuccess { data ->
                                knownCameras = data.cameras
                                allItems = data.cameras.map { Item(transport.cameraKey(it.name), it.friendlyName, CameraSource.Frigate, frigate = it) } + allItems
                            }
                            showTvSettings(1)
                        }
                    } }
                }
                val ordered = CameraArrangement.ordered(allItems.map { it.key }, config?.order.orEmpty())
                if (ordered.isEmpty()) body.addView(text(getString(R.string.no_cameras)))
                ordered.forEach { key -> allItems.firstOrNull { it.key == key }?.let { item ->
                    body.addView(tv.row(item.name, getString(R.string.camera_all_settings)) { showCameraPanel(item) })
                    body.addView(tv.toggle(getString(R.string.tv_visible), key !in config!!.hidden) { visible ->
                        val hidden = config.hidden.toMutableSet().apply { if (visible) remove(key) else add(key) }
                        if (saveConfiguration(TvConfiguration(config.frigate, config.onvif, config.ha, config.order, hidden))) showTvSettings(1)
                    })
                    body.addView(tv.action(getString(R.string.tv_move_up)) {
                        val next = ordered.toMutableList(); val index = next.indexOf(key)
                        if (index > 0) {
                            java.util.Collections.swap(next, index, index - 1)
                            if (saveConfiguration(TvConfiguration(config!!.frigate, config.onvif, config.ha, next, config.hidden))) showTvSettings(1)
                        }
                    }.apply { isEnabled = ordered.indexOf(key) > 0 })
                    body.addView(tv.action(getString(R.string.tv_primary_camera)) {
                        selectedKey = key; tv.prefs.edit().putString("primary", key).apply(); showHome()
                    }.apply { isEnabled = key !in config!!.hidden })
                } }
            }
            2 -> {
                body.addView(tv.toggle(getString(R.string.tv_main_stream), !tv.prefs.getBoolean("economy", false)) {
                    tv.prefs.edit().putBoolean("economy", !it).apply()
                })
                body.addView(tv.toggle(getString(R.string.tv_start_muted), tv.prefs.getBoolean("muted", true)) {
                    tv.prefs.edit().putBoolean("muted", it).apply()
                })
                listOf(360, 720, 1080).forEach { height ->
                    body.addView(tv.row(getString(R.string.tv_preview_resolution), "$height p" + if (tv.prefs.getInt("previewHeight", 720) == height) " ✓" else "") {
                        tv.prefs.edit().putInt("previewHeight", height).apply(); showTvSettings(2)
                    })
                }
                body.addView(tv.toggle(getString(R.string.tv_fill_video), tv.prefs.getBoolean("fill", false)) {
                    tv.prefs.edit().putBoolean("fill", it).apply()
                })
            }
            3 -> {
                body.addView(tv.toggle(getString(R.string.tv_black_background), tv.prefs.getBoolean("black", true)) {
                    tv.prefs.edit().putBoolean("black", it).apply(); Palette.oledBlack = it; showTvSettings(3)
                })
                body.addView(tv.toggle(getString(R.string.tv_large_text), tv.prefs.getBoolean("largeText", false)) {
                    tv.prefs.edit().putBoolean("largeText", it).apply(); showTvSettings(3)
                })
                listOf("pink", "blue", "green", "violet", "ochre").forEachIndexed { index, color ->
                    val names = resources.getStringArray(R.array.tv_accent_names)
                    body.addView(tv.row(getString(R.string.tv_accent), names[index]) {
                        tv.prefs.edit().putString("accent", color).apply(); Palette.accent = tv.accent; showTvSettings(3)
                    })
                }
            }
            4 -> {
                body.addView(tv.row(getString(R.string.tv_app_version), packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()))
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = cm.activeNetwork?.let(cm::getNetworkCapabilities)
                val network = when {
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                    capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                    else -> getString(R.string.frigate_offline)
                }
                body.addView(tv.row(getString(R.string.local_network), network))
                config?.frigate.orEmpty().forEach { server ->
                    body.addView(tv.row(server.name, server.baseUrl))
                    body.addView(ServerRouteStatusView(this, server.baseUrl, "/api/version", server.localRoute))
                    server.localRoute?.let { body.addView(tv.row(getString(R.string.local_address), it.localUrl)) }
                    body.addView(tv.action(getString(R.string.tv_test_connection)) { testServer(server, body) })
                }
                config?.ha?.let { server ->
                    body.addView(tv.row("Home Assistant", server.baseUrl))
                    body.addView(ServerRouteStatusView(this, server.baseUrl, "/api/", server.localRoute))
                    body.addView(tv.action(getString(R.string.tv_test_connection)) { testHa(server, body) })
                }
            }
        }
    }
    private fun saveConfiguration(next: TvConfiguration): Boolean = runCatching {
        TvConfigurationStore(this).save(next); configuration = next
    }.isSuccess.also { if (!it) Toast.makeText(this, R.string.frigate_storage_error, Toast.LENGTH_LONG).show() }

    private fun testServer(server: FrigateServer, body: LinearLayout) {
        val status = text(getString(R.string.frigate_checking)); body.addView(status)
        val request = generation
        val client = http.newBuilder().addInterceptor(LocalRouteInterceptor.create(this, server.baseUrl, server.localRoute, server.token, "/api/version")).build()
        FrigateNativeApi(server.baseUrl, client).loadNvrVersion(server.token) { result -> runOnUiThread {
            if (request == generation && status.isAttachedToWindow) status.text = result.fold({ getString(R.string.connected, server.name) }, { getString(R.string.frigate_offline) })
        } }
    }
    private fun testHa(server: HomeAssistantConnection, body: LinearLayout) {
        val status = text(getString(R.string.frigate_checking)); body.addView(status)
        val request = generation
        worker.execute {
            val result = runCatching { HomeAssistantClient(server.baseUrl, server.token, this, server.localRoute).use { it.cameras().size } }
            runOnUiThread { if (request == generation && status.isAttachedToWindow) status.text = result.fold({ getString(R.string.ha_camera_count, it) }, { getString(R.string.ha_connection_failed) }) }
        }
    }
    private fun editor(title: String, content: (LinearLayout, android.app.Dialog) -> Unit) {
        sidePanel?.dismiss()
        val dialog = android.app.Dialog(this)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = tv.column().apply { setBackgroundColor(tv.background); setPadding(dp(22), dp(16), dp(22), dp(22)) }
        body.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 21), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tv.icon(R.drawable.ic_arrow_back, getString(R.string.onvif_back)) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(46), dp(44)))
        })
        content(body, dialog)
        dialog.setContentView(tv.scroll(body)); dialog.setOnDismissListener { if (sidePanel === dialog) sidePanel = null }
        sidePanel = dialog; dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(tv.background))
            setGravity(Gravity.END); setLayout((resources.displayMetrics.widthPixels * 0.43f).toInt().coerceAtLeast(dp(320)), -1)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(0.35f)
        }
    }
    private fun editFrigate(server: FrigateServer) = editor(server.name) { body, dialog ->
        val ui = ConnectionFormUi(this)
        val name = ui.field(body, getString(R.string.frigate_server_name), "Frigate", server.name, key = "tv-server-name")
        val address = ui.field(body, getString(R.string.frigate_server), "https://nvr.example.fr", server.baseUrl, key = "tv-server")
        val local = LocalRouteFields(this, body, server.localRoute)
        val token = ui.field(body, getString(R.string.tv_api_token), "", server.token, true, "tv-server-token")
        val error = text("", 14); body.addView(error)
        body.addView(tv.action(getString(R.string.frigate_save_name)) {
            val updated = runCatching {
                val title = name.text.toString().trim(); require(title.isNotEmpty() && title.length <= 160)
                server.copy(name = title, baseUrl = frigateServerUrl(address.text.toString()), token = token.text.toString(), localRoute = local.value())
            }.getOrNull()
            val config = configuration
            if (updated == null || config == null) error.text = getString(R.string.frigate_bad_fields)
            else if (saveConfiguration(TvWorkspace.remap(TvConfiguration(config.frigate.map { if (it.id == server.id) updated else it }, config.onvif, config.ha, config.order, config.hidden), "frigate", server.baseUrl, updated.baseUrl))) {
                selectedKey = (selectedKey ?: tv.prefs.getString("primary", null))?.let { TvWorkspace.remapKey(it, "frigate", server.baseUrl, updated.baseUrl) }
                selectedKey?.let { tv.prefs.edit().putString("primary", it).apply() }
                resetCatalog()
                dialog.dismiss(); showTvSettings(0)
            } else error.text = getString(R.string.frigate_bad_fields)
        })
        body.addView(tv.action(getString(R.string.tv_remove_source)) {
            confirmRemove(server.name) {
                configuration?.let { config -> saveConfiguration(TvConfiguration(config.frigate.filter { it.id != server.id }, config.onvif, config.ha, config.order, config.hidden)) }
            }
        })
    }
    private fun editHa(server: HomeAssistantConnection) = editor("Home Assistant") { body, dialog ->
        val ui = ConnectionFormUi(this)
        val address = ui.field(body, getString(R.string.ha_server), "https://ha.example.fr", server.baseUrl, key = "tv-ha-server")
        val local = LocalRouteFields(this, body, server.localRoute)
        val token = ui.field(body, getString(R.string.ha_token), "", server.token, true, "tv-ha-token")
        val error = text("", 14); body.addView(error)
        body.addView(tv.action(getString(R.string.ha_save_server)) {
            val updated = runCatching {
                val secret = token.text.toString(); require(secret.length in 1..8192 && secret.all { it.code in 33..126 })
                HomeAssistantConnection(homeAssistantBaseUrl(address.text.toString()), secret, server.cameras, local.value())
            }.getOrNull()
            val config = configuration
            if (updated == null || config == null) error.text = getString(R.string.ha_invalid_address)
            else if (saveConfiguration(TvWorkspace.remap(TvConfiguration(config.frigate, config.onvif, updated, config.order, config.hidden), "ha", server.baseUrl, updated.baseUrl))) {
                selectedKey = (selectedKey ?: tv.prefs.getString("primary", null))?.let { TvWorkspace.remapKey(it, "ha", server.baseUrl, updated.baseUrl) }
                selectedKey?.let { tv.prefs.edit().putString("primary", it).apply() }
                resetCatalog(); dialog.dismiss(); showTvSettings(0)
            }
        })
        body.addView(tv.action(getString(R.string.tv_remove_source)) {
            confirmRemove("Home Assistant") {
                configuration?.let { config -> saveConfiguration(TvConfiguration(config.frigate, config.onvif, null, config.order, config.hidden)) }
            }
        })
    }
    private fun confirmRemove(name: String, remove: () -> Unit) = editor(name) { body, dialog ->
        body.addView(text(getString(R.string.tv_remove_confirm)))
        body.addView(tv.action(getString(android.R.string.cancel)) { dialog.dismiss() })
        body.addView(tv.action(getString(R.string.tv_remove_source)) {
            remove(); resetCatalog()
            dialog.dismiss(); showTvSettings(0)
        })
    }
    private fun showCameraPanel(item: Item): Unit = editor(item.name) { body, dialog ->
        body.tag = "tv-camera-panel"
        var changingFeature = false
        body.addView(tv.row(getString(R.string.tv_source), when (item.source) {
            CameraSource.Frigate -> "Frigate"
            CameraSource.Onvif -> "ONVIF"
            else -> "Home Assistant"
        }))
        body.addView(tv.action(getString(R.string.camera_fullscreen)) { dialog.dismiss(); openCamera(item) })
        val camera = item.frigate
        val transport = api
        if (camera != null && transport != null) {
            val server = transport.serverFor(camera.name)
            body.addView(ServerRouteStatusView(this, server.baseUrl, "/api/version", server.localRoute))
            body.addView(tv.row(getString(R.string.tv_stream), if (tv.prefs.getBoolean("economy", false)) getString(R.string.tv_economy) else getString(R.string.tv_main_stream)))
            val features = listOf(
                Triple(R.string.detection, "detect", camera.detectEnabled),
                Triple(R.string.motion, "motion", camera.motionEnabled),
                Triple(R.string.recordings, "recordings", camera.recordingsEnabled),
                Triple(R.string.snapshots, "snapshots", camera.snapshotsEnabled),
                Triple(R.string.audio, "audio", camera.audioEnabled),
                Triple(R.string.transcription, "audio_transcription", camera.audioTranscriptionEnabled),
                Triple(R.string.notifications, "notifications", camera.notificationsEnabled))
            features.forEach { (title, feature, enabled) ->
                body.addView(tv.toggle(getString(title), enabled == true, enabled != null) { next ->
                    if (changingFeature) return@toggle
                    changingFeature = true
                    val request = generation
                    transport.setCameraFeature(server.token, camera.name, feature, next) { result -> runOnUiThread {
                        if (request != generation || !dialog.isShowing) return@runOnUiThread
                        val changed = if (result.isSuccess) when (feature) {
                            "detect" -> camera.copy(detectEnabled = next)
                            "motion" -> camera.copy(motionEnabled = next)
                            "recordings" -> camera.copy(recordingsEnabled = next)
                            "snapshots" -> camera.copy(snapshotsEnabled = next)
                            "audio" -> camera.copy(audioEnabled = next)
                            "audio_transcription" -> camera.copy(audioTranscriptionEnabled = next)
                            else -> camera.copy(notificationsEnabled = next)
                        } else camera
                        knownCameras = knownCameras.map { if (it.name == camera.name) changed else it }
                        val updated = item.copy(frigate = changed)
                        allItems = allItems.map { if (it.key == updated.key) updated else it }
                        if (result.isFailure) Toast.makeText(this, R.string.accessory_failed, Toast.LENGTH_LONG).show()
                        showCameraPanel(updated)
                    } }
                })
            }
        }
        val target = CameraControlTarget(item.name, item.onvif,
            if (item.ha != null) configuration?.ha else null, item.ha?.entityId,
            if (camera != null) transport?.serverFor(camera.name) else null,
            camera?.let { transport?.nativeName(it.name) })
        body.addView(text(getString(R.string.camera_native_controls), 18))
        body.addView(CameraControlsView(this, target, allowed = { dialog.isShowing && !isFinishing }))
        body.addView(tv.toggle(getString(R.string.microphone), false, false) {})
        item.onvif?.let { saved ->
            body.addView(tv.action(getString(R.string.device_information)) {
                val status = text(getString(R.string.accessory_loading)); body.addView(status)
                val request = generation
                worker.execute {
                    val client = OnvifDeviceSettingsClient()
                    val result = runCatching { client.load(saved) }
                    client.close()
                    runOnUiThread {
                        if (request != generation || !dialog.isShowing) return@runOnUiThread
                        status.text = result.fold({ state ->
                            val labels = resources.getStringArray(R.array.device_info_names)
                            listOf("Manufacturer", "Model", "SerialNumber", "FirmwareVersion", "HardwareId").mapIndexed { index, key ->
                                "${labels[index]} : ${state.information[key].orEmpty()}"
                            }.joinToString("\n") + "\n" + state.profiles.joinToString("\n") { listOfNotNull(it.name, it.encoding, it.resolution).joinToString(" · ") }
                        }, { getString(R.string.accessory_failed) })
                    }
                }
            })
            body.addView(tv.action(getString(R.string.device_connect_settings)) { editOnvif(saved) })
        }
    }
    private fun editOnvif(camera: OnvifSavedCamera) = editor(camera.name) { body, dialog ->
        val ui = ConnectionFormUi(this)
        val name = ui.field(body, getString(R.string.onvif_name), "", camera.name, key = "tv-onvif-name")
        val address = ui.field(body, getString(R.string.server), "", camera.endpoint, key = "tv-onvif-server")
        val user = ui.field(body, getString(R.string.username), "", camera.credentials.username, key = "tv-onvif-user")
        val password = ui.field(body, getString(R.string.password), "", camera.credentials.password, true, "tv-onvif-password")
        val error = text("", 14); body.addView(error)
        body.addView(tv.action(getString(R.string.frigate_save_name)) {
            val updated = runCatching {
                val title = name.text.toString().trim(); require(title.length in 1..160)
                val endpoint = onvifEndpoint(address.text.toString()).toASCIIString()
                OnvifSavedCamera(camera.id, title, endpoint,
                    OnvifCredentials(user.text.toString(), password.text.toString()), camera.profileToken.takeIf { endpoint == camera.endpoint })
            }.getOrNull()
            val config = configuration
            if (updated == null || config == null) error.text = getString(R.string.onvif_invalid_address)
            else if (saveConfiguration(TvConfiguration(config.frigate, config.onvif.map { if (it.id == camera.id) updated else it }, config.ha, config.order, config.hidden))) {
                dialog.dismiss(); showHome()
            }
        })
        body.addView(tv.action(getString(R.string.tv_remove_source)) {
            confirmRemove(camera.name) {
                configuration?.let { config -> saveConfiguration(TvConfiguration(config.frigate, config.onvif.filter { it.id != camera.id }, config.ha, config.order, config.hidden)) }
            }
        })
    }
    private fun showManualSources() {
        clear(); mode = "manual"
        val body = root(getString(R.string.tv_setup_manual))
        body.gravity = Gravity.CENTER_HORIZONTAL
        listOf("Frigate" to FrigateServersActivity::class.java, "ONVIF" to OnvifActivity::class.java,
            "Home Assistant" to HomeAssistantActivity::class.java).forEachIndexed { index, (label, activity) ->
            body.addView(action(label) {
                manualProvider = listOf("frigate", "onvif", "ha")[index]
                manualSetup.launch(Intent(this, activity))
            }.apply {
                tag = "tv-manual-source-$index"
                if (index == 0) requestFocus()
            }, LinearLayout.LayoutParams(dp(380), dp(54)).apply { topMargin = dp(14) })
        }
        body.addView(action(getString(R.string.onvif_back)) { showHome() },
            LinearLayout.LayoutParams(dp(380), dp(50)).apply { topMargin = dp(14) })
    }
    private fun populate(all: List<Item>) {
        onvifCards.forEach { it.setActive(false) }; onvifCards.clear()
        haCards.forEach { it.setActive(false) }; haCards.clear()
        val config = configuration ?: return
        allItems = all
        val byKey = all.associateBy { it.key }
        val items = CameraArrangement.ordered(all.map { it.key }, config.order).mapNotNull { byKey[it] }.filter { it.key !in config.hidden }
        selectedKey = TvWorkspace.primary(items.map { it.key }, selectedKey ?: tv.prefs.getString("primary", null))
        val selected = items.firstOrNull { it.key == selectedKey }
        if (selected != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TvDashboardView(this, items.map { TvCameraTile(it.key, it.name) }, selectedKey, homePage,
            render = { frame, tile, live -> items.firstOrNull { it.key == tile.key }?.let { addSource(frame, it, live) } },
            select = { key -> selectedKey = key; tv.prefs.edit().putString("primary", key).apply(); showHome() },
            fullscreen = { selected?.let(::openCamera) }, settings = { selected?.let(::showCameraPanel) },
            admin = { showTvSettings() }, activity = { showTvEvents() }, reload = { showHome(reload = true) },
            changePage = { page -> homePage = page; showHome() }))
    }
    private fun openCamera(item: Item) {
        clear(); mode = "live"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(frame)
        addSource(frame, item, true)
        frame.addView(action(getString(R.string.onvif_back)) { showHome() }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(18), dp(18), 0, 0)
        })
        frame.addView(text(item.name, 20).apply { setPadding(dp(18), dp(8), dp(18), dp(8)); setBackgroundColor(0xb0000000.toInt()) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        frame.getChildAt(frame.childCount - 2).requestFocus()
    }
    private fun showTvEvents() {
        clear(); mode = "events"
        val body = root(getString(R.string.events_tab))
        body.addView(action(getString(R.string.onvif_back)) { showHome() })
        val rows = tv.column()
        body.addView(tv.scroll(rows), LinearLayout.LayoutParams(-1, 0, 1f))
        if (knownEvents.isEmpty()) rows.addView(text(getString(R.string.tv_no_events)))
        knownEvents.sortedByDescending { it.startTime ?: 0.0 }.take(50).forEach { event ->
            val camera = knownCameras.firstOrNull { it.name == event.camera }?.friendlyName ?: api?.nativeName(event.camera).orEmpty()
            rows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(80); focus(this)
                event.thumbnail?.let { bitmap ->
                    addView(ImageView(this@TvActivity).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(dp(112), dp(68)))
                }
                addView(text("${event.label} · $camera\n${event.time}", 16), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                setOnClickListener { playTvEvent(event) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        body.getChildAt(1).requestFocus()
    }
    private fun playTvEvent(event: NativeEvent) {
        val transport = api ?: return
        val server = transport.serverFor(event.camera)
        clear(); mode = "event"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val body = root(event.label)
        body.addView(action(getString(R.string.onvif_back)) { showTvEvents() })
        val view = PlayerView(this).apply { useController = true; tintVideoBuffering() }
        body.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val playback = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(mediaClient(server)))).build()
        player = playback; view.player = playback; playback.volume = if (tv.prefs.getBoolean("muted", true)) 0f else 1f
        playback.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { Toast.makeText(this@TvActivity, R.string.frigate_unavailable, Toast.LENGTH_LONG).show() }
        })
        playback.setMediaItem(MediaItem.fromUri(transport.eventVodUrl(event.id))); playback.prepare(); playback.playWhenReady = true
        view.requestFocus()
    }
    private fun addSource(frame: FrameLayout, item: Item, live: Boolean) {
        val request = generation
        when {
            item.onvif != null -> {
                val card = OnvifLiveCard(this, item.onvif, {}, {}, playerOnly = true)
                frame.addView(card, FrameLayout.LayoutParams(-1, -1)); onvifCards.add(card)
                card.doOnLayout { if (request == generation) card.setActive(true) }
                if (live) addSound(frame, card.expandedSoundControl())
            }
            item.ha != null -> {
                val config = configuration?.ha ?: return
                val card = HomeAssistantCameraView(this, config, item.ha, {}, {}, fullscreenMode = true, playerOnly = true)
                frame.addView(card, FrameLayout.LayoutParams(-1, -1)); haCards.add(card)
                card.doOnLayout { if (request == generation) { card.setActive(true); if (live) card.startLive() } }
                if (live) addSound(frame, card.expandedSoundControl())
            }
            else -> {
                val camera = item.frigate ?: return
                val transport = api ?: return
                val server = transport.serverFor(camera.name)
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                val quality = text(getString(R.string.tv_preview_quality), 14).apply {
                    tag = "tv-preview-quality"; setBackgroundColor(0xb0000000.toInt())
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                }
                if (live) frame.addView(quality, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(18), dp(18), 0) })
                fun startMjpeg() { stream = transport.openMjpegStream(server.token, camera.streamName,
                    { bitmap -> runOnUiThread { if (request == generation) image.setImageBitmap(bitmap) } },
                    { runOnUiThread { if (request == generation && player?.isPlaying != true) frame.addView(text(getString(R.string.frigate_unavailable)), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) } }) }
                if (live) {
                    startMjpeg()
                    val source = if (tv.prefs.getBoolean("economy", false)) camera.streamName else camera.tvStreamName
                    startFrigateHls(frame, server, transport.nativeName(source), request, ::startMjpeg)
                } else transport.loadBitmap(server.token, transport.latestFrameUrl(camera.name).toHttpUrl().newBuilder()
                    .setQueryParameter("h", tv.prefs.getInt("previewHeight", 720).coerceIn(360, 1080).toString()).build().toString()) { bitmap ->
                    runOnUiThread { if (request == generation && image.isAttachedToWindow) image.setImageBitmap(bitmap) }
                }
            }
        }
    }
    private fun mediaClient(server: FrigateServer): OkHttpClient {
        val base = server.baseUrl.toHttpUrl()
        // Check every playlist and segment request before attaching the server credential.
        return http.newBuilder().dispatcher(Dispatcher()).addInterceptor { chain ->
            val url = chain.request().url
            val prefix = base.encodedPath.trimEnd('/')
            if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                !(url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/"))) throw java.io.IOException("Invalid media origin")
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${server.token}").build())
        }.apply {
            server.localRoute?.let { addInterceptor(LocalRouteInterceptor.create(this@TvActivity, server.baseUrl, it, server.token, "/api/version")) }
        }.build().also { mediaHttp = it }
    }
    private fun startFrigateHls(frame: FrameLayout, server: FrigateServer, name: String, request: Int, fallback: () -> Unit) {
        val client = mediaClient(server)
        val timeout = Runnable { client.dispatcher.cancelAll() }
        var resolved = false
        handler.postDelayed(timeout, 10_000)
        FrigateNativeApi(server.baseUrl, client, server.localRoute).requestHlsUrl(server.token, name) { result -> runOnUiThread {
            if (request != generation) return@runOnUiThread
            if (resolved) return@runOnUiThread
            resolved = true
            handler.removeCallbacks(timeout)
            val url = result.getOrNull() ?: return@runOnUiThread
            val view = PlayerView(this).apply {
                useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); tintVideoBuffering()
                resizeMode = if (tv.prefs.getBoolean("fill", false)) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            frame.addView(view, 1, FrameLayout.LayoutParams(-1, -1))
            val sound = ImageButton(this).apply {
                setImageResource(R.drawable.ic_volume_off); setColorFilter(Color.WHITE)
                contentDescription = getString(R.string.playback_sound)
                isEnabled = false
            }
            addSound(frame, sound)
            val playback = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))).build()
            player = playback; view.player = playback; playback.volume = if (tv.prefs.getBoolean("muted", true)) 0f else 1f
            sound.setImageResource(if (playback.volume == 0f) R.drawable.ic_volume_off else R.drawable.ic_volume)
            sound.setOnClickListener {
                val audible = playback.volume == 0f
                playback.volume = if (audible) 1f else 0f
                sound.setImageResource(if (audible) R.drawable.ic_volume else R.drawable.ic_volume_off)
            }
            playback.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (request == generation && state == Player.STATE_READY) {
                        sound.isEnabled = true; stream?.cancel(); stream = null
                        frame.findViewWithTag<View>("tv-preview-quality")?.visibility = View.GONE
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (request == generation) {
                        view.visibility = View.GONE; sound.visibility = View.GONE
                        frame.findViewWithTag<View>("tv-preview-quality")?.visibility = View.VISIBLE
                        playback.release(); if (player === playback) player = null
                        if (stream == null) fallback()
                    }
                }
            })
            playback.setMediaItem(MediaItem.fromUri(url)); playback.prepare(); playback.playWhenReady = true
        } }
    }
    private fun addSound(frame: FrameLayout, sound: View) {
        focus(sound)
        frame.addView(sound, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(18)) })
    }
    private fun startPairing() {
        clear(); mode = "pair"
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = root(getString(R.string.tv_pair))
        body.addView(text(getString(R.string.loading_cameras)))
        val request = generation
        worker.execute {
            val result = runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val address = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull { TvPairingTicket.privateIpv4(it.hostAddress.orEmpty()) }
                    ?: error("No local address")
                TvPairingReceiver(address, { bytes -> runOnUiThread {
                    if (request != generation || mode != "pair") bytes.fill(0) else proposal(bytes)
                } }, { runOnUiThread { if (request == generation && mode == "pair") showHome(getString(R.string.tv_pair_expired)) } })
            }
            runOnUiThread {
                if (request != generation) { result.getOrNull()?.close(); return@runOnUiThread }
                result.onSuccess { pairing ->
                    receiver = pairing
                    val page = root(getString(R.string.tv_pair))
                    val qr = BarcodeEncoder().encodeBitmap(pairing.ticket.qr(), BarcodeFormat.QR_CODE, 768, 768)
                    page.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ImageView(this@TvActivity).apply { setImageBitmap(qr); contentDescription = getString(R.string.tv_pair) }, LinearLayout.LayoutParams(dp(280), dp(280)))
                        addView(LinearLayout(this@TvActivity).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(dp(28), 0, 0, 0)
                            addView(text(getString(R.string.tv_pair_code, pairing.ticket.code()), 28))
                            addView(text(getString(R.string.tv_pair_instructions), 18).apply { setPadding(0, dp(20), 0, dp(20)) })
                            addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }, LinearLayout.LayoutParams(-1, 0, 1f))
                    pairing.start()
                }.onFailure { showHome(getString(R.string.tv_network_required)) }
            }
        }
    }
    private fun proposal(bytes: ByteArray) {
        val imported = runCatching { TvConfiguration.decode(bytes) }.getOrNull()
            ?: run { bytes.fill(0); showHome(getString(R.string.tv_pair_failed)); return }
        val code = receiver?.ticket?.code().orEmpty()
        receiver?.close(); receiver = null
        pendingBytes = bytes
        mode = "proposal"
        val body = root(getString(R.string.tv_import_title))
        body.addView(text(getString(R.string.tv_pair_code, code), 24))
        body.addView(text(getString(R.string.tv_config_summary, imported.frigate.size, imported.onvif.size, imported.ha?.cameras?.size ?: 0), 20).apply { setPadding(0, dp(20), 0, dp(20)) })
        body.addView(text(getString(R.string.tv_import_warning), 16))
        body.addView(action(getString(R.string.tv_import_accept)) {
            val request = ++generation
            mode = "saving"
            body.removeAllViews(); body.addView(text(getString(R.string.tv_sending), 20))
            worker.execute {
                val result = runCatching { TvConfigurationStore(this).save(imported) }
                bytes.fill(0)
                runOnUiThread {
                    if (request == generation) {
                        if (result.isSuccess) { configuration = imported; resetCatalog() }
                        showHome(if (result.isFailure) getString(R.string.tv_pair_failed) else null)
                    }
                }
            }
        }.apply { tag = "tv-import-accept" })
        body.addView(action(getString(android.R.string.cancel)) { showHome() }.apply { requestFocus() })
    }
    override fun onStop() { clear(); super.onStop() }
    override fun onDestroy() { clear(); worker.shutdownNow(); http.connectionPool.evictAll(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
