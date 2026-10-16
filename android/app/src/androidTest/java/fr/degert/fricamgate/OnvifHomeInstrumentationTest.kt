package com.nvr.camnory

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OnvifHomeInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage get() = File(context.noBackupFilesDir, "onvif-cameras.enc")
    private var originalStorage: ByteArray? = null
    private lateinit var native: Map<String, *>
    private lateinit var settings: Map<String, *>

    @Before fun setup() {
        originalStorage = storage.takeIf { it.exists() }?.readBytes()
        native = prefs("fricamgate-native").all
        settings = prefs("camgate-settings").all
        prefs("fricamgate-native").edit().remove("token").commit()
        prefs("camgate-settings").edit().putBoolean("biometricLock", false).putString("homeCameraLayout", "column").commit()
    }
    @After fun cleanup() {
        originalStorage?.let { storage.writeBytes(it) } ?: storage.delete()
        restore(prefs("fricamgate-native"), native)
        restore(prefs("camgate-settings"), settings)
    }

    @Test fun directCameraAppearsWithoutFrigateAndNavigationReleasesIt() {
        val camera = OnvifSavedCamera("home-test", "ONVIF test", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("test", "test"))
        OnvifCameraStore(context).save(listOf(camera))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val cards = cards(activity)
                assertEquals(1, cards.size)
                assertNull(MainActivity::class.java.getDeclaredField("session").apply { isAccessible = true }.get(activity))
                val card = cards.single()
                val field = OnvifLiveCard::class.java.getDeclaredField("active").apply { isAccessible = true }
                assertTrue(field.getBoolean(card))
                MainActivity::class.java.getDeclaredMethod("renderSettings").apply { isAccessible = true }.invoke(activity)
                assertFalse(field.getBoolean(card))
                assertNull(OnvifLiveCard::class.java.getDeclaredField("player").apply { isAccessible = true }.get(card))
                assertTrue(cards(activity).isEmpty())
            }
        }
        assertFalse(prefs("fricamgate-native").contains("token"))
    }

    @Test fun profileSelectionSurvivesEncryptedRoundTripAndOldRecords() {
        val credentials = OnvifCredentials("camera-user", "camera-secret")
        val store = OnvifCameraStore(context)
        store.save(listOf(OnvifSavedCamera("old", "Old", "http://camera", credentials),
            OnvifSavedCamera("new", "New", "http://camera", credentials, "main")))
        val loaded = store.load()
        assertNull(loaded.first().profileToken)
        assertEquals("main", loaded.last().profileToken)
        assertFalse(String(storage.readBytes(), Charsets.ISO_8859_1).contains("camera-secret"))
    }

    @Test fun invalidCredentialsDoNotCauseAutomaticLoginAttempts() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
            server.start()
            OnvifCameraStore(context).save(listOf(OnvifSavedCamera("auth-test", "Test camera", server.url("/onvif/device_service").toString(),
                OnvifCredentials("test", "wrong"))))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(10, java.util.concurrent.TimeUnit.SECONDS))
                Thread.sleep(3000)
                assertEquals("Authentication failure must require manual intervention", 2, server.requestCount)
                scenario.onActivity { activity ->
                    val failed = OnvifLiveCard::class.java.getDeclaredField("failed").apply { isAccessible = true }
                    assertTrue(failed.getBoolean(cards(activity).single()))
                }
            }
        }
    }

    @Test fun compactCardControlsFitSmallColumns() {
        OnvifCameraStore(context).save(emptyList())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val camera = OnvifSavedCamera("layout", "A long camera name", "http://camera", OnvifCredentials("", ""))
                for (widthDp in listOf(90, 144, 296)) {
                    val card = OnvifLiveCard(activity, camera, {}, {})
                    val width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    card.layout(0, 0, width, card.measuredHeight)
                    val controls = card.getChildAt(2) as ViewGroup
                    for (index in 0 until controls.childCount) {
                        val control = controls.getChildAt(index)
                        if (control.visibility != View.GONE) {
                            assertTrue("Control starts outside card at $widthDp dp", control.left >= 0)
                            assertTrue("Control ends outside card at $widthDp dp", control.right <= width)
                        }
                    }
                }
            }
        }
    }

    @Test fun fullscreenReleasesItsPlayerAndManagerEditsAreLocal() {
        val camera = OnvifSavedCamera("fullscreen-test", "Test camera", "http://127.0.0.1:1/onvif/device_service", OnvifCredentials("test", "test"))
        OnvifCameraStore(context).save(listOf(camera))
        val before = prefs("fricamgate-native").all
        ActivityScenario.launch<OnvifActivity>(android.content.Intent(context, OnvifActivity::class.java)
            .putExtra("fullscreen", true)).use { scenario ->
            scenario.onActivity { activity ->
                OnvifActivity::class.java.getDeclaredMethod("play", OnvifSavedCamera::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(activity, camera, "rtsp://127.0.0.1:1/test")
                assertNotNull(OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity))
            }
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1500)
            scenario.onActivity { activity ->
                val full = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                val buttons = (0 until full.childCount).map { full.getChildAt(it) }.filterIsInstance<android.widget.ImageButton>()
                assertEquals(3, buttons.size)
                buttons.forEach { button ->
                    assertTrue("Fullscreen control is outside the screen", button.getGlobalVisibleRect(android.graphics.Rect()))
                    assertTrue(button.right <= full.width)
                }
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { activity ->
                assertNull(OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity))
            }
        }
        ActivityScenario.launch<OnvifActivity>(android.content.Intent(context, OnvifActivity::class.java)
            .putExtra("cameraId", camera.id).putExtra("manage", true)).use { scenario ->
            scenario.onActivity { activity ->
                val dialog = OnvifActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(activity) as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.isShowing)
                dialog.dismiss()
            }
        }
        assertTrue("Frigate credentials changed", before == prefs("fricamgate-native").all)
        assertEquals(camera.endpoint, OnvifCameraStore(context).load().single().endpoint)
    }

    @Test fun hardwareDevantPlaysOnHomeAndReleasesInBackground() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareOnvif") == "true")
        val secret = File(context.filesDir, "onvif-hardware-test.json")
        try {
            val data = JSONObject(secret.readText())
            OnvifCameraStore(context).save(listOf(OnvifSavedCamera("devant-home-test", "Devant", data.getString("endpoint"),
                OnvifCredentials(data.getString("username"), data.getString("password")))))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var card: OnvifLiveCard? = null
                var playing = false
                for (attempt in 0 until 200) {
                    scenario.onActivity { activity ->
                        card = cards(activity).single()
                        playing = player(card!!)?.isPlaying == true
                    }
                    if (playing) break
                    Thread.sleep(200)
                }
                assertTrue("Direct camera home preview did not start", playing)
                var position = 0L
                scenario.onActivity { position = player(card!!)!!.currentPosition }
                Thread.sleep(1500)
                scenario.onActivity { assertTrue(player(card!!)!!.currentPosition > position + 300) }
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                context.openFileOutput("onvif-home-devant.png", Context.MODE_PRIVATE).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { assertNull(player(card!!)) }
                scenario.moveToState(Lifecycle.State.RESUMED)
            }
            ActivityScenario.launch<OnvifActivity>(android.content.Intent(context, OnvifActivity::class.java)
                .putExtra("cameraId", "devant-home-test").putExtra("fullscreen", true)).use { scenario ->
                var playing = false
                for (attempt in 0 until 200) {
                    scenario.onActivity { activity ->
                        playing = (OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as? ExoPlayer)?.isPlaying == true
                    }
                    if (playing) break
                    Thread.sleep(200)
                }
                assertTrue("Fullscreen stream did not start", playing)
                scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                var landscape = false
                for (attempt in 0 until 50) {
                    scenario.onActivity { landscape = it.window.decorView.width > it.window.decorView.height }
                    if (landscape) break
                    Thread.sleep(100)
                }
                assertTrue("Fullscreen did not adapt to landscape", landscape)
                InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("Got it")?.firstOrNull()
                    ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                Thread.sleep(1200)
                scenario.onActivity { activity ->
                    assertTrue((OnvifActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as ExoPlayer).isPlaying)
                }
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                context.openFileOutput("onvif-fullscreen-devant.png", Context.MODE_PRIVATE).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { secret.delete() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cards(activity: MainActivity) = MainActivity::class.java.getDeclaredField("onvifCards").apply { isAccessible = true }
        .get(activity) as List<OnvifLiveCard>
    private fun player(card: OnvifLiveCard) = OnvifLiveCard::class.java.getDeclaredField("player").apply { isAccessible = true }.get(card) as? ExoPlayer
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun restore(prefs: SharedPreferences, original: Map<String, *>) {
        val edit = prefs.edit().clear()
        original.forEach { (key, value) -> when (value) {
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
