package com.nvr.camnory

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class HomeAssistantCamera(val entityId: String, val name: String, val available: Boolean)
internal class HomeAssistantConnection(val baseUrl: String, val token: String, val cameras: List<HomeAssistantCamera>) {
    override fun toString() = "HomeAssistantConnection([redacted])"
}
internal class HomeAssistantAuthException : IOException("Home Assistant authentication failed")

internal fun validHomeAssistantCamera(entity: String) = entity.length <= 255 && Regex("camera\\.[a-z0-9_]+").matches(entity)

internal fun homeAssistantBaseUrl(value: String): String {
    require(value.length <= 2048)
    val url = value.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    val host = url.host
    val octets = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
    val local = host == "localhost" || host == "::1" || host.startsWith("fd", true) && host.contains(':') ||
        host.startsWith("fc", true) && host.contains(':') || host.startsWith("fe80:", true) || host.endsWith(".local") ||
        octets.size == 4 && (octets[0] == 10 || octets[0] == 127 || octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 172 && octets[1] in 16..31 || octets[0] == 169 && octets[1] == 254)
    require(url.isHttps || local)
    return url.toString().trimEnd('/')
}

/** Camera reads/playback plus strictly allowlisted, explicitly requested light/siren commands. */
internal class HomeAssistantClient(baseUrl: String, private val token: String) : Closeable {
    @Volatile private var closed = false
    private val base = (homeAssistantBaseUrl(baseUrl) + "/").toHttpUrl()
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<HomeAssistantSocket>()
    internal val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).addInterceptor { chain ->
            if (closed) throw IOException("Home Assistant client closed")
            if (!sameOrigin(chain.request().url)) throw IOException("External camera URL refused")
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        }.build()

    init { require(token.length in 1..8192 && token.all { it.code in 33..126 }) }
    override fun toString() = "HomeAssistantClient([redacted])"
    private fun sameOrigin(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
        url.username.isEmpty() && url.password.isEmpty()
    internal fun mediaUrl(value: String): String {
        val url = base.resolve(value) ?: throw IOException("Invalid camera URL")
        require(sameOrigin(url) && url.fragment == null)
        require(url.encodedPath.startsWith(base.encodedPath + "api/hls/"))
        return url.toString()
    }
    internal fun endpoint(path: String): HttpUrl = base.newBuilder().encodedPath(base.encodedPath + path).build()
    fun cameras(): List<HomeAssistantCamera> {
        val states = JSONArray(String(get(endpoint("api/states"), 4 * 1024 * 1024), Charsets.UTF_8))
        require(states.length() <= 100_000)
        return (0 until states.length()).mapNotNull { index ->
            val state = states.optJSONObject(index) ?: return@mapNotNull null
            val entity = state.optString("entity_id")
            if (!validHomeAssistantCamera(entity)) return@mapNotNull null
            val name = state.optJSONObject("attributes")?.optString("friendly_name")?.filterNot { it.isISOControl() }?.take(160)
                ?.takeIf { it.isNotBlank() } ?: entity.removePrefix("camera.")
            HomeAssistantCamera(entity, name, state.optString("state") !in setOf("unavailable", "unknown"))
        }.distinctBy { it.entityId }.sortedBy { it.name.lowercase() }.take(200)
    }
    fun image(entity: String): ByteArray {
        require(validHomeAssistantCamera(entity))
        return get(endpoint("api/camera_proxy/$entity").newBuilder().addQueryParameter("width", "640").build(), 8 * 1024 * 1024)
    }
    fun accessories(): List<CameraAccessory> = parseCameraAccessories(JSONArray(String(get(endpoint("api/states"), 4 * 1024 * 1024), Charsets.UTF_8)))

    fun cameraBuiltins(entity: String): List<CameraAccessory> {
        require(validHomeAssistantCamera(entity))
        val ids = socket().use { cameraBuiltinEntityIds(entity, it.entityRegistry()) }
        return if (ids.isEmpty()) emptyList() else accessories().filter { it.entityId in ids }
    }

    fun setCameraBuiltin(camera: String, entity: String, enabled: Boolean) {
        require(cameraBuiltins(camera).any { it.entityId == entity })
        setAccessory(entity, enabled)
    }

    fun setAccessory(entity: String, enabled: Boolean) {
        require(validCameraAccessory(entity))
        val accessory = accessories().firstOrNull { it.entityId == entity } ?: throw IOException("Unavailable accessory")
        require(if (enabled) accessory.enabled != null && accessory.canOn else accessory.canOff)
        val action = if (enabled) "turn_on" else "turn_off"
        val body = JSONObject().put("entity_id", entity).toString().toRequestBody("application/json".toMediaType())
        // No toggle, script, broadcast target, generic service, automatic retry or alarm-panel access.
        http.newBuilder().retryOnConnectionFailure(false).build().newCall(Request.Builder()
            .url(endpoint("api/services/${accessory.domain}/$action")).post(body).build()).execute().use {
            if (it.code in setOf(401, 403)) throw HomeAssistantAuthException()
            if (!it.isSuccessful) throw IOException("Accessory command refused")
        }
    }
    private fun get(url: HttpUrl, limit: Int): ByteArray {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code in setOf(401, 403)) throw HomeAssistantAuthException()
            if (!response.isSuccessful) throw IOException("Home Assistant request failed (${response.code})")
            val body = response.body ?: throw IOException("Missing response")
            if (body.contentLength() > limit) throw IOException("Response too large")
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw IOException("Response too large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }
    fun socket() = HomeAssistantSocket(this, token).also { sockets.add(it) }
    override fun close() {
        closed = true
        sockets.forEach { it.close() }; sockets.clear(); http.dispatcher.cancelAll()
        // TLS close_notify can write to the socket, even after the response was read.
        http.dispatcher.executorService.execute { http.connectionPool.evictAll() }
    }
}

internal class HomeAssistantSocket(private val client: HomeAssistantClient, token: String) : Closeable {
    private class Reply { val latch = CountDownLatch(1); var value: JSONObject? = null }
    private val ids = AtomicInteger()
    private val authenticated = CountDownLatch(1)
    private val replies = ConcurrentHashMap<Int, Reply>()
    @Volatile private var failure: Exception? = null
    @Volatile private var stopped = false
    @Volatile private var event: ((JSONObject) -> Unit)? = null
    private var offerId = -1
    private val socket: WebSocket = client.http.newWebSocket(Request.Builder().url(client.endpoint("api/websocket")).build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 1_048_576 || stopped) { close(); return }
                runCatching {
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                        "auth_ok" -> authenticated.countDown()
                        "auth_invalid" -> fail(HomeAssistantAuthException())
                        "result" -> replies[message.optInt("id")]?.let { it.value = message; it.latch.countDown() }
                        "event" -> if (message.optInt("id") == offerId) message.optJSONObject("event")?.let { event?.invoke(it) }
                    }
                }.onFailure { fail(IOException("Invalid Home Assistant response")) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(if (response?.code in setOf(401, 403)) HomeAssistantAuthException() else IOException("Home Assistant connection interrupted"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { fail(IOException("Home Assistant connection closed")) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })

    private fun fail(error: Exception) {
        if (stopped) return
        failure = error; authenticated.countDown(); replies.values.forEach { it.latch.countDown() }
        event?.invoke(JSONObject().put("type", "error"))
    }
    private fun requestEnvelope(type: String, entity: String?, extra: JSONObject = JSONObject(), subscription: Boolean = false): JSONObject {
        require(if (type == "config/entity_registry/list") entity == null else entity != null && validHomeAssistantCamera(entity))
        check(type in setOf("camera/capabilities", "camera/stream", "camera/webrtc/get_client_config", "camera/webrtc/offer", "config/entity_registry/list"))
        if (!authenticated.await(15, TimeUnit.SECONDS)) throw IOException("Home Assistant authentication timed out")
        failure?.let { throw it }; if (stopped) throw IOException("Closed")
        val id = ids.incrementAndGet()
        val reply = Reply(); replies[id] = reply
        if (subscription) offerId = id
        try {
            val message = extra.put("id", id).put("type", type)
            if (entity != null) message.put("entity_id", entity)
            check(socket.send(message.toString()))
            if (!reply.latch.await(20, TimeUnit.SECONDS)) throw IOException("Camera request timed out")
            failure?.let { throw it }
            val response = reply.value ?: throw IOException("Camera connection closed")
            if (!response.optBoolean("success")) throw IOException("Camera operation unavailable")
            return response
        } finally { replies.remove(id) }
    }
    private fun request(type: String, entity: String, extra: JSONObject = JSONObject(), subscription: Boolean = false): JSONObject =
        requestEnvelope(type, entity, extra, subscription).optJSONObject("result") ?: JSONObject()
    fun entityRegistry(): JSONArray = requestEnvelope("config/entity_registry/list", null).optJSONArray("result")
        ?: throw IOException("Entity registry unavailable")
    fun capabilities(entity: String): Set<String> {
        val array = request("camera/capabilities", entity).optJSONArray("frontend_stream_types") ?: JSONArray()
        return (0 until array.length()).map { array.optString(it) }.filter { it in setOf("hls", "web_rtc") }.toSet()
    }
    fun stream(entity: String) = client.mediaUrl(request("camera/stream", entity, JSONObject().put("format", "hls")).getString("url"))
    fun webRtcConfig(entity: String) = request("camera/webrtc/get_client_config", entity)
    fun offer(entity: String, sdp: String, callback: (JSONObject) -> Unit) {
        require(sdp.length in 1..131072)
        event = callback
        request("camera/webrtc/offer", entity, JSONObject().put("offer", sdp), subscription = true)
    }
    fun candidate(entity: String, session: String, value: JSONObject) {
        require(validHomeAssistantCamera(entity) && session.length in 1..256 && value.toString().length <= 16384)
        if (stopped || failure != null) return
        socket.send(JSONObject().put("id", ids.incrementAndGet()).put("type", "camera/webrtc/candidate")
            .put("entity_id", entity).put("session_id", session).put("candidate", value).toString())
    }
    override fun close() {
        stopped = true; event = null; authenticated.countDown(); replies.values.forEach { it.latch.countDown() }; socket.cancel()
    }
}
