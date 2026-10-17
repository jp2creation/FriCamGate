package com.nvr.camnory

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** An explicit private IPv4 network, not a guess based on Internet availability. */
internal data class LocalServerRoute(val localUrl: String, val networkCidr: String) {
    fun validate(): LocalServerRoute {
        require(localUrl.length <= 4096 && networkCidr.length <= 32)
        val local = localUrl.toHttpUrl()
        val network = networkCidr.split('/')
        require(network.size == 2)
        val bits = network[1].toInt()
        require(bits in 8..30)
        val address = ipv4(network[0]) ?: error("Invalid network")
        require(privateIp(address) && local.username.isEmpty() && local.password.isEmpty() && local.query == null && local.fragment == null)
        val host = ipv4(local.host) ?: error("Local address must be private IPv4")
        require(privateIp(host) && contains(host) && address and mask(bits) == address)
        return this
    }
    fun matches(addresses: List<String>) = addresses.any { ipv4(it)?.let(::contains) == true }
    private fun contains(address: Long): Boolean {
        val parts = networkCidr.split('/')
        if (parts.size != 2) return false
        val network = ipv4(parts[0]) ?: return false
        val bits = parts[1].toIntOrNull()?.takeIf { it in 8..30 } ?: return false
        return address and mask(bits) == network and mask(bits)
    }
    fun rewrite(base: HttpUrl, url: HttpUrl): HttpUrl {
        val prefix = base.encodedPath.trimEnd('/')
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            (url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/")))
        val local = localUrl.toHttpUrl()
        return url.newBuilder().scheme(local.scheme).host(local.host).port(local.port)
            .encodedPath(local.encodedPath.trimEnd('/') + url.encodedPath.removePrefix(prefix)).build()
    }
    fun canonical(base: HttpUrl, url: HttpUrl): HttpUrl {
        val local = localUrl.toHttpUrl()
        val prefix = local.encodedPath.trimEnd('/')
        if (url.scheme != local.scheme || url.host != local.host || url.port != local.port ||
            !(url.encodedPath == prefix || url.encodedPath.startsWith("$prefix/"))) return url
        return url.newBuilder().scheme(base.scheme).host(base.host).port(base.port)
            .encodedPath(base.encodedPath.trimEnd('/') + url.encodedPath.removePrefix(prefix)).build()
    }
    fun json() = JSONObject().put("url", localUrl).put("network", networkCidr)
    companion object {
        fun decode(value: JSONObject?) = value?.let { LocalServerRoute(it.getString("url"), it.getString("network")).validate() }
        fun ipv4(value: String): Long? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() } || part.length > 1 && part.startsWith('0')) return null
                val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                result = (result shl 8) or octet.toLong()
            }
            return result
        }
        private fun privateIp(ip: Long) = ip ushr 24 == 10L || ip ushr 20 == 0xac1L || ip ushr 16 == 0xc0a8L
        private fun mask(bits: Int) = (0xffffffffL shl (32 - bits)) and 0xffffffffL
    }
}
