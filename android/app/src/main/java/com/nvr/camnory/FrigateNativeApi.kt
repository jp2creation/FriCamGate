package com.nvr.camnory

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import pl.droidsonroids.gif.GifDrawable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal open class FrigateNativeApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val localRoute: LocalServerRoute? = null
) : NvrConnector, NvrPlaybackMedia, NvrLiveFrames, NvrEventHistory {
    override fun login(username: String, password: String, callback: (Result<NativeLogin>) -> Unit) {
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

    override fun loadDashboard(token: String, callback: (Result<DashboardData>) -> Unit) {
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

    override fun loadEvents(
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

    override fun searchEvents(token: String, search: EventSearch, now: Double, before: Double?, callback: (Result<List<NativeEvent>>) -> Unit) {
        getJsonArray(token, search.query(now, before)) { result -> callback(result.mapCatching { parseEvents(it) }) }
    }

    override fun loadNvrVersion(token: String, callback: (Result<String>) -> Unit) {
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

    override fun loadBitmap(token: String, url: String, callback: (Bitmap?) -> Unit) {
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

    override fun loadGif(token: String, url: String, callback: (Drawable?) -> Unit) {
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

    open fun setCameraFeature(
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

    override fun openMjpegStream(
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

    open fun requestHlsUrl(token: String, streamName: String, callback: (Result<String>) -> Unit) {
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

    override fun latestFrameUrl(cameraName: String): String = endpoint("/api/${Uri.encode(cameraName)}/latest.jpg?h=360&_=${System.currentTimeMillis()}")
    override fun eventSnapshotUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/snapshot.jpg?h=360")
    override fun eventPreviewUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/preview.gif")
    open fun eventClipUrl(eventId: String): String = endpoint("/api/events/${Uri.encode(eventId)}/clip.mp4")
    override fun eventVodUrl(eventId: String): String = endpoint("/vod/event/${Uri.encode(eventId)}/master.m3u8")
    override fun recordingClipUrl(cameraName: String, startSec: Long, endSec: Long): String =
        endpoint("/api/${Uri.encode(cameraName)}/start/$startSec/end/$endSec/clip.mp4")
    override fun recordingVodUrl(cameraName: String, startSec: Long, endSec: Long, stream: String?): String =
        endpoint(recordingVodPath(cameraName, startSec, endSec, stream))
    override fun recordingSnapshotUrl(cameraName: String, frameTime: Double): String =
        endpoint("/api/${Uri.encode(cameraName)}/recordings/${frameTime.toLong()}/snapshot.webp")
    open fun liveMseWebSocketUrl(streamName: String): String = liveWebSocketUrl(streamName)

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
                tvStreamName = TvStreamPolicy.select(name, camera.optJSONObject("live")?.optJSONObject("streams")?.let { streams ->
                    streams.keys().asSequence().associateWith { streams.optString(it) }
                }.orEmpty()),
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
        if (path.startsWith("http://") || path.startsWith("https://")) return localRoute?.canonical(baseUrl.toHttpUrl(), path.toHttpUrl())?.toString() ?: path
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
