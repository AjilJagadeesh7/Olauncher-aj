package app.olauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

/**
 * A NestedScrollView that delivers gesture callbacks:
 *  - A fast downward fling when already at the top → [onFlingDown] (open notifications).
 *  - Long-press on an empty area → [onLongPress] (open settings).
 *
 * The app list is now inline below the favorites, so there is no separate drawer to
 * escalate into — scrolling down naturally reveals all apps.
 */
class HomeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    /** Called when the user flings down while at the top (or no overflow). */
    var onFlingDown: (() -> Unit)? = null

    /** Called when the user long-presses on an empty area of the scroll view. */
    var onLongPress: (() -> Unit)? = null

    private var gestureHandled = false

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            private val VELOCITY_THRESHOLD = 300f

            override fun onDown(e: MotionEvent) = true

            override fun onLongPress(e: MotionEvent) {
                onLongPress?.invoke()
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // velocityY > 0  → finger moved DOWN → user wants notifications / scroll back up
                return when {
                    velocityY > VELOCITY_THRESHOLD && !canScrollVertically(-1) -> {
                        // At top (or no overflow) with a downward fling → notifications / swipe-down action
                        gestureHandled = true
                        onFlingDown?.invoke()
                        true
                    }
                    else -> false
                }
            }
        }
    )

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestureHandled = false
        gestureDetector.onTouchEvent(ev)
        if (gestureHandled) return true
        return super.onTouchEvent(ev)
    }
}
