package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/** Displays the supplied brand artwork, not a substitute system font. */
internal class BrandWordmarkView(context: Context, private val maxWidthDp: Int) : View(context) {
    private val source = artwork ?: BitmapFactory.decodeResource(resources, R.drawable.camnory_brand_source).also { artwork = it }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init { contentDescription = "Camnory · CLIENT FOR FRIGATE" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize((maxWidthDp * resources.displayMetrics.density).toInt(), widthMeasureSpec)
        setMeasuredDimension(width, resolveSize((minOf(width.toFloat(), maxWidthDp * resources.displayMetrics.density) * 265 / 1090).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val drawnWidth = minOf(width.toFloat(), maxWidthDp * resources.displayMetrics.density)
        val drawnHeight = drawnWidth * 265 / 1090
        // The source JPG is preserved unchanged. Its black backdrop is masked when drawn.
        fun part(left: Int, right: Int, top: Int, bottom: Int, lightLetters: Boolean) {
            val matrix = if (lightLetters) floatArrayOf(
                -1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 1f,0f,0f,0f,0f
            ) else floatArrayOf(
                1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 1f,0f,0f,0f,0f
            )
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
            val src = Rect(left * source.width / 1280, top * source.height / 1280,
                right * source.width / 1280, bottom * source.height / 1280)
            canvas.drawBitmap(source, src, RectF((left - 100) * drawnWidth / 1090, (top - 800) * drawnHeight / 265,
                (right - 100) * drawnWidth / 1090, (bottom - 800) * drawnHeight / 265), paint)
        }
        canvas.save()
        val nameClip = android.graphics.Path().apply {
            fillType = android.graphics.Path.FillType.EVEN_ODD
            addRect(0f, 0f, drawnWidth, drawnHeight, android.graphics.Path.Direction.CW)
            addRect(230 * drawnWidth / 1090, 0f, drawnWidth, 40 * drawnHeight / 265, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(nameClip)
        part(100, 675, 800, 1016, !Palette.dark)
        part(675, 1190, 800, 1016, false)
        canvas.restore()
        part(100, 300, 1016, 1065, false)
        part(300, 980, 1016, 1065, !Palette.dark)
        part(980, 1190, 1016, 1065, false)
    }

    companion object { private var artwork: Bitmap? = null }
}
