package com.nvr.camnory

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** Qualified identifiers never leave this router; transports receive native server names. */
internal class MultiFrigateApi(val servers: List<FrigateServer>, client: OkHttpClient, context: android.content.Context? = null) :
    FrigateNativeApi(servers.first().baseUrl, client) {
    private val selection = context?.let { TvProviderStorage.read(it)?.selection }.orEmpty()
    private fun permits(server: FrigateServer, name: String) = selection[server.baseUrl]?.contains(name) ?: true
    private val transports = servers.associate { it.id to FrigateNativeApi(it.baseUrl, client.newBuilder()
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).apply {
            if (context != null) {
                if (it.localRoute != null) retryOnConnectionFailure(false)
                addInterceptor(LocalRouteInterceptor.create(context, it.baseUrl, it.localRoute, it.token, "/api/version"))
            }
        }.build(), it.localRoute, if (context != null && TelevisionMode.enabled(context)) 960 else 640) }
    @Volatile var unavailable: Set<String> = emptySet()
        private set
    fun serverFor(value: String): FrigateServer = if (value.startsWith("@")) {
        servers.firstOrNull { value.startsWith("@${it.id}:") } ?: error("Unknown Frigate source")
    } else servers.firstOrNull { it.legacy } ?: error("Missing Frigate source")
    fun nativeName(value: String): String = serverFor(value).let { if (it.legacy) value else value.removePrefix("@${it.id}:") }
    private fun api(server: FrigateServer) = transports.getValue(server.id)
    fun cameraKey(name: String) = serverFor(name).let { CameraArrangement.key("frigate", it.baseUrl, nativeName(name)) }
    fun serverForUrl(value: String): FrigateServer? {
        val url = value.toHttpUrlOrNull() ?: return null
        return servers.sortedByDescending { it.baseUrl.length }.firstOrNull {
            val base = it.baseUrl.toHttpUrlOrNull() ?: return@firstOrNull false
            url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
                (url.encodedPath == base.encodedPath.trimEnd('/') || url.encodedPath.startsWith(base.encodedPath.trimEnd('/') + "/"))
        }
    }
    private fun qualify(server: FrigateServer, event: NativeEvent) = event.copy(id = server.qualify(event.id), camera = server.qualify(event.camera))
    override fun loadDashboard(token: String, callback: (Result<DashboardData>) -> Unit) {
        val results = mutableMapOf<String, Result<DashboardData>>()
        servers.forEach { server -> api(server).loadDashboard(server.token) { result ->
            val combined = synchronized(results) {
                results[server.id] = result
                if (results.size < servers.size) null else {
                    unavailable = results.filterValues { it.isFailure }.keys.toSet()
                    val loaded = servers.mapNotNull { s -> results[s.id]?.getOrNull()?.let { s to it } }
                    if (loaded.isEmpty()) Result.failure(IOException("Frigate unavailable")) else Result.success(DashboardData(
                        loaded.flatMap { (s, data) -> data.cameras.filter { permits(s, it.name) }.map { it.copy(name = s.qualify(it.name), streamName = s.qualify(it.streamName), tvStreamName = s.qualify(it.tvStreamName),
                            friendlyName = if (servers.size > 1) "${it.friendlyName} · ${s.name}" else it.friendlyName) } },
                        loaded.flatMap { (s, data) -> data.events.filter { permits(s, it.camera) }.map { qualify(s, it) } }.sortedByDescending { it.startTime ?: 0.0 }))
                }
            }
            combined?.let(callback)
        } }
    }
    override fun loadEvents(token: String, cameraName: String, afterSec: Double, beforeSec: Double, callback: (Result<List<NativeEvent>>) -> Unit) {
        val s = serverFor(cameraName)
        if (!permits(s, nativeName(cameraName))) { callback(Result.success(emptyList())); return }
        api(s).loadEvents(s.token, nativeName(cameraName), afterSec, beforeSec) { callback(it.map { events -> events.map { e -> qualify(s, e) } }) }
    }
    override fun searchEvents(token: String, search: EventSearch, now: Double, before: Double?, callback: (Result<List<NativeEvent>>) -> Unit) {
        val selected = search.camera?.let { listOf(serverFor(it)) } ?: servers
        val results = mutableMapOf<String, Result<List<NativeEvent>>>()
        selected.forEach { s ->
            api(s).searchEvents(s.token, search.copy(camera = search.camera?.let(::nativeName)), now, before) { result ->
                val combined = synchronized(results) {
                    results[s.id] = result.map { it.filter { e -> permits(s, e.camera) }.map { e -> qualify(s, e) } }
                    if (results.size < selected.size) null else if (results.values.any { it.isFailure })
                        Result.failure(IOException("Frigate history unavailable"))
                    else Result.success(results.values.flatMap { it.getOrThrow() }.sortedByDescending { it.startTime ?: 0.0 }.take(100))
                }
                combined?.let(callback)
            }
        }
    }
    override fun loadNvrVersion(token: String, callback: (Result<String>) -> Unit) = api(servers.first()).loadNvrVersion(servers.first().token, callback)
    override fun loadBitmap(token: String, url: String, callback: (Bitmap?) -> Unit) {
        val s = serverForUrl(url) ?: return callback(null)
        api(s).loadBitmap(s.token, url, callback)
    }
    override fun loadGif(token: String, url: String, callback: (Drawable?) -> Unit) {
        val s = serverForUrl(url) ?: return callback(null)
        api(s).loadGif(s.token, url, callback)
    }
    override fun latestFrameUrl(cameraName: String) = serverFor(cameraName).let { api(it).latestFrameUrl(nativeName(cameraName)) }
    override fun eventSnapshotUrl(eventId: String) = serverFor(eventId).let { api(it).eventSnapshotUrl(nativeName(eventId)) }
    override fun eventPreviewUrl(eventId: String) = serverFor(eventId).let { api(it).eventPreviewUrl(nativeName(eventId)) }
    override fun eventClipUrl(eventId: String) = serverFor(eventId).let { api(it).eventClipUrl(nativeName(eventId)) }
    override fun eventVodUrl(eventId: String) = serverFor(eventId).let { api(it).eventVodUrl(nativeName(eventId)) }
    override fun recordingClipUrl(cameraName: String, startSec: Long, endSec: Long) = serverFor(cameraName).let {
        api(it).recordingClipUrl(nativeName(cameraName), startSec, endSec) }
    override fun recordingVodUrl(cameraName: String, startSec: Long, endSec: Long, stream: String?) = serverFor(cameraName).let {
        api(it).recordingVodUrl(nativeName(cameraName), startSec, endSec, stream?.let(::nativeName)) }
    override fun recordingSnapshotUrl(cameraName: String, frameTime: Double) = serverFor(cameraName).let { api(it).recordingSnapshotUrl(nativeName(cameraName), frameTime) }
    override fun liveMseWebSocketUrl(streamName: String) = serverFor(streamName).let { api(it).liveMseWebSocketUrl(nativeName(streamName)) }
    override fun openMjpegStream(token: String, streamName: String, onFrame: (Bitmap) -> Unit, onError: () -> Unit): WebSocket =
        serverFor(streamName).let { api(it).openMjpegStream(it.token, nativeName(streamName), onFrame, onError) }
    override fun openMseStream(token: String, streamName: String, onData: (ByteArray) -> Unit, onError: () -> Unit, audioOnly: Boolean) =
        serverFor(streamName).let { api(it).openMseStream(it.token, nativeName(streamName), onData, onError, audioOnly) }
    override fun setCameraFeature(token: String, cameraName: String, feature: String, enabled: Boolean, callback: (Result<Unit>) -> Unit) =
        serverFor(cameraName).let { api(it).setCameraFeature(it.token, nativeName(cameraName), feature, enabled, callback) }
}
