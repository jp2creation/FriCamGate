package com.nvr.camnory

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Keeps the supplied logo and lettering together at their original proportions. */
internal class BrandWordmarkView(context: Context, private val maxWidthDp: Int, portrait: Boolean = false) : View(context) {
    private val source = BitmapFactory.decodeResource(resources,
        if (portrait) R.drawable.camnory_brand_portrait else R.drawable.camnory_brand_horizontal)
    private val crop = if (portrait) Rect(60, 230, 670, 1010) else Rect(110, 190, 1180, 520)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init { contentDescription = "Camnory · EVERY CAMERA. ONE PLACE." }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (maxWidthDp * resources.displayMetrics.density).toInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(minOf(width, desiredWidth) * crop.height() / crop.width(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        paint.colorFilter = if (Palette.dark) ColorMatrixColorFilter(floatArrayOf(
            1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 1f,0f,0f,0f,0f
        )) else null
        val scale = minOf(width.toFloat() / crop.width(), height.toFloat() / crop.height())
        val drawnWidth = crop.width() * scale
        val drawnHeight = crop.height() * scale
        canvas.drawBitmap(source, crop, RectF((width - drawnWidth) / 2f, (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f, (height + drawnHeight) / 2f), paint)
    }
}
