package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

internal class OnvifPtzSheet(context: Context, camera: OnvifSavedCamera, allowed: () -> Boolean)
    : CameraPtzSheet(context, camera.name, OnvifCameraPtzDriver(camera), allowed)

internal open class CameraPtzSheet(context: Context, private val cameraName: String, private val client: CameraPtzDriver,
    private val allowed: () -> Boolean) : SelectionBottomSheet(context, "PTZ · $cameraName") {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rows: LinearLayout
    private var catalog: CameraPtzState? = null
    private var confirmation: AlertDialog? = null
    @Volatile private var closed = false
    private var started = false
    private var busy = false
    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(20)); rows = this
    }
    override fun onStart() { super.onStart(); if (!started) { started = true; load() } }
    private fun load() {
        busy = true
        rows.removeAllViews(); rows.addView(ConnectionFormUi(context).text(context.getString(R.string.accessory_loading)))
        worker.execute {
            val result = runCatching { client.discover() }
            handler.post { if (!closed) { busy = false; catalog = result.getOrNull(); render(result.isFailure) } }
        }
    }
    private fun render(failed: Boolean = false) {
        rows.removeAllViews()
        val ui = ConnectionFormUi(context)
        if (failed) {
            rows.addView(ui.text(context.getString(R.string.accessory_failed)))
            rows.addView(ui.action(context.getString(R.string.retry), false) { if (!busy) load() }); return
        }
        val current = catalog
        if (current == null || !current.available) {
            rows.addView(ui.text(context.getString(R.string.ptz_unavailable))); return
        }
        if (current.panTilt) {
            rows.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(control("up", R.drawable.ic_arrow_up, R.string.ptz_up) { act { client.move("up") } })
            })
            rows.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(control("left", R.drawable.ic_arrow_up, R.string.ptz_left, -90f) { act { client.move("left") } })
                addView(control("stop", R.drawable.ic_stop, R.string.ptz_stop) { act { client.stop() } })
                addView(control("right", R.drawable.ic_arrow_up, R.string.ptz_right, 90f) { act { client.move("right") } })
            })
            rows.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(control("down", R.drawable.ic_arrow_up, R.string.ptz_down, 180f) { act { client.move("down") } })
            })
        }
        if (current.zoom) rows.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(control("out", R.drawable.ic_zoom_out, R.string.ptz_zoom_out) { act { client.move("out") } })
            addView(control("in", R.drawable.ic_zoom_in, R.string.ptz_zoom_in) { act { client.move("in") } })
            if (!current.panTilt) addView(control("stop", R.drawable.ic_stop, R.string.ptz_stop) { act { client.stop() } })
        })
        current.presets.forEach { preset ->
            rows.addView(ui.action(preset.name, false) {
                if (!busy && !closed && allowed()) {
                    confirmation?.dismiss()
                    confirmation = AlertDialog.Builder(context).setTitle(preset.name)
                        .setMessage(context.getString(R.string.ptz_preset_confirm, cameraName))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok) { _, _ -> act { client.preset(preset.token) } }.show()
                }
            })
        }
    }
    private fun control(key: String, icon: Int, label: Int, angle: Float = 0f, action: () -> Unit) = ImageButton(context).apply {
        tag = "ptz-$key"; contentDescription = context.getString(label)
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        setImageResource(icon); setColorFilter(Palette.accent); setBackgroundColor(Color.TRANSPARENT)
        rotation = angle; setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        setOnClickListener { action() }
    }
    private fun act(command: () -> Unit) {
        if (closed || busy || !allowed()) return
        busy = true
        worker.execute {
            val result = runCatching { if (!closed && allowed()) command() }
            handler.post { if (!closed) { busy = false; if (result.isFailure) render(true) } }
        }
    }
    override fun dismiss() {
        closed = true; confirmation?.dismiss(); handler.removeCallbacksAndMessages(null)
        client.close(); worker.shutdownNow(); super.dismiss()
    }
}
