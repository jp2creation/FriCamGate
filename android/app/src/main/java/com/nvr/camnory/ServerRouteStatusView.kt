package com.nvr.camnory

import android.content.Context
import android.net.ConnectivityManager

internal class ServerRouteStatusView(context: Context, private val base: String, private val provider: String,
    private val route: LocalServerRoute?) : androidx.appcompat.widget.AppCompatTextView(context) {
    private val refresh = object : Runnable {
        override fun run() { render(); postDelayed(this, 2000) }
    }
    init {
        textSize = 12f; setTextColor(Palette.muted)
        setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        tag = "server-route-status"
        render()
    }
    private fun render() {
        val entry = ServerRouteStatus.shared.get(base, provider)
        val network = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork?.toString()
        text = when {
            entry == null -> context.getString(R.string.route_unverified)
            entry.network != network -> context.getString(R.string.route_network_changed)
            !entry.success -> context.getString(R.string.route_failed)
            else -> context.getString(R.string.route_last_success, context.getString(when {
                entry.local -> R.string.route_local
                route == null -> R.string.route_primary
                else -> R.string.route_external
            })) + "\n" + entry.address
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); refresh.run() }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
}
