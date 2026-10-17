package com.nvr.camnory

import android.view.View
import android.widget.RadioButton
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Build the native dialog without showing it or touching live cameras. */
@SdkSuppress(minSdkVersion = 28)
class HomeCameraFilterSheetInstrumentationTest {
    @Test fun combinesBothChoicesAndResetDoesNotApplyUntilConfirmed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_NoActionBar)
            var result: Pair<String?, String?>? = null
            val sheet = HomeCameraFilterSheet(context,
                listOf(AppearanceSheetOption("all", "All"), AppearanceSheetOption("ha", "Home Assistant")),
                listOf(AppearanceSheetOption("all", "All"), AppearanceSheetOption("garden", "Garden")), null, null,
                { server, category -> result = server to category })
            sheet.create()
            val root = sheet.window!!.decorView
            root.findViewWithTag<RadioButton>("home-filter-server-ha").performClick()
            root.findViewWithTag<View>("home-filter-tab-category").performClick()
            root.findViewWithTag<RadioButton>("home-filter-category-garden").performClick()
            assertNull(result)
            root.findViewWithTag<View>("home-filter-tab-server").performClick()
            assertTrue(root.findViewWithTag<RadioButton>("home-filter-server-ha").isChecked)
            root.findViewWithTag<View>("home-filter-apply").performClick()
            assertEquals("ha" to "garden", result)
            result = null
            val reset = HomeCameraFilterSheet(context,
                listOf(AppearanceSheetOption("all", "All")), listOf(AppearanceSheetOption("all", "All")),
                "ha", "garden", { a, b -> result = a to b })
            reset.create()
            reset.window!!.decorView.findViewWithTag<View>("home-filter-reset").performClick()
            assertNull(result)
            reset.window!!.decorView.findViewWithTag<View>("home-filter-apply").performClick()
            assertEquals(Pair<String?, String?>(null, null), result)
        }
    }
}
