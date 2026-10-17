package com.nvr.camnory

import android.content.Context
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class OnvifRelay(val token: String, val mode: String)
internal class OnvifRelayCatalog(val endpoint: String, val offset: Long, val relays: List<OnvifRelay>)
internal fun parseOnvifRelays(root: OnvifXmlNode): List<OnvifRelay> = root.descendants("RelayOutputs").mapNotNull {
    val token = it.attributes["token"]?.takeIf { value -> value.length in 1..256 && value.none(Char::isISOControl) } ?: return@mapNotNull null
    OnvifRelay(token, it.descendant("Mode")?.text.orEmpty().take(40))
}.distinctBy { it.token }.take(128)

/** No relay is actuated during discovery, playback or binding. */
internal class OnvifRelayClient {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(10, TimeUnit.SECONDS).build()
    private val reader = OnvifReadOnlyClient(http)
    private val soap = OnvifSoapTransport(http)
    fun discover(camera: OnvifSavedCamera): OnvifRelayCatalog {
        val connection = reader.connect(camera.endpoint, camera.credentials)
        val services = soap.request(camera.endpoint, camera.endpoint, camera.credentials, OnvifSoapOperation.Services, connection.clockOffsetMs)
        val endpoint = services.descendants("Service").firstOrNull {
            it.child("Namespace")?.text == OnvifSoapOperation.Relays.namespace
        }?.child("XAddr")?.text ?: return OnvifRelayCatalog(camera.endpoint, connection.clockOffsetMs, emptyList())
        val relays = soap.request(camera.endpoint, endpoint, camera.credentials, OnvifSoapOperation.Relays, connection.clockOffsetMs)
        return OnvifRelayCatalog(endpoint, connection.clockOffsetMs, parseOnvifRelays(relays))
    }
    fun set(camera: OnvifSavedCamera, catalog: OnvifRelayCatalog, token: String, active: Boolean) {
        require(catalog.relays.any { it.token == token })
        val current = soap.request(camera.endpoint, catalog.endpoint, camera.credentials, OnvifSoapOperation.Relays, catalog.offset)
        if (parseOnvifRelays(current).none { it.token == token }) throw IOException("Relay no longer available")
        soap.request(camera.endpoint, catalog.endpoint, camera.credentials, OnvifSoapOperation.SetRelay, catalog.offset, token, active)
    }
    fun close() {
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.execute { http.connectionPool.evictAll() }
    }
}

internal class OnvifRelayBindings(context: Context, camera: OnvifSavedCamera) {
    private val prefs = context.getSharedPreferences("onvif-relay-bindings", 0)
    private val prefix = MessageDigest.getInstance("SHA-256").digest("${camera.endpoint}\n${camera.id}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun get(kind: String): String? = prefs.getString("$prefix:$kind", null)
    fun set(kind: String, token: String?) {
        require(kind in setOf("light", "siren"))
        require(token == null || token.length in 1..256 && token.none(Char::isISOControl))
        check(prefs.edit().apply { if (token == null) remove("$prefix:$kind") else putString("$prefix:$kind", token) }.commit())
    }
}
