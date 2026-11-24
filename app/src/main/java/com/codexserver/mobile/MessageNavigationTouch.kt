package com.codexserver.mobile

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** TextView consumes the initial down even on plain text. Bridge short horizontal
 * swipes there while leaving taps, links, vertical scrolling and long selection alone. */
internal class MessageNavigationTouch(
    private val target: (Float, Float, Float) -> (() -> Unit)?
) : View.OnTouchListener {
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var abandoned = false
    private var action: (() -> Unit)? = null
    private var direction = 0f

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val density = view.resources.displayMetrics.density
        val dx = event.rawX - startX
        val dy = event.rawY - startY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                downTime = event.eventTime
                abandoned = false
                action = null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (action != null) view.parent.requestDisallowInterceptTouchEvent(false)
                abandoned = true
                action = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (abandoned) return false
                if (action != null) return true
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout() ||
                    kotlin.math.abs(dy) > slop && kotlin.math.abs(dy) >= kotlin.math.abs(dx)) {
                    abandoned = true
                    return false
                }
                if (kotlin.math.abs(dx) >= 42 * density && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                    val rootPosition = IntArray(2).also(view.rootView::getLocationOnScreen)
                    action = target((startX - rootPosition[0]) / density, view.rootView.width / density, dx / density)
                    if (action != null) {
                        direction = dx
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        view.onTouchEvent(cancel)
                        cancel.recycle()
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val navigate = action
                action = null
                if (navigate != null) {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (direction * dx > 0 && kotlin.math.abs(dx) >= 42 * density) navigate()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (action != null) view.parent.requestDisallowInterceptTouchEvent(false)
                action = null
                abandoned = true
            }
        }
        return false
    }
}
