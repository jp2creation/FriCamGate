package com.nvr.camnory

import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SelectiveTransferInstrumentationTest {
    @Test fun manualInvitationEntryIsReachableWithRemoteAndRejectsShortCodes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<TvTransferActivity>(Intent(context, TvTransferActivity::class.java)).use { scenario ->
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("transfer-manual").requestFocus() }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("transfer-invitation", it.window.decorView.findFocus()?.tag) }
            instrumentation.sendStringSync("123456")
            val deadline = android.os.SystemClock.uptimeMillis() + 3000
            var visible = false
            while (!visible && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { visible = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true }
                if (!visible) android.os.SystemClock.sleep(30)
            }
            if (visible) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            var hidden = false
            val hideDeadline = android.os.SystemClock.uptimeMillis() + 3000
            while (!hidden && android.os.SystemClock.uptimeMillis() < hideDeadline) {
                scenario.onActivity { hidden = it.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false }
                if (!hidden) android.os.SystemClock.sleep(30)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("transfer-invitation-next", it.window.decorView.findFocus()?.tag) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertNotNull(it.window.decorView.findViewWithTag<View>("transfer-invitation"))
                assertNull(TvTransferActivity::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(it))
            }
        }
    }
    @Test fun selectionFitsPhoneWidthWithoutClippingText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
            scenario.onActivity { activity ->
                val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                    screenWidthDp = 360; screenHeightDp = 800; uiMode = android.content.res.Configuration.UI_MODE_TYPE_NORMAL
                }
                val phone = android.view.ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme_NoActionBar)
                val view = TransferSelectionView(phone, TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet()), CameraCatalog(), emptyList(), mapOf("theme" to "dark"), true) { }
                view.applyPreset("appearance")
                val width = (360 * phone.resources.displayMetrics.density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                fun check(node: View) {
                    assertTrue("Child outside parent: ${node.tag}", node.width <= width)
                    if (node is android.widget.TextView && node.text.isNotEmpty()) {
                        val layout = requireNotNull(node.layout)
                        assertEquals(0, layout.getEllipsisCount(layout.lineCount - 1))
                    }
                    if (node is android.view.ViewGroup) repeat(node.childCount) { check(node.getChildAt(it)) }
                }
                check(view)
            }
        }
    }
    @Test fun realTvTlsReceptionShowsAnUncheckedReplaceOptionWithoutSaving() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val before = java.io.File(context.noBackupFilesDir, "tv-config.enc").readBytes()
        ActivityScenario.launch<TvActivity>(Intent(context, TvActivity::class.java).setAction("com.nvr.camnory.TV_PAIR")).use { scenario ->
            var receiver: TvPairingReceiver? = null
            val deadline = android.os.SystemClock.uptimeMillis() + 8000
            while (receiver == null && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity -> receiver = TvActivity::class.java.getDeclaredField("receiver").apply { isAccessible = true }.get(activity) as? TvPairingReceiver }
                if (receiver == null) android.os.SystemClock.sleep(50)
            }
            assertNotNull(receiver)
            val transfer = ConfigurationTransfer(null, null, mapOf("accent" to "blue"))
            TvPairingSender().use { it.send(requireNotNull(receiver).ticket, transfer.encode()) }
            var replace: CheckBox? = null
            val receivedDeadline = android.os.SystemClock.uptimeMillis() + 5000
            while (replace == null && android.os.SystemClock.uptimeMillis() < receivedDeadline) {
                scenario.onActivity { replace = it.window.decorView.findViewWithTag("transfer-replace") }
                if (replace == null) android.os.SystemClock.sleep(50)
            }
            assertNotNull(replace)
            scenario.onActivity {
                assertFalse(replace!!.isChecked)
                assertNotNull(it.window.decorView.findViewWithTag<View>("tv-import-accept"))
            }
            assertArrayEquals(before, java.io.File(context.noBackupFilesDir, "tv-config.enc").readBytes())
        }
    }
    @Test fun nativeImportMergesAndReplacesOnlyExplicitSectionsInIsolatedStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "transfer-qa-${System.nanoTime()}"
        val folder = java.io.File(context.cacheDir, prefix).apply { mkdirs() }
        val names = mutableSetOf<String>()
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                names.add("$prefix-$name")
                return context.getSharedPreferences("$prefix-$name", mode)
            }
        }
        try {
            val old = TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet(), listOf(IpCamera("old", "Old", "http://192.0.2.10/video")))
            val incoming = TvConfiguration(emptyList(), emptyList(), null, emptyList(), emptySet(), listOf(IpCamera("new", "New", "http://192.0.2.11/video")))
            TvConfigurationStore(isolated).save(old)
            TransferAppearance.write(isolated, mapOf("accent" to "blue"), true)
            val catalog = CameraCatalog(listOf(CameraCategory("garden", "Garden")))
            TransferImport.apply(isolated, ConfigurationTransfer(incoming, catalog, null), false)
            assertEquals(setOf("old", "new"), TvConfigurationStore(isolated).load()!!.ip.map { it.id }.toSet())
            assertEquals("blue", TransferAppearance.read(isolated)["accent"])
            TransferImport.apply(isolated, ConfigurationTransfer(null, null, mapOf("accent" to "green")), true)
            assertEquals(2, TvConfigurationStore(isolated).load()!!.ip.size)
            assertEquals("Garden", CameraCatalogStore(isolated).load().categories.single().name)
            TransferImport.apply(isolated, ConfigurationTransfer(incoming, CameraCatalog(), null), true)
            assertEquals(listOf("new"), TvConfigurationStore(isolated).load()!!.ip.map { it.id })
            assertEquals("green", TransferAppearance.read(isolated)["accent"])
        } finally {
            names.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
            folder.deleteRecursively()
        }
    }
    @Test fun categorySelectionStaysConsistentAndRemoteCanReviewWithoutSending() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val a = CameraArrangement.key("onvif", "", "qa-a")
        val b = CameraArrangement.key("onvif", "", "qa-b")
        val c = CameraArrangement.key("ip", "", "qa-c")
        val source = TvConfiguration(emptyList(), listOf("qa-a", "qa-b").map {
            OnvifSavedCamera(it, it, "http://192.0.2.1/onvif/device_service", OnvifCredentials("", ""))
        }, null, listOf(a, b, c), emptySet(), listOf(IpCamera("qa-c", "Webcam", "http://192.0.2.2/stream")))
        val catalog = CameraCatalog(listOf(CameraCategory("garden", "Jardin"))).label(a, "Portail", setOf("garden")).label(b, "Terrasse", setOf("garden"))
        var reviewed: ConfigurationTransfer? = null
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply { action = "com.nvr.camnory.TV_SETTINGS" }).use { scenario ->
            lateinit var view: TransferSelectionView
            scenario.onActivity { activity ->
                view = TransferSelectionView(activity, source, catalog, listOf(TransferCamera(a, "A"), TransferCamera(b, "B"), TransferCamera(c, "Webcam")), mapOf("accent" to "blue"), true) { reviewed = it }
                activity.setContentView(ScrollView(activity).apply { setBackgroundColor(Palette.background); setPadding(40, 20, 40, 20); addView(view) })
                view.applyPreset("cameras")
                view.findViewWithTag<View>("transfer-expand:garden").performClick()
                view.findViewWithTag<View>("transfer-category:garden").requestFocus()
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertFalse(view.findViewWithTag<CheckBox>("transfer-camera:$a").isChecked)
                assertFalse(view.findViewWithTag<CheckBox>("transfer-camera:$b").isChecked)
                assertTrue(view.findViewWithTag<CheckBox>("transfer-camera:$c").isChecked)
                view.findViewWithTag<View>("transfer-review").requestFocus()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            assertEquals(listOf(c), reviewed!!.config!!.order)
            assertNull(reviewed!!.appearance)
            assertTrue(reviewed!!.config!!.onvif.isEmpty())
            scenario.onActivity {
                view.applyPreset("appearance")
                view.findViewWithTag<View>("transfer-review").requestFocus()
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            instrumentation.waitForIdleSync()
            assertNull(reviewed!!.config)
            assertEquals("blue", reviewed!!.appearance!!["accent"])
            scenario.onActivity { view.applyPreset("all") }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.cacheDir, "selective-transfer-tv.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
}
