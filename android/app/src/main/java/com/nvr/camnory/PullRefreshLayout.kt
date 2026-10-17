package com.nvr.camnory

import android.content.Context
import android.view.View
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Only the supplied page scroll owns this gesture; dialogs have their own window. */
internal class PullRefreshLayout(context: Context, private val scroll: View,
    private val allowed: () -> Boolean, private val refresh: (() -> Unit) -> Unit) : SwipeRefreshLayout(context) {
    private var running = false
    private var generation = 0
    private val timeout = Runnable { finish(generation) }
    init {
        tag = "pull-to-refresh"
        setColorSchemeColors(Palette.accent)
        setProgressBackgroundColorSchemeColor(Palette.card)
        addView(scroll, LayoutParams(-1, -1))
        setOnChildScrollUpCallback { _, _ -> !allowed() || scroll.canScrollVertically(-1) }
        setOnRefreshListener { refreshNow() }
    }
    internal fun refreshNow() {
        if (running) return
        if (!allowed() || scroll.canScrollVertically(-1)) { isRefreshing = false; return }
        running = true; isRefreshing = true
        val request = ++generation
        postDelayed(timeout, 30_000)
        refresh { finish(request) }
    }
    private fun finish(request: Int) {
        if (request != generation) return
        removeCallbacks(timeout)
        running = false; isRefreshing = false
    }
    override fun onDetachedFromWindow() {
        generation++; removeCallbacks(timeout); running = false; isRefreshing = false
        super.onDetachedFromWindow()
    }
}
