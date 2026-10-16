package com.nvr.camnory

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Test

class ConnectionScreenTest {
    @Test fun providerSelectionShowsOnlyItsFormAndPreservesDrafts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderLogin").apply { isAccessible = true }.invoke(activity)
                val views = mutableListOf<View>()
                fun collect(view: View) {
                    views.add(view)
                    if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
                collect(activity.findViewById(android.R.id.content))
                fun snapshot(name: String) {
                    val content = activity.findViewById<View>(android.R.id.content)
                    val metrics = activity.resources.displayMetrics
                    content.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
                    content.layout(0, 0, content.measuredWidth, content.measuredHeight)
                    val bitmap = android.graphics.Bitmap.createBitmap(content.width, content.height, android.graphics.Bitmap.Config.ARGB_8888)
                    content.draw(android.graphics.Canvas(bitmap))
                    activity.openFileOutput(name, 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                snapshot("connection-frigate.png")
                val inputs = views.filterIsInstance<EditText>()
                assertEquals(7, inputs.size)
                inputs.first().setText("http://draft.example")
                val buttons = views.filterIsInstance<Button>()
                buttons.first { it.text == "ONVIF" }.performClick()
                assertFalse(inputs.first().isShown)
                assertTrue(inputs[3].isShown)
                snapshot("connection-onvif.png")
                buttons.first { it.text == activity.getString(R.string.onvif_connect) }.performClick()
                assertNotNull(inputs[4].error)
                buttons.first { it.text == "Frigate" }.performClick()
                assertTrue(inputs.first().isShown)
                assertFalse(inputs[3].isShown)
                assertEquals("http://draft.example", inputs.first().text.toString())
            }
        }
    }
}
