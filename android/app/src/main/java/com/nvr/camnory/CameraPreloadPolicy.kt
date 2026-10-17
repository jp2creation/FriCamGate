package com.nvr.camnory

internal fun cameraConnectionActive(active: Boolean, attached: Boolean, inViewport: Boolean, preload: Boolean): Boolean =
    active && attached && (preload || inViewport)
