package com.nvr.camnory

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class CameraPtzState(val panTilt: Boolean, val zoom: Boolean, val presets: List<OnvifPtzPreset>) {
    val available get() = panTilt || zoom || presets.isNotEmpty()
}
internal interface CameraPtzDriver : Closeable {
    fun discover(): CameraPtzState
    fun move(direction: String)
    fun stop()
    fun preset(token: String)
}
internal class OnvifCameraPtzDriver(private val camera: OnvifSavedCamera) : CameraPtzDriver {
    private val client = OnvifPtzClient()
    private var catalog: OnvifPtzCatalog? = null
    override fun discover(): CameraPtzState {
        catalog = client.discover(camera)
        return CameraPtzState(catalog?.spaces?.panTilt != null, catalog?.spaces?.zoom != null, catalog?.presets.orEmpty())
    }
    override fun move(direction: String) = client.move(camera, requireNotNull(catalog), direction)
    override fun stop() = client.stop(camera, requireNotNull(catalog))
    override fun preset(token: String) = client.preset(camera, requireNotNull(catalog), token)
    override fun close() = client.close()
}

internal fun frigatePtzState(info: JSONObject): CameraPtzState {
    val features = info.optJSONArray("features")
    val names = (0 until (features?.length() ?: 0)).map { features!!.optString(it) }.toSet()
    val presets = info.optJSONArray("presets")
    return CameraPtzState("pt-r-fov" in names, "pt-r-fov" in names && "zoom-r" in names,
        (0 until (presets?.length() ?: 0)).mapNotNull {
            presets!!.optString(it).takeIf { name -> name.length in 1..160 && name.none(Char::isISOControl) }
                ?.let { name -> OnvifPtzPreset(name, name) }
        }.distinctBy { it.token }.take(128))
}

/** Only finite relative steps and advertised presets. No continuous move, relay or generic command. */
internal class FrigateCameraPtzDriver(private val server: FrigateServer, private val camera: String) : CameraPtzDriver {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()
    private val base = server.baseUrl.trimEnd('/').toHttpUrl()
    @Volatile private var closed = false
    @Volatile private var socket: WebSocket? = null
    private var state: CameraPtzState? = null
    init { require(camera.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) }
    override fun discover(): CameraPtzState {
        check(!closed)
        val url = base.newBuilder().addPathSegments("api").addPathSegment(camera).addPathSegments("ptz/info").build()
        val info = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${server.token}").build()).execute().use {
            if (!it.isSuccessful) throw IOException("PTZ unavailable")
            val source = it.body?.source() ?: throw IOException("Missing PTZ response")
            if (source.request(524289)) throw IOException("PTZ response too large")
            JSONObject(source.readUtf8())
        }
        return frigatePtzState(info).also { state = it }
    }
    override fun move(direction: String) {
        val current = requireNotNull(state)
        val coordinates = when (direction) {
            "left" -> { require(current.panTilt); "-0.1_0_0" }
            "right" -> { require(current.panTilt); "0.1_0_0" }
            "up" -> { require(current.panTilt); "0_0.1_0" }
            "down" -> { require(current.panTilt); "0_-0.1_0" }
            "in" -> { require(current.zoom); "0_0_0.1" }
            "out" -> { require(current.zoom); "0_0_-0.1" }
            else -> throw IllegalArgumentException("Unsupported PTZ direction")
        }
        send("move_relative_$coordinates")
    }
    override fun stop() { require(state?.available == true); send("stop") }
    override fun preset(token: String) {
        require(state?.presets?.any { it.token == token } == true)
        require(discover().presets.any { it.token == token })
        send("preset_$token")
    }
    private fun send(command: String) {
        check(!closed)
        val completed = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val ws = http.newWebSocket(Request.Builder().url(base.newBuilder().addPathSegment("ws").build())
            .header("Authorization", "Bearer ${server.token}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed || !webSocket.send(JSONObject().put("topic", "$camera/ptz").put("payload", command).toString())) {
                    failure.set(IOException("PTZ command not sent")); webSocket.cancel(); completed.countDown(); return
                }
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { completed.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(IOException("PTZ connection failed")); completed.countDown()
            }
        })
        socket = ws
        try {
            if (!completed.await(10, TimeUnit.SECONDS)) throw IOException("PTZ command timed out")
            failure.get()?.let { throw it }
        } finally { ws.cancel(); socket = null }
    }
    override fun close() { closed = true; socket?.cancel(); http.dispatcher.cancelAll(); http.dispatcher.executorService.execute { http.connectionPool.evictAll() } }
}
