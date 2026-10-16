package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal class OnvifDiscoveryView(context: Context, private val select: (OnvifDiscoveredCamera) -> Unit,
    private val search: () -> Unit, private val cancel: () -> Unit) : LinearLayout(context) {
    private val found = mutableSetOf<String>()
    private val status = TextView(context).apply {
        textSize = 14f; setTextColor(Palette.muted); setPadding(dp(8), dp(12), dp(8), dp(16))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val progress = ProgressBar(context).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
    }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val action = Button(context).apply {
        isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Palette.accent); cornerRadius = dp(6).toFloat() }
    }

    init {
        orientation = VERTICAL
        addView(status, LayoutParams(-1, -2))
        addView(progress, LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(rows) }, LayoutParams(-1, 0, 1f))
        addView(action, LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
    }

    fun begin() {
        found.clear(); rows.removeAllViews(); progress.visibility = VISIBLE
        status.setText(R.string.onvif_searching)
        action.setText(android.R.string.cancel); action.setOnClickListener { cancel() }
    }

    fun add(camera: OnvifDiscoveredCamera, alreadySaved: Boolean) {
        if (!found.add(camera.host)) return
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            minimumHeight = dp(72)
            if (!alreadySaved) { isClickable = true; setOnClickListener { select(camera) } }
            contentDescription = "${camera.name}, ${camera.host}" + if (alreadySaved) ", ${context.getString(R.string.onvif_already_saved)}" else ""
        }
        row.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_videocam); setColorFilter(if (alreadySaved) Palette.muted else Palette.accent)
        }, LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        row.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply { text = camera.name; textSize = 16f; setTextColor(Palette.text) })
            addView(TextView(context).apply {
                text = if (alreadySaved) "${camera.host} · ${context.getString(R.string.onvif_already_saved)}" else camera.host
                textSize = 12f; setTextColor(Palette.muted); setPadding(0, dp(4), 0, 0)
            })
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rows.addView(row, LayoutParams(-1, -2))
        status.text = context.getString(R.string.onvif_found_count, found.size)
    }

    fun finish(error: Int? = null) {
        progress.visibility = GONE
        status.text = when {
            error != null -> context.getString(error)
            found.isEmpty() -> context.getString(R.string.onvif_search_empty)
            else -> context.getString(R.string.onvif_found_count, found.size)
        }
        action.setText(R.string.onvif_search_again); action.setOnClickListener { search() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
