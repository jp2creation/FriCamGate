package com.nvr.camnory

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.*

internal data class TransferCamera(val key: String, val name: String)

internal class TransferSelectionView(context: Context, private val source: TvConfiguration,
    private val catalog: CameraCatalog, private val cameras: List<TransferCamera>,
    private val appearance: Map<String, Any>, private val inventoryReady: Boolean,
    private val review: (ConfigurationTransfer) -> Unit) : LinearLayout(context) {
    private val ui = ConnectionFormUi(context)
    private val selected = cameras.map { it.key }.toMutableSet()
    private var includeCameras = true
    private var includeAppearance = true
    private var includeCategories = true
    private var preset = "all"
    private var syncing = false
    private val cameraChecks = mutableMapOf<String, MutableList<CheckBox>>()
    private val categoryChecks = mutableListOf<Pair<CheckBox, List<String>>>()
    private val options = LinearLayout(context).apply { orientation = VERTICAL }
    private val summary = ui.text("", 15, Palette.text).apply { tag = "transfer-summary" }
    private val presetButton = choice(context.getString(R.string.transfer_all), R.drawable.ic_settings_link) { choosePreset() }.apply { tag = "transfer-preset" }
    private val next = ui.action(context.getString(R.string.transfer_review)) {
        review(ConfigurationTransfer.select(source, catalog, selected.toSet(), if (includeAppearance) appearance else null, includeCameras, includeCategories))
    }.apply { tag = "transfer-review" }
    init {
        orientation = VERTICAL
        addView(ui.text(context.getString(R.string.transfer_choose), 22, Palette.text))
        addView(presetButton)
        addView(options)
        addView(summary)
        addView(next)
        render()
    }
    internal fun applyPreset(value: String) {
        preset = value
        includeCameras = value != "appearance"
        includeAppearance = value != "cameras"
        includeCategories = true
        selected.clear(); selected.addAll(cameras.map { it.key })
        render()
    }
    private fun choosePreset() {
        AppearanceChoiceSheet(context, context.getString(R.string.transfer_choose), "transfer", preset,
            listOf(AppearanceSheetOption("all", context.getString(R.string.transfer_all)),
                AppearanceSheetOption("appearance", context.getString(R.string.transfer_appearance_only)),
                AppearanceSheetOption("cameras", context.getString(R.string.transfer_cameras_only)),
                AppearanceSheetOption("custom", context.getString(R.string.transfer_custom)))) { applyPreset(it) }.show()
    }
    private fun check(title: String, tagValue: String, checked: Boolean, change: (Boolean) -> Unit) = CheckBox(context).apply {
        text = title; tag = tagValue; textSize = 15f; setTextColor(Palette.text)
        buttonTintList = ColorStateList.valueOf(Palette.accent); isChecked = checked
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnCheckedChangeListener { _, value -> if (!syncing) change(value) }
    }
    private fun render() {
        val focusedTag = findFocus()?.tag
        cameraChecks.clear(); categoryChecks.clear()
        options.removeAllViews()
        presetButton.text = context.getString(when (preset) { "all" -> R.string.transfer_all; "appearance" -> R.string.transfer_appearance_only; "cameras" -> R.string.transfer_cameras_only; else -> R.string.transfer_custom })
        options.addView(check(context.getString(R.string.cameras), "transfer-cameras", includeCameras) { includeCameras = it; preset = "custom"; render() })
        options.addView(check(context.getString(R.string.transfer_appearance), "transfer-appearance", includeAppearance) { includeAppearance = it; preset = "custom"; render() })
        if (includeCameras) {
            options.addView(check(context.getString(R.string.transfer_categories), "transfer-categories", includeCategories) { includeCategories = it; preset = "custom"; updateSummary() })
            if (!inventoryReady) options.addView(ui.text(context.getString(R.string.transfer_inventory_failed), 14, Palette.accent))
            val groups = catalog.categories.filter { c -> cameras.any { c.id in catalog.labels[it.key]?.categories.orEmpty() } }
            groups.forEach { category ->
                val keys = cameras.filter { category.id in catalog.labels[it.key]?.categories.orEmpty() }.map { it.key }
                val group = LinearLayout(context).apply { orientation = VERTICAL; visibility = View.GONE; tag = "transfer-group:${category.id}"; setPadding(20, 0, 0, 0) }
                options.addView(choice("${category.name} · ${keys.size}", R.drawable.ic_grid) {
                    group.visibility = if (group.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    TvRemoteSupport.linkForm(this)
                }.apply { tag = "transfer-expand:${category.id}" })
                val all = check(context.getString(R.string.all_cameras), "transfer-category:${category.id}", selected.containsAll(keys)) { value ->
                    if (value) selected.addAll(keys) else selected.removeAll(keys.toSet())
                    preset = "custom"; syncChecks()
                }
                categoryChecks.add(all to keys)
                group.addView(all)
                cameras.filter { it.key in keys }.forEach { camera -> group.addView(cameraCheck(camera)) }
                options.addView(group)
            }
            options.addView(ui.text(context.getString(R.string.transfer_selection), 14))
            cameras.forEach { options.addView(cameraCheck(it)) }
        }
        updateSummary()
        TvRemoteSupport.install(this)
        TvRemoteSupport.linkForm(this)
        if (focusedTag != null) findViewWithTag<View>(focusedTag)?.requestFocus()
    }
    private fun cameraCheck(camera: TransferCamera) = check(catalog.displayName(camera.key, camera.name), "transfer-camera:${camera.key}", camera.key in selected) {
        if (it) selected.add(camera.key) else selected.remove(camera.key)
        preset = "custom"
        syncChecks()
    }.also { cameraChecks.getOrPut(camera.key) { mutableListOf() }.add(it) }
    private fun syncChecks() {
        syncing = true
        cameraChecks.forEach { (key, boxes) -> boxes.forEach { it.isChecked = key in selected } }
        categoryChecks.forEach { (box, keys) -> box.isChecked = selected.containsAll(keys) }
        syncing = false
        presetButton.setText(R.string.transfer_custom)
        updateSummary()
    }
    private fun updateSummary() {
        presetButton.setText(when (preset) { "all" -> R.string.transfer_all; "appearance" -> R.string.transfer_appearance_only; "cameras" -> R.string.transfer_cameras_only; else -> R.string.transfer_custom })
        val count = if (includeCameras) selected.size else 0
        val groups = if (includeCameras && includeCategories) selected.flatMap { catalog.labels[it]?.categories.orEmpty() }.distinct().size else 0
        summary.text = context.getString(R.string.transfer_summary, count, groups, context.getString(if (includeAppearance) R.string.transfer_yes else R.string.transfer_no))
        next.isEnabled = (includeAppearance || includeCameras && selected.isNotEmpty()) && (!includeCameras || inventoryReady && selected.isNotEmpty())
        next.alpha = if (next.isEnabled) 1f else 0.45f
    }
    private fun choice(title: String, icon: Int, click: () -> Unit) = ui.action(title, false, click).apply {
        val density = resources.displayMetrics.density
        gravity = android.view.Gravity.CENTER_VERTICAL
        setTextColor(Palette.text)
        background = android.graphics.drawable.GradientDrawable().apply { setColor(Palette.card); cornerRadius = 8 * density }
        val leading = context.getDrawable(icon)?.mutate()?.apply { setTint(Palette.text); setBounds(0, 0, (22 * density).toInt(), (22 * density).toInt()) }
        val trailing = context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply { setTint(Palette.muted); setBounds(0, 0, (18 * density).toInt(), (18 * density).toInt()) }
        compoundDrawablePadding = (12 * density).toInt()
        setCompoundDrawablesRelative(leading, null, trailing, null)
    }
}
