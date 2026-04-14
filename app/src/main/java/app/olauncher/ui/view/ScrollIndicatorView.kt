package app.olauncher.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import app.olauncher.R

/**
 * A minimal scroll-position indicator: a thin rounded track plus a pill-shaped
 * handle whose vertical position reflects where the user is in the list.
 * Fades in when scrolling begins and fades out when the list settles.
 */
class ScrollIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var scrollProgress = 0f   // 0 = top, 1 = bottom
    private var alphaFraction = 0f    // 0 = fully hidden, 1 = fully visible
    private var hideRunnable: Runnable? = null
    private var alphaAnimator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val trackRect = RectF()
    private val handleRect = RectF()

    private val trackW = 2f.dpToPx()
    private val handleW = 4f.dpToPx()
    private val handleH = 28f.dpToPx()
    private val vertPad = handleH / 2f

    fun setScrollProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped == scrollProgress) return
        scrollProgress = clamped
        invalidate()
    }

    fun show() {
        hideRunnable?.let { removeCallbacks(it) }
        animateAlpha(1f)
    }

    fun scheduleHide(delayMs: Long = 1400) {
        hideRunnable?.let { removeCallbacks(it) }
        hideRunnable = Runnable { animateAlpha(0f) }
        postDelayed(hideRunnable, delayMs)
    }

    override fun onDraw(canvas: Canvas) {
        if (alphaFraction <= 0f || height == 0 || width == 0) return
        updateColors()

        val cx = width / 2f
        val trackTop = vertPad
        val trackBottom = height - vertPad

        // Track — very faint
        trackPaint.alpha = (alphaFraction * 55).toInt().coerceIn(0, 255)
        trackRect.set(cx - trackW / 2f, trackTop, cx + trackW / 2f, trackBottom)
        canvas.drawRoundRect(trackRect, trackW, trackW, trackPaint)

        // Handle — drifts along the track
        val travel = (trackBottom - trackTop - handleH).coerceAtLeast(0f)
        val top = trackTop + scrollProgress * travel
        handleRect.set(cx - handleW / 2f, top, cx + handleW / 2f, top + handleH)
        handlePaint.alpha = (alphaFraction * 190).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(handleRect, handleW, handleW, handlePaint)
    }

    private fun animateAlpha(target: Float) {
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(alphaFraction, target).apply {
            duration = if (target > alphaFraction) 140L else 500L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                alphaFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun updateColors() {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(R.attr.primaryColor, tv, true)) {
            trackPaint.color = tv.data
            handlePaint.color = tv.data
        } else {
            trackPaint.color = Color.WHITE
            handlePaint.color = Color.WHITE
        }
    }

    private fun Float.dpToPx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics,
    )
}
