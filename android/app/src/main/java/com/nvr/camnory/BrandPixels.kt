package com.nvr.camnory

/** Removes the black matte of the supplied JPEG without changing the red brand color. */
internal object BrandPixels {
    fun foreground(pixel: Int, dark: Boolean, lettering: Boolean): Int {
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        val brightest = maxOf(red, green, blue)
        if (brightest <= 12) return 0
        val alpha = (brightest - 12) * 255 / 243
        val neutral = brightest - minOf(red, green, blue) < brightest / 4
        val rgb = if (!dark && lettering && neutral) 0x202127 else {
            ((red * 255 / brightest) shl 16) or ((green * 255 / brightest) shl 8) or (blue * 255 / brightest)
        }
        return (alpha shl 24) or rgb
    }
}
