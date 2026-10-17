package com.nvr.camnory

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Keep changes local until Apply; dismissing the panel never changes the active query. */
internal class EventFilterSheet(context: Context, initial: EventSearch,
    private val cameras: List<Pair<String, String>>, private val apply: (EventSearch) -> Unit
) : SelectionBottomSheet(context, context.getString(R.string.event_filters_title)) {
    private var draft = initial
    private var choice: AppearanceChoiceSheet? = null
    private lateinit var name: EditText
    private lateinit var cameraValue: TextView
    private lateinit var kindValue: TextView
    private lateinit var periodValue: TextView
    private val periods = listOf(null, 24, 168, 720)
    private val periodTitles = listOf(R.string.filter_any_time, R.string.filter_day, R.string.filter_week, R.string.filter_month)

    override fun createSheetContent(): View {
        val ui = ConnectionFormUi(context)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(16))
            cameraValue = row(this, context.getString(R.string.cameras), "camera") {
                choose(context.getString(R.string.cameras), "camera", draft.camera ?: "",
                    listOf(AppearanceSheetOption("", context.getString(R.string.all_cameras))) + cameras.map { AppearanceSheetOption(it.first, it.second) }) {
                    draft = draft.copy(camera = it.takeIf(String::isNotEmpty)); updateValues()
                }
            }
            kindValue = row(this, context.getString(R.string.filter_type), "type") {
                choose(context.getString(R.string.filter_type), "type", draft.kind.name,
                    EventKind.values().map { AppearanceSheetOption(it.name, context.getString(it.titleRes)) }) {
                    draft = draft.copy(kind = EventKind.valueOf(it)); updateValues()
                }
            }
            periodValue = row(this, context.getString(R.string.filter_period), "period") {
                choose(context.getString(R.string.filter_period), "period", draft.hours?.toString() ?: "all",
                    periods.mapIndexed { index, hours -> AppearanceSheetOption(hours?.toString() ?: "all", context.getString(periodTitles[index])) }) {
                    draft = draft.copy(hours = it.toIntOrNull()); updateValues()
                }
            }
            name = ui.field(this, context.getString(R.string.filter_name), context.getString(R.string.filter_name),
                draft.name.orEmpty(), key = "event-filter-name").apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            updateValues()
        }
    }

    private fun row(parent: LinearLayout, title: String, key: String, click: () -> Unit): TextView {
        val ui = ConnectionFormUi(context)
        val value = ui.text("", 14, Palette.muted).apply {
            gravity = Gravity.END; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        parent.addView(LinearLayout(context).apply {
            tag = "event-filter-$key"; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60); setPadding(0, dp(12), 0, dp(12))
            isFocusable = true; setOnClickListener { click() }
            addView(ui.text(title, 14, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            addView(value, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right); setColorFilter(Palette.muted)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2))
        parent.addView(View(context).apply { setBackgroundColor(Palette.divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        return value
    }

    private fun choose(title: String, key: String, selected: String, options: List<AppearanceSheetOption>, select: (String) -> Unit) {
        name.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(name.windowToken, 0)
        choice?.dismiss()
        choice = AppearanceChoiceSheet(context, title, "event-$key", selected, options, select).also { it.show() }
    }

    private fun updateValues() {
        cameraValue.text = cameras.firstOrNull { it.first == draft.camera }?.second ?: draft.camera ?: context.getString(R.string.all_cameras)
        kindValue.setText(draft.kind.titleRes)
        periodValue.setText(periodTitles[periods.indexOf(draft.hours).coerceAtLeast(0)])
    }

    override fun createSheetFooter(): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16))
        val ui = ConnectionFormUi(context)
        addView(ui.action(context.getString(R.string.filter_reset), false) {
            draft = EventSearch(); name.text.clear(); updateValues()
        }.apply { tag = "event-filter-reset" }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.action(context.getString(R.string.event_filters_apply)) {
            val next = draft.copy(name = name.text.toString().trim().takeIf { it.isNotEmpty() })
            dismiss(); apply(next)
        }.apply { tag = "event-filter-apply" }, LinearLayout.LayoutParams(0, -2, 1.7f).apply { marginStart = dp(10) })
    }

    override fun onStart() {
        super.onStart()
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun dismiss() {
        choice?.dismiss(); choice = null
        super.dismiss()
    }
}
