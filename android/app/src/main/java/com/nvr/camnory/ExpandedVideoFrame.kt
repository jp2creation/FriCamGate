package com.nvr.camnory

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Shared video chrome for live cameras and event playback; no transport ownership. */
internal class ExpandedVideoFrame(context: Context) : FrameLayout(context) {
    init { setBackgroundColor(Palette.black) }
    fun addPlaybackActions(sound: View, fullscreen: View, key: String) {
        val actions = LinearLayout(context).apply {
            tag = key; gravity = Gravity.CENTER_VERTICAL
            addView(sound, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(fullscreen, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        }
        addView(actions, LayoutParams(-2, -2, Gravity.END or Gravity.BOTTOM).apply { marginEnd = dp(12); bottomMargin = dp(10) })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
