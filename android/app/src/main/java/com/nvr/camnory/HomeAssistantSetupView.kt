package com.nvr.camnory

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors

/** One reusable camera-only setup form, embedded in onboarding or settings. */
internal class HomeAssistantSetupView(
    context: Context,
    private val saved: HomeAssistantConnection?,
    private val canInteract: () -> Boolean,
    private val onSaved: () -> Unit,
    private val onBusy: (Boolean) -> Unit = {},
    private val allowDisconnect: Boolean = false
) : LinearLayout(context) {
    private val ui = ConnectionFormUi(context)
    private var client: HomeAssistantClient? = null
    private var worker: java.util.concurrent.ExecutorService? = null
    private var generation = 0
    private var busy = false
    private var status: TextView? = null
    private var connect: Button? = null
    private var dialog: AlertDialog? = null
    var choosing = false
        private set

    init { orientation = VERTICAL; tag = "connection-ha-form"; showForm() }

    fun showForm() {
        cancel(); choosing = false; removeAllViews()
        val address = ui.field(this, context.getString(R.string.ha_server), "https://maison.example.fr", saved?.baseUrl.orEmpty(), key = "connection-ha-server")
        val token = ui.field(this, context.getString(R.string.ha_token), context.getString(if (saved == null) R.string.ha_token_hint else R.string.ha_keep_token),
            secret = true, key = "connection-ha-token")
        addView(ui.action(context.getString(R.string.connection_token_help), false) {
            dialog = AlertDialog.Builder(context).setTitle(R.string.ha_token).setMessage(R.string.ha_token_help)
                .setPositiveButton(android.R.string.ok, null).show()
        }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; textSize = 12f * ui.textScale })
        val advanced = ui.advanced(this, "connection-ha-advanced")
        val localHttp = CheckBox(context).apply {
            text = context.getString(R.string.ha_allow_local_http); setTextColor(Palette.muted); textSize = 12f
            isChecked = saved?.baseUrl?.startsWith("http://") == true
        }
        advanced.addView(localHttp)
        if (allowDisconnect && saved != null) advanced.addView(ui.action(context.getString(R.string.ha_disconnect), false) {
            dialog = AlertDialog.Builder(context).setMessage(R.string.ha_disconnect_question)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!canInteract()) return@setPositiveButton
                    runCatching { HomeAssistantStore(context).save(null) }.onSuccess { onSaved() }
                        .onFailure { Toast.makeText(context, R.string.ha_storage_failed, Toast.LENGTH_LONG).show() }
                }.show()
        })
        val message = ui.text("").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        status = message
        val button = ui.action(context.getString(R.string.ha_select_cameras)) {}
        connect = button
        button.setOnClickListener {
            if (!canInteract() || busy) return@setOnClickListener
            val base = runCatching { homeAssistantBaseUrl(address.text.toString()) }.getOrNull()
            if (base == null) { address.error = context.getString(R.string.ha_invalid_address); return@setOnClickListener }
            if (base.startsWith("http://") && !localHttp.isChecked) {
                if (advanced.visibility == View.GONE) findViewWithTag<View>("connection-ha-advanced").performClick()
                message.text = context.getString(R.string.ha_allow_local_http); return@setOnClickListener
            }
            val secret = token.text.toString().trim().ifBlank { saved?.takeIf { it.baseUrl == base }?.token.orEmpty() }
            if (secret.isBlank()) { token.error = context.getString(R.string.ha_token_hint); return@setOnClickListener }
            val api = runCatching { HomeAssistantClient(base, secret) }.getOrNull()
            if (api == null) { token.error = context.getString(R.string.ha_token_hint); return@setOnClickListener }
            cancel(); busy = true; client = api; button.isEnabled = false; onBusy(true)
            message.text = context.getString(R.string.connecting)
            val request = generation
            val executor = Executors.newSingleThreadExecutor().also { worker = it }
            executor.submit {
                val result = runCatching { api.cameras() }
                post {
                    if (request != generation || !isAttachedToWindow || !isShown || !canInteract()) return@post
                    busy = false; button.isEnabled = true; onBusy(false)
                    result.onSuccess { cameras -> token.text.clear(); showSelection(HomeAssistantConnection(base, secret, cameras)) }
                        .onFailure { message.text = context.getString(if (it is HomeAssistantAuthException) R.string.ha_auth_failed else R.string.ha_connection_failed) }
                    api.close()
                }
            }
        }
        ui.spacer(this); addView(button); addView(message)
    }

    private fun showSelection(connection: HomeAssistantConnection) {
        cancel(); choosing = true; removeAllViews()
        addView(ui.action(context.getString(R.string.onvif_back), false) { showForm() }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL })
        addView(ui.text(context.getString(R.string.ha_select_cameras), 18, Palette.text))
        val selected = if (saved?.baseUrl == connection.baseUrl) saved.cameras.map { it.entityId }.toMutableSet() else mutableSetOf()
        val count = ui.text("").apply { setPadding(0, ui.dp(12), 0, 0) }
        fun refresh() { count.text = context.getString(R.string.ha_selected_count, selected.size) }
        if (connection.cameras.isEmpty()) addView(ui.text(context.getString(R.string.ha_no_cameras)))
        connection.cameras.forEach { camera ->
            addView(CheckBox(context).apply {
                text = if (camera.available) camera.name else "${camera.name} · ${context.getString(R.string.ha_unavailable)}"
                setTextColor(Palette.text); textSize = 15f; minHeight = ui.dp(52); isChecked = camera.entityId in selected
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(camera.entityId) else selected.remove(camera.entityId); refresh() }
            }, LayoutParams(-1, -2))
        }
        refresh(); addView(count)
        addView(ui.action(context.getString(R.string.ha_save_selection)) {
            if (canInteract()) runCatching {
                HomeAssistantStore(context).save(HomeAssistantConnection(connection.baseUrl, connection.token,
                    connection.cameras.filter { it.entityId in selected }))
            }.onSuccess { onSaved() }.onFailure { Toast.makeText(context, R.string.ha_storage_failed, Toast.LENGTH_LONG).show() }
        })
    }

    fun cancel() {
        generation++; client?.close(); client = null; worker?.shutdownNow(); worker = null
        busy = false; connect?.isEnabled = true; onBusy(false)
        status?.text = ""; dialog?.dismiss(); dialog = null
    }
    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
}
