package com.nvr.camnory

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal val ONVIF_IMAGE_FIELDS = setOf("Brightness", "Contrast", "ColorSaturation", "Sharpness")
internal data class OnvifImageSetting(val key: String, val value: Double, val min: Double, val max: Double)
internal data class OnvifDeviceSettings(val information: Map<String, String>, val profiles: List<OnvifProfile>,
    val imagingEndpoint: String?, val source: String?, val offset: Long, val imaging: List<OnvifImageSetting>,
    val imagingFailed: Boolean, val services: List<String>, val readOnlyImage: Map<String, String> = emptyMap())

internal fun onvifSettingValues(root: OnvifXmlNode): Map<String, String> {
    val values = linkedMapOf<String, String>()
    fun walk(node: OnvifXmlNode, prefix: String) {
        node.children.forEach { child ->
            val path = if (prefix.isEmpty()) child.name else "$prefix / ${child.name}"
            if (child.children.isEmpty() && child.text.isNotBlank() && values.size < 128) values[path] = child.text.take(256)
            else walk(child, path)
        }
    }
    walk(root, "")
    return values
}

internal fun parseOnvifImageSettings(settings: OnvifXmlNode, options: OnvifXmlNode): List<OnvifImageSetting> =
    ONVIF_IMAGE_FIELDS.mapNotNull { key ->
        val value = settings.descendant(key)?.text?.toDoubleOrNull() ?: return@mapNotNull null
        val range = options.descendant(key) ?: return@mapNotNull null
        val min = range.child("Min")?.text?.toDoubleOrNull() ?: return@mapNotNull null
        val max = range.child("Max")?.text?.toDoubleOrNull() ?: return@mapNotNull null
        if (!listOf(value, min, max).all(Double::isFinite) || min >= max || value !in min..max) null
        else OnvifImageSetting(key, value, min, max)
    }

/** Reads never change configuration. Only four explicitly supported scalar image fields can be written. */
internal class OnvifDeviceSettingsClient {
    @Volatile private var cancelled = false
    @Volatile private var closed = false
    private fun active() {
        if (closed || cancelled || Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
    }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()
    private val reader = OnvifReadOnlyClient(http)
    private val soap = OnvifSoapTransport(http)
    fun load(camera: OnvifSavedCamera): OnvifDeviceSettings {
        cancelled = false; active()
        val connection = runCatching { reader.connect(camera.endpoint, camera.credentials, supportedOnly = false) }.getOrNull()
        active()
        val offset = connection?.clockOffsetMs ?: 0
        val info = soap.request(camera.endpoint, camera.endpoint, camera.credentials, OnvifSoapOperation.DeviceInfo, offset)
        val information = listOf("Manufacturer", "Model", "SerialNumber", "FirmwareVersion", "HardwareId").associateWith {
            info.child(it)?.text.orEmpty().take(256)
        }
        active()
        val services = soap.request(camera.endpoint, camera.endpoint, camera.credentials, OnvifSoapOperation.Services, offset)
        val endpoint = services.descendants("Service").firstOrNull { it.child("Namespace")?.text == OnvifSoapOperation.ImageSettings.namespace }
            ?.child("XAddr")?.text
        val profile = if (camera.profileToken != null) connection?.profiles?.firstOrNull { it.token == camera.profileToken }
            else connection?.profiles?.firstOrNull()
        val source = profile?.videoSource
        var rawImage = emptyMap<String, String>()
        val fields = if (endpoint != null && source != null) runCatching { readImage(camera, endpoint, source, offset) { rawImage = it } } else Result.success(emptyList())
        return OnvifDeviceSettings(information, connection?.profiles.orEmpty(), endpoint, source, offset, fields.getOrDefault(emptyList()),
            fields.isFailure, services.descendants("Service").mapNotNull { it.child("Namespace")?.text }.distinct().take(32), rawImage)
    }
    private fun readImage(camera: OnvifSavedCamera, endpoint: String, source: String, offset: Long,
        observed: ((Map<String, String>) -> Unit)? = null): List<OnvifImageSetting> {
        active()
        val settings = soap.request(camera.endpoint, endpoint, camera.credentials, OnvifSoapOperation.ImageSettings, offset, source)
        settings.child("ImagingSettings")?.let { observed?.invoke(onvifSettingValues(it)) }
        active()
        val options = soap.request(camera.endpoint, endpoint, camera.credentials, OnvifSoapOperation.ImageOptions, offset, source)
        return parseOnvifImageSettings(settings, options)
    }
    fun setImage(camera: OnvifSavedCamera, state: OnvifDeviceSettings, key: String, value: Double) {
        require(key in ONVIF_IMAGE_FIELDS && value.isFinite())
        val endpoint = requireNotNull(state.imagingEndpoint)
        val source = requireNotNull(state.source)
        val current = readImage(camera, endpoint, source, state.offset).firstOrNull { it.key == key }
            ?: error("Image setting unavailable")
        require(value in current.min..current.max)
        active()
        soap.request(camera.endpoint, endpoint, camera.credentials, OnvifSoapOperation.SetImage, state.offset, source, imageChange = key to value)
    }
    fun cancel() { cancelled = true; http.dispatcher.cancelAll() }
    fun close() { closed = true; cancel(); http.dispatcher.executorService.execute { http.connectionPool.evictAll() } }
}
