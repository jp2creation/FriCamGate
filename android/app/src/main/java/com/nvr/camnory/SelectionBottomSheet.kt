package com.nvr.camnory

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Shared bottom panel for appearance choices and camera selection. */
internal open class SelectionBottomSheet(
    context: Context,
    private val heading: String,
    private val sheetContent: View? = null
) : Dialog(context) {
    protected fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    protected val textScale = Appearance.textScale(context.getSharedPreferences("camgate-settings", 0).getString("textSize", null))
    protected open fun createSheetContent(): View = requireNotNull(sheetContent)
    protected open fun createSheetToolbar(): View? = null
    protected open fun createSheetFooter(): View? = null
    protected open fun createHeaderAccessory(): View? = null
    protected open val maxHeightFraction: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = object : SheetDragLayout(context, { dismiss() }) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val fraction = maxHeightFraction
                if (fraction == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
                val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
                    ?: resources.displayMetrics.heightPixels
                val limit = minOf(available, (resources.displayMetrics.heightPixels * fraction).toInt())
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            tag = "selection-sheet"
            background = GradientDrawable().apply {
                setColor(Palette.card)
                cornerRadii = floatArrayOf(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val handle = FrameLayout(context).apply {
            addView(View(context).apply {
                background = GradientDrawable().apply { setColor(Palette.muted); cornerRadius = dp(2).toFloat() }
            }, FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER))
        }
        body.addView(handle, LinearLayout.LayoutParams(-1, dp(24)))
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), dp(8))
            addView(TextView(context).apply {
                text = heading
                textSize = 18f * textScale
                setTextColor(Palette.text)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                ViewCompat.setAccessibilityHeading(this, true)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            createHeaderAccessory()?.let { addView(it) }
            addView(FrameLayout(context).apply {
                contentDescription = context.getString(android.R.string.cancel)
                isFocusable = true
                setOnClickListener { dismiss() }
                addView(ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setColorFilter(Palette.muted)
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        body.addView(header)
        createSheetToolbar()?.let { body.addView(it) }
        val rows = createSheetContent()
        val list = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * 0.62).toInt()
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply { tag = "sheet-scroll"; addView(rows); isFillViewport = false }
        body.scrollView = list
        body.addView(list, LinearLayout.LayoutParams(-1, -2, if (maxHeightFraction != null) 1f else 0f))
        createSheetFooter()?.let { body.addView(it) }
        ViewCompat.setOnApplyWindowInsetsListener(body) { view, insets ->
            view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
        setContentView(body)
        setCanceledOnTouchOutside(true)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (!context.getSharedPreferences("camgate-settings", 0).getBoolean("reducedMotion", false))
                attributes = attributes.apply { windowAnimations = R.style.AppearanceSheetAnimation }
        }
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
