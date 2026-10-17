package com.nvr.camnory

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs

/** Intercept only downward vertical drags once the touched list is at its top. */
internal class SheetDragLayout(context: Context, private val close: () -> Unit) : LinearLayout(context) {
    var scrollView: ScrollView? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var beganInList = false
    private var dragging = false
    private var settling = false
    private var velocity: VelocityTracker? = null
    private val density = resources.displayMetrics.density
    private val reducedMotion = context.getSharedPreferences("camgate-settings", 0).getBoolean("reducedMotion", false)

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // The parent still needs to observe when scrolling reaches its top.
        super.requestDisallowInterceptTouchEvent(false)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (settling) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                dragging = false
                downX = event.rawX
                downY = event.rawY
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
                val location = IntArray(2)
                val list = scrollView
                list?.getLocationOnScreen(location)
                beganInList = list != null && event.rawX >= location[0] && event.rawX < location[0] + list.width &&
                    event.rawY >= location[1] && event.rawY < location[1] + list.height
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) return false
                val dy = event.rawY - downY
                if (beganInList && scrollView?.canScrollVertically(-1) == true) {
                    downY = event.rawY
                } else if (dy > slop && dy > abs(event.rawX - downX)) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.recycle()
                velocity = null
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settling) return true
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downY
                if (!dragging && event.pointerCount == 1 && dy > slop && dy > abs(event.rawX - downX) &&
                    (!beganInList || scrollView?.canScrollVertically(-1) != true)) dragging = true
                if (dragging) translationY = dy.coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val speed = velocity?.yVelocity ?: 0f
                val shouldClose = event.actionMasked == MotionEvent.ACTION_UP && dragging &&
                    (translationY >= 64 * density || translationY > 24 * density && speed > 1000 * density)
                velocity?.recycle()
                velocity = null
                dragging = false
                if (shouldClose) {
                    settling = true
                    animate().translationY(height.toFloat()).setDuration(if (reducedMotion) 0 else 140).withEndAction { close() }.start()
                } else animate().translationY(0f).setDuration(if (reducedMotion) 0 else 120).start()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}
