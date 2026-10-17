package com.nvr.camnory

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.File

class TvEntryInstrumentationTest {
    @Test fun tvHasItsOwnLauncherAndAnEmptyInstallExposesFocusablePairing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeFalse("Do not open real configured cameras", File(context.noBackupFilesDir, "tv-config.enc").exists())
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(launch, 0).any { it.activityInfo.name == TvActivity::class.java.name })
        ActivityScenario.launch(TvActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val pair = root.findViewWithTag<View>("tv-pair")
                assertNotNull(pair)
                assertTrue(pair.isFocusable)
                assertTrue(pair.isClickable)
            }
        }
    }
}
