package com.nvr.camnory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class TvConfiguration(val frigate: List<FrigateServer>, val onvif: List<OnvifSavedCamera>,
    val ha: HomeAssistantConnection?, val order: List<String>, val hidden: Set<String>) {
    override fun toString() = "TvConfiguration([redacted])"
    fun encode(): ByteArray {
        val servers = JSONArray()
        frigate.forEach { servers.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.baseUrl)
            .put("user", it.username).put("token", it.token).put("legacy", it.legacy).put("localRoute", it.localRoute?.json())) }
        val cameras = JSONArray()
        onvif.forEach { cameras.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.endpoint)
            .put("user", it.credentials.username).put("password", it.credentials.password).put("profile", it.profileToken)) }
        val assistant = ha?.let { connection -> JSONObject().put("url", connection.baseUrl).put("token", connection.token).put("localRoute", connection.localRoute?.json())
            .put("cameras", JSONArray().apply { connection.cameras.forEach { put(JSONObject()
                .put("id", it.entityId).put("name", it.name)) } }) }
        return JSONObject().put("version", 1).put("frigate", servers).put("onvif", cameras)
            .put("ha", assistant ?: JSONObject.NULL).put("order", JSONArray(order)).put("hidden", JSONArray(hidden.toList()))
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }
    companion object {
        const val MAX_BYTES = 524288
        private fun text(o: JSONObject, key: String, max: Int, blank: Boolean = false): String =
            o.getString(key).also { require(it.length <= max && (blank || it.isNotBlank()) && '\u0000' !in it) }
        private fun array(o: JSONObject, key: String, max: Int) = o.getJSONArray(key).also { require(it.length() <= max) }
        private fun strings(o: JSONObject, key: String): List<String> = array(o, key, 500).let { a ->
            (0 until a.length()).map { a.getString(it).also { value -> require(value.length <= 4096) } }.distinct()
        }
        fun decode(bytes: ByteArray): TvConfiguration {
            require(bytes.size in 2..MAX_BYTES)
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            require(root.getInt("version") == 1)
            require(root.keys().asSequence().toSet() == setOf("version", "frigate", "onvif", "ha", "order", "hidden"))
            val servers = array(root, "frigate", 20).let { a -> (0 until a.length()).map { index ->
                val o = a.getJSONObject(index)
                val id = text(o, "id", 64).also { require(it.matches(Regex("[a-zA-Z0-9-]+"))) }
                val token = text(o, "token", 32768).also { require('\n' !in it && '\r' !in it) }
                FrigateServer(id, text(o, "name", 160), frigateServerUrl(text(o, "url", 4096)),
                    text(o, "user", 160), token, o.getBoolean("legacy"), LocalServerRoute.decode(o.optJSONObject("localRoute")))
            } }
            require(servers.map { it.id }.distinct().size == servers.size && servers.count { it.legacy } <= 1)
            require(servers.map { it.baseUrl }.distinct().size == servers.size)
            val cameras = array(root, "onvif", 200).let { a -> (0 until a.length()).map { index ->
                val o = a.getJSONObject(index)
                OnvifSavedCamera(text(o, "id", 160), text(o, "name", 160), onvifEndpoint(text(o, "url", 4096)).toASCIIString(),
                    OnvifCredentials(text(o, "user", 160, true), text(o, "password", 4096, true)),
                    if (o.isNull("profile")) null else text(o, "profile", 512))
            } }
            require(cameras.map { it.id }.distinct().size == cameras.size)
            val ha = if (root.isNull("ha")) null else root.getJSONObject("ha").let { o ->
                val haCameras = array(o, "cameras", 200).let { a -> (0 until a.length()).map { index ->
                    val camera = a.getJSONObject(index)
                    HomeAssistantCamera(text(camera, "id", 255).also { require(validHomeAssistantCamera(it)) }, text(camera, "name", 160), true)
                } }
                require(haCameras.map { it.entityId }.distinct().size == haCameras.size)
                HomeAssistantConnection(homeAssistantBaseUrl(text(o, "url", 4096)), text(o, "token", 32768)
                    .also { require('\r' !in it && '\n' !in it) }, haCameras, LocalServerRoute.decode(o.optJSONObject("localRoute")))
            }
            return TvConfiguration(servers, cameras, ha, strings(root, "order"), strings(root, "hidden").toSet())
        }
        fun fromPhone(context: Context): TvConfiguration {
            TvProviderStorage.read(context)?.let { return it }
            val servers = FrigateServerStore(context).load()
            val settings = context.getSharedPreferences("camgate-settings", 0)
            val legacyPrefs = context.getSharedPreferences("fricamgate-native", 0)
            val hidden = settings.getStringSet("hiddenSourceCameras", emptySet()).orEmpty().toMutableSet()
            servers.forEach { s -> legacyPrefs.getStringSet("hiddenCameras:${s.baseUrl}", emptySet()).orEmpty().forEach {
                hidden.add(CameraArrangement.key("frigate", s.baseUrl, it))
            } }
            return TvConfiguration(servers, OnvifCameraStore(context).load(), HomeAssistantStore(context).load(),
                CameraArrangementStore(context).savedOrder(), hidden).let { decode(it.encode()) }
        }
    }
}

/** One atomic file: a failed or interrupted import never leaves half a configuration. */
internal class TvConfigurationStore(context: Context, storageName: String = "tv-config.enc") {
    private val file = AtomicFile(File(context.noBackupFilesDir, storageName))
    private fun key(): SecretKey {
        val alias = "com.nvr.camnory.tv.v1"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): TvConfiguration? = synchronized(LOCK) {
        if (!file.baseFile.exists()) return@synchronized null
        require(file.baseFile.length() in 29..TvConfiguration.MAX_BYTES.toLong() + 28)
        val data = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        TvConfiguration.decode(cipher.doFinal(data.copyOfRange(12, data.size)))
    }
    fun save(config: TvConfiguration) = synchronized(LOCK) {
        val bytes = config.encode()
        TvConfiguration.decode(bytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try { stream.write(cipher.iv + cipher.doFinal(bytes)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        finally { bytes.fill(0) }
    }
    fun update(change: (TvConfiguration) -> TvConfiguration) = synchronized(LOCK) {
        save(change(load() ?: TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet())))
    }
    companion object { private val LOCK = Any() }
}
