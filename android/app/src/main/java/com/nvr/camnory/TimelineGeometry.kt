package com.nvr.camnory

internal val timelineZoomDensities = intArrayOf(4, 8, 12, 18, 34, 62, 100)

internal fun timelineReturnDuration(distancePx: Int, viewportPx: Int): Long =
    (700.0 + distancePx.coerceAtLeast(0).toDouble() / viewportPx.coerceAtLeast(1) * 220.0).toLong().coerceIn(700L, 4500L)

internal fun timelineZoomIndex(density: Int?, legacyIndex: Int): Int {
    val saved = density ?: intArrayOf(18, 34, 62, 100)[legacyIndex.coerceIn(0, 3)]
    return timelineZoomDensities.indices.minBy { kotlin.math.abs(timelineZoomDensities[it] - saved) }
}

internal fun timelineLabelStep(density: Int): Int = when {
    density <= 4 -> 15
    density <= 8 -> 10
    density <= 12 -> 5
    density <= 34 -> 2
    else -> 1
}

internal fun timelineMinorStep(density: Int): Int = when {
    density <= 4 -> 180
    density <= 8 -> 60
    density <= 18 -> 30
    density <= 34 -> 15
    density <= 62 -> 10
    else -> 5
}

internal fun timelineY(anchorSec: Double, timeSec: Double, topPx: Int, pixelsPerMinute: Int): Int =
    topPx + (((anchorSec - timeSec).coerceAtLeast(0.0) / 60.0) * pixelsPerMinute).toInt()

internal fun timelineFirstTick(anchorSec: Double, stepMinutes: Int): Double {
    val step = stepMinutes.coerceAtLeast(1) * 60.0
    return kotlin.math.floor(anchorSec / step) * step
}

// Keep the newest event as the exact time anchor; nearby events share its preview.
internal fun timelinePreviewGroups(timesDescending: List<Double>, minimumGapSeconds: Double): List<List<Int>> {
    val groups = mutableListOf<MutableList<Int>>()
    timesDescending.forEachIndexed { index, time ->
        val last = groups.lastOrNull()
        if (last == null || timesDescending[last.first()] - time >= minimumGapSeconds) {
            groups.add(mutableListOf(index))
        } else last.add(index)
    }
    return groups
}

// Ignore legacy overrides so existing installations also use the shared accent.
internal fun timelineColor(@Suppress("UNUSED_PARAMETER") legacyMode: String?, accent: Int): Int = accent
