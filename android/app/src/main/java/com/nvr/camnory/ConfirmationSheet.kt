package com.nvr.camnory

import android.content.Context
import android.view.View
import android.widget.LinearLayout

internal class ConfirmationSheet(context: Context, title: String, private val message: String,
    private val confirm: () -> Unit) : SelectionBottomSheet(context, title) {
    override val maxHeightFraction = 0.70f
    override fun createSheetContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(12))
        addView(ConnectionFormUi(context).text(message, 15, Palette.text))
    }
    override fun createSheetFooter(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), 0, dp(20), dp(16))
        val ui = ConnectionFormUi(context)
        addView(ui.action(context.getString(android.R.string.ok), true) {
            dismiss(); confirm()
        }.apply { tag = "confirmation-accept" })
        addView(ui.action(context.getString(android.R.string.cancel), false) { dismiss() }
            .apply { tag = "confirmation-cancel" })
    }
}
