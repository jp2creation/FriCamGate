package com.nvr.camnory

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ExpandedVideoFrameTest {
    @Test fun actionsStayTogetherAtBottomRightWithoutTransportControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val frame = ExpandedVideoFrame(context)
            val sound = View(context)
            val fullscreen = View(context)
            frame.addPlaybackActions(sound, fullscreen, "actions")
            val actions = frame.findViewWithTag<LinearLayout>("actions")
            assertEquals(1, frame.childCount)
            assertEquals(2, actions.childCount)
            assertSame(sound, actions.getChildAt(0))
            assertSame(fullscreen, actions.getChildAt(1))
            assertEquals(Gravity.END or Gravity.BOTTOM, (actions.layoutParams as FrameLayout.LayoutParams).gravity)
            assertEquals(sound.layoutParams.width, fullscreen.layoutParams.width)
        }
    }
}
