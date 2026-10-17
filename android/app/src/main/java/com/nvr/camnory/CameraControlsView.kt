package com.nvr.camnory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import java.util.concurrent.Executors

internal data class CameraControlTarget(val name: String, val onvif: OnvifSavedCamera? = null,
    val homeAssistant: HomeAssistantConnection? = null, val haEntity: String? = null,
    val frigate: FrigateServer? = null, val frigateCamera: String? = null) {
    init { require(listOf(onvif != null, homeAssistant != null, frigate != null).count { it } <= 1) }
    fun ptzDriver(): CameraPtzDriver? = when {
        onvif != null -> OnvifCameraPtzDriver(onvif)
        frigate != null && frigateCamera != null -> FrigateCameraPtzDriver(frigate, frigateCamera)
        else -> null
    }
}

/** Same controls for every source. No manual bindings, generic relays, scripts or household alarms. */
internal class CameraControlsView(context: Context, private val target: CameraControlTarget, private val allowed: () -> Boolean)
    : LinearLayout(context) {
    private val ui = ConnectionFormUi(context)
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val ha = target.homeAssistant?.let { HomeAssistantClient(it.baseUrl, it.token) }
    private var probe: CameraPtzDriver? = null
    private var capabilities: CameraPtzState? = null
    private var entities = emptyList<CameraAccessory>()
    private var ptzSheet: CameraPtzSheet? = null
    private var confirmation: AlertDialog? = null
    @Volatile private var closed = false
    private var started = false
    private var busy = false
    private var loading = true
    init { orientation = VERTICAL; tag = "camera-builtins"; render() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!started) { started = true; load() } }
    private fun load() {
        if (closed || busy) return
        loading = true; busy = true; render()
        worker.execute {
            val accessories = runCatching {
                if (closed || ha == null || target.haEntity == null) emptyList() else ha.cameraBuiltins(target.haEntity)
            }
            val ptz = runCatching {
                probe?.close()
                if (closed) null else target.ptzDriver()?.also { probe = it }?.discover()
            }
            handler.post {
                if (!closed) { busy = false; loading = false; entities = accessories.getOrDefault(emptyList()); capabilities = ptz.getOrNull(); render() }
            }
        }
    }
    private fun render() {
        removeAllViews()
        listOf("light" to R.string.accessory_light, "siren" to R.string.accessory_siren).forEach { (domain, label) ->
            val entity = entities.singleOrNull { it.domain == domain }
            val usable = !loading && entity?.enabled != null && entity.canOn && entity.canOff
            val toggle = SwitchCompat(context).apply {
                tag = "builtin-$domain"; contentDescription = context.getString(label)
                isChecked = entity?.enabled == true; isEnabled = usable && !busy
                applyAccentTint()
                setOnCheckedChangeListener { _, next ->
                    if (entity != null && !busy && allowed()) {
                        // Re-render immediately: the displayed state changes only after a fresh server read.
                        render(); confirm(entity, next)
                    }
                }
            }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(66)
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(ui.text(context.getString(label), 15, if (usable) Palette.text else Palette.muted))
                    addView(ui.text(context.getString(if (loading) R.string.builtin_checking else if (!usable) R.string.builtin_unavailable
                        else if (entity?.enabled == true) R.string.enabled else R.string.disabled), 12))
                }, LayoutParams(0, -2, 1f))
                addView(toggle)
            }, LayoutParams(-1, -2))
        }
        addView(ui.action(context.getString(R.string.ptz_title), false) {
            if (!closed && !busy && allowed() && capabilities?.available == true) {
                val driver = target.ptzDriver() ?: return@action
                ptzSheet?.dismiss()
                ptzSheet = CameraPtzSheet(context, target.name, driver, allowed).also { it.show() }
            }
        }.apply {
            tag = "builtin-ptz"; isEnabled = !busy && capabilities?.available == true
            alpha = if (isEnabled) 1f else 0.4f
        })
        if (capabilities?.available != true) addView(ui.text(context.getString(if (loading) R.string.builtin_checking else R.string.builtin_unavailable)))
    }
    private fun confirm(entity: CameraAccessory, enabled: Boolean) {
        if (closed || busy || !allowed()) return
        confirmation?.dismiss()
        val title = context.getString(if (enabled) R.string.accessory_on else R.string.accessory_off)
        confirmation = AlertDialog.Builder(context).setTitle("$title · ${context.getString(if (entity.domain == "light") R.string.accessory_light else R.string.accessory_siren)}")
            .setMessage(target.name + if (entity.domain == "siren") "\n\n" + context.getString(R.string.accessory_siren_warning) else "")
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(title) { _, _ ->
                if (!closed && !busy && allowed()) {
                    busy = true; render()
                    worker.execute {
                        val result = runCatching {
                            if (closed) throw java.io.InterruptedIOException()
                            requireNotNull(ha).setCameraBuiltin(requireNotNull(target.haEntity), entity.entityId, enabled)
                        }
                        handler.post {
                            if (!closed) {
                                busy = false
                                if (result.isFailure) android.widget.Toast.makeText(context, R.string.accessory_failed, android.widget.Toast.LENGTH_LONG).show()
                                load()
                            }
                        }
                    }
                }
            }.show()
    }
    fun close() {
        closed = true; confirmation?.dismiss(); ptzSheet?.dismiss(); probe?.close(); ha?.close()
        handler.removeCallbacksAndMessages(null); worker.shutdownNow()
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}

internal class CameraControlsSheet(context: Context, private val target: CameraControlTarget, private val allowed: () -> Boolean)
    : SelectionBottomSheet(context, target.name) {
    override val maxHeightFraction = 0.70f
    private var controls: CameraControlsView? = null
    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(16))
        addView(CameraControlsView(context, target, allowed).also { controls = it })
        target.onvif?.let { camera -> addView(ConnectionFormUi(context).action(context.getString(R.string.camera_all_settings), false) {
            if (allowed()) {
                context.startActivity(android.content.Intent(context, OnvifCameraSettingsActivity::class.java).putExtra("cameraId", camera.id)); dismiss()
            }
        }) }
    }
    override fun dismiss() { controls?.close(); super.dismiss() }
}
