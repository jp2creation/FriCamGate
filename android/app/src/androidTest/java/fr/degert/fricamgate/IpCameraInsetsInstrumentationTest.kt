package com.nvr.camnory

import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Synthetic phone bars on native Android views; no activity or camera is opened. */
class IpCameraInsetsInstrumentationTest {
    @Test fun statusCutoutNavigationAndKeyboardInsetsNeverAccumulate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_NoActionBar)
            val root = ScrollView(context)
            val column = LinearLayout(context)
            root.addView(FrameLayout(context).apply { addView(column, FrameLayout.LayoutParams(720, -2)) })
            root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 720, 1600)
            fun insets(keyboard: Int) = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 64, 0, 48))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(30, 80, 12, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard)).build()
            repeat(2) { applyIpCameraFormInsets(root, column, insets(0)) }
            assertEquals(80, root.paddingTop)
            assertEquals(30, root.paddingLeft)
            assertEquals(12, root.paddingRight)
            assertEquals(48, root.paddingBottom)
            assertEquals(678, column.layoutParams.width)
            applyIpCameraFormInsets(root, column, insets(600))
            assertEquals(600, root.paddingBottom)
            applyIpCameraFormInsets(root, column, insets(0))
            assertEquals(48, root.paddingBottom)
        }
    }
}
