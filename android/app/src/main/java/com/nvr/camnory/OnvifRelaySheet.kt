package com.nvr.camnory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

internal class OnvifRelaySheet(context: Context, private val camera: OnvifSavedCamera,
    private val allowed: () -> Boolean, private val changed: () -> Unit
) : SelectionBottomSheet(context, "ONVIF · ${camera.name}") {
    private val client = OnvifRelayClient()
    private val bindings = OnvifRelayBindings(context, camera)
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rows: LinearLayout
    private var catalog: OnvifRelayCatalog? = null
    private var picker: AppearanceChoiceSheet? = null
    private var confirmation: AlertDialog? = null
    private var ptzSheet: OnvifPtzSheet? = null
    @Volatile private var closed = false
    private var started = false
    private var busy = false

    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(20)); rows = this
        addView(ConnectionFormUi(context).text(context.getString(R.string.accessory_loading)))
    }
    override fun onStart() {
        super.onStart()
        if (!started) { started = true; discover() }
    }
    private fun discover() {
        busy = true
        worker.execute {
            val result = runCatching { client.discover(camera) }
            handler.post {
                if (closed) return@post
                busy = false
                result.onSuccess { catalog = it; render() }.onFailure { render(failed = true) }
            }
        }
    }
    private fun render(failed: Boolean = false) {
        val ui = ConnectionFormUi(context)
        rows.removeAllViews()
        rows.addView(ui.action(context.getString(R.string.ptz_title), false) {
            if (!closed && allowed()) {
                ptzSheet?.dismiss()
                ptzSheet = OnvifPtzSheet(context, camera, allowed).also { it.show() }
            }
        }.apply { tag = "native-ptz" })
        if (failed) {
            rows.addView(ui.text(context.getString(R.string.accessory_failed)))
            rows.addView(ui.action(context.getString(R.string.retry), false) { if (!busy) discover() })
            return
        }
        val outputs = catalog?.relays.orEmpty()
        if (outputs.isEmpty()) { rows.addView(ui.text(context.getString(R.string.onvif_no_relays))); return }
        rows.addView(ui.text(context.getString(R.string.onvif_relay_warning)))
        listOf("light", "siren").forEach { kind ->
            val token = bindings.get(kind)
            val relay = outputs.firstOrNull { it.token == token }
            val title = context.getString(if (kind == "light") R.string.accessory_light else R.string.accessory_siren)
            rows.addView(ui.action("$title · ${token ?: context.getString(R.string.accessory_none)}", false) {
                if (!busy && allowed()) {
                    picker = AppearanceChoiceSheet(context, title, "native-$kind", token ?: "",
                        listOf(AppearanceSheetOption("", context.getString(R.string.accessory_none))) +
                            outputs.filter { it.token != bindings.get(if (kind == "light") "siren" else "light") }
                                .map { AppearanceSheetOption(it.token, "${it.token} · ${it.mode}") }) {
                        if (!closed && allowed()) {
                            bindings.set(kind, it.takeIf(String::isNotEmpty)); render(); changed()
                        }
                    }.also { it.show() }
                }
            })
            if (relay != null) {
                rows.addView(ui.text(context.getString(R.string.onvif_relay_state_unknown)))
                listOf(true, false).forEach { active ->
                    rows.addView(ui.action(context.getString(if (active) R.string.accessory_on else R.string.accessory_off), false) {
                        confirm(kind, relay.token, active)
                    }.apply { tag = "native-relay-$kind-$active" })
                }
            }
        }
    }
    private fun confirm(kind: String, token: String, active: Boolean) {
        if (closed || busy || !allowed() || bindings.get(kind) != token) return
        val title = context.getString(if (active) R.string.accessory_on else R.string.accessory_off)
        confirmation = AlertDialog.Builder(context).setTitle(title)
            .setMessage(context.getString(R.string.accessory_confirmation, camera.name, token, java.net.URI(camera.endpoint).host) +
                "\n\n" + context.getString(if (kind == "siren") R.string.accessory_siren_warning else R.string.onvif_relay_warning))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(title) { _, _ ->
                if (!closed && !busy && allowed() && bindings.get(kind) == token) {
                    busy = true
                    worker.execute {
                        val result = runCatching { if (!closed) client.set(camera, requireNotNull(catalog), token, active) }
                        handler.post { if (!closed) { busy = false; render(failed = result.isFailure) } }
                    }
                }
            }.show()
    }
    override fun dismiss() {
        closed = true; confirmation?.dismiss(); picker?.dismiss(); ptzSheet?.dismiss()
        handler.removeCallbacksAndMessages(null); client.close(); worker.shutdownNow(); super.dismiss()
    }
}
