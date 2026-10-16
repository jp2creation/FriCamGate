package com.nvr.camnory

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared compact fields for onboarding and provider settings. */
internal class ConnectionFormUi(private val context: Context) {
    val textScale = Appearance.textScale(context.getSharedPreferences("camgate-settings", Context.MODE_PRIVATE).getString("textSize", null))
    fun text(value: String, size: Int = 13, color: Int = Palette.muted) = TextView(context).apply {
        text = value; textSize = size * textScale; setTextColor(color)
    }
    fun outline() = GradientDrawable().apply {
        setColor(Color.TRANSPARENT); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Palette.border)
    }
    fun field(parent: LinearLayout, title: String, hint: String, value: String = "", secret: Boolean = false, key: String): EditText {
        val label = text(title, 12).apply { setPadding(0, dp(18), 0, dp(8)) }
        parent.addView(label)
        val frame = FrameLayout(context).apply { background = outline() }
        val input = EditText(context).apply {
            id = View.generateViewId(); tag = key; setText(value); this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or when {
                secret -> InputType.TYPE_TEXT_VARIATION_PASSWORD
                key.endsWith("server") -> InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_TEXT_VARIATION_NORMAL
            }
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isSingleLine = true; textSize = 15f * textScale; setTextColor(Palette.text); setHintTextColor(Palette.muted)
            background = null; setPadding(dp(14), 0, if (secret) dp(48) else dp(14), 0)
            isSaveEnabled = !secret
            if (secret && android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setOnFocusChangeListener { _, focused ->
                frame.background = outline().apply { setStroke(dp(1), if (focused) Palette.accent else Palette.border) }
            }
        }
        label.labelFor = input.id
        frame.addView(input, FrameLayout.LayoutParams(-1, -1))
        if (secret) {
            input.transformationMethod = PasswordTransformationMethod.getInstance()
            frame.addView(ImageButton(context).apply {
                tag = "$key-eye"; setImageResource(R.drawable.ic_connection_eye); setColorFilter(Palette.muted)
                setBackgroundColor(Color.TRANSPARENT); setPadding(dp(12), dp(12), dp(12), dp(12))
                fun update() {
                    val hidden = input.transformationMethod != null
                    setImageResource(if (hidden) R.drawable.ic_connection_eye else R.drawable.ic_connection_eye_off)
                    contentDescription = context.getString(if (hidden) R.string.connection_show_password else R.string.connection_hide_secret)
                    androidx.appcompat.widget.TooltipCompat.setTooltipText(this, contentDescription)
                }
                setOnClickListener {
                    val selection = input.selectionStart.coerceAtLeast(0)
                    input.transformationMethod = if (input.transformationMethod == null) PasswordTransformationMethod.getInstance() else null
                    input.setSelection(selection.coerceAtMost(input.length())); update()
                }
                update()
            }, FrameLayout.LayoutParams(dp(44), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        parent.addView(frame, LinearLayout.LayoutParams(-1, maxOf(dp(50), (input.textSize * 1.5f + dp(12)).toInt())))
        return input
    }
    fun action(title: String, primary: Boolean = true, click: () -> Unit) = Button(context).apply {
        text = title; textSize = 15f * textScale; isAllCaps = false; minHeight = dp(50)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else Palette.muted)
        background = if (primary) GradientDrawable().apply { setColor(Palette.accent); cornerRadius = dp(6).toFloat() }
            else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        stateListAnimator = null; setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (primary) 22 else 4) }
        if (primary) {
            val arrow = context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply { setTint(Color.WHITE); setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawablesRelative(null, null, arrow, null)
        }
        setOnClickListener { click() }
    }
    fun advanced(parent: LinearLayout, key: String): LinearLayout {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; tag = "$key-body" }
        val arrow = ImageView(context).apply { setImageResource(R.drawable.ic_connection_expand); setColorFilter(Palette.muted) }
        parent.addView(LinearLayout(context).apply {
            tag = key; gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
            contentDescription = context.getString(R.string.connection_advanced)
            addView(text(context.getString(R.string.connection_advanced), 14, Palette.text), LinearLayout.LayoutParams(0, -2, 1f))
            addView(arrow, LinearLayout.LayoutParams(dp(20), dp(20)))
            setOnClickListener { body.visibility = if (body.visibility == View.GONE) View.VISIBLE else View.GONE; arrow.rotation = if (body.visibility == View.VISIBLE) 180f else 0f }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(12) })
        parent.addView(body, LinearLayout.LayoutParams(-1, -2))
        return body
    }
    fun spacer(parent: LinearLayout) { parent.addView(Space(context), LinearLayout.LayoutParams(1, 0, 1f)) }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
