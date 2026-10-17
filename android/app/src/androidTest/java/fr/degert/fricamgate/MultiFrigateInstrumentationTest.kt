package com.nvr.camnory

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class MultiFrigateInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val native get() = context.getSharedPreferences("fricamgate-native", 0)
    private val settings get() = context.getSharedPreferences("camgate-settings", 0)
    private val a = MockWebServer()
    private val b = MockWebServer()
    private val requestsA = CopyOnWriteArrayList<RecordedRequest>()
    private val requestsB = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var profiles: List<FrigateServer>
    private lateinit var files: Map<String, ByteArray?>
    private lateinit var preferences: Map<String, Map<String, *>>
    @Volatile private var offlineB = false
    @Volatile private var rejectLogin = false
    @Volatile private var delayDashboard = false
    private val store get() = FrigateServerStore(context)
    private fun filesNow() = listOf("frigate-servers.enc", "home-assistant.enc", "onvif-cameras.enc").associateWith {
        File(context.noBackupFilesDir, it).takeIf { f -> f.exists() }?.readBytes()
    }
    @Before fun setup() {
        files = filesNow()
        preferences = listOf("fricamgate-native", "camgate-settings").associateWith { context.getSharedPreferences(it, 0).all }
        File(context.noBackupFilesDir, "frigate-servers.enc").delete()
        settings.edit().clear().putString("theme", "dark").putBoolean("reducedMotion", true).commit()
        fun dispatcher(requests: MutableList<RecordedRequest>, second: Boolean) = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                if (second && offlineB) return MockResponse().setResponseCode(503)
                val now = System.currentTimeMillis() / 1000.0
                return when (request.requestUrl?.encodedPath) {
                    "/api/login" -> if (rejectLogin) MockResponse().setResponseCode(401) else MockResponse().setBody("""{"token":"new-token","username":"admin"}""")
                    "/api/config" -> MockResponse().setBody("""{"cameras":{"front":{"enabled":true,"friendly_name":"Devant","live":{"streams":{"sub":"front_sub"}}}}}""")
                        .setBodyDelay(if (delayDashboard) 3 else 0, TimeUnit.SECONDS)
                    "/api/events" -> MockResponse().setBody("""[{"id":"same-event","camera":"front","label":"person","start_time":${now - 1},"end_time":$now}]""")
                    "/api/version" -> MockResponse().setBody("0.16")
                    else -> MockResponse().setBody("media")
                }
            }
        }
        a.dispatcher = dispatcher(requestsA, false); b.dispatcher = dispatcher(requestsB, true)
        a.start(); b.start()
        profiles = listOf(FrigateServer("legacy", "Maison", a.url("/").toString().trimEnd('/'), "admin", "token-a", true),
            FrigateServer("office", "Bureau", b.url("/").toString().trimEnd('/'), "admin", "token-b"))
        native.edit().clear().putString("baseUrl", profiles[0].baseUrl).putString("username", "admin").putString("token", "token-a").commit()
        profiles = listOf(store.load().single(), profiles[1])
    }
    @After fun cleanup() {
        context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
        files.forEach { (name, bytes) -> File(context.noBackupFilesDir, name).let { if (bytes == null) it.delete() else it.writeBytes(bytes) } }
        preferences.forEach { (name, values) ->
            val edit = context.getSharedPreferences(name, 0).edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }; edit.commit()
        }
        a.shutdown(); b.shutdown()
    }
    private fun <T> await(action: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1); var result: Result<T>? = null
        action { result = it; latch.countDown() }; assertTrue("Response timeout", latch.await(10, TimeUnit.SECONDS))
        return requireNotNull(result).getOrThrow()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun waitFor(test: () -> Boolean) { repeat(100) { if (test()) return; Thread.sleep(50) }; fail("Condition did not become true") }

    @Test fun launchWindowDoesNotShowAStandaloneIcon() {
        val theme = android.view.ContextThemeWrapper(context, R.style.AppTheme_NoActionBarLaunch)
        val value = android.util.TypedValue()
        assertTrue(theme.theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, value, true))
        val drawable = requireNotNull(theme.getDrawable(value.resourceId))
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(android.graphics.Canvas(bitmap))
        assertTrue("Android must not show a standalone logo before the branded loading screen",
            (0 until 96).all { x -> (0 until 96).all { y -> android.graphics.Color.alpha(bitmap.getPixel(x, y)) == 0 } })
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            assertTrue(theme.theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, value, true))
            assertEquals(R.drawable.launch_transparent_icon, value.resourceId)
        }
        bitmap.recycle()
    }

    @Test fun slowLaunchShowsTheLogoAndSloganUntilCamerasAreReady() {
        delayDashboard = true
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
            scenario.onActivity { activity ->
                val brands = views(activity.findViewById(android.R.id.content)).filterIsInstance<BrandWordmarkView>()
                assertEquals("Show one complete branded loading screen", 1, brands.size)
                assertTrue(brands.single().width > 0 && brands.single().height > 0)
            }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "launch-logo-slogan.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            waitFor {
                var ready = false
                scenario.onActivity { activity -> ready = (MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>).isNotEmpty() }
                ready
            }
        }
    }

    @Test fun migrationEncryptedStorageAndDeletionKeepOtherProvidersUntouched() {
        HomeAssistantStore(context).save(HomeAssistantConnection("https://ha.example.test", "protected-ha-token",
            listOf(HomeAssistantCamera("camera.ring", "Ring", true))))
        OnvifCameraStore(context).save(listOf(OnvifSavedCamera("protected-onvif", "Devant", "http://192.0.2.10/onvif/device_service",
            OnvifCredentials("camera-user", "protected-password"))))
        val protected = filesNow().filterKeys { it != "frigate-servers.enc" }
        assertTrue(protected.values.all { it != null && it.isNotEmpty() })
        assertEquals(profiles.take(1), store.load())
        store.upsert(profiles[1])
        assertEquals(profiles, store.load())
        val bytes = File(context.noBackupFilesDir, "frigate-servers.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("token-b")); assertFalse(bytes.contains("token-a"))
        assertTrue(runCatching { store.upsert(profiles[1].copy(id = "duplicate")) }.isFailure)
        assertEquals(profiles, store.load())
        store.upsert(profiles[0].copy(name = "Maison renommée"))
        assertEquals("legacy", store.load().first().id)
        store.remove("legacy")
        assertEquals(listOf(profiles[1]), store.load())
        assertFalse(native.contains("token"))
        protected.forEach { (key, value) -> assertArrayEquals(value, filesNow()[key]) }
    }
    @Test fun identicalNamesRouteDashboardHistoryAndMediaToTheOwningServer() {
        val router = MultiFrigateApi(profiles, OkHttpClient())
        val data = await<DashboardData> { router.loadDashboard("wrong-token", it) }
        assertEquals(setOf("front", "@office:front"), data.cameras.map { it.name }.toSet())
        assertEquals(setOf("same-event", "@office:same-event"), data.events.map { it.id }.toSet())
        assertEquals("front_sub", data.cameras.first().streamName)
        assertEquals("@office:front_sub", data.cameras.last().streamName)
        val history = await<List<NativeEvent>> { router.loadEvents("wrong", "@office:front", 0.0, Double.MAX_VALUE, it) }
        assertEquals("@office:same-event", history.single().id)
        assertEquals("front", requestsB.last().requestUrl?.queryParameter("camera"))
        assertTrue(router.eventVodUrl("@office:same-event").startsWith(profiles[1].baseUrl + "/vod/event/same-event/"))
        assertTrue(router.recordingSnapshotUrl("@office:front", 123.0).contains("/api/front/recordings/123/"))
        assertTrue(router.liveMseWebSocketUrl("@office:front_sub").endsWith("src=front_sub"))
        val countA = requestsA.size
        await<List<NativeEvent>> { router.searchEvents("wrong", EventSearch(camera = "@office:front"), 123.0, null, it) }
        assertEquals(countA, requestsA.size)
        assertEquals("front", requestsB.last().requestUrl?.queryParameter("cameras"))
        val latch = CountDownLatch(1)
        router.loadBitmap("wrong", router.eventSnapshotUrl("@office:same-event")) { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("Bearer token-b", requestsB.last().getHeader("Authorization"))
        assertTrue(requestsA.all { it.getHeader("Authorization") == "Bearer token-a" })
        assertTrue(requestsB.all { it.getHeader("Authorization") == "Bearer token-b" })
        assertNull(router.serverForUrl("https://untrusted.example/video"))
        assertTrue(runCatching { router.latestFrameUrl("@missing:front") }.isFailure)
        offlineB = true
        val partial = await<DashboardData> { router.loadDashboard("wrong", it) }
        assertEquals(listOf("front"), partial.cameras.map { it.name })
        assertEquals(setOf("office"), router.unavailable)
    }
    @Test fun formAddsServerAndFailedLoginKeepsFormAndExistingConnections() {
        val protected = filesNow().filterKeys { it != "frigate-servers.enc" }
        ActivityScenario.launch(FrigateServersActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.findViewWithTag<View>("frigate-add").performClick()
                fun field(tag: String, value: String) { root.findViewWithTag<EditText>(tag).setText(value) }
                field("frigate-name", "Bureau"); field("frigate-server", profiles[1].baseUrl)
                field("frigate-user", "admin"); field("frigate-password", "not-stored-password")
                rejectLogin = true
                root.findViewWithTag<View>("frigate-connect").performClick()
            }
            waitFor { requestsB.any { it.path == "/api/login" } }
            Thread.sleep(300)
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                assertNotNull(root.findViewWithTag<View>("frigate-password"))
                assertEquals(1, store.load().size)
                rejectLogin = false
                root.findViewWithTag<View>("frigate-connect").performClick()
            }
            waitFor { store.load().size == 2 }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertNotNull(activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("frigate-add")) }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(context.getExternalFilesDir(null), "frigate-servers-dark.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        }
        protected.forEach { (key, value) -> assertArrayEquals(value, filesNow()[key]) }
        assertFalse(File(context.noBackupFilesDir, "frigate-servers.enc").readBytes().toString(Charsets.ISO_8859_1).contains("not-stored-password"))
    }
    @Test fun notificationsHaveIndependentCursorsAndQualifiedIds() {
        store.save(profiles)
        settings.edit().putBoolean("notificationsEnabled", true).putBoolean("alertSound", false).putBoolean("alertVibration", false).commit()
        val now = System.currentTimeMillis()
        profiles.forEach { native.edit().putLong("notificationCursor:${it.baseUrl}:${it.username}", now - 10_000).commit() }
        assertTrue(CameraNotifications.check(context) { false })
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        waitFor { manager.activeNotifications.map { it.tag }.toSet() == setOf("same-event", "@office:same-event") }
        assertEquals(setOf("same-event", "@office:same-event"), manager.activeNotifications.map { it.tag }.toSet())
        manager.cancelAll()
        waitFor { manager.activeNotifications.isEmpty() }
        assertTrue(CameraNotifications.check(context) { false })
        assertTrue(manager.activeNotifications.isEmpty())
        store.remove("office")
        val count = requestsB.size
        assertTrue(CameraNotifications.check(context) { false })
        assertEquals(count, requestsB.size)
    }
    @Test fun homeFilterSelectsOneServerWithoutChangingConnectionsAndCanReset() {
        store.save(profiles)
        val connections = File(context.noBackupFilesDir, "frigate-servers.enc").readBytes()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var count = 0
            waitFor {
                scenario.onActivity { activity -> count = (MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>).size }
                count == 2
            }
            scenario.onActivity { activity ->
                fun root() = activity.findViewById<ViewGroup>(android.R.id.content)
                fun tiles() = views(root()).filter { (it.tag as? String)?.startsWith("dashboard-camera:") == true }
                assertEquals(2, tiles().size)
                root().findViewWithTag<View>("home-server-filter").performClick()
                val field = MainActivity::class.java.getDeclaredField("homeSourceSheet").apply { isAccessible = true }
                val sheet = field.get(activity) as AppearanceChoiceSheet
                sheet.window!!.decorView.findViewWithTag<View>("appearance-option-home-server-frigate:office").performClick()
                assertEquals(1, tiles().size)
                assertTrue(root().findViewWithTag<View>("home-server-filter").isSelected)
                assertEquals("frigate:office", settings.getString("homeCameraServer", null))
                assertArrayEquals(connections, File(context.noBackupFilesDir, "frigate-servers.enc").readBytes())
                root().findViewWithTag<View>("home-server-filter").performClick()
                (field.get(activity) as AppearanceChoiceSheet).window!!.decorView
                    .findViewWithTag<View>("appearance-option-home-server-all").performClick()
                assertEquals(2, tiles().size)
                assertFalse(root().findViewWithTag<View>("home-server-filter").isSelected)
                assertFalse(settings.contains("homeCameraServer"))
            }
        }
    }

    @Test fun dashboardAndPlayerUseBothSourcesAndSettingsReturnToConnections() {
        store.save(profiles)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var count = 0
            waitFor {
                scenario.onActivity { activity -> count = (MainActivity::class.java.getDeclaredField("cameras").apply { isAccessible = true }.get(activity) as List<*>).size }
                count == 2
            }
            lateinit var factory: androidx.media3.datasource.DataSource.Factory
            scenario.onActivity { activity ->
                factory = MainActivity::class.java.getDeclaredMethod("authDataSource", String::class.java).apply { isAccessible = true }
                    .invoke(activity, "must-not-be-used") as androidx.media3.datasource.DataSource.Factory
            }
            val source = factory.createDataSource()
            try {
                source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(profiles[1].baseUrl + "/vod/event/same-event/master.m3u8")))
                assertEquals("Bearer token-b", requestsB.last().getHeader("Authorization"))
            } finally { source.close() }
            assertTrue(runCatching {
                factory.createDataSource().let { try { it.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse("https://unknown.invalid/clip.mp4"))) } finally { it.close() } }
            }.isFailure)
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderConnectionsSettings").apply { isAccessible = true }.invoke(activity)
                activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("settings-entry-frigate").performClick()
            }
            instrumentation.waitForIdleSync()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.frigate_servers))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            androidx.test.espresso.Espresso.pressBack()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> assertEquals("connections", MainActivity::class.java.getDeclaredField("settingsPage").apply { isAccessible = true }.get(activity)) }
        }
    }
    @Test fun readingAndHidingOnOneServerDoesNotAffectTheOther() {
        store.save(profiles)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var data: List<NativeEvent> = emptyList()
            waitFor {
                scenario.onActivity { activity ->
                    @Suppress("UNCHECKED_CAST")
                    val loaded = MainActivity::class.java.getDeclaredField("events").apply { isAccessible = true }.get(activity) as List<NativeEvent>
                    data = loaded
                }
                data.size == 2
            }
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("markAlertsSeen", List::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, data.filter { it.id == "same-event" }, true)
                val unread = MainActivity::class.java.getDeclaredMethod("alertEvents").apply { isAccessible = true }.invoke(activity) as List<*>
                assertEquals(listOf("@office:same-event"), unread.filterIsInstance<NativeEvent>().map { it.id })
                MainActivity::class.java.getDeclaredMethod("setCameraHidden", String::class.java, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(activity, "front", true)
                val visible = MainActivity::class.java.getDeclaredMethod("visibleCameras").apply { isAccessible = true }.invoke(activity) as List<*>
                assertEquals(listOf("@office:front"), visible.filterIsInstance<NativeCamera>().map { it.name })
            }
        }
    }
    @Test fun captureConnectionFormInBothThemes() {
        for (theme in listOf("dark", "light")) {
            settings.edit().putString("theme", theme).commit()
            ActivityScenario.launch(FrigateServersActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                scenario.onActivity { activity -> activity.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<View>("frigate-add").performClick() }
                instrumentation.waitForIdleSync()
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    val all = views(activity.findViewById(android.R.id.content))
                    assertEquals(4, all.filterIsInstance<EditText>().size)
                    assertTrue(all.filterIsInstance<EditText>().all { it.width > 0 && it.height > 0 })
                    assertEquals(theme == "dark", Palette.dark)
                }
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(context.getExternalFilesDir(null), "frigate-form-$theme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
        }
    }
}
