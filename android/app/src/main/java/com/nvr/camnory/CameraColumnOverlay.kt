package com.nvr.camnory

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat

/** Shared column chrome; each source keeps its own supported playback actions. */
internal class CameraColumnOverlay(context: Context, name: String, source: CameraSource, buttons: List<View>) : FrameLayout(context) {
    init {
        tag = "camera-column-overlay"
        val scale = Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
        val nameText = TextView(context).apply {
            text = name; textSize = 12f * scale; setTextColor(Palette.text)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = name; TooltipCompat.setTooltipText(this, name)
        }
        val nameChip = LinearLayout(context).apply {
            tag = "camera-column-name"
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable().apply { setColor(Palette.overlay); cornerRadius = dp(16).toFloat() }
            addView(source.iconView(context), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
            addView(nameText)
        }
        val controls = LinearLayout(context).apply {
            tag = "camera-column-controls"; gravity = Gravity.CENTER
            buttons.forEach { button ->
                button.background = GradientDrawable().apply { setColor(Palette.overlay); cornerRadius = dp(22).toFloat() }
                if (button is android.widget.ImageButton) button.setPadding(dp(9), dp(9), dp(9), dp(9))
                addView(button, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
            }
        }
        addView(nameChip, LayoutParams(-2, dp(32), Gravity.START or Gravity.BOTTOM).apply { marginStart = dp(14); bottomMargin = dp(14) })
        addView(controls, LayoutParams(-2, dp(46), Gravity.END or Gravity.BOTTOM).apply { marginEnd = dp(12); bottomMargin = dp(12) })
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val iconWidth = if (CameraArrangementStore(context).showSourceIcons) dp(22) else 0
            val maxWidth = (width - controls.width - dp(60) - iconWidth).coerceAtLeast(dp(12))
            if (nameText.maxWidth != maxWidth) nameText.maxWidth = maxWidth
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
