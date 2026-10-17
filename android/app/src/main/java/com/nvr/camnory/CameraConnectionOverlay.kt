package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Passive, source-neutral loading state; camera actions remain on the tile. */
internal class CameraConnectionOverlay(context: Context) : FrameLayout(context) {
    private val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val symbol = ImageView(context).apply {
        setImageResource(R.drawable.ic_videocam); setColorFilter(0xff9b9ba2.toInt())
        tag = "connection-camera-placeholder"
    }
    private val progress = ProgressBar(context).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
    }
    private val message = TextView(context).apply {
        text = context.getString(R.string.connecting); textSize = 13f; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }
    init {
        tag = "camera-connection-overlay"
        setBackgroundColor(0xff17171a.toInt())
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(backdrop, LayoutParams(-1, -1))
        addView(View(context).apply { setBackgroundColor(0x99000000.toInt()) }, LayoutParams(-1, -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(symbol, LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(12) })
            addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(10) })
            addView(message, LinearLayout.LayoutParams(-2, -2))
        }, LayoutParams(-2, -2, Gravity.CENTER))
    }
    fun showConnection(preview: Bitmap? = null) {
        showPreview(preview)
        progress.visibility = VISIBLE
        message.setText(R.string.connecting)
        visibility = VISIBLE
    }
    fun showUnavailable(preview: Bitmap? = null) {
        showPreview(preview)
        progress.visibility = GONE
        message.setText(R.string.ha_unavailable)
        visibility = VISIBLE
    }
    private fun showPreview(preview: Bitmap?) {
        // Downsampling makes the retained frame softly blurred on older Android too.
        backdrop.setImageBitmap(preview?.let {
            val small = Bitmap.createScaledBitmap(it, 48, 27, true)
            Bitmap.createScaledBitmap(small, 480, 270, true)
        })
        (backdrop.drawable as? android.graphics.drawable.BitmapDrawable)?.setFilterBitmap(true)
        symbol.visibility = if (preview == null) VISIBLE else GONE
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
