package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CamnoryBrandingTest {
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
                val texts = mutableListOf<TextView>()
                fun collect(view: View) {
                    if (view is TextView) texts.add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(header)
                assertTrue(texts.any { it.text.toString() == "Camnory" })
                assertTrue(texts.any { it.text.toString() == "CLIENT FOR FRIGATE" })
                // Branding and the two right-hand actions must not collide at 320 dp.
                val brand = header.getChildAt(0)
                val chat = header.getChildAt(2)
                assertTrue("Compact header overlaps chat", brand.right <= chat.left)
                val bitmap = Bitmap.createBitmap(width, header.measuredHeight, Bitmap.Config.ARGB_8888)
                header.draw(Canvas(bitmap))
                activity.openFileOutput("camnory-header-test.png", Context.MODE_PRIVATE).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }
}
