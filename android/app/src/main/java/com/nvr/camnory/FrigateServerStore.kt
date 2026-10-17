package com.nvr.camnory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class FrigateServer(val id: String, val name: String, val baseUrl: String,
    val username: String, val token: String, val legacy: Boolean = false, val localRoute: LocalServerRoute? = null) {
    fun qualify(value: String) = if (legacy) value else "@$id:$value"
    override fun toString() = "FrigateServer($id, [redacted])"
}

internal fun frigateServerUrl(value: String): String {
    val url = normalizeBaseUrl(value.trim()).toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    return url.toString().trimEnd('/')
}

internal class FrigateServerStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "frigate-servers.enc"))
    private val prefs = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val alias = "com.nvr.camnory.frigate.v1"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<FrigateServer> = synchronized(LOCK) {
        TvProviderStorage.read(context)?.let { return@synchronized it.frigate }
        if (!file.baseFile.exists()) {
            val url = prefs.getString("baseUrl", null) ?: return@synchronized emptyList()
            val token = prefs.getString("token", null) ?: return@synchronized emptyList()
            return@synchronized listOf(FrigateServer("legacy", android.net.Uri.parse(url).host ?: "Frigate", frigateServerUrl(url),
                prefs.getString("username", "admin").orEmpty(), token, true))
        }
        require(file.baseFile.length() in 29..1_048_576)
        val bytes = file.readFully(); require(bytes.size in 29..1_048_576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(array.length() <= 20)
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            FrigateServer(it.getString("id"), it.getString("name"), frigateServerUrl(it.getString("baseUrl")),
                it.getString("username"), it.getString("token"), it.optBoolean("legacy"), LocalServerRoute.decode(it.optJSONObject("localRoute")))
        } }.also(::validate)
    }
    fun save(servers: List<FrigateServer>) = synchronized(LOCK) {
        validate(servers)
        if (TvProviderStorage.update(context) { TvConfiguration(servers, it.onvif, it.ha, it.order, it.hidden, it.ip) }) return@synchronized
        val array = JSONArray()
        servers.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", frigateServerUrl(it.baseUrl))
            .put("username", it.username).put("token", it.token).put("legacy", it.legacy).put("localRoute", it.localRoute?.json())) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try { stream.write(cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        check(load() == servers.map { it.copy(baseUrl = frigateServerUrl(it.baseUrl)) })
        // Retain only the legacy token for backwards compatibility; new tokens stay encrypted.
        val edit = prefs.edit()
        servers.firstOrNull()?.let {
            edit.putString("baseUrl", it.baseUrl).putString("username", it.username)
            if (it.legacy) edit.putString("token", it.token) else edit.remove("token")
        }
            ?: edit.remove("token")
        check(edit.commit())
    }
    fun upsert(server: FrigateServer) = synchronized(LOCK) {
        val current = load()
        save(if (current.any { it.id == server.id }) current.map { if (it.id == server.id) server else it } else current + server)
    }
    fun remove(id: String) = synchronized(LOCK) { save(load().filter { it.id != id }) }
    companion object {
        private val LOCK = Any()
        fun newId() = UUID.randomUUID().toString()
        private fun validate(servers: List<FrigateServer>) {
            servers.forEach { it.localRoute?.validate() }
            require(servers.size <= 20 && servers.count { it.legacy } <= 1)
            require(servers.map { it.id }.distinct().size == servers.size)
            require(servers.map { frigateServerUrl(it.baseUrl) }.distinct().size == servers.size)
            require(servers.all { it.id.matches(Regex("[a-zA-Z0-9-]{1,64}")) && it.name.isNotBlank() && it.name.length <= 160 &&
                it.baseUrl.length <= 4096 && it.username.isNotBlank() && it.username.length <= 160 &&
                it.token.isNotBlank() && it.token.length <= 32768 && !it.token.contains('\n') && !it.token.contains('\r') })
        }
    }
}
