package com.nvr.camnory

import android.content.Context
import java.net.URI
import org.json.JSONObject

internal fun ipCameraUrl(value: String): String {
    val text = value.trim()
    require(text.isNotEmpty() && text.length <= 4096 && text.none { it.code < 32 || it.code == 127 })
    val uri = URI(if ("://" in text) text else "http://$text")
    require(uri.scheme in setOf("rtsp", "http", "https") && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null && uri.fragment == null && uri.port in -1..65535 && uri.port != 0)
    return uri.toASCIIString()
}

internal class IpCamera(val id: String, val name: String, val url: String, val username: String = "", val password: String = "") {
    init {
        require(id.isNotBlank() && id.length <= 160 && name.isNotBlank() && name.length <= 160)
        require(url == ipCameraUrl(url))
        require(username.length <= 160 && password.length <= 4096 && (username + password).none { it.code < 32 || it.code == 127 })
        require(password.isEmpty() || username.isNotBlank())
    }
    override fun toString() = "IpCamera([redacted])"
    fun json() = JSONObject().put("id", id).put("name", name).put("url", url).put("user", username).put("password", password)
    companion object {
        fun decode(o: JSONObject) = IpCamera(o.getString("id"), o.getString("name"), ipCameraUrl(o.getString("url")), o.getString("user"), o.getString("password"))
    }
}

internal class IpCameraStore(private val context: Context, private val storageName: String = "ip-cameras.enc") {
    private val store = TvConfigurationStore(context, storageName)
    fun load(): List<IpCamera> = (if (storageName == "ip-cameras.enc") TvProviderStorage.read(context)?.ip else null) ?: store.load()?.ip.orEmpty()
    fun save(cameras: List<IpCamera>) {
        require(cameras.size <= 200 && cameras.map { it.id }.distinct().size == cameras.size)
        if (storageName == "ip-cameras.enc" && TvProviderStorage.update(context) { TvConfiguration(it.frigate, it.onvif, it.ha, it.order, it.hidden, cameras) }) return
        store.save(TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet(), cameras))
    }
}
