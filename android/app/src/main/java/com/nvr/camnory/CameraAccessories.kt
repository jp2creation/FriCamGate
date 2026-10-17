package com.nvr.camnory

import org.json.JSONArray

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
