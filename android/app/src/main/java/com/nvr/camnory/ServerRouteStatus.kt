package com.nvr.camnory

import java.util.concurrent.ConcurrentHashMap

/** Process-only observations of completed requests, never a prediction based on Wi-Fi. */
internal class ServerRouteStatus {
    data class Entry(val local: Boolean, val success: Boolean, val network: String?, val address: String)
    private val entries = ConcurrentHashMap<Pair<String, String>, Entry>()
    fun record(base: String, provider: String, local: Boolean, success: Boolean, network: String?, address: String = base) {
        entries[base to provider] = Entry(local, success, network, address)
    }
    fun get(base: String, provider: String) = entries[base to provider]
    companion object { val shared = ServerRouteStatus() }
}
