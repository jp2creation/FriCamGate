package com.nvr.camnory

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OnvifDiscoveryInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var settings: Map<String, *>
    @Before fun setup() {
        settings = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).all
        context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).edit().putBoolean("biometricLock", false).commit()
    }
    @After fun cleanup() {
        val edit = context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).edit().clear()
        settings.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        edit.commit()
    }

    @Test fun selectingDiscoveryResultPrefillsFormWithoutConnectingOrSaving() {
        val before = OnvifCameraStore(context).load().map { it.id }
        val native = context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE).all
        ActivityScenario.launch(OnvifActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val camera = OnvifDiscoveredCamera("Camera test", "http://192.168.1.25:8000/onvif/device_service", "192.168.1.25")
                val view = OnvifDiscoveryView(activity, select = { selected ->
                    OnvifActivity::class.java.getDeclaredMethod("add", OnvifSavedCamera::class.java, OnvifDiscoveredCamera::class.java)
                        .apply { isAccessible = true }.invoke(activity, null, selected)
                }, search = {}, cancel = {})
                activity.setContentView(view)
                view.begin(); view.add(camera, false); view.add(camera, false); view.finish()
                val rows = (view.getChildAt(2) as android.widget.ScrollView).getChildAt(0) as ViewGroup
                assertEquals(1, rows.childCount)
                rows.getChildAt(0).performClick()
                val dialog = OnvifActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(activity) as androidx.appcompat.app.AlertDialog
                val inputs = descendants(dialog.window!!.decorView).filterIsInstance<EditText>()
                assertEquals(listOf(camera.name, camera.endpoint, "", ""), inputs.map { it.text.toString() })
                dialog.dismiss()
            }
        }
        assertEquals(before, OnvifCameraStore(context).load().map { it.id })
        assertTrue("Discovery changed the Frigate session", native == context.getSharedPreferences("fricamgate-native", Context.MODE_PRIVATE).all)
    }

    @Test fun resultStatesAndAlreadySavedRowsRemainUsableAtSmallWidth() {
        ActivityScenario.launch(OnvifActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var searches = 0
                var cancels = 0
                val view = OnvifDiscoveryView(activity, select = { fail("Already saved result must not connect") },
                    search = { searches++ }, cancel = { cancels++ })
                activity.setContentView(view)
                view.begin()
                val action = view.getChildAt(3)
                action.performClick(); assertEquals(1, cancels)
                view.add(OnvifDiscoveredCamera("Already saved", "http://192.168.1.25:8000/onvif/device_service", "192.168.1.25"), true)
                val rows = (view.getChildAt(2) as android.widget.ScrollView).getChildAt(0) as ViewGroup
                assertFalse(rows.getChildAt(0).isClickable)
                view.finish(); action.performClick(); assertEquals(1, searches)
                view.begin(); view.finish(R.string.onvif_search_network)
                assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.onvif_search_network) })
                val width = (320 * activity.resources.displayMetrics.density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertTrue(action.bottom <= view.height)
            }
        }
    }

    @Test fun backgroundingClosesAnActiveDiscoverySocket() {
        val address = localAddress()
        DatagramSocket(InetSocketAddress(address, 0)).use { fixture ->
            fixture.soTimeout = 2000
            ActivityScenario.launch(OnvifActivity::class.java).use { scenario ->
                lateinit var discovery: OnvifDiscovery
                scenario.onActivity { activity ->
                    discovery = OnvifActivity::class.java.getDeclaredField("discovery").apply { isAccessible = true }.get(activity) as OnvifDiscovery
                }
                val done = CountDownLatch(1)
                val failure = AtomicReference<Throwable?>()
                thread(isDaemon = true) {
                    try { discovery.search(address, target = InetSocketAddress(address, fixture.localPort)) }
                    catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
                }
                try {
                    fixture.receive(DatagramPacket(ByteArray(32768), 32768))
                    scenario.moveToState(Lifecycle.State.CREATED)
                    assertTrue(done.await(1, TimeUnit.SECONDS)); assertNull(failure.get())
                } finally { discovery.cancel() }
            }
        }
    }

    @Test fun hardwareDiscoveryTargetsOnlyDevant() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hardwareDiscovery") == "true")
        val local = requireNotNull(onvifDiscoveryNetwork(context))
        val discovery = OnvifDiscovery()
        val found = discovery.search(local.address, bindNetwork = { local.network.bindSocket(it) }, target = InetSocketAddress("192.168.0.109", 3702))
        assertTrue("Devant unicast discovery: packets=${discovery.receivedPacketCount}, local=${local.address.hostAddress}", found.any { it.host == "192.168.0.109" })
        assertTrue(found.all { it.host == "192.168.0.109" })
        val drawn = CountDownLatch(1)
        ActivityScenario.launch(OnvifActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background)
                    setPadding(24, 96, 24, 48)
                    addView(TextView(activity).apply { text = activity.getString(R.string.onvif_search); textSize = 22f; setTextColor(Palette.text) })
                }
                val view = OnvifDiscoveryView(activity, select = {}, search = {}, cancel = {})
                root.viewTreeObserver.addOnDrawListener { drawn.countDown() }
                root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                activity.setContentView(root)
                view.begin(); found.forEach { view.add(it, false) }; view.finish()
            }
            assertTrue("Discovery results were not drawn", drawn.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(300)
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            context.openFileOutput("onvif-discovery-devant.png", Context.MODE_PRIVATE).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun localAddress(): Inet4Address = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>().first { it.isSiteLocalAddress }
}
