package com.nvr.camnory

internal enum class OnvifPlaybackVisibility { Release, Pause, Play }

internal fun onvifPlaybackVisibility(active: Boolean, attached: Boolean, shown: Boolean, inViewport: Boolean, preload: Boolean = false): OnvifPlaybackVisibility =
    if (!active || !attached || !shown && !preload) OnvifPlaybackVisibility.Release
    else if (!shown || !inViewport) OnvifPlaybackVisibility.Pause else OnvifPlaybackVisibility.Play
