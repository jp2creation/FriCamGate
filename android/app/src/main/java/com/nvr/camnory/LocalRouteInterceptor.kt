package com.nvr.camnory

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Last application interceptor: origin checks and authentication run on the canonical URL first. */
internal class LocalRouteInterceptor(
    private val baseUrl: String,
    private val route: LocalServerRoute,
    private val token: String,
    private val probePath: String,
    private val network: () -> Pair<String, List<String>>?,
    private val probeClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS).callTimeout(1800, TimeUnit.MILLISECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val boundProbe: ((String) -> OkHttpClient)? = null
) : Interceptor {
    private val base = baseUrl.toHttpUrl()
    private var networkId: String? = null
    private var until = 0L
    private var local = false
    init { route.validate() }
    @Synchronized private fun useLocal(): Boolean {
        val current = network()
        if (current == null || !route.matches(current.second)) { networkId = null; until = 0; local = false; return false }
        val now = clock()
        if (current.first == networkId && now < until) return local
        networkId = current.first
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + probePath).build()
        local = runCatching {
            (boundProbe?.invoke(current.first) ?: probeClient).newCall(Request.Builder().url(route.rewrite(base, url))
                .header("Authorization", "Bearer $token").get().build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (network()?.first != current.first) local = false
        until = now + if (local) 30_000 else 5_000
        return local
    }
    @Synchronized private fun invalidate() { local = false; until = clock() + 5_000 }
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Never route a foreign origin (including update checks or third-party media).
        val rewritten = runCatching { route.rewrite(base, original.url) }.getOrNull() ?: return chain.proceed(original)
        if (!useLocal()) return chain.proceed(original)
        val readOnly = original.method in setOf("GET", "HEAD") && original.header("Upgrade") == null
        try {
            val response = chain.proceed(original.newBuilder().url(rewritten).build())
            if (!readOnly || response.code < 500) return response
            response.close()
        } catch (error: IOException) {
            invalidate()
            if (!readOnly || chain.call().isCanceled()) throw error
        }
        invalidate()
        return chain.proceed(original)
    }
    companion object {
        fun create(context: Context, base: String, route: LocalServerRoute, token: String, probe: String): LocalRouteInterceptor {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return LocalRouteInterceptor(base, route, token, probe, {
                val net = cm.activeNetwork
                val caps = net?.let(cm::getNetworkCapabilities)
                if (net == null || caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) null
                else net.toString() to cm.getLinkProperties(net)?.linkAddresses.orEmpty().mapNotNull { it.address.hostAddress }
            }, boundProbe = { id ->
                val net = cm.allNetworks.firstOrNull { it.toString() == id } ?: throw IOException("Local network disconnected")
                OkHttpClient.Builder().socketFactory(net.socketFactory).dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) = net.getAllByName(hostname).toList()
                })
                    .connectTimeout(1200, TimeUnit.MILLISECONDS).readTimeout(1200, TimeUnit.MILLISECONDS).callTimeout(1800, TimeUnit.MILLISECONDS)
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            })
        }
    }
}
