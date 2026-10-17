package com.nvr.camnory

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CameraCatalogInstrumentationTest {
    @Test fun remoteCanCreateOpenAndAssignCategoryWithoutSkippingInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preference = "catalog-remote-${System.nanoTime()}"
        val store = CameraCatalogStore(context, preference)
        var sheet: CameraCatalogSheet? = null
        fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); instrumentation.waitForIdleSync() }
        var step = 0
        fun focused(tag: String) = instrumentation.runOnMainSync {
            step++
            assertEquals("Remote step $step", tag, sheet!!.window!!.decorView.findFocus()?.tag)
        }
        fun awaitKeyboard(visible: Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            val matched = java.util.concurrent.atomic.AtomicBoolean(false)
            while (!matched.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    matched.set(sheet!!.window!!.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == visible)
                }
                if (!matched.get()) android.os.SystemClock.sleep(30)
            }
            assertTrue("Keyboard visible=$visible", matched.get())
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
                scenario.onActivity { activity ->
                    sheet = CameraCatalogSheet(activity, store, cameras = listOf("frigate:qa" to "Jardin", "onvif:qa" to "Portail", "ha:qa" to "Salon"))
                    sheet!!.show()
                }
                instrumentation.waitForIdleSync()
                focused("category-add")
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                focused("category-name")
                instrumentation.sendStringSync("TVTest")
                awaitKeyboard(true)
                key(android.view.KeyEvent.KEYCODE_BACK)
                awaitKeyboard(false)
                focused("category-name")
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                focused("category-save")
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                focused("category-name")
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                focused("category-camera:frigate:qa")
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                focused("category-camera:onvif:qa")
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                focused("category-camera:ha:qa")
                key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                focused("category-save")
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                assertEquals("TVTest", store.load().categories.single().name)
                val id = store.load().categories.single().id
                assertTrue(store.load().select(id).includes("frigate:qa"))
                assertTrue(store.load().select(id).includes("onvif:qa"))
                assertFalse(store.load().select(id).includes("ha:qa"))
                key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                scenario.onActivity {
                    assertTrue(sheet!!.window!!.decorView.findViewWithTag<CheckBox>("category-camera:frigate:qa").isChecked)
                }
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "camera-category-remote.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                scenario.onActivity { sheet!!.dismiss() }
            }
        } finally {
            instrumentation.runOnMainSync { runCatching { sheet?.dismiss() } }
            context.getSharedPreferences(preference, 0).edit().clear().commit()
        }
    }
    @Test fun categoriesNamesAndHomeFilterPersistWithoutRenamingSources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preference = "catalog-qa-${System.nanoTime()}"
        val store = CameraCatalogStore(context, preference)
        val original = NativeCamera("catalog-fixture", "Caméra originale", true, "fixture", null, null, null, null, null, null, null)
        val key = CameraArrangement.key("frigate", "", original.name)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    field("cameraCatalogStore").set(activity, store)
                    field("cameras").set(activity, listOf(original))
                    field("events").set(activity, emptyList<NativeEvent>())
                    field("api").set(activity, null)
                    field("session").set(activity, null)
                    MainActivity::class.java.getDeclaredMethod("renderCameraSettings").apply { isAccessible = true }.invoke(activity)
                    activity.window.decorView.findViewWithTag<View>("camera-categories").performClick()
                    val sheet = field("cameraCatalogSheet").get(activity) as CameraCatalogSheet
                    for (name in listOf("Extérieur", "Garage")) {
                        sheet.window!!.decorView.findViewWithTag<View>("category-add").performClick()
                        sheet.window!!.decorView.findViewWithTag<EditText>("category-name").setText(name)
                        sheet.window!!.decorView.findViewWithTag<View>("category-save").performClick()
                        sheet.window!!.decorView.findViewWithTag<CheckBox>("category-camera:$key").isChecked = true
                        sheet.window!!.decorView.findViewWithTag<View>("category-save").performClick()
                    }
                    assertEquals(2, store.load().categories.size)
                    sheet.dismiss()
                    assertTrue(activity.window.decorView.findViewWithTag<View>("camera-personalize:$key").performClick())
                    val editor = field("cameraCatalogSheet").get(activity) as CameraCatalogSheet
                    editor.window!!.decorView.findViewWithTag<EditText>("camera-display-name").setText("Portail")
                    editor.window!!.decorView.findViewWithTag<View>("camera-label-save").performClick()
                    val saved = CameraCatalogStore(context, preference).load()
                    assertEquals("Portail", saved.displayName(key, original.friendlyName))
                    assertEquals(2, saved.labels.getValue(key).categories.size)
                    assertEquals("Caméra originale", (field("cameras").get(activity) as List<NativeCamera>).single().friendlyName)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    fun textExists(view: View): Boolean = (view is android.widget.TextView && view.text.toString() == "Portail") ||
                        (view is ViewGroup && (0 until view.childCount).any { textExists(view.getChildAt(it)) })
                    assertTrue(textExists(activity.window.decorView))
                    MainActivity::class.java.getDeclaredMethod("showCameraCategoryFilter").apply { isAccessible = true }.invoke(activity)
                    val filter = field("homeSourceSheet").get(activity) as AppearanceChoiceSheet
                    val selected = store.load().categories.first().id
                    filter.window!!.decorView.findViewWithTag<View>("appearance-option-camera-category-$selected").performClick()
                    assertEquals(selected, store.load().selected)
                    assertNotNull(activity.window.decorView.findViewWithTag<View>("home-category-filter"))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val catalog = CameraCatalogStore(context, preference).load()
                    assertTrue(catalog.includes(key))
                    assertFalse(catalog.includes(CameraArrangement.key("onvif", "", "catalog-fixture")))
                    val selected = requireNotNull(catalog.selected)
                    store.update { it.rename(selected, "Jardin") }
                    assertEquals(selected, store.load().selected)
                    store.update { it.delete(selected) }
                    assertNull(store.load().selected)
                    assertEquals("Portail", store.load().displayName(key, "Original"))
                    MainActivity::class.java.getDeclaredMethod("renderCameraSettings").apply { isAccessible = true }.invoke(activity)
                }
                val drawn = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { it.window.decorView.postOnAnimation { drawn.countDown() } }
                assertTrue(drawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.cacheDir, "camera-catalog-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally { context.getSharedPreferences(preference, 0).edit().clear().commit() }
    }
}
