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

internal class HomeAssistantStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "home-assistant.enc"))
    private fun key(): SecretKey {
        val alias = "com.nvr.camnory.homeassistant.v1"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): HomeAssistantConnection? {
        TvProviderStorage.read(context)?.let { return it.ha }
        if (!file.baseFile.exists()) return null
        require(file.baseFile.length() in 29..1_048_576)
        val bytes = file.readFully(); require(bytes.size in 29..1_048_576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val value = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val cameras = value.getJSONArray("cameras"); require(cameras.length() <= 200)
        return HomeAssistantConnection(homeAssistantBaseUrl(value.getString("baseUrl")), value.getString("token"),
            (0 until cameras.length()).map { cameras.getJSONObject(it) }.map {
                val entity = it.getString("entityId"); require(validHomeAssistantCamera(entity))
                HomeAssistantCamera(entity, it.getString("name").take(160), it.optBoolean("available", true))
            }, LocalServerRoute.decode(value.optJSONObject("localRoute")))
    }
    fun save(connection: HomeAssistantConnection?) {
        if (TvProviderStorage.update(context) { it.copy(ha = connection) }) return
        if (connection == null) { file.delete(); return }
        require(connection.cameras.size <= 200 && connection.cameras.all { validHomeAssistantCamera(it.entityId) })
        val cameras = JSONArray()
        connection.cameras.forEach { cameras.put(JSONObject().put("entityId", it.entityId).put("name", it.name).put("available", it.available)) }
        connection.localRoute?.validate()
        val value = JSONObject().put("baseUrl", homeAssistantBaseUrl(connection.baseUrl)).put("token", connection.token).put("cameras", cameras)
            .put("localRoute", connection.localRoute?.json())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))); file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
