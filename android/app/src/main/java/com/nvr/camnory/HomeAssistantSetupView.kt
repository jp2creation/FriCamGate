package com.nvr.camnory

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
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
    private var connection = saved
    private var addressDraft = saved?.baseUrl.orEmpty()
    private var tokenDraft = ""
    private var allowHttpDraft = saved?.baseUrl?.startsWith("http://") == true
    var choosing = false
        private set

    init { orientation = VERTICAL; tag = "connection-ha-form"; showForm() }

    fun showForm() {
        cancel(); choosing = false; removeAllViews()
        val address = ui.field(this, context.getString(R.string.ha_server), "https://maison.example.fr", addressDraft, key = "connection-ha-server")
        val token = ui.field(this, context.getString(R.string.ha_token), context.getString(if (connection == null) R.string.ha_token_hint else R.string.ha_keep_token),
            tokenDraft, secret = true, key = "connection-ha-token")
        address.doAfterTextChanged { addressDraft = it.toString() }
        token.doAfterTextChanged { tokenDraft = it.toString() }
        addView(ui.action(context.getString(R.string.connection_token_help), false) {
            dialog = AlertDialog.Builder(context).setTitle(R.string.ha_token).setMessage(R.string.ha_token_help)
                .setPositiveButton(android.R.string.ok, null).show()
        }.apply { gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL; textSize = 12f * ui.textScale })
        val advanced = ui.advanced(this, "connection-ha-advanced")
        val localHttp = CheckBox(context).apply {
            text = context.getString(R.string.ha_allow_local_http); setTextColor(Palette.muted); textSize = 12f
            isChecked = allowHttpDraft
            setOnCheckedChangeListener { _, checked -> allowHttpDraft = checked }
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
            val secret = token.text.toString().trim().ifBlank { connection?.takeIf { it.baseUrl == base }?.token.orEmpty() }
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
                    result.onSuccess { cameras ->
                        val previous = connection?.takeIf { it.baseUrl == base }?.cameras.orEmpty()
                        val validated = HomeAssistantConnection(base, secret, previous)
                        runCatching {
                            if (connection == null || connection?.baseUrl == base) {
                                HomeAssistantStore(context).save(validated)
                                connection = validated
                            }
                        }.onSuccess {
                            showSelection(HomeAssistantConnection(base, secret, cameras))
                        }.onFailure { message.text = context.getString(R.string.ha_storage_failed) }
                    }
                        .onFailure { message.text = context.getString(if (it is HomeAssistantAuthException) R.string.ha_auth_failed else R.string.ha_connection_failed) }
                    api.close()
                }
            }
        }
        ui.spacer(this); addView(button); addView(message)
    }

    private fun showSelection(connection: HomeAssistantConnection) {
        cancel(); choosing = true; removeAllViews()
        isFocusableInTouchMode = true; requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(windowToken, 0)
        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (!allowDisconnect) addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_back); setColorFilter(Palette.text)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                contentDescription = context.getString(R.string.onvif_back); tag = "ha-selection-back"
                setOnClickListener { showForm() }
            }, LayoutParams(ui.dp(44), ui.dp(48)))
            addView(ui.text(context.getString(R.string.ha_select_cameras), 18, Palette.text), LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        val search = EditText(context).apply {
            tag = "ha-camera-search"; hint = context.getString(R.string.ha_search_cameras)
            contentDescription = hint; isSingleLine = true; textSize = 14f * ui.textScale
            setTextColor(Palette.text); setHintTextColor(Palette.muted); background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(ui.dp(44), 0, ui.dp(12), 0)
        }
        addView(FrameLayout(context).apply {
            background = ui.outline()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_search); setColorFilter(Palette.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL).apply { marginStart = ui.dp(14) })
            addView(search, FrameLayout.LayoutParams(-1, -1))
        }, LayoutParams(-1, ui.dp(48)).apply { topMargin = ui.dp(16); bottomMargin = ui.dp(12) })
        val availableIds = connection.cameras.map { it.entityId }.toSet()
        val selected = if (this.connection?.baseUrl == connection.baseUrl)
            this.connection!!.cameras.map { it.entityId }.filter { it in availableIds }.toMutableSet() else mutableSetOf()
        var saveButton: Button? = null
        val count = ui.text("", 12).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ui.text(context.getString(R.string.ha_camera_count, connection.cameras.size), 12), LayoutParams(0, -2, 1f))
            addView(count, LayoutParams(0, -2, 1f).apply { count.gravity = android.view.Gravity.END })
        }, LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        fun refresh() {
            count.text = context.getString(R.string.ha_selected_count, selected.size)
            count.setTextColor(if (selected.isEmpty()) Palette.muted else Palette.accent)
            saveButton?.isEnabled = selected.isNotEmpty()
            saveButton?.alpha = if (selected.isEmpty()) 0.45f else 1f
        }
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        val empty = ui.text(context.getString(R.string.ha_no_cameras)).apply {
            setPadding(ui.dp(8), ui.dp(24), ui.dp(8), ui.dp(24))
            visibility = if (connection.cameras.isEmpty()) View.VISIBLE else View.GONE
        }
        rows.addView(empty)
        val cameraRows = connection.cameras.map { camera ->
            val checkbox = androidx.appcompat.widget.AppCompatCheckBox(context).apply {
                text = camera.name; textSize = 14f * ui.textScale; setTextColor(Palette.text)
                buttonDrawable = null; background = null; minHeight = 0; minWidth = 0
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 0, ui.dp(4), 0)
                setCompoundDrawablesRelative(null, null, selectionGlyph(), null)
                compoundDrawablePadding = ui.dp(10); isChecked = camera.entityId in selected
                contentDescription = "${camera.name}, ${context.getString(if (camera.available) R.string.ha_camera_available else R.string.ha_unavailable)}"
            }
            val row = LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_videocam); setColorFilter(Palette.muted)
                    setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                    background = android.graphics.drawable.GradientDrawable().apply { setColor(Palette.surface); cornerRadius = ui.dp(6).toFloat() }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(ui.dp(38), ui.dp(38)).apply { marginEnd = ui.dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(checkbox, LayoutParams(-1, -2))
                    addView(ui.text(context.getString(if (camera.available) R.string.ha_camera_available else R.string.ha_unavailable), 11))
                }, LayoutParams(0, -2, 1f))
                setOnClickListener { checkbox.toggle() }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            fun highlight(checked: Boolean) {
                row.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (checked) androidx.core.graphics.ColorUtils.setAlphaComponent(Palette.accent, 16) else android.graphics.Color.TRANSPARENT)
                    cornerRadius = ui.dp(6).toFloat()
                }
            }
            highlight(checkbox.isChecked)
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) selected.add(camera.entityId) else selected.remove(camera.entityId)
                highlight(checked); refresh()
            }
            val item = LinearLayout(context).apply {
                orientation = VERTICAL; addView(row, LayoutParams(-1, -2))
                addView(View(context).apply { setBackgroundColor(Palette.divider) }, LayoutParams(-1, ui.dp(1)))
            }
            rows.addView(item, LayoutParams(-1, -2))
            camera to item
        }
        addView(object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val fraction = if (allowDisconnect) 0.5f else 0.35f
                val limit = (resources.displayMetrics.heightPixels * fraction).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = false; isNestedScrollingEnabled = true
            minimumHeight = ui.dp(140); addView(rows)
        }, LayoutParams(-1, -2))
        search.doAfterTextChanged { query ->
            fun key(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
            val term = key(query.toString().trim())
            var matches = 0
            cameraRows.forEach { (camera, row) ->
                val match = term.isEmpty() || key(camera.name).contains(term) || camera.entityId.contains(term, ignoreCase = true)
                row.visibility = if (match) View.VISIBLE else View.GONE
                if (match) matches++
            }
            empty.text = context.getString(if (connection.cameras.isEmpty()) R.string.ha_no_cameras else R.string.ha_search_empty)
            empty.visibility = if (matches == 0) View.VISIBLE else View.GONE
        }
        ui.spacer(this)
        saveButton = ui.action(context.getString(R.string.ha_save_selection)) {
            if (canInteract() && selected.isNotEmpty()) runCatching {
                HomeAssistantStore(context).save(HomeAssistantConnection(connection.baseUrl, connection.token,
                    connection.cameras.filter { it.entityId in selected }))
            }.onSuccess { onSaved() }.onFailure { Toast.makeText(context, R.string.ha_storage_failed, Toast.LENGTH_LONG).show() }
        }
        addView(saveButton)
        refresh()
    }

    private fun selectionGlyph(): android.graphics.drawable.Drawable {
        fun box(color: Int, outline: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            setSize(ui.dp(20), ui.dp(20)); cornerRadius = ui.dp(5).toFloat(); setColor(color)
            if (outline) setStroke(ui.dp(1), Palette.muted)
        }
        val selected = android.graphics.drawable.LayerDrawable(arrayOf(
            box(Palette.accent, false), context.getDrawable(R.drawable.ic_selection_check)!!.mutate()
        )).apply {
            setLayerSize(1, ui.dp(14), ui.dp(14)); setLayerGravity(1, android.view.Gravity.CENTER)
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), selected)
            addState(intArrayOf(), box(android.graphics.Color.TRANSPARENT, true))
            setBounds(0, 0, ui.dp(20), ui.dp(20))
        }
    }

    fun cancel() {
        generation++; client?.close(); client = null; worker?.shutdownNow(); worker = null
        busy = false; connect?.isEnabled = true; onBusy(false)
        status?.text = ""; dialog?.dismiss(); dialog = null
    }
    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
}
