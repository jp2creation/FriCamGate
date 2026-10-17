package com.nvr.camnory

import java.util.Locale

internal object TvStreamPolicy {
    fun select(camera: String, streams: Map<String, String>): String {
        val available = streams.filterValues { it.isNotBlank() }.entries
        val low = Regex("(^|[^a-z0-9])(sub|low|detect|sd|mobile|grid)([^a-z0-9]|$)")
        val main = Regex("(^|[^a-z0-9])(main|high|hd|uhd|4k|primary)([^a-z0-9]|$)")
        return available.firstOrNull { main.containsMatchIn("${it.key} ${it.value}".lowercase(Locale.ROOT)) }?.value
            ?: available.firstOrNull { !low.containsMatchIn("${it.key} ${it.value}".lowercase(Locale.ROOT)) }?.value
            ?: available.firstOrNull()?.value ?: camera
    }
}
