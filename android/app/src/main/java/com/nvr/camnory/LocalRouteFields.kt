package com.nvr.camnory

import android.content.Context
import android.widget.LinearLayout

internal class LocalRouteFields(context: Context, body: LinearLayout, saved: LocalServerRoute?) {
    private val ui = ConnectionFormUi(context)
    private val address = ui.field(body, context.getString(R.string.local_address), "http://192.168.0.144:5000", saved?.localUrl.orEmpty(), key = "local-route-url")
    private val network = ui.field(body, context.getString(R.string.local_network), "192.168.0.0/24", saved?.networkCidr.orEmpty(), key = "local-route-network")
    init {
        body.addView(ui.action(context.getString(R.string.local_use_network), false) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let(cm::getNetworkCapabilities)
            val link = net?.let(cm::getLinkProperties)?.linkAddresses?.firstOrNull {
                it.address is java.net.Inet4Address && it.prefixLength in 8..30 && it.address.isSiteLocalAddress
            }
            if (link != null && caps != null && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                val value = LocalServerRoute.ipv4(link.address.hostAddress.orEmpty())!!
                val masked = value and ((0xffffffffL shl (32 - link.prefixLength)) and 0xffffffffL)
                network.setText((3 downTo 0).joinToString(".") { ((masked ushr (it * 8)) and 255).toString() } + "/${link.prefixLength}")
            } else network.error = context.getString(R.string.local_route_invalid)
        })
        body.addView(ui.text(context.getString(R.string.local_route_detail), 12))
    }
    fun value(): LocalServerRoute? {
        if (address.text.isNullOrBlank() && network.text.isNullOrBlank()) return null
        return LocalServerRoute(address.text.toString().trim(), network.text.toString().trim()).validate()
    }
}
