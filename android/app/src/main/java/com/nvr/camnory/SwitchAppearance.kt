package com.nvr.camnory

import android.content.res.ColorStateList
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils

/** Explicit state colors avoid AppCompat's default teal and follow the selected palette. */
internal fun SwitchCompat.applyAccentTint() {
    val states = arrayOf(
        intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
        intArrayOf(-android.R.attr.state_enabled),
        intArrayOf(android.R.attr.state_checked),
        intArrayOf()
    )
    thumbTintList = ColorStateList(states, intArrayOf(
        ColorUtils.setAlphaComponent(Palette.accent, 97),
        ColorUtils.setAlphaComponent(Palette.muted, 97), Palette.accent, Palette.muted))
    trackTintList = ColorStateList(states, intArrayOf(
        ColorUtils.setAlphaComponent(Palette.accent, 38),
        ColorUtils.setAlphaComponent(Palette.border, 97),
        ColorUtils.setAlphaComponent(Palette.accent, 85), Palette.border))
}
