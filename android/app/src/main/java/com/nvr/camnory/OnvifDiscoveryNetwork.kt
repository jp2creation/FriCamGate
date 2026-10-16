package com.nvr.camnory

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal data class OnvifDiscoveryNetwork(val network: Network, val address: Inet4Address, val wifi: Boolean)

internal fun onvifDiscoveryNetwork(context: Context): OnvifDiscoveryNetwork? {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return null
    val capabilities = manager.getNetworkCapabilities(network) ?: return null
    val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
        (!wifi && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
    val address = manager.getLinkProperties(network)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { (it.isSiteLocalAddress || it.isLinkLocalAddress) && !it.isLoopbackAddress } ?: return null
    return OnvifDiscoveryNetwork(network, address, wifi)
}
