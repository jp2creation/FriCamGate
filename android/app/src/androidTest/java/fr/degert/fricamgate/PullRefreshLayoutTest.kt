package com.nvr.camnory

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PullRefreshLayoutTest {
    @Test fun refreshRequiresTopAndPermissionAndDoesNotDuplicatePendingRequest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            var scrolled = true
            var allowed = true
            var calls = 0
            var finish: (() -> Unit)? = null
            val scroll = object : View(context) {
                override fun canScrollVertically(direction: Int) = direction == -1 && scrolled
            }
            val panel = PullRefreshLayout(context, scroll, { allowed }) { done -> calls++; finish = done }
            panel.refreshNow()
            assertEquals(0, calls)
            scrolled = false; allowed = false
            panel.refreshNow()
            assertEquals(0, calls)
            allowed = true
            panel.refreshNow(); panel.refreshNow()
            assertEquals(1, calls)
            assertTrue(panel.isRefreshing)
            finish!!.invoke()
            assertFalse(panel.isRefreshing)
            panel.refreshNow()
            assertEquals(2, calls)
            finish!!.invoke()
        }
    }
}
