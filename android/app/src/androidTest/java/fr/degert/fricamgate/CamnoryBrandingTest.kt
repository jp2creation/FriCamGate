package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CamnoryBrandingTest {
    @Test fun launcherLogoFitsInsideTheMaskOnPureBlack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.getDrawable(R.mipmap.ic_launcher)!!
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bitmap))
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(216, 24))
        var logoPixels = 0
        for (y in 0 until 432) for (x in 0 until 432) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 100 && android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) * 2) {
                logoPixels++
                val distance = kotlin.math.hypot((x - 216).toDouble(), (y - 216).toDouble())
                assertTrue("Logo touches launcher mask", distance < 200)
            }
        }
        assertTrue("Logo is missing", logoPixels > 1000)
        context.openFileOutput("camnory-launcher.png", Context.MODE_PRIVATE).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun newIdentityAndCompactHeaderFitSmallPhones() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.nvr.camnory", context.packageName)
        assertEquals("Camnory", context.getString(R.string.app_name))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val method = MainActivity::class.java.getDeclaredMethod("appHeader").apply { isAccessible = true }
                val header = method.invoke(activity) as ViewGroup
                val width = (320 * activity.resources.displayMetrics.density).toInt()
                header.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                header.layout(0, 0, width, header.measuredHeight)
                val artworkViews = mutableListOf<BrandWordmarkView>()
                fun collect(view: View) {
                    if (view is BrandWordmarkView) artworkViews.add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(header)
                assertEquals(1, artworkViews.size)
                assertEquals(0.65f, artworkViews.first().scaleX, 0.001f)
                assertEquals(0.65f, artworkViews.first().scaleY, 0.001f)
                assertEquals(0f, artworkViews.first().pivotX, 0.001f)
                assertEquals("Camnory · EVERY CAMERA. ONE PLACE.", artworkViews.first().contentDescription)
                assertNull(header.getChildAt(1).background)
                assertNull(header.getChildAt(2).background)
                // Branding and the two right-hand actions must not collide at 320 dp.
                val brand = header.getChildAt(0)
                val chat = header.getChildAt(2)
                assertTrue("Compact header overlaps chat", brand.right <= chat.left)
                val wasDark = Palette.dark
                for (dark in listOf(false, true)) {
                    Palette.dark = dark
                    val rendered = method.invoke(activity) as ViewGroup
                    rendered.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    rendered.layout(0, 0, width, rendered.measuredHeight)
                    val bitmap = Bitmap.createBitmap(width, rendered.measuredHeight, Bitmap.Config.ARGB_8888)
                    rendered.draw(Canvas(bitmap))
                    activity.openFileOutput("camnory-header-${if (dark) "dark" else "light"}.png", Context.MODE_PRIVATE).use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                Palette.dark = wasDark
            }
        }
    }
}
