package com.nvr.camnory

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the real shared shell/tiles with fixture photographs, without connecting cameras. */
class TvCameraGridInstrumentationTest {
    @Test fun focusHasVisibleAccentEdgeAndGentleFalloffInBothThemes() {
        val dark = Palette.dark
        try {
            for (theme in listOf(true, false)) {
                Palette.dark = theme
                val image = android.graphics.Bitmap.createBitmap(160, 96, android.graphics.Bitmap.Config.ARGB_8888)
                TvRemoteSupport.CameraHalo(1f).apply {
                    setBounds(0, 0, 160, 96)
                    draw(android.graphics.Canvas(image))
                }
                val near = android.graphics.Color.alpha(image.getPixel(0, 48))
                val far = android.graphics.Color.alpha(image.getPixel(8, 48))
                assertTrue("A single fine edge must stay readable without a bright frame", near in 160..195)
                assertEquals("Focus follows the chosen accent", android.graphics.Color.red(Palette.accent), android.graphics.Color.red(image.getPixel(0, 48)))
                assertTrue("Diffusion must be much fainter than the fine edge", android.graphics.Color.alpha(image.getPixel(2, 48)) in 1..25)
                assertTrue("Diffusion fades away before obscuring the video", far in 0 until near)
                assertEquals("The image centre must remain unchanged", 0, android.graphics.Color.alpha(image.getPixel(80, 48)))
                image.recycle()
            }
        } finally { Palette.dark = dark }
    }

    @Test fun fourCamerasAreEntirelyVisible() = checkGrid(4)
    @Test fun navigationChangesWholePages() = checkGrid(8)

    private fun checkGrid(count: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TelevisionMode.enabled(context))
        val tiles = mutableListOf<View>()
        lateinit var scroll: TvCameraScrollView
        lateinit var grid: TvCameraGrid
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
            action = "com.nvr.camnory.TV_SETTINGS"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                val session = MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }
                val original = session.get(activity)
                try {
                    session.set(activity, null)
                    repeat(count) { index ->
                        val camera = NativeCamera("fixture-$index", "Camera ${index + 1}", true, "fixture-$index",
                            null, null, null, null, null, null, null)
                        val tile = MainActivity::class.java.getDeclaredMethod("cameraCard", NativeCamera::class.java)
                            .apply { isAccessible = true }.invoke(activity, camera) as android.view.ViewGroup
                        val asset = listOf("driveway", "entrance", "terrace", "workshop")[index % 4]
                        instrumentation.context.assets.open("ha-previews/$asset.jpg").use {
                            val image = tile.getChildAt(0) as ImageView
                            val original = BitmapFactory.decodeStream(it)
                            image.setImageBitmap(android.graphics.Bitmap.createBitmap(original, 0, 0,
                                if (index % 2 == 0) original.width else original.width / 2,
                                if (index % 2 == 0) original.height / 2 else original.height))
                        }
                        tiles.add(tile)
                    }
                } finally { session.set(activity, original) }
                scroll = TvCameraScrollView(activity)
                val column = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 20, 24, 24)
                }
                grid = TvCameraGrid(activity, 2, tiles)
                column.addView(grid, LinearLayout.LayoutParams(-1, -2))
                scroll.addView(column)
                grid.attachToViewport(scroll, column)
                val shell = MainActivity::class.java.declaredMethods.first { it.name == "setShell" && it.parameterCount == 4 }.apply { isAccessible = true }
                val home = shell.parameterTypes[1].enumConstants!!.first { it.toString() == "Home" }
                shell.invoke(activity, scroll, home, true, false)
            }
            instrumentation.waitForIdleSync()
            val focusDeadline = android.os.SystemClock.elapsedRealtime() + 10000
            var windowReady = false
            while (!windowReady && android.os.SystemClock.elapsedRealtime() < focusDeadline) {
                scenario.onActivity { windowReady = it.hasWindowFocus() && grid.height > 1 }
                if (!windowReady) Thread.sleep(50)
            }
            assertTrue("TV window must own remote focus", windowReady)
            scenario.onActivity {
                tiles.take(4).forEach { tile ->
                    val visible = Rect()
                    assertTrue(tile.getGlobalVisibleRect(visible))
                    assertEquals("Clipped camera height", tile.height, visible.height())
                    assertEquals("Clipped camera width", tile.width, visible.width())
                    val image = (tile as android.view.ViewGroup).getChildAt(0) as ImageView
                    assertEquals("TV thumbnails must fill the halo frame", ImageView.ScaleType.CENTER_CROP, image.scaleType)
                    val pixels = android.graphics.RectF(0f, 0f, image.drawable.intrinsicWidth.toFloat(), image.drawable.intrinsicHeight.toFloat())
                    image.imageMatrix.mapRect(pixels)
                    assertTrue("No horizontal letterbox inside halo", pixels.left <= 1 && pixels.right >= image.width - 1)
                    assertTrue("No vertical letterbox inside halo", pixels.top <= 1 && pixels.bottom >= image.height - 1)
                }
                assertTrue(tiles[0].requestFocus())
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(tiles[2].hasFocus()) }
            scenario.onActivity {
                val chip = tiles[2].findViewWithTag<View>("camera-column-name")
                val pixel = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                val originalBounds = android.graphics.Rect(chip.background.bounds)
                chip.background.apply { setBounds(0, 0, 100, 100); draw(android.graphics.Canvas(pixel).apply { translate(-50f, -50f) }) }
                chip.background.bounds = originalBounds
                assertEquals("Focus must never tint the camera name background", 0, android.graphics.Color.red(pixel.getPixel(0, 0)))
                pixel.recycle()
            }
            if (count > 4) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                val deadline = android.os.SystemClock.elapsedRealtime() + 3000
                var complete = false
                while (!complete && android.os.SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity {
                        complete = grid.currentPage == 1 && scroll.scrollY == 0
                    }
                    if (!complete) Thread.sleep(30)
                }
                assertTrue("Navigation must change the whole page", complete)
                scenario.onActivity {
                    assertTrue(tiles[4].hasFocus())
                    assertTrue(tiles.take(4).none { it.isShown })
                    assertTrue(grid.findViewWithTag<View>("tv-page-dot-1").isSelected)
                    val rect = Rect()
                    tiles[4].getGlobalVisibleRect(rect)
                    assertEquals(tiles[4].height, rect.height())
                }
            } else scenario.onActivity { assertEquals(0, scroll.scrollY) }
            val drawn = java.util.concurrent.CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.window.decorView.postOnAnimation { activity.window.decorView.postOnAnimation { drawn.countDown() } }
            }
            assertTrue(drawn.await(3, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.FileOutputStream(java.io.File(context.cacheDir, "tv-grid-$count.png")).use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
    }
}
