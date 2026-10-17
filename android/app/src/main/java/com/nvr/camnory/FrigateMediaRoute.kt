package com.nvr.camnory

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** HLS resolves relative segments against the final, possibly local, response URL. */
internal fun canonicalFrigateMediaSource(servers: List<FrigateServer>, value: String): Pair<FrigateServer, HttpUrl>? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    fun belongs(base: HttpUrl, candidate: HttpUrl): Boolean {
        val prefix = base.encodedPath.trimEnd('/')
        return candidate.scheme == base.scheme && candidate.host == base.host && candidate.port == base.port &&
            (candidate.encodedPath == prefix || candidate.encodedPath.startsWith("$prefix/"))
    }
    val canonical = servers.mapNotNull { server ->
        val base = server.baseUrl.toHttpUrlOrNull() ?: return@mapNotNull null
        if (belongs(base, url)) server to url else null
    }.sortedByDescending { it.first.baseUrl.length }
    if (canonical.isNotEmpty()) return canonical.first()
    val local = servers.mapNotNull { server ->
        val base = server.baseUrl.toHttpUrlOrNull() ?: return@mapNotNull null
        val mapped = server.localRoute?.canonical(base, url) ?: return@mapNotNull null
        if (mapped != url && belongs(base, mapped)) server to mapped else null
    }
    // Never guess which credential owns a shared local alias.
    return local.singleOrNull()
}
