package com.nvr.camnory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object TransferAppearance {
    private val choices = mapOf(
        "theme" to setOf("system", "dark", "light"), "accent" to setOf("red", "pink", "blue", "green", "violet", "ochre"),
        "backgroundStyle" to BackgroundStyle.values().map { it.preference }.toSet(),
        "textSize" to setOf("small", "standard", "large"),
        "homeCameraLayout" to HomeCameraLayout.values().map { it.preference }.toSet(),
        "tabletCameraLayout" to HomeCameraLayout.values().map { it.preference }.toSet(),
        "tvCameraLayout" to TvGridLayout.values().map { it.preference }.toSet(),
        "activityBriefingStyle" to ActivityBriefingStyle.values().map { it.preference }.toSet(),
        "timelineColor" to setOf("accent", "red", "blue", "green", "violet", "ochre"))
    private val toggles = setOf("oledBlack", "reducedMotion", "showCameraSourceIcons", "showHomeEvents", "showHomeCameraToolbar", "showHeaderChat", "showHeaderAlerts") +
        CameraButton.values().map { "cameraButton:${it.preference}" }
    fun validate(values: Map<String, Any>): Map<String, Any> = values.also {
        require(it.size <= choices.size + toggles.size)
        it.forEach { (key, value) -> require(if (key in toggles) value is Boolean else value is String && value in choices[key].orEmpty()) }
    }
    fun read(context: Context, includeDefaults: Boolean = false): Map<String, Any> {
        val prefs = context.getSharedPreferences("camgate-settings", 0)
        val stored = prefs.all.filter { (key, value) -> if (key in toggles) value is Boolean else value is String && value in choices[key].orEmpty() }.mapValues { requireNotNull(it.value) }
        if (!includeDefaults) return validate(stored)
        val defaults: Map<String, Any> = mapOf("theme" to "system", "accent" to "pink", "backgroundStyle" to "automatic", "textSize" to "standard",
            "homeCameraLayout" to "column", "tabletCameraLayout" to "two_columns", "tvCameraLayout" to "2x2", "activityBriefingStyle" to "floating", "timelineColor" to "accent") +
            toggles.associateWith { key -> when (key) {
                "oledBlack", "reducedMotion" -> false
                "showHomeEvents", "showHomeCameraToolbar", "showHeaderChat", "showHeaderAlerts" -> !TelevisionMode.enabled(context)
                else -> true
            } }
        return validate(defaults + stored)
    }
    fun write(context: Context, values: Map<String, Any>, replace: Boolean) {
        validate(values)
        val edit = context.getSharedPreferences("camgate-settings", 0).edit()
        if (replace) (choices.keys + toggles).forEach { edit.remove(it) }
        values.forEach { (key, value) -> if (value is Boolean) edit.putBoolean(key, value) else edit.putString(key, value as String) }
        check(edit.commit())
    }
}

/** A bounded proposal, never a device command or an automatic synchronization. */
internal data class ConfigurationTransfer(val config: TvConfiguration?, val catalog: CameraCatalog?, val appearance: Map<String, Any>?) {
    override fun toString() = "ConfigurationTransfer([redacted])"
    fun encode(): ByteArray = JSONObject().put("transfer", 1)
        .put("config", config?.let { JSONObject(String(it.encode(), Charsets.UTF_8)) } ?: JSONObject.NULL)
        .put("catalog", catalog?.let { JSONObject(it.encode()) } ?: JSONObject.NULL)
        .put("appearance", appearance?.let { JSONObject(TransferAppearance.validate(it)) } ?: JSONObject.NULL)
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= TvConfiguration.MAX_BYTES) }

    companion object {
        fun decode(bytes: ByteArray): ConfigurationTransfer {
            require(bytes.size in 2..TvConfiguration.MAX_BYTES)
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (!root.has("transfer")) return ConfigurationTransfer(TvConfiguration.decode(bytes), null, null)
            require(root.getInt("transfer") == 1 && root.keys().asSequence().toSet() == setOf("transfer", "config", "catalog", "appearance"))
            val config = if (root.isNull("config")) null else TvConfiguration.decode(root.getJSONObject("config").toString().toByteArray(Charsets.UTF_8))
            val catalog = if (root.isNull("catalog")) null else CameraCatalog.decode(root.getJSONObject("catalog").toString())
            val appearance = if (root.isNull("appearance")) null else root.getJSONObject("appearance").let { o ->
                TransferAppearance.validate(o.keys().asSequence().associateWith { o.get(it) })
            }
            require(config != null || appearance != null)
            require(catalog == null || config != null)
            return ConfigurationTransfer(config, catalog, appearance)
        }
        fun select(source: TvConfiguration, catalog: CameraCatalog, keys: Set<String>, appearance: Map<String, Any>?,
            includeCameras: Boolean, includeCategories: Boolean = true): ConfigurationTransfer {
            if (!includeCameras) return ConfigurationTransfer(null, null, appearance?.let(TransferAppearance::validate)).also { require(appearance != null) }
            require(keys.isNotEmpty() && keys.size <= 500)
            val identities = keys.map { JSONArray(it).also { key -> require(key.length() == 3) } }
            val frigate = source.frigate.filter { s -> identities.any { it.getString(0) == "frigate" && it.getString(1) == s.baseUrl } }
            val scope = frigate.associate { s -> s.baseUrl to identities.filter { it.getString(0) == "frigate" && it.getString(1) == s.baseUrl }.map { it.getString(2) }.toSet() }
            val onvif = source.onvif.filter { CameraArrangement.key("onvif", "", it.id) in keys }
            val ip = source.ip.filter { CameraArrangement.key("ip", "", it.id) in keys }
            val ha = source.ha?.let { c -> HomeAssistantConnection(c.baseUrl, c.token, c.cameras.filter { CameraArrangement.key("ha", c.baseUrl, it.entityId) in keys }, c.localRoute) }?.takeIf { it.cameras.isNotEmpty() }
            val config = TvConfiguration(frigate, onvif, ha, (source.order.filter { it in keys } + keys).distinct(), source.hidden.intersect(keys), ip, scope)
            val labels = catalog.labels.filterKeys { it in keys }.mapValues { (_, value) -> if (includeCategories) value else value.copy(categories = emptySet()) }
            val used = labels.values.flatMap { it.categories }.toSet()
            val metadata = CameraCatalog(catalog.categories.filter { it.id in used }, labels)
            return ConfigurationTransfer(TvConfiguration.decode(config.encode()), metadata, appearance?.let(TransferAppearance::validate))
        }
        fun mergeConfig(old: TvConfiguration, incoming: TvConfiguration): TvConfiguration {
            fun <T> merge(a: List<T>, b: List<T>, key: (T) -> String): List<T> = (a + b).associateBy(key).values.toList()
            // A single HA connection is supported. Refuse ambiguous merges rather than erase one.
            require(old.ha == null || incoming.ha == null || old.ha.baseUrl == incoming.ha.baseUrl)
            incoming.onvif.forEach { c -> require(old.onvif.none { it.id == c.id && it.endpoint != c.endpoint }) }
            incoming.ip.forEach { c -> require(old.ip.none { it.id == c.id && it.url != c.url }) }
            val servers = old.frigate.toMutableList()
            incoming.frigate.forEach { s ->
                val position = servers.indexOfFirst { it.baseUrl == s.baseUrl }
                if (position >= 0) servers[position] = s.copy(id = servers[position].id, legacy = servers[position].legacy)
                else servers.add(s.copy(id = if (servers.any { it.id == s.id }) java.util.UUID.randomUUID().toString() else s.id,
                    legacy = s.legacy && servers.none { it.legacy }))
            }
            val selection = servers.mapNotNull { s ->
                val inOld = old.frigate.any { it.baseUrl == s.baseUrl }; val inNew = incoming.frigate.any { it.baseUrl == s.baseUrl }
                val a = old.selection[s.baseUrl]; val b = incoming.selection[s.baseUrl]
                if (inOld && a == null || inNew && b == null) null else s.baseUrl to (a.orEmpty() + b.orEmpty())
            }.toMap()
            val ha = if (old.ha != null && incoming.ha != null) HomeAssistantConnection(incoming.ha.baseUrl, incoming.ha.token, merge(old.ha.cameras, incoming.ha.cameras) { it.entityId }, incoming.ha.localRoute) else incoming.ha ?: old.ha
            return TvConfiguration.decode(TvConfiguration(servers, merge(old.onvif, incoming.onvif) { it.id }, ha,
                (old.order + incoming.order).distinct(), old.hidden + incoming.hidden, merge(old.ip, incoming.ip) { it.id }, selection).encode())
        }
        fun mergeCatalog(old: CameraCatalog, incoming: CameraCatalog): CameraCatalog {
            var result = old
            val remap = incoming.categories.associate { c ->
                val existing = result.categories.firstOrNull { it.name.equals(c.name, true) }
                val id = existing?.id ?: if (result.categories.any { it.id == c.id }) java.util.UUID.randomUUID().toString() else c.id
                if (existing == null) result = result.add(id, c.name)
                c.id to id
            }
            incoming.labels.forEach { (key, value) ->
                val previous = result.labels[key]
                result = result.label(key, value.alias.ifBlank { previous?.alias.orEmpty() }, previous?.categories.orEmpty() + value.categories.map { remap.getValue(it) })
            }
            return result
        }
    }
}
