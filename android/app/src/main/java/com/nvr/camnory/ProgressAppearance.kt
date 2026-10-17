package com.nvr.camnory

import android.content.res.ColorStateList
import android.view.View
import android.widget.ProgressBar

internal fun View.tintVideoBuffering() {
    findViewById<ProgressBar>(androidx.media3.ui.R.id.exo_buffering)?.indeterminateTintList = ColorStateList.valueOf(Palette.accent)
}
