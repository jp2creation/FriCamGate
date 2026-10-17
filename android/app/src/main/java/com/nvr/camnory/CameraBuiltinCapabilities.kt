package com.nvr.camnory

import org.json.JSONArray

/** No matching by label, room, IP address, previous manual links, parent device or relay name. */
internal fun cameraBuiltinEntityIds(camera: String, registry: JSONArray): Set<String> {
    require(validHomeAssistantCamera(camera) && registry.length() <= 100_000)
    val entries = (0 until registry.length()).mapNotNull { registry.optJSONObject(it) }
        .filter { it.isNull("disabled_by") }
    val selected = entries.filter { it.optString("entity_id") == camera }.singleOrNull() ?: return emptySet()
    val device = selected.optString("device_id").takeIf { it.isNotBlank() && it != "null" } ?: return emptySet()
    val platform = selected.optString("platform").takeIf { it.isNotBlank() && it != "null" } ?: return emptySet()
    val sameDevice = entries.filter { it.optString("device_id") == device }
    if (sameDevice.count { validHomeAssistantCamera(it.optString("entity_id")) } != 1) return emptySet()
    val candidates = sameDevice.filter { it.optString("platform") == platform && validCameraAccessory(it.optString("entity_id")) }
    return listOf("light", "siren").mapNotNull { domain ->
        candidates.filter { it.optString("entity_id").startsWith("$domain.") }.singleOrNull()?.optString("entity_id")
    }.toSet()
}
