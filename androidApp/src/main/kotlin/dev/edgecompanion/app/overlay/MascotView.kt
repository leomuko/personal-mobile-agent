package dev.edgecompanion.app.overlay

import dev.edgecompanion.app.R

import android.content.Context
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import kotlin.math.abs

/** Click and accessibility actions share the same handlers; dragging never clicks. */
@SuppressLint("AppCompatCustomView") // Service-owned API 31+ overlay, not an AppCompat-themed Activity view.
internal class MascotView(context: Context) : ImageView(context) {
    var onDrag: (Int, Int) -> Unit = { _, _ -> }
    var onDragEnd: () -> Unit = {}
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var held = false
    private var tracking = false
    private val hold = Runnable { held = performLongClick() }

    init {
        setImageResource(R.drawable.mascot)
        contentDescription = context.getString(R.string.mascot_open)
        isFocusable = true
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun performLongClick(): Boolean = super.performLongClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
            context.getString(R.string.mascot_stop)))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX; startY = event.rawY
                lastX = startX; lastY = startY
                dragging = false; held = false; tracking = true
                postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (tracking && !held) {
                if (abs(event.rawX - startX) + abs(event.rawY - startY) > ViewConfiguration.get(context).scaledTouchSlop) {
                    dragging = true
                    removeCallbacks(hold)
                }
                if (dragging) {
                    onDrag((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                if (tracking && !held) { if (dragging) onDragEnd() else performClick() }
                tracking = false
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(hold); tracking = false
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        tracking = false
        super.onDetachedFromWindow()
    }
}
