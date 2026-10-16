package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Local, non-playing preview: no stream or camera command is started here. */
internal class AppearancePreviewView(context: Context, private var thumbnail: Bitmap?) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val prefs = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
    private val d = resources.displayMetrics.density

    init { contentDescription = context.getString(R.string.appearance_preview) }

    fun showThumbnail(bitmap: Bitmap) {
        thumbnail = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val imageBottom = height - 40 * d
        val image = RectF(0f, 0f, w, imageBottom)
        canvas.save()
        canvas.clipRect(image)
        paint.color = Palette.surface
        canvas.drawRect(image, paint)
        val thumbnail = thumbnail
        if (thumbnail != null && !thumbnail.isRecycled) {
            val scale = maxOf(w / thumbnail.width, imageBottom / thumbnail.height)
            val bw = thumbnail.width * scale
            val bh = thumbnail.height * scale
            canvas.drawBitmap(thumbnail, null, RectF((w - bw) / 2, (imageBottom - bh) / 2, (w + bw) / 2, (imageBottom + bh) / 2), paint)
        } else {
            paint.color = Palette.muted
            paint.textSize = 14 * resources.displayMetrics.scaledDensity
            canvas.drawText(context.getString(R.string.appearance_preview), 14 * d, imageBottom / 2, paint)
        }
        paint.color = 0x99000000.toInt()
        canvas.drawRoundRect(RectF(10 * d, imageBottom - 32 * d, 142 * d, imageBottom - 8 * d), 6 * d, 6 * d, paint)
        paint.color = Color.WHITE
        paint.textSize = 12 * resources.displayMetrics.scaledDensity * Appearance.textScale(prefs.getString("textSize", null))
        val showIcons = prefs.getBoolean("showCameraSourceIcons", true)
        if (showIcons) {
            val icon = context.getDrawable(R.drawable.ic_source_frigate)?.mutate()
            icon?.setTint(Color.WHITE)
            icon?.setBounds((18*d).toInt(), (imageBottom-27*d).toInt(), (32*d).toInt(), (imageBottom-13*d).toInt())
            icon?.draw(canvas)
        }
        canvas.drawText(context.getString(R.string.cameras), (if (showIcons) 40 else 18) * d, imageBottom - 15 * d, paint)
        canvas.restore()
        paint.color = Palette.card
        canvas.drawRect(0f, imageBottom, w, height.toFloat(), paint)
        paint.color = Palette.tick
        paint.strokeWidth = d
        for (i in 0..20) {
            val x = 12 * d + (w - 24 * d) * i / 20
            canvas.drawLine(x, imageBottom + 10*d, x, imageBottom + (if (i % 5 == 0) 22 else 17) * d, paint)
        }
        paint.color = timelineColor(prefs.getString("timelineColor", "accent"), Palette.accent)
        paint.strokeWidth = 2 * d
        canvas.drawLine(w * 0.42f, imageBottom + 5*d, w * 0.42f, height - 4*d, paint)
        canvas.drawRoundRect(RectF(w*0.6f, imageBottom+26*d, w*0.9f, imageBottom+30*d), 2*d, 2*d, paint)
    }
}
