package com.nvr.camnory

import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.TooltipCompat

internal data class CameraArrangementRow(
    val key: String, val name: String, val source: CameraSource,
    val enabled: Boolean, val visible: Boolean
)

internal class CameraArrangementView(
    context: Context,
    rows: List<CameraArrangementRow>,
    private val onOrder: (List<String>) -> Unit,
    private val onVisibility: (String, Boolean) -> Unit
) : LinearLayout(context) {
    private val keys = rows.map { it.key }.toMutableList()
    private val rowViews = mutableMapOf<String, View>()

    init {
        orientation = VERTICAL
        tag = "camera-arrangement"
        rows.forEach { camera ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                tag = "camera-arrangement-row:${camera.key}"
                addView(camera.source.iconView(context), LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = camera.name; textSize = 14f; setTextColor(Palette.text)
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        text = camera.source.title; textSize = 11f; setTextColor(Palette.muted)
                    })
                }, LayoutParams(0, -2, 1f))
                addView(SwitchCompat(context).apply {
                    tag = "camera-visibility:${camera.key}"
                    contentDescription = context.getString(R.string.camera_visibility_name, camera.name)
                    isChecked = camera.visible; isEnabled = camera.enabled
                    setOnCheckedChangeListener { _, checked -> onVisibility(camera.key, checked) }
                })
                addView(ImageButton(context).apply {
                    tag = "camera-reorder:${camera.key}"
                    setImageResource(R.drawable.ic_drag_handle); setColorFilter(Palette.muted)
                    setBackgroundColor(Color.TRANSPARENT); setPadding(dp(14), dp(14), dp(14), dp(14))
                    contentDescription = context.getString(R.string.camera_reorder_name, camera.name)
                    TooltipCompat.setTooltipText(this, contentDescription)
                    setOnClickListener { anchor ->
                        PopupMenu(context, anchor).apply {
                            menu.add(0, 1, 0, R.string.camera_move_up).isEnabled = keys.indexOf(camera.key) > 0
                            menu.add(0, 2, 1, R.string.camera_move_down).isEnabled = keys.indexOf(camera.key) < keys.lastIndex
                            setOnMenuItemClickListener {
                                val index = keys.indexOf(camera.key)
                                val target = keys.getOrNull(index + if (it.itemId == 1) -1 else 1)
                                if (target != null) move(camera.key, target, it.itemId == 2)
                                true
                            }
                        }.show()
                    }
                    setOnLongClickListener {
                        startDragAndDrop(ClipData.newPlainText("camera", ""), View.DragShadowBuilder(rowViews.getValue(camera.key)), camera.key, 0)
                    }
                }, LayoutParams(dp(48), dp(48)))
                setOnDragListener { target, event ->
                    val moving = event.localState as? String
                    if (moving == null || moving !in keys) false else {
                        when (event.action) {
                            DragEvent.ACTION_DRAG_ENTERED -> target.setBackgroundColor(Palette.surface)
                            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> target.setBackgroundColor(Color.TRANSPARENT)
                            DragEvent.ACTION_DRAG_LOCATION -> autoScroll(target, event.y)
                            DragEvent.ACTION_DROP -> {
                                target.setBackgroundColor(Color.TRANSPARENT)
                                move(moving, camera.key, event.y > target.height / 2f)
                            }
                        }
                        true
                    }
                }
            }
            rowViews[camera.key] = row
            addView(row, LayoutParams(-1, dp(68)))
        }
    }

    private fun move(moving: String, target: String, after: Boolean) {
        val next = CameraArrangement.move(keys, moving, target, after)
        if (next == keys) return
        keys.clear(); keys.addAll(next)
        val row = rowViews.getValue(moving)
        removeView(row); addView(row, keys.indexOf(moving))
        onOrder(keys.toList())
        row.announceForAccessibility(context.getString(R.string.camera_order_position, keys.indexOf(moving) + 1, keys.size))
    }

    private fun autoScroll(target: View, y: Float) {
        var ancestor = parent
        while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
        val scroll = ancestor as? ScrollView ?: return
        val targetLocation = IntArray(2); val scrollLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation); scroll.getLocationOnScreen(scrollLocation)
        val pointer = targetLocation[1] + y - scrollLocation[1]
        if (pointer < dp(64)) scroll.smoothScrollBy(0, -dp(24))
        else if (pointer > scroll.height - dp(64)) scroll.smoothScrollBy(0, dp(24))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
