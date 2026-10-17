package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Keeps the supplied logo and lettering together at their original proportions. */
internal class BrandWordmarkView(context: Context, private val maxWidthDp: Int, portrait: Boolean = false,
    private val darkBackground: Boolean? = null) : View(context) {
    private val source = BitmapFactory.decodeResource(resources,
        if (portrait) R.drawable.camnory_brand_portrait else R.drawable.camnory_brand_horizontal)
    private val crop = if (portrait) Rect(60, 230, 670, 1010) else Rect(110, 190, 1180, 520)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artwork = listOf(false, true).map { dark ->
        val pixels = IntArray(crop.width() * crop.height())
        source.getPixels(pixels, 0, crop.width(), crop.left, crop.top, crop.width(), crop.height())
        // Keep the black lens and the icon's shading opaque; only its exterior is matte.
        val iconRows = (0 until crop.height()).map { y ->
            val row = (0 until crop.width()).filter { x ->
                val inIcon = if (portrait) crop.top + y < 805 else crop.left + x < 435
                val pixel = pixels[y * crop.width() + x]
                inIcon && maxOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255) > 40
            }
            (row.firstOrNull() ?: 1) to (row.lastOrNull() ?: 0)
        }
        pixels.indices.forEach { index ->
            val lettering = if (portrait) crop.top + index / crop.width() >= 805
                else crop.left + index % crop.width() >= 435
            val (left, right) = iconRows[index / crop.width()]
            val insideIcon = !lettering && index % crop.width() in (left + 2)..(right - 2)
            pixels[index] = if (insideIcon) pixels[index] else BrandPixels.foreground(pixels[index], dark, lettering)
        }
        Bitmap.createBitmap(pixels, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    }

    init { contentDescription = "Camnory · EVERY CAMERA. ONE PLACE." }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (maxWidthDp * resources.displayMetrics.density).toInt()
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(width, resolveSize(minOf(width, desiredWidth) * crop.height() / crop.width(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width.toFloat() / crop.width(), height.toFloat() / crop.height())
        val drawnWidth = crop.width() * scale
        val drawnHeight = crop.height() * scale
        canvas.drawBitmap(artwork[if (darkBackground ?: Palette.dark) 1 else 0], null, RectF((width - drawnWidth) / 2f, (height - drawnHeight) / 2f,
            (width + drawnWidth) / 2f, (height + drawnHeight) / 2f), paint)
    }
}
