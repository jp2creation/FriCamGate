package com.nvr.camnory

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Spinner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ConnectionScreenTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.openFileOutput(name, 0).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun providerSelectionShowsAllThreeFormsInlineAndPreservesDrafts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderLogin").apply { isAccessible = true }.invoke(activity)
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val selector = views(root).filterIsInstance<Spinner>().single()
                assertEquals(3, selector.adapter.count)
                root.findViewWithTag<EditText>("connection-frigate-server").setText("https://draft.example")
                selector.setSelection(1)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertFalse(root.findViewWithTag<View>("connection-frigate-form").isShown)
                assertTrue(root.findViewWithTag<View>("connection-onvif-form").isShown)
                root.findViewWithTag<EditText>("connection-onvif-name").setText("Camera draft")
                views(root).filterIsInstance<Spinner>().single().setSelection(2)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screenshot("connection-home-assistant.png")
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue(root.findViewWithTag<View>("connection-ha-form").isShown)
                assertTrue(root.findViewWithTag<EditText>("connection-ha-server").isShown)
                assertFalse(root.findViewWithTag<View>("connection-onvif-form").isShown)
                assertFalse(root.findViewWithTag<View>("connection-frigate-form").isShown)
                views(root).filterIsInstance<Spinner>().single().setSelection(0)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screenshot("connection-frigate.png")
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals("https://draft.example", root.findViewWithTag<EditText>("connection-frigate-server").text.toString())
                views(root).filterIsInstance<Spinner>().single().setSelection(1)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            screenshot("connection-onvif.png")
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals("Camera draft", root.findViewWithTag<EditText>("connection-onvif-name").text.toString())
            }
        }
    }
    @Test fun secretVisibilityAndAdvancedOptionsAreExplicit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderLogin").apply { isAccessible = true }.invoke(activity)
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val password = root.findViewWithTag<EditText>("connection-frigate-password")
                password.setText("draft-password")
                assertNotNull(password.transformationMethod)
                root.findViewWithTag<View>("connection-frigate-password-eye").performClick()
                assertNull(password.transformationMethod)
                root.findViewWithTag<View>("connection-frigate-password-eye").performClick()
                assertNotNull(password.transformationMethod)
                assertFalse(root.findViewWithTag<View>("connection-frigate-advanced-body").isShown)
                root.findViewWithTag<View>("connection-frigate-advanced").performClick()
                assertTrue(root.findViewWithTag<View>("connection-frigate-advanced-body").isShown)
            }
        }
    }
}
