package fr.degert.fricamgate

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
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

class SettingsFeaturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var native: SharedPreferences
    private lateinit var settings: SharedPreferences
    private lateinit var savedNative: Map<String, *>
    private lateinit var savedSettings: Map<String, *>
    private lateinit var server: MockWebServer
    private var oldLocales = LocaleListCompat.getEmptyLocaleList()

    @Before
    fun setup() {
        native = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE)
        settings = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE)
        savedNative = native.all
        savedSettings = settings.all
        oldLocales = AppCompatDelegate.getApplicationLocales()
        CameraNotifications.configure(context, false)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/api/config") == true -> MockResponse().setBody("{\"cameras\":{\"test\":{\"enabled\":true}}}")
                request.path?.startsWith("/api/events") == true -> MockResponse().setBody("[]")
                request.path?.startsWith("/api/version") == true -> MockResponse().setBody("test-nvr")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        native.edit().clear().putString("baseUrl", server.url("/").toString().trimEnd('/'))
            .putString("username", "test").putString("token", "test-token").commit()
        settings.edit().clear().commit()
    }

    @After
    fun cleanup() {
        CameraNotifications.configure(context, false)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(oldLocales) }
        restore(native, savedNative)
        restore(settings, savedSettings)
        server.shutdown()
    }

    @Test
    fun allOfferedLanguagesHaveTranslationsAndFormats() {
        val expected = mapOf("fr" to "Réglages", "en" to "Settings", "de" to "Einstellungen", "es" to "Ajustes", "it" to "Impostazioni", "pt" to "Definições")
        expected.forEach { (code, title) ->
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(code)) }
            val localized = context.createConfigurationContext(config)
            assertEquals(title, localized.getString(R.string.settings))
            assertTrue(localized.getString(R.string.camera_count, 2, 4).contains("2 / 4"))
            assertFalse(localized.getString(R.string.notifications_background).isBlank())
            assertFalse(localized.getString(R.string.biometric_detail).isBlank())
        }
    }

    @Test
    fun localeChangesActivityAndSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fr")) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("Réglages", it.getString(R.string.settings)) }
            scenario.recreate()
            scenario.onActivity { assertEquals("fr", AppCompatDelegate.getApplicationLocales().get(0)?.language) }
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals("Settings", it.getString(R.string.settings)) }
            scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(AppCompatDelegate.getApplicationLocales().isEmpty) }
        }
    }

    @Test
    fun persistedLockCoversContentAndCannotBeDisabledWithoutAuthentication() {
        settings.edit().putBoolean("biometricLock", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val lock = BiometricAppLock(activity) { fail("Unexpected unlock") }
                assertTrue(lock.locked)
                activity.setContentView(TextView(activity).apply { text = "private-camera-content" })
                lock.protectContent()
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, content.getChildAt(0).importantForAccessibility)
                lock.changeEnabled(false) { assertTrue(it) }
                assertTrue(settings.getBoolean("biometricLock", false))
                assertTrue(lock.locked)
            }
        }
    }

    @Test
    fun eventNotificationsAreDeliveredOnceAndStopWhenDisabled() {
        val after = System.currentTimeMillis() - 2000
        val time = System.currentTimeMillis() / 1000.0
        native.edit().putLong("notificationCursor:${native.getString("baseUrl", "")}:test", after).commit()
        settings.edit().putBoolean("notificationsEnabled", true).commit()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[{\"id\":\"test-alert\",\"camera\":\"front\",\"label\":\"person\",\"start_time\":$time}]")
        }
        assertTrue(CameraNotifications.check(context) { false })
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.count { it.tag == "test-alert" })
        manager.cancel("test-alert", 0)
        assertTrue(CameraNotifications.check(context) { false })
        assertEquals(0, manager.activeNotifications.count { it.tag == "test-alert" })
        settings.edit().putBoolean("notificationsEnabled", false).commit()
        assertTrue(CameraNotifications.check(context) { false })
        assertEquals(0, manager.activeNotifications.count { it.tag == "test-alert" })
    }

    private fun restore(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
    }
}
