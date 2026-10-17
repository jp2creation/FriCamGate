package com.nvr.camnory

import android.view.View
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CameraButtonAppearanceInstrumentationTest {
    @Test fun allSourcesHideButtonsWithoutHidingNameOrSettingsListAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("camgate-settings", 0)
        val saved = CameraButton.values().associate { "cameraButton:${it.preference}" to prefs.all["cameraButton:${it.preference}"] }
        try {
            CameraButton.values().forEach { assertTrue(it.visible(context) || saved["cameraButton:${it.preference}"] == false) }
            prefs.edit().apply { CameraButton.values().forEach { putBoolean("cameraButton:${it.preference}", false) } }.commit()
            instrumentation.runOnMainSync {
                CameraSource.values().forEach { source ->
                    val overlay = CameraColumnOverlay(context, "Fixture", source,
                        CameraButton.values().map { View(context).cameraButton(it) })
                    assertEquals(0, overlay.findViewWithTag<LinearLayout>("camera-column-controls").childCount)
                    assertNotNull(overlay.findViewWithTag<View>("camera-column-name"))
                    var opened = false
                    val row = CameraArrangementView(context, listOf(CameraArrangementRow("fixture", "Fixture", source, true, true)),
                        onOrder = {}, onVisibility = { _, _ -> }, onOpen = { key, _ -> assertEquals("fixture", key); opened = true })
                    row.findViewWithTag<View>("camera-settings:fixture").performClick()
                    assertTrue(opened)
                }
            }
        } finally {
            prefs.edit().apply { saved.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
        }
    }
}
