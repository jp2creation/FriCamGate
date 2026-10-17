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

internal class OnvifSavedCamera(val id: String, val name: String, val endpoint: String, val credentials: OnvifCredentials,
    val profileToken: String? = null) {
    override fun toString() = "OnvifSavedCamera([redacted])"
}

internal class OnvifCameraStore(private val context: Context, private val storageName: String = "onvif-cameras.enc") {
    private val file = AtomicFile(File(context.noBackupFilesDir, storageName))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<OnvifSavedCamera> {
        if (storageName == "onvif-cameras.enc") TvProviderStorage.read(context)?.let { return it.onvif }
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.readFully()
        require(bytes.size in 29..1_048_576)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val array = JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            OnvifSavedCamera(o.getString("id"), o.getString("name"), o.getString("endpoint"),
                OnvifCredentials(o.getString("username"), o.getString("password")),
                o.optString("profileToken").takeIf { it.isNotBlank() })
        }
    }
    fun save(cameras: List<OnvifSavedCamera>) {
        if (storageName == "onvif-cameras.enc" && TvProviderStorage.update(context) { TvConfiguration(it.frigate, cameras, it.ha, it.order, it.hidden) }) return
        val array = JSONArray()
        cameras.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("endpoint", it.endpoint)
            .put("username", it.credentials.username).put("password", it.credentials.password)
            .put("profileToken", it.profileToken)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    companion object { private const val ALIAS = "com.nvr.camnory.onvif.v1" }
}
