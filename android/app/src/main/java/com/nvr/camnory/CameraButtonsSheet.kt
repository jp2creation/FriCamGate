package com.nvr.camnory

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat

internal class CameraButtonsSheet(context: Context)
    : SelectionBottomSheet(context, context.getString(R.string.camera_buttons_title)) {
    override val maxHeightFraction = 0.70f
    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        tag = "camera-buttons-options"
        setPadding(dp(20), 0, dp(16), dp(16))
        val ui = ConnectionFormUi(context)
        CameraButton.values().forEach { button ->
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                addView(ui.text(context.getString(button.titleRes), 15, Palette.text).apply {
                    setPadding(0, dp(10), dp(12), dp(10))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(SwitchCompat(context).apply {
                    tag = "appearance-camera-button:${button.preference}"
                    contentDescription = context.getString(button.titleRes)
                    isChecked = button.visible(context)
                    applyAccentTint()
                    setOnCheckedChangeListener { _, checked ->
                        context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).edit()
                            .putBoolean("cameraButton:${button.preference}", checked).apply()
                    }
                })
            }, LinearLayout.LayoutParams(-1, -2))
            if (button != CameraButton.values().last()) addView(View(context).apply {
                setBackgroundColor(Palette.divider)
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }
}
