package com.nvr.camnory

import android.content.Context
import android.widget.ImageView
import androidx.appcompat.widget.TooltipCompat

internal enum class CameraSource(val title: String, val icon: Int) {
    Frigate("Frigate", R.drawable.ic_source_frigate),
    Onvif("ONVIF", R.drawable.ic_source_onvif),
    HomeAssistant("Home Assistant", R.drawable.ic_source_home_assistant),
    Ip("IP / Webcam", R.drawable.ic_videocam);

    fun iconView(context: Context, tint: Int = Palette.text): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(tint)
        contentDescription = title
        tag = "camera-source-${name}"
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = if (CameraArrangementStore(context).showSourceIcons) android.view.View.VISIBLE else android.view.View.GONE
        TooltipCompat.setTooltipText(this, title)
    }
}
