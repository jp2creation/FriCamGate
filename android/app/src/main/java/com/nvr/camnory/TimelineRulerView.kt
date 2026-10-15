package com.nvr.camnory

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.ColorUtils

/** Draw only visible ticks, even when an entire day is available to scroll. */
internal class TimelineRulerView(context: Context, private val anchorSec: Double,
    private val topPx: Int, private val pixelsPerMinute: Int, private val density: Int,
    private val formatTime: (Double) -> String) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val clip = canvas.clipBounds
        val minor = timelineMinorStep(density)
        val labels = timelineLabelStep(density) * 60L
        val firstVisible = anchorSec - ((clip.top - topPx).coerceAtLeast(0) / pixelsPerMinute.toDouble()) * 60
        var time = kotlin.math.floor(firstVisible / minor) * minor
        val oldestVisible = anchorSec - ((clip.bottom - topPx) / pixelsPerMinute.toDouble()) * 60 - minor
        paint.strokeWidth = dp
        paint.textSize = 12 * resources.displayMetrics.scaledDensity
        while (time >= oldestVisible) {
            val y = timelineY(anchorSec, time, topPx, pixelsPerMinute).toFloat()
            val major = time.toLong() % labels == 0L
            paint.color = if (major) Palette.tick else ColorUtils.blendARGB(Palette.backgroundAlt, Palette.tick, 0.7f)
            canvas.drawLine(4 * dp, y, (if (major) 26 else 12) * dp, y, paint)
            if (major) {
                paint.color = Palette.muted
                canvas.drawText(formatTime(time), 28 * dp, y + 4 * dp, paint)
            }
            time -= minor
        }
    }
}
