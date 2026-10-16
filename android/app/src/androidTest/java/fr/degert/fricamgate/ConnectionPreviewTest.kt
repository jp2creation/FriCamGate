package com.nvr.camnory

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/** Explicit, fixture-free renders for design approval; never connects a server. */
class ConnectionPreviewTest {
    @Test fun captureCompactSources() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("camgate-settings", 0)
        val oldTheme = prefs.getString("theme", null)
        val oldLocales = AppCompatDelegate.getApplicationLocales()
        val localeManager = if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java) else null
        val oldPlatformLocales = if (android.os.Build.VERSION.SDK_INT >= 33) localeManager?.applicationLocales else null
        val theme = InstrumentationRegistry.getArguments().getString("previewTheme", "dark")
        prefs.edit().putString("theme", theme).commit()
        instrumentation.runOnMainSync {
            if (android.os.Build.VERSION.SDK_INT >= 33) localeManager?.applicationLocales = android.os.LocaleList.forLanguageTags("fr")
            else AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr"))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("renderLogin").apply { isAccessible = true }.invoke(activity)
                }
                for ((position, name) in listOf("frigate", "onvif", "home-assistant").withIndex()) {
                    scenario.onActivity { activity ->
                        fun find(view: View): Spinner? {
                            if (view is Spinner) return view
                            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        find(activity.findViewById(android.R.id.content))!!.setSelection(position)
                    }
                    instrumentation.waitForIdleSync(); Thread.sleep(500)
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    context.openFileOutput("compact-$theme-$name.png", 0).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally {
            val edit = prefs.edit()
            if (oldTheme == null) edit.remove("theme") else edit.putString("theme", oldTheme)
            edit.commit()
            instrumentation.runOnMainSync {
                if (android.os.Build.VERSION.SDK_INT >= 33 && oldPlatformLocales != null) localeManager?.applicationLocales = oldPlatformLocales
                else AppCompatDelegate.setApplicationLocales(oldLocales)
            }
        }
    }
}
