package fr.degert.fricamgate

import java.net.URI

internal fun trustedUpdateUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
        uri.port == -1 && uri.path.startsWith("/jp2creation/FriCamGate/releases/download/") &&
        uri.path.endsWith(".apk") && uri.query == null && uri.fragment == null
}.getOrDefault(false)

internal fun updateDownloadPercent(downloaded: Long, total: Long): Int? =
    if (total <= 0) null else ((downloaded.coerceAtLeast(0).toDouble() / total) * 100).toInt().coerceIn(0, 100)
