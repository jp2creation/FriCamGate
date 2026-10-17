package com.nvr.camnory

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

internal fun validCameraAccessory(entity: String) = entity.length <= 255 && Regex("(light|siren)\\.[a-z0-9_]+").matches(entity)
internal data class CameraAccessory(val entityId: String, val name: String, val enabled: Boolean?, val canOn: Boolean, val canOff: Boolean) {
    val domain get() = entityId.substringBefore('.')
}
internal fun parseCameraAccessories(states: JSONArray): List<CameraAccessory> {
    require(states.length() <= 100_000)
    return (0 until states.length()).mapNotNull { index ->
        val state = states.optJSONObject(index) ?: return@mapNotNull null
        val entity = state.optString("entity_id")
        if (!validCameraAccessory(entity)) return@mapNotNull null
        val attrs = state.optJSONObject("attributes")
        val enabled = when (state.optString("state")) { "on" -> true; "off" -> false; else -> null }
        val features = attrs?.optInt("supported_features", 0) ?: 0
        CameraAccessory(entity, attrs?.optString("friendly_name")?.filterNot(Char::isISOControl)?.take(160)?.takeIf(String::isNotBlank) ?: entity,
            enabled, entity.startsWith("light.") || features and 1 != 0, entity.startsWith("light.") || features and 2 != 0)
    }.distinctBy { it.entityId }.sortedBy { it.name.lowercase() }
}
internal class CameraAccessoryBindings(context: Context, cameraKey: String, baseUrl: String) {
    private val prefs = context.getSharedPreferences("camera-accessory-bindings", Context.MODE_PRIVATE)
    private val prefix = MessageDigest.getInstance("SHA-256").digest("$baseUrl\n$cameraKey".toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun get(domain: String): String? = prefs.getString("$prefix:$domain", null)?.takeIf { validCameraAccessory(it) && it.substringBefore('.') == domain }
    fun set(domain: String, entity: String?) {
        require(domain in setOf("light", "siren"))
        require(entity == null || validCameraAccessory(entity) && entity.substringBefore('.') == domain)
        check(prefs.edit().apply { if (entity == null) remove("$prefix:$domain") else putString("$prefix:$domain", entity) }.commit())
    }
}
