package com.nvr.camnory

import org.junit.Assert.*
import org.junit.Test

class BrandPixelsTest {
    @Test fun blackMatteIsTransparentInBothThemes() {
        for (dark in listOf(false, true)) {
            assertEquals(0, BrandPixels.foreground(0xff000000.toInt(), dark, true))
            assertEquals(0, BrandPixels.foreground(0xff080808.toInt(), dark, true))
        }
    }

    @Test fun letteringHasContrastOnLightAndDarkBackgrounds() {
        assertEquals(0xff202127.toInt(), BrandPixels.foreground(0xffffffff.toInt(), false, true))
        assertEquals(0xffffffff.toInt(), BrandPixels.foreground(0xffffffff.toInt(), true, true))
        assertEquals(0xffffffff.toInt(), BrandPixels.foreground(0xffffffff.toInt(), false, false))
    }

    @Test fun redBrandColorIsPreservedAcrossThemes() {
        val red = 0xffff2d63.toInt()
        assertEquals(red, BrandPixels.foreground(red, false, true))
        assertEquals(red, BrandPixels.foreground(red, true, false))
    }
}
