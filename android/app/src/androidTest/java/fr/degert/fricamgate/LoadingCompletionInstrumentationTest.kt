package com.nvr.camnory

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LoadingCompletionInstrumentationTest {
    @Test fun completesBeforeOpeningWithAndWithoutAnimation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("camgate-settings", 0)
        val previous = if (prefs.contains("reducedMotion")) prefs.getBoolean("reducedMotion", false) else null
        try {
            for (reduced in listOf(false, true)) {
                prefs.edit().putBoolean("reducedMotion", reduced).commit()
                ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                    action = "com.nvr.camnory.TV_SETTINGS"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }).use { scenario ->
                    val completed = CountDownLatch(1)
                    var progress = 0f
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("renderLoading", String::class.java)
                            .apply { isAccessible = true }.invoke(activity, "Connexion au NVR")
                        val fill = activity.window.decorView.findViewWithTag<View>("loading-progress-fill")
                        fill.scaleX = 0.43f
                        val ready: () -> Unit = { progress = fill.scaleX; completed.countDown() }
                        MainActivity::class.java.getDeclaredMethod("finishLoading", kotlin.jvm.functions.Function0::class.java)
                            .apply { isAccessible = true }.invoke(activity, ready)
                    }
                    assertTrue("Loading completion must reach its callback", completed.await(3, TimeUnit.SECONDS))
                    assertEquals("The screen must open only at 100%", 1f, progress, 0.001f)
                }
            }
        } finally {
            prefs.edit().apply { if (previous == null) remove("reducedMotion") else putBoolean("reducedMotion", previous) }.commit()
        }
    }
}
