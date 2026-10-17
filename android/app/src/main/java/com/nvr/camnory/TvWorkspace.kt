package com.nvr.camnory

internal object TvWorkspace {
    fun primary(keys: List<String>, preferred: String?) = preferred?.takeIf { it in keys } ?: keys.firstOrNull()
    fun alternatives(keys: List<String>, selected: String, page: Int) = keys.filter { it != selected }.drop(page.coerceAtLeast(0) * 3).take(3)
    fun merge(current: TvConfiguration?, added: TvConfiguration, provider: String): TvConfiguration {
        if (current == null) return added
        return TvConfiguration(
            if (provider == "frigate") {
                val mapped = added.frigate.map { server -> current.frigate.firstOrNull { it.baseUrl == server.baseUrl }?.let { server.copy(id = it.id, legacy = it.legacy) } ?: server }
                (current.frigate + mapped).associateBy { it.id }.values.toList()
            } else current.frigate,
            if (provider == "onvif") (current.onvif + added.onvif).associateBy { it.id }.values.toList() else current.onvif,
            if (provider == "ha") added.ha ?: current.ha else current.ha, current.order, current.hidden)
    }
    fun remapKey(key: String, source: String, old: String, next: String): String = runCatching {
        val parts = org.json.JSONArray(key)
        if (parts.length() == 3 && parts.getString(0) == source && parts.getString(1) == old.trimEnd('/'))
            CameraArrangement.key(source, next, parts.getString(2)) else key
    }.getOrDefault(key)
    fun remap(config: TvConfiguration, source: String, old: String, next: String) = TvConfiguration(
        config.frigate, config.onvif, config.ha, config.order.map { remapKey(it, source, old, next) },
        config.hidden.map { remapKey(it, source, old, next) }.toSet())
}
