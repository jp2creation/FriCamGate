package com.nvr.camnory

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal class CameraCatalogSheet(context: Context, private val store: CameraCatalogStore,
    private val cameraKey: String? = null, private val originalName: String = "",
    private val cameras: List<Pair<String, String>> = emptyList(),
    private val allowed: () -> Boolean = { true }) : SelectionBottomSheet(context,
    context.getString(if (cameraKey == null) R.string.camera_categories else R.string.camera_display_name)) {
    override val maxHeightFraction = 0.7f
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(16)) }
    private var categoryId: String? = null
    private var adding = false
    private var confirmation: ConfirmationSheet? = null
    private var headerAction: View? = null
    override fun createSheetContent(): View { render(); return rows }
    override fun createHeaderAccessory(): View? = if (cameraKey != null) null else icon(android.R.drawable.ic_input_add, R.string.category_add) {
        if (adding || categoryId != null) { adding = false; categoryId = null } else adding = true
        render()
    }.apply { tag = "category-add"; headerAction = this }
    override fun onStart() { super.onStart(); wireFocus(true) }
    override fun onStop() { confirmation?.dismiss(); super.onStop() }

    private fun render() {
        rows.removeAllViews()
        val ui = ConnectionFormUi(context)
        val status = ui.text("", 13, Palette.accent).apply { tag = "catalog-error" }
        val catalog = runCatching { store.load() }.getOrElse { rows.addView(ui.text(context.getString(R.string.category_error))); return }
        headerAction?.let { view ->
            (view as ImageButton).setImageResource(if (adding || categoryId != null) R.drawable.ic_arrow_back else android.R.drawable.ic_input_add)
            view.contentDescription = context.getString(if (adding || categoryId != null) R.string.onvif_back else R.string.category_add)
        }
        fun commit(change: (CameraCatalog) -> CameraCatalog, after: () -> Unit) {
            if (!allowed()) return
            runCatching { store.update(change) }.onSuccess { after() }.onFailure { status.text = context.getString(R.string.category_error) }
        }
        if (cameraKey != null) {
            val input = ui.field(rows, context.getString(R.string.camera_display_name), originalName,
                catalog.labels[cameraKey]?.alias.orEmpty(), key = "camera-display-name").apply { filters = arrayOf(android.text.InputFilter.LengthFilter(120)) }
            rows.addView(ui.action(context.getString(R.string.camera_restore_name), false) { input.setText("") })
            rows.addView(ui.action(context.getString(R.string.ip_save)) {
                val key = cameraKey ?: return@action
                commit({ it.label(key, input.text.toString(), it.labels[key]?.categories.orEmpty()) }) { dismiss() }
            }.apply { tag = "camera-label-save" })
        } else if (!adding && categoryId == null) {
            catalog.categories.forEach { category ->
                rows.addView(ui.action(category.name, false) { categoryId = category.id; render() }
                    .apply { tag = "catalog-category:${category.id}" })
            }
        } else {
            val id = categoryId
            val name = ui.field(rows, context.getString(R.string.category_name), "",
                catalog.categories.firstOrNull { it.id == id }?.name.orEmpty(), key = "category-name").apply {
                filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            }
            val selected = cameras.filter { id in catalog.labels[it.first]?.categories.orEmpty() }.map { it.first }.toMutableSet()
            if (!adding) cameras.forEach { (key, title) ->
                rows.addView(CheckBox(context).apply {
                    text = title; setTextColor(Palette.text); textSize = 15f * textScale
                    buttonTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
                    tag = "category-camera:$key"; isChecked = key in selected
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(key) else selected.remove(key) }
                }, LinearLayout.LayoutParams(-1, dp(52)))
            }
            rows.addView(ui.action(context.getString(R.string.ip_save)) {
                val newId = id ?: java.util.UUID.randomUUID().toString()
                commit({ current ->
                    if (id == null) current.add(newId, name.text.toString())
                    else current.rename(id, name.text.toString()).assign(id, cameras.map { it.first }.toSet(), selected)
                }) {
                    if (id == null) { adding = false; categoryId = newId } else categoryId = null
                    render()
                }
            }.apply { tag = "category-save" })
            if (id != null) rows.addView(ui.action(context.getString(R.string.category_delete), false) {
                confirmation?.dismiss()
                confirmation = ConfirmationSheet(context, context.getString(R.string.category_delete), name.text.toString()) {
                    commit({ it.delete(id) }) { categoryId = null; render() }
                }.also { it.show() }
            }.apply { tag = "category-delete" })
        }
        rows.addView(status)
        TvRemoteSupport.install(rows)
        rows.post { wireFocus(true) }
    }

    /** Explicit links keep TV focus search from skipping EditText cursor rectangles. */
    private fun wireFocus(selectFirst: Boolean) {
        if (!TelevisionMode.enabled(context) || !isShowing) return
        val actions = mutableListOf<View>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE || !view.isEnabled) return
            if (view is EditText || view.isClickable && view !is ViewGroup) actions.add(view)
            else if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        collect(rows)
        fun close(view: View): View? {
            if (view.isClickable && view.contentDescription == context.getString(android.R.string.cancel)) return view
            if (view is ViewGroup) repeat(view.childCount) { close(view.getChildAt(it))?.let { found -> return found } }
            return null
        }
        val header = headerAction ?: window?.decorView?.let { close(it) }
        (listOfNotNull(header) + actions).forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        actions.forEachIndexed { index, view ->
            view.nextFocusUpId = actions.getOrNull(index - 1)?.id ?: header?.id ?: view.id
            view.nextFocusDownId = actions.getOrNull(index + 1)?.id ?: view.id
            if (view is EditText) view.setOnKeyListener { _, code, event ->
                val destination = when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> actions.getOrNull(index + 1)
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> actions.getOrNull(index - 1) ?: header
                    else -> null
                }
                if (destination == null) false else {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) destination.requestFocus()
                    true
                }
            }
        }
        if (header != null) header.nextFocusDownId = actions.firstOrNull()?.id ?: header.id
        if (selectFirst) (actions.firstOrNull() ?: header)?.requestFocus()
    }
    private fun icon(drawable: Int, description: Int, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(drawable); setColorFilter(Palette.text); setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setPadding(dp(12), dp(12), dp(12), dp(12)); contentDescription = context.getString(description)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)); isFocusable = true
        androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
        setOnClickListener { if (allowed()) action() }
    }
}
