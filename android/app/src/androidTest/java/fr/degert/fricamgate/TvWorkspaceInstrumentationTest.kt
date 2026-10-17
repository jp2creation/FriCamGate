package com.nvr.camnory

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TvWorkspaceInstrumentationTest {
    @Test fun dashboardRendersFourSourcesAndRoutesRemoteActionsWithoutNetworking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val images = listOf("driveway", "entrance", "terrace", "workshop").map { name ->
            instrumentation.context.assets.open("ha-previews/$name.jpg").use { BitmapFactory.decodeStream(it) }
        }
        instrumentation.runOnMainSync {
            var selected = ""; var full = false; var settings = false; var admin = false; var events = false
            val rendered = mutableListOf<Pair<String, Boolean>>()
            val names = listOf("Allée", "Entrée", "Terrasse", "Devant", "Salon")
            val cameras = names.mapIndexed { i, name -> TvCameraTile("fixture-$i", name) }
            val root = TvDashboardView(context, cameras, "fixture-3", 0,
                render = { frame, tile, live ->
                    rendered.add(tile.key to live)
                    frame.addView(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(images[cameras.indexOf(tile) % images.size])
                    }, FrameLayout.LayoutParams(-1, -1))
                }, select = { selected = it }, fullscreen = { full = true }, settings = { settings = true },
                admin = { admin = true }, activity = { events = true }, reload = {}, changePage = {})
            for (size in listOf(1280 to 720, 1920 to 1080)) {
                root.measure(View.MeasureSpec.makeMeasureSpec(size.first, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size.second, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, size.first, size.second)
                val primary = root.findViewWithTag<View>("tv-primary")
                val alternative = root.findViewWithTag<View>("tv-camera:fixture-0")
                assertTrue(primary.width > alternative.width * 1.7)
                assertTrue(primary.height > alternative.height * 2)
                assertNotNull(FocusFinder.getInstance().findNextFocus(root, primary, View.FOCUS_RIGHT))
                assertTrue(primary.width > 0 && primary.height > 0)
                val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val file = File(context.getExternalFilesDir(null), "tv-workspace-${size.first}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertEquals(4, rendered.size)
            assertEquals(listOf("fixture-3"), rendered.filter { it.second }.map { it.first })
            root.findViewWithTag<View>("tv-camera:fixture-0").performClick(); assertEquals("fixture-0", selected)
            root.findViewWithTag<View>("tv-primary-fullscreen").performClick(); assertTrue(full)
            root.findViewWithTag<View>("tv-primary-settings").performClick(); assertTrue(settings)
            root.findViewWithTag<View>("tv-open-admin").performClick(); assertTrue(admin)
            root.findViewWithTag<View>("tv-open-events").performClick(); assertTrue(events)
        }
        images.forEach { it.recycle() }
    }

    @Test fun realAdministrationNavigatesAllFiveSectionsWithDpadWithoutOpeningCameras() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configFile = File(instrumentation.targetContext.noBackupFilesDir, "tv-config.enc")
        val before = configFile.takeIf { it.exists() }?.readBytes()
        val intent = Intent(instrumentation.targetContext, TvActivity::class.java).setAction("com.nvr.camnory.TV_SETTINGS")
        ActivityScenario.launch<TvActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("tv-admin-v2"))
            }
            for (index in 0..4) {
                if (index > 0) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    assertEquals("tv-admin-section-$index", activity.currentFocus?.tag)
                    assertNotNull(root.findViewWithTag<View>("tv-admin-content"))
                    assertNull(root.findViewWithTag<View>("tv-dashboard-v2"))
                    if (index == 4) {
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        File(activity.getExternalFilesDir(null), "tv-admin-diagnostics.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
            scenario.onActivity { activity ->
                val editor = TvActivity::class.java.declaredMethods.single { it.name == "editor" }
                editor.isAccessible = true
                val content: (LinearLayout, android.app.Dialog) -> Unit = { body, _ ->
                    val ui = TvUi(activity)
                    body.addView(ui.row(activity.getString(R.string.tv_source), "Frigate"))
                    body.addView(ui.row(activity.getString(R.string.tv_stream), activity.getString(R.string.tv_main_stream)))
                    body.addView(ui.toggle(activity.getString(R.string.playback_sound), false) {})
                    body.addView(ui.toggle(activity.getString(R.string.accessory_light), false, false) {})
                    body.addView(ui.toggle(activity.getString(R.string.accessory_siren), false, false) {})
                    body.addView(ui.toggle(activity.getString(R.string.ptz_title), false, false) {})
                }
                editor.invoke(activity, "Caméra de test", content)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val field = TvActivity::class.java.getDeclaredField("sidePanel").apply { isAccessible = true }
                val dialog = field.get(activity) as android.app.Dialog
                val decor = dialog.window!!.decorView
                val screen = activity.resources.displayMetrics.widthPixels
                assertTrue(decor.width in (screen * 0.4).toInt()..(screen * 0.5).toInt())
                val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(bitmap))
                File(activity.getExternalFilesDir(null), "tv-side-panel.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertNotNull(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("tv-admin-v2")) }
        }
        assertEquals("Navigation must not create or delete TV configuration", before != null, configFile.exists())
        if (before != null) assertTrue("Navigation must not rewrite TV configuration", before.contentEquals(configFile.readBytes()))
    }
}
