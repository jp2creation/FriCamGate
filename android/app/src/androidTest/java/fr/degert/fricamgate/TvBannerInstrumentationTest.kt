package com.nvr.camnory

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class TvBannerInstrumentationTest {
    @Test fun launcherBannerHasRealBrandColorsAndIsNotWhite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(ComponentName(context, TvActivity::class.java), 0)
        val banner = info.loadBanner(context.packageManager)
        assertNotNull(banner)
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        banner.setBounds(0, 0, 320, 180); banner.draw(Canvas(bitmap))
        var pink = 0; var black = 0
        for (y in 0 until 180) for (x in 0 until 320) {
            val pixel = bitmap.getPixel(x, y)
            val red = pixel ushr 16 and 255; val green = pixel ushr 8 and 255; val blue = pixel and 255
            if (red > 100 && red > green * 1.7 && red > blue * 1.15) pink++
            if (maxOf(red, green, blue) < 20) black++
        }
        assertTrue("Brand red/pink missing", pink > 1500)
        assertTrue("Banner must have a black background", black > 30000)
        val icon = info.loadIcon(context.packageManager)
        assertEquals("Round launcher must receive square artwork", icon.intrinsicWidth, icon.intrinsicHeight)
        val iconBitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 320, 320); icon.draw(Canvas(iconBitmap))
        var left = 320; var right = -1; var top = 320; var bottom = -1
        for (y in 0 until 320) for (x in 0 until 320) {
            val pixel = iconBitmap.getPixel(x, y)
            if (maxOf(pixel ushr 16 and 255, pixel ushr 8 and 255, pixel and 255) > 65) {
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                assertTrue("Symbol clipped by round launcher mask", (x - 159.5) * (x - 159.5) + (y - 159.5) * (y - 159.5) < 150 * 150)
            }
        }
        assertTrue("Horizontal margins differ", kotlin.math.abs(left - (319 - right)) <= 3)
        assertTrue("Vertical margins differ", kotlin.math.abs(top - (319 - bottom)) <= 3)
        iconBitmap.recycle()
        bitmap.recycle()
    }
}
