package com.nvr.camnory

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView

/** Adds remote affordances to the shared UI, without replacing click handlers. */
internal object TvRemoteSupport {
    internal class CameraHalo(private val density: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        override fun draw(canvas: Canvas) {
            val color = Palette.accent
            // A thin readable edge is the focus cue; diffusion is only a supplement.
            for (layer in 0..11) {
                val inset = (0.5f + layer) * density
                paint.color = color
                paint.alpha = if (layer == 0) 185 else (16.0 * kotlin.math.exp(-(layer - 1) * (layer - 1) / 18.0)).toInt()
                paint.strokeWidth = if (layer == 0) 1f * density else 0.8f * density
                canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset,
                    (8f * density - inset).coerceAtLeast(0f), (8f * density - inset).coerceAtLeast(0f), paint)
            }
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private var started = false
    fun linkForm(root: View) {
        if (!TelevisionMode.enabled(root.context)) return
        val fields = mutableListOf<View>()
        fun collect(view: View) {
            if (view.visibility != View.VISIBLE || !view.isEnabled) return
            if (view is EditText || view.isClickable && view.isFocusable) fields.add(view)
            else if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
        }
        collect(root)
        fields.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        fields.forEachIndexed { index, view ->
            val up = fields.getOrNull(index - 1) ?: view
            val down = fields.getOrNull(index + 1) ?: view
            view.nextFocusUpId = up.id; view.nextFocusDownId = down.id
            if (view is EditText) view.setOnKeyListener { _, key, event ->
                val destination = when (key) { android.view.KeyEvent.KEYCODE_DPAD_UP -> up; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> down; else -> null }
                if (destination == null) false else {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) destination.requestFocus()
                    true
                }
            }
        }
    }
    fun start(application: Application) {
        if (started || !TelevisionMode.enabled(application)) return
        started = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { install(activity.window.decorView) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
    fun install(root: View) {
        if (!TelevisionMode.enabled(root.context) || root.getTag(R.id.tv_remote_root) == true) return
        root.setTag(R.id.tv_remote_root, true)
        fun scan(view: View) {
            if (view is TextView && view.getTag(R.id.tv_remote_text) != true) {
                view.setTag(R.id.tv_remote_text, true)
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * 1.12f)
            }
            if ((view.isClickable || view is EditText) && view.getTag(R.id.tv_remote_action) != true) {
                view.setTag(R.id.tv_remote_action, true)
                view.isFocusable = true
                val cell = if (view is android.widget.CompoundButton) (view.parent as? View) ?: view else view
                val border: Drawable = if (view.getTag(R.id.tv_camera_tile) == true) CameraHalo(view.resources.displayMetrics.density)
                else GradientDrawable().apply {
                    cornerRadius = 6 * view.resources.displayMetrics.density
                    setColor(Color.argb(26, 255, 255, 255))
                }
                fun refresh() {
                    cell.overlay.remove(border)
                    if (view.hasFocus() && view.isEnabled) {
                        border.setBounds(0, 0, cell.width, cell.height)
                        cell.overlay.add(border)
                    }
                }
                val previous = view.onFocusChangeListener
                view.onFocusChangeListener = View.OnFocusChangeListener { target, focused -> previous?.onFocusChange(target, focused); refresh() }
                cell.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
                refresh()
            }
            if (view is ViewGroup) repeat(view.childCount) { scan(view.getChildAt(it)) }
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { scan(root) }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                view.setTag(R.id.tv_remote_root, null)
            }
        })
        scan(root)
    }
}
