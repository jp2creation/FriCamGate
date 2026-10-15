package com.nvr.camnory

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NotificationDeliveryInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings by lazy { context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE) }
    private val native by lazy { context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE) }
    private lateinit var savedSettings: Map<String, *>
    private lateinit var savedNative: Map<String, *>
    private lateinit var server: MockWebServer
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var eventBody = "[]"

    @Before fun setup() {
        savedSettings = settings.all
        savedNative = native.all
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/events") == true -> MockResponse().setBody(eventBody)
                request.path?.startsWith("/api/config") == true -> MockResponse().setBody("""{"cameras":{}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        native.edit().clear().putString("baseUrl", server.url("/").toString().trimEnd('/'))
            .putString("username", "notification-test").putString("token", "test-token").commit()
        settings.edit().clear().putBoolean("notificationsEnabled", true).commit()
        manager.cancelAll()
    }

    @After fun cleanup() {
        context.stopService(Intent(context, CameraAlertMonitorService::class.java))
        waitFor { !CameraAlertMonitorService.running }
        manager.cancelAll()
        restore(settings, savedSettings)
        restore(native, savedNative)
        CameraNotifications.schedule(context)
        server.shutdown()
    }

    @Test fun testAlertActuallyReachesAndroidWithSoundAndVibrationChannel() {
        assertTrue(CameraNotifications.permitted(context))
        assertTrue(CameraNotifications.test(context))
        assertTrue(waitFor { manager.activeNotifications.any { it.tag?.startsWith("notification-test-") == true } })
        val channel = manager.getNotificationChannel(CameraNotifications.selectedChannel(context))
        assertNotNull(channel.sound)
        assertTrue(channel.shouldVibrate())
    }

    @Test fun realWorkerCheckAppliesFiltersQuietHoursAndDeduplication() {
        val now = System.currentTimeMillis() / 1000.0
        val serverUrl = native.getString("baseUrl", "")!!
        native.edit().putLong("notificationCursor:$serverUrl:notification-test", ((now - 10) * 1000).toLong()).commit()
        settings.edit().putBoolean("alertType:vehicle", false).putBoolean("alertAllCameras", false)
            .putStringSet("alertCameras", setOf("front")).putBoolean("alertQuietHours", true)
            .putInt("alertQuietStart", 0).putInt("alertQuietEnd", 0).commit()
        eventBody = """[{"id":"person-1","camera":"front","label":"person","sub_label":"Bob","start_time":$now},
            {"id":"car-1","camera":"front","label":"car","start_time":$now},
            {"id":"person-2","camera":"back","label":"person","start_time":$now}]"""
        assertTrue(CameraNotifications.check(context) { false })
        assertTrue(waitFor { manager.activeNotifications.any { it.tag == "person-1" } })
        assertEquals(setOf("person-1"), manager.activeNotifications.mapNotNull { it.tag }.toSet())
        val channel = manager.getNotificationChannel(manager.activeNotifications.first().notification.channelId)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        manager.cancelAll()
        assertTrue(CameraNotifications.check(context) { false })
        assertTrue(manager.activeNotifications.isEmpty())
        settings.edit().putLong("alertCooldown", 60).commit()
        eventBody = """[{"id":"person-3","camera":"front","label":"person","start_time":$now}]"""
        assertTrue(CameraNotifications.check(context) { false })
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun ignoredVehicleBurstDoesNotHideAPersonAlert() {
        val now = System.currentTimeMillis() / 1000.0
        val serverUrl = native.getString("baseUrl", "")!!
        native.edit().putLong("notificationCursor:$serverUrl:notification-test", ((now - 10) * 1000).toLong()).commit()
        settings.edit().putBoolean("alertType:vehicle", false).commit()
        val events = org.json.JSONArray().put(org.json.JSONObject()
            .put("id", "wanted-person").put("camera", "front").put("label", "person").put("start_time", now - 2))
        repeat(25) { index -> events.put(org.json.JSONObject()
            .put("id", "ignored-car-$index").put("camera", "front").put("label", "car").put("start_time", now)) }
        eventBody = events.toString()
        assertTrue(CameraNotifications.check(context) { false })
        assertTrue(waitFor { manager.activeNotifications.any { it.tag == "wanted-person" } })
        assertEquals(setOf("wanted-person"), manager.activeNotifications.mapNotNull { it.tag }.toSet())
    }

    @Test fun notificationSettingsSwitchesPersistAndAffectThePolicy() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(R.string.no_cameras))
                .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredMethod("renderNotificationSettings").apply { isAccessible = true }.invoke(activity)
                fun find(view: android.view.View, description: String): androidx.appcompat.widget.SwitchCompat? {
                    if (view is androidx.appcompat.widget.SwitchCompat && view.contentDescription == description) return view
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                        find(view.getChildAt(index), description)?.let { return it }
                    }
                    return null
                }
                val root = activity.findViewById<android.view.View>(android.R.id.content)
                find(root, activity.getString(R.string.alert_sound))!!.isChecked = false
                find(root, activity.getString(R.string.vehicle))!!.isChecked = false
                assertFalse(settings.getBoolean("alertSound", true))
                assertFalse(CameraNotifications.policy(context).accepts("front", "car", 600))
            }
            Thread.sleep(400)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.openFileOutput("notification-settings-test.png", Context.MODE_PRIVATE).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test fun eventAlertIncludesAuthenticatedThumbnail() {
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().apply {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, this)
        }.toByteArray()
        bitmap.recycle()
        val now = System.currentTimeMillis() / 1000.0
        val serverUrl = native.getString("baseUrl", "")!!
        native.edit().putLong("notificationCursor:$serverUrl:notification-test", ((now - 10) * 1000).toLong()).commit()
        var authenticated = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path?.contains("snapshot.jpg") == true) {
                authenticated = request.getHeader("Authorization") == "Bearer test-token"
                MockResponse().setBody(okio.Buffer().write(bytes)).setHeader("Content-Type", "image/jpeg")
            } else MockResponse().setBody("""[{"id":"image-event","camera":"front","label":"person","start_time":$now}]""")
        }
        assertTrue(CameraNotifications.check(context) { false })
        assertTrue(authenticated)
        assertTrue(waitFor { manager.activeNotifications.any { it.tag == "image-event" } })
        assertTrue(manager.activeNotifications.first { it.tag == "image-event" }.notification.extras.containsKey(android.app.Notification.EXTRA_PICTURE))
    }

    @Test fun enhancedMonitoringStartsAndStopsAsAVisibleForegroundService() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(CameraAlertMonitorService.start(it)) }
            assertTrue(waitFor { CameraAlertMonitorService.running && manager.activeNotifications.any { it.id == 4201 } })
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertTrue(CameraAlertMonitorService.running)
            assertTrue(manager.activeNotifications.any { it.id == 4201 })
            scenario.onActivity { context.stopService(Intent(context, CameraAlertMonitorService::class.java)) }
            assertTrue(waitFor { !CameraAlertMonitorService.running })
        }
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(80) { if (condition()) return true; Thread.sleep(100) }
        return false
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        edit.commit()
    }
}
