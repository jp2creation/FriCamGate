package com.nvr.camnory

internal fun streamRetryDelay(attempt: Int): Long = (1200L * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(30000L)

internal class PlaybackResumeGate<T : Any> {
    private var pending: T? = null
    fun remember(player: T?, playing: Boolean) { pending = if (playing) player else null }
    fun consume(player: T?): Boolean {
        val resume = player != null && player === pending
        pending = null
        return resume
    }
}
