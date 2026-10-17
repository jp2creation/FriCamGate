package com.nvr.camnory

import android.content.Context
import org.json.JSONArray

internal object CameraArrangement {
    fun key(source: String, server: String, id: String): String =
        JSONArray(listOf(source, server.trimEnd('/'), id)).toString()

    fun ordered(available: List<String>, saved: List<String>): List<String> =
        (saved.filter { it in available } + available).distinct()

    fun move(order: List<String>, moving: String, target: String, after: Boolean): List<String> {
        if (moving == target || moving !in order || target !in order) return order
        return order.toMutableList().apply {
            remove(moving)
            add(indexOf(target) + if (after) 1 else 0, moving)
        }
    }
}

internal class CameraArrangementStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
    val showSourceIcons get() = prefs.getBoolean("showCameraSourceIcons", true)
    fun savedOrder(): List<String> = TvProviderStorage.read(context)?.order ?: runCatching {
        val array = JSONArray(prefs.getString("cameraDisplayOrder", "[]"))
        (0 until array.length()).map { array.getString(it) }.distinct()
    }.getOrDefault(emptyList())

    fun saveOrder(order: List<String>) {
        if (TvProviderStorage.update(context) { config ->
            TvConfiguration(config.frigate, config.onvif, config.ha, (order + config.order.filter { it !in order }).distinct(), config.hidden, config.ip)
        }) return
        // Keep absent cameras so a temporary disconnect does not reset their position.
        val retained = savedOrder().filter { it !in order }
        prefs.edit().putString("cameraDisplayOrder", JSONArray((order + retained).distinct()).toString()).apply()
    }

    fun hidden(key: String): Boolean = TvProviderStorage.read(context)?.let { key in it.hidden }
        ?: prefs.getStringSet("hiddenSourceCameras", emptySet()).orEmpty().contains(key)
    fun setHidden(key: String, hidden: Boolean) {
        if (TvProviderStorage.update(context) { config ->
            val keys = config.hidden.toMutableSet().apply { if (hidden) add(key) else remove(key) }
            TvConfiguration(config.frigate, config.onvif, config.ha, config.order, keys, config.ip)
        }) return
        val keys = prefs.getStringSet("hiddenSourceCameras", emptySet()).orEmpty().toMutableSet()
        if (hidden) keys.add(key) else keys.remove(key)
        prefs.edit().putStringSet("hiddenSourceCameras", keys).apply()
    }
}
