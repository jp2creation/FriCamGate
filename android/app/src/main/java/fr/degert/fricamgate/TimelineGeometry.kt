package fr.degert.fricamgate

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

internal fun timelineColor(mode: String?, accent: Int): Int =
    if (mode == "blue") 0xff07b8ff.toInt() else accent
