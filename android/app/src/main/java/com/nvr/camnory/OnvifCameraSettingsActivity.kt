package com.nvr.camnory

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import java.util.concurrent.Executors

class OnvifCameraSettingsActivity : AppCompatActivity() {
    private val client = OnvifDeviceSettingsClient()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var lock: BiometricAppLock
    private lateinit var rows: LinearLayout
    private var controls: OnvifRelaySheet? = null
    private var confirmation: AlertDialog? = null
    @Volatile private var generation = 0
    @Volatile private var foreground = false
    private var busy = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun permitted() = foreground && !lock.locked && !isFinishing
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Appearance.loadPalette(getSharedPreferences("camgate-settings", 0), resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !Palette.dark; isAppearanceLightNavigationBars = !Palette.dark
        }
        lock = BiometricAppLock(this) { load() }
        val ui = ConnectionFormUi(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.background) }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(16), 0)
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = getString(R.string.onvif_back); setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(56)))
            addView(ui.text(getString(R.string.camera_all_settings), 19, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
        })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root); lock.protectContent()
    }
    private fun load() {
        if (!permitted() || busy) return
        val saved = runCatching { OnvifCameraStore(this).load().firstOrNull { it.id == intent.getStringExtra("cameraId") } }.getOrNull()
        if (saved == null) { finish(); return }
        busy = true; val request = ++generation
        rows.removeAllViews(); rows.addView(ConnectionFormUi(this).text(getString(R.string.accessory_loading)))
        worker.execute {
            val result = runCatching { client.load(saved) }
            runOnUiThread {
                if (request != generation || !permitted()) return@runOnUiThread
                busy = false
                result.onSuccess { render(saved, it) }.onFailure {
                    rows.removeAllViews(); val ui = ConnectionFormUi(this)
                    rows.addView(ui.text(getString(R.string.accessory_failed)))
                    rows.addView(ui.action(getString(R.string.retry), false) { load() }); connectionButton(saved)
                }
            }
        }
    }
    private fun section(title: String, body: LinearLayout.() -> Unit) {
        val ui = ConnectionFormUi(this)
        rows.addView(ui.text(title, 16, Palette.text).apply { setPadding(0, dp(24), 0, dp(12)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        rows.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body() })
    }
    private fun LinearLayout.fact(title: String, value: String) {
        val ui = ConnectionFormUi(this@OnvifCameraSettingsActivity)
        addView(ui.text(title, 12).apply { setPadding(0, dp(10), 0, dp(4)) })
        addView(ui.text(value.ifBlank { getString(R.string.accessory_unavailable) }, 15, Palette.text).apply {
            setTextIsSelectable(true); setPadding(0, 0, 0, dp(8))
        })
    }
    private fun render(saved: OnvifSavedCamera, state: OnvifDeviceSettings) {
        val ui = ConnectionFormUi(this)
        rows.removeAllViews(); rows.addView(ui.text(saved.name, 23, Palette.text))
        section(getString(R.string.device_information)) {
            val labels = resources.getStringArray(R.array.device_info_names)
            listOf("Manufacturer", "Model", "SerialNumber", "FirmwareVersion", "HardwareId").forEachIndexed { index, key -> fact(labels[index], state.information[key].orEmpty()) }
        }
        section(getString(R.string.device_video_profiles)) {
            if (state.profiles.isEmpty()) addView(ui.text(getString(R.string.accessory_unavailable)))
            state.profiles.forEach { profile -> fact(profile.name, listOfNotNull(profile.encoding, profile.resolution).joinToString(" · ")) }
            addView(ui.text(getString(R.string.device_readonly_note)))
        }
        section(getString(R.string.device_image)) {
            if (state.imaging.isEmpty()) addView(ui.text(getString(if (state.imagingFailed) R.string.accessory_failed else R.string.device_no_image)))
            state.imaging.forEach { field -> imageControl(this, saved, state, field) }
            state.readOnlyImage.filterKeys { path -> path !in state.imaging.map { it.key } }.forEach { (name, value) -> fact(name, value) }
            addView(ui.text(getString(R.string.device_readonly_note)))
        }
        section(getString(R.string.camera_native_controls)) {
            addView(ui.action(getString(R.string.ptz_title) + " · " + getString(R.string.accessory_title), false) {
                if (permitted()) { controls?.dismiss(); controls = OnvifRelaySheet(this@OnvifCameraSettingsActivity, saved, ::permitted) {}.also { it.show() } }
            })
            addView(ui.text(getString(R.string.camera_only_warning)))
        }
        section(getString(R.string.device_services)) {
            state.services.forEach { fact("ONVIF", it) }
            addView(ui.text(getString(R.string.device_advanced_note)))
        }
        connectionButton(saved)
    }
    private fun connectionButton(saved: OnvifSavedCamera) {
        rows.addView(ConnectionFormUi(this).action(getString(R.string.device_connect_settings), false) {
            if (permitted()) startActivity(Intent(this, OnvifActivity::class.java).putExtra("cameraId", saved.id).putExtra("manage", true))
        })
    }
    private fun imageControl(parent: LinearLayout, saved: OnvifSavedCamera, state: OnvifDeviceSettings, field: OnvifImageSetting) {
        val ui = ConnectionFormUi(this)
        val title = resources.getStringArray(R.array.device_image_names)[ONVIF_IMAGE_FIELDS.indexOf(field.key)]
        var value = field.value
        fun caption() = "$title · ${String.format(Locale.getDefault(), "%.1f", value)}"
        val label = ui.text(caption(), 15, Palette.text); parent.addView(label)
        parent.addView(SeekBar(this).apply {
            max = 1000; progress = ((value - field.min) / (field.max - field.min) * 1000).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(Palette.accent); contentDescription = title
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { value = field.min + (field.max - field.min) * progress / 1000.0; label.text = caption() }
                }
            })
        }, LinearLayout.LayoutParams(-1, dp(48)))
        parent.addView(ui.action(getString(R.string.device_apply), false) {
            if (!permitted() || busy || value == field.value) return@action
            val target = value; confirmation?.dismiss()
            confirmation = AlertDialog.Builder(this).setTitle(title)
                .setMessage(getString(R.string.device_image_confirm, saved.name, String.format(Locale.getDefault(), "%.1f", target)))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.device_apply) { _, _ ->
                    if (!permitted() || busy) return@setPositiveButton
                    busy = true; val request = ++generation
                    worker.execute {
                        val result = runCatching {
                            if (!foreground || request != generation) throw java.io.InterruptedIOException()
                            client.setImage(saved, state, field.key, target)
                        }
                        runOnUiThread {
                            if (request != generation || !permitted()) return@runOnUiThread
                            busy = false
                            if (result.isFailure) Toast.makeText(this, R.string.accessory_failed, Toast.LENGTH_LONG).show()
                            load()
                        }
                    }
                }.show()
        })
    }
    override fun onStart() { super.onStart(); foreground = true; lock.onForeground(); if (!lock.locked) load() }
    override fun onPause() { lock.protectContent(); super.onPause() }
    override fun onStop() {
        foreground = false; generation++; busy = false; client.cancel()
        confirmation?.dismiss(); controls?.dismiss(); lock.onBackground(); super.onStop()
    }
    override fun onDestroy() { client.close(); worker.shutdownNow(); super.onDestroy() }
}
