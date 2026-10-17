package com.nvr.camnory

import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val ONVIF_RELATIVE_PAN_TILT = "http://www.onvif.org/ver10/tptz/PanTiltSpaces/TranslationGenericSpace"
internal const val ONVIF_RELATIVE_ZOOM = "http://www.onvif.org/ver10/tptz/ZoomSpaces/TranslationGenericSpace"
internal data class OnvifPtzSpaces(val panTilt: String?, val zoom: String?)
internal data class OnvifPtzPreset(val token: String, val name: String)
internal data class OnvifPtzTranslation(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0,
    val panTiltSpace: String? = null, val zoomSpace: String? = null)
internal data class OnvifPtzCatalog(val endpoint: String, val profile: String, val offset: Long,
    val spaces: OnvifPtzSpaces, val presets: List<OnvifPtzPreset>)

internal fun parseOnvifPtzSpaces(root: OnvifXmlNode): OnvifPtzSpaces {
    fun range(node: OnvifXmlNode?, axis: String): Boolean {
        val min = node?.child(axis)?.child("Min")?.text?.toDoubleOrNull() ?: return false
        val max = node.child(axis)?.child("Max")?.text?.toDoubleOrNull() ?: return false
        return min.isFinite() && max.isFinite() && min <= -0.1 && max >= 0.1
    }
    val pan = root.descendants("RelativePanTiltTranslationSpace").firstOrNull {
        it.child("URI")?.text == ONVIF_RELATIVE_PAN_TILT && range(it, "XRange") && range(it, "YRange")
    }
    val zoom = root.descendants("RelativeZoomTranslationSpace").firstOrNull {
        it.child("URI")?.text == ONVIF_RELATIVE_ZOOM && range(it, "XRange")
    }
    return OnvifPtzSpaces(pan?.child("URI")?.text, zoom?.child("URI")?.text)
}

internal fun parseOnvifPtzPresets(root: OnvifXmlNode): List<OnvifPtzPreset> = root.descendants("Preset").mapNotNull {
    val token = it.attributes["token"]?.takeIf { value -> value.length in 1..256 && value.none(Char::isISOControl) } ?: return@mapNotNull null
    OnvifPtzPreset(token, it.child("Name")?.text?.take(160)?.ifBlank { token } ?: token)
}.distinctBy { it.token }.take(128)

/** Discovery only reads. Every move is a finite relative step, never a continuous movement. */
internal class OnvifPtzClient {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()
    private val reader = OnvifReadOnlyClient(http)
    private val soap = OnvifSoapTransport(http)
    fun discover(camera: OnvifSavedCamera): OnvifPtzCatalog? {
        val connection = reader.connect(camera.endpoint, camera.credentials)
        val profile = if (camera.profileToken != null) connection.profiles.firstOrNull { it.token == camera.profileToken }
            else connection.profiles.firstOrNull()
        val configuration = profile?.ptzConfiguration ?: return null
        val services = soap.request(camera.endpoint, camera.endpoint, camera.credentials, OnvifSoapOperation.Services, connection.clockOffsetMs)
        val endpoint = services.descendants("Service").firstOrNull { it.child("Namespace")?.text == OnvifSoapOperation.PtzOptions.namespace }
            ?.child("XAddr")?.text ?: return null
        val options = soap.request(camera.endpoint, endpoint, camera.credentials, OnvifSoapOperation.PtzOptions, connection.clockOffsetMs, configuration)
        // Presets are optional; a failed listing must never invent a position or move the camera.
        val presets = runCatching { parseOnvifPtzPresets(soap.request(camera.endpoint, endpoint, camera.credentials,
            OnvifSoapOperation.PtzPresets, connection.clockOffsetMs, profile.token)) }.getOrDefault(emptyList())
        return OnvifPtzCatalog(endpoint, profile.token, connection.clockOffsetMs, parseOnvifPtzSpaces(options), presets)
    }
    fun move(camera: OnvifSavedCamera, catalog: OnvifPtzCatalog, direction: String) {
        val step = when (direction) {
            "left" -> OnvifPtzTranslation(x = -0.1, panTiltSpace = catalog.spaces.panTilt)
            "right" -> OnvifPtzTranslation(x = 0.1, panTiltSpace = catalog.spaces.panTilt)
            "up" -> OnvifPtzTranslation(y = 0.1, panTiltSpace = catalog.spaces.panTilt)
            "down" -> OnvifPtzTranslation(y = -0.1, panTiltSpace = catalog.spaces.panTilt)
            "in" -> OnvifPtzTranslation(z = 0.1, zoomSpace = catalog.spaces.zoom)
            "out" -> OnvifPtzTranslation(z = -0.1, zoomSpace = catalog.spaces.zoom)
            else -> throw IllegalArgumentException("Unsupported PTZ direction")
        }
        require(step.panTiltSpace != null || step.zoomSpace != null)
        soap.request(camera.endpoint, catalog.endpoint, camera.credentials, OnvifSoapOperation.PtzMove, catalog.offset, catalog.profile, ptzMove = step)
    }
    fun preset(camera: OnvifSavedCamera, catalog: OnvifPtzCatalog, token: String) {
        require(catalog.presets.any { it.token == token })
        val current = parseOnvifPtzPresets(soap.request(camera.endpoint, catalog.endpoint, camera.credentials,
            OnvifSoapOperation.PtzPresets, catalog.offset, catalog.profile))
        if (current.none { it.token == token }) throw IOException("Preset unavailable")
        soap.request(camera.endpoint, catalog.endpoint, camera.credentials, OnvifSoapOperation.PtzPreset, catalog.offset,
            catalog.profile, presetToken = token)
    }
    fun stop(camera: OnvifSavedCamera, catalog: OnvifPtzCatalog) {
        soap.request(camera.endpoint, catalog.endpoint, camera.credentials, OnvifSoapOperation.PtzStop, catalog.offset, catalog.profile)
    }
    fun close() { http.dispatcher.cancelAll(); http.dispatcher.executorService.execute { http.connectionPool.evictAll() } }
}
