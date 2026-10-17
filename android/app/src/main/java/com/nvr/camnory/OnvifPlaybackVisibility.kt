package com.nvr.camnory

internal enum class OnvifPlaybackVisibility { Release, Pause, Play }

internal fun onvifPlaybackVisibility(active: Boolean, attached: Boolean, shown: Boolean, inViewport: Boolean): OnvifPlaybackVisibility =
    if (!active || !attached || !shown) OnvifPlaybackVisibility.Release
    else if (!inViewport) OnvifPlaybackVisibility.Pause else OnvifPlaybackVisibility.Play
