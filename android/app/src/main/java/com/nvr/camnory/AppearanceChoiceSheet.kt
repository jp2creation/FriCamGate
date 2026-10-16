package com.nvr.camnory

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal data class AppearanceSheetOption(val value: String, val title: String, val color: Int? = null, val textScale: Float = 1f)

internal class AppearanceChoiceSheet(
    context: Context,
    heading: String,
    private val key: String,
    private val selected: String,
    private val options: List<AppearanceSheetOption>,
    private val onSelected: (String) -> Unit
) : SelectionBottomSheet(context, heading) {
    override fun createSheetContent(): View {
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { index, option ->
            if (index > 0) rows.addView(View(context).apply { setBackgroundColor(Palette.divider) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(20); marginEnd = dp(20) })
            rows.addView(LinearLayout(context).apply {
                tag = "appearance-option-$key-${option.value}"
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isFocusable = true
                isSelected = option.value == selected
                contentDescription = option.title
                setOnClickListener { dismiss(); onSelected(option.value) }
                if (option.color != null) addView(View(context).apply {
                    background = GradientDrawable().apply {
                        setColor(option.color)
                        cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), Palette.border)
                    }
                }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
                addView(TextView(context).apply {
                    text = option.title
                    textSize = 15f * textScale * option.textScale
                    setTextColor(Palette.text)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_selection_check)
                    setColorFilter(Palette.accent)
                    visibility = if (option.value == selected) View.VISIBLE else View.INVISIBLE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        return rows
    }
}
