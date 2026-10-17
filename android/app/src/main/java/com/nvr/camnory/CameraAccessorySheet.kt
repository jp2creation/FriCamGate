package com.nvr.camnory

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/** Discovery and association are read-only. Commands exist only behind the confirmation action. */
internal class CameraAccessorySheet(context: Context, cameraKey: String, private val cameraName: String,
    private val connection: HomeAssistantConnection?, private val allowed: () -> Boolean,
    private val onChanged: () -> Unit = {}
) : SelectionBottomSheet(context, cameraName) {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = connection?.let { HomeAssistantClient(it.baseUrl, it.token) }
    private val bindings = connection?.let { CameraAccessoryBindings(context, cameraKey, it.baseUrl) }
    private lateinit var rows: LinearLayout
    private var entities = emptyList<CameraAccessory>()
    @Volatile private var closed = false
    private var started = false
    private var busy = false
    private var confirmation: AlertDialog? = null
    private var picker: AppearanceChoiceSheet? = null

    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(20)); rows = this
        addView(ConnectionFormUi(context).text(context.getString(if (client == null) R.string.accessory_no_ha else R.string.accessory_loading)))
    }

    override fun onStart() {
        super.onStart()
        if (!started) { started = true; if (client != null) load() }
    }

    private fun load() {
        busy = true
        worker.execute {
            val result = runCatching { requireNotNull(client).accessories() }
            handler.post {
                if (closed) return@post
                busy = false
                result.onSuccess { entities = it; render() }.onFailure { render(failed = true) }
            }
        }
    }

    private fun render(failed: Boolean = false) {
        rows.removeAllViews()
        val ui = ConnectionFormUi(context)
        rows.addView(ui.text(context.getString(R.string.accessory_via_ha)))
        if (failed) {
            rows.addView(ui.text(context.getString(R.string.accessory_failed)))
            rows.addView(ui.action(context.getString(R.string.retry), false) { if (!busy) load() })
            return
        }
        listOf("light", "siren").forEach { domain ->
            val current = bindings?.get(domain)
            val entity = entities.firstOrNull { it.entityId == current }
            val heading = context.getString(if (domain == "light") R.string.accessory_light else R.string.accessory_siren)
            rows.addView(ui.text(heading, 16, Palette.text).apply { setPadding(0, dp(20), 0, dp(6)) })
            rows.addView(ui.action(entity?.name ?: current ?: context.getString(R.string.accessory_none), false) { choose(domain) }.apply {
                tag = "accessory-bind-$domain"
            })
            if (current != null) {
                rows.addView(ui.text(entity?.enabled?.let { context.getString(if (it) R.string.enabled else R.string.disabled) }
                    ?: context.getString(R.string.accessory_unavailable)))
                rows.addView(LinearLayout(context).apply {
                    listOf(true, false).forEach { target ->
                        addView(ui.action(context.getString(if (target) R.string.accessory_on else R.string.accessory_off), false) {
                            if (entity != null) confirm(entity, target)
                        }.apply {
                            tag = "accessory-command-$domain-$target"
                            isEnabled = entity != null && (if (target) entity.enabled != null && entity.canOn else entity.canOff)
                            alpha = if (isEnabled) 1f else 0.4f
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                })
            }
        }
    }

    private fun choose(domain: String) {
        if (busy || closed || !allowed()) return
        val options = listOf(AppearanceSheetOption("", context.getString(R.string.accessory_none))) +
            entities.filter { it.domain == domain }.map { AppearanceSheetOption(it.entityId, "${it.name} · ${it.entityId}") }
        picker = AppearanceChoiceSheet(context, context.getString(if (domain == "light") R.string.accessory_light else R.string.accessory_siren),
            "accessory-$domain", bindings?.get(domain) ?: "", options) { id ->
            if (!closed && allowed()) {
                runCatching { requireNotNull(bindings).set(domain, id.takeIf(String::isNotEmpty)) }
                    .onSuccess { render(); onChanged() }.onFailure { render(failed = true) }
            }
        }.also { it.show() }
    }

    private fun confirm(entity: CameraAccessory, enabled: Boolean) {
        if (closed || busy || !allowed() || bindings?.get(entity.domain) != entity.entityId) return
        val action = context.getString(if (enabled) R.string.accessory_on else R.string.accessory_off)
        confirmation = AlertDialog.Builder(context).setTitle("$action · ${entity.name}")
            .setMessage(context.getString(R.string.accessory_confirmation, cameraName, entity.entityId,
                android.net.Uri.parse(connection?.baseUrl.orEmpty()).host.orEmpty()) +
                if (entity.domain == "siren") "\n\n${context.getString(R.string.accessory_siren_warning)}" else "")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(action) { _, _ ->
                if (!closed && !busy && allowed() && bindings?.get(entity.domain) == entity.entityId) {
                    busy = true; rows.isEnabled = false
                    worker.execute {
                        val result = runCatching { if (!closed) requireNotNull(client).setAccessory(entity.entityId, enabled) }
                        handler.post {
                            if (closed) return@post
                            busy = false; rows.isEnabled = true
                            result.onSuccess { load() }.onFailure { render(failed = true) }
                        }
                    }
                }
            }.show()
    }

    override fun dismiss() {
        closed = true; confirmation?.dismiss(); picker?.dismiss()
        handler.removeCallbacksAndMessages(null); client?.close(); worker.shutdownNow()
        super.dismiss()
    }
}
