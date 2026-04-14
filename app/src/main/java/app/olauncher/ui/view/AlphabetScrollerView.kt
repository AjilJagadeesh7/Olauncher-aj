package app.olauncher.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.animation.doOnEnd
import app.olauncher.data.Constants
import app.olauncher.R
import kotlin.math.abs
import kotlin.math.exp

class AlphabetScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val canonicalLetters = listOf('★') + ('A'..'Z').toList() + '#'
    private var letters = canonicalLetters
    private val starColor = 0xFFFFD700.toInt()

    // Each letter slot height — tight packing
    private val letterTextSize: Float get() = 9f.spToPx()
    private val slotHeight: Float get() = letterTextSize * 1.55f

    // Total height of the packed letter block
    private val blockHeight: Float get() = slotHeight * letters.size

    // Y offset so the block is vertically centered in the view
    private val blockOffsetY: Float get() = ((height - blockHeight) / 2f).coerceAtLeast(0f)

    // Letter rendering paint
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Preview bubble paints
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = 20f.spToPx()
        color = Color.WHITE
    }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewRect = RectF()

    private var textColor = Color.WHITE
    private var activeIndex = -1
    private var touchActive = false

    // activationProgress: 0=idle, 1=fully active (drives bubble + alpha fade-in)
    private var activationProgress = 0f
    private var activationAnimator: ValueAnimator? = null

    // waveProgress: independent animator so the wave fades out smoothly even after finger lifts
    private var waveProgress = 0f
    private var waveAnimator: ValueAnimator? = null

    private var showOnRight = true
    private var activeTouchY = 0f
    private var activeCenterIndex = -1f
    private var waveStyle = Constants.WaveStyle.BALANCED

    var onLetterSelected: ((Char) -> Unit)? = null
    var onScrollProgress: ((Float) -> Unit)? = null
    /** Called when the user lifts their finger from the scroller. */
    var onScrollingEnded: (() -> Unit)? = null

    fun isUserTouchActive(): Boolean = touchActive

    init {
        isClickable = true
        isFocusable = true
        updateThemeColor()
    }

    // -------------------------------------------------------------------------

    fun setSide(showOnRight: Boolean) {
        this.showOnRight = showOnRight
        invalidate()
    }

    fun setAvailableLetters(sectionLetters: List<Char>) {
        val normalizedSet = sectionLetters
            .map {
                when {
                    it == '★' -> '★'
                    it.isLetter() -> it.uppercaseChar()
                    else -> '#'
                }
            }
            .toSet()

        val next = canonicalLetters.filter { it in normalizedSet }
            .ifEmpty { canonicalLetters }

        if (next == letters) return
        letters = next

        if (activeIndex >= letters.size) {
            activeIndex = if (touchActive) letters.lastIndex else -1
            activeCenterIndex = if (touchActive) activeIndex.toFloat() else -1f
        }

        requestLayout()
        invalidate()
    }

    fun setWaveStyle(style: Int) {
        val next = when (style) {
            Constants.WaveStyle.SUBTLE,
            Constants.WaveStyle.BALANCED,
            Constants.WaveStyle.DRAMATIC -> style
            else -> Constants.WaveStyle.BALANCED
        }
        if (waveStyle == next) return
        waveStyle = next
        invalidate()
    }

    fun highlightLetter(letter: Char) {
        if (touchActive) return
        val target = when {
            letter == '★' -> '★'
            letter.isLetter() -> letter.uppercaseChar()
            else -> '#'
        }
        val idx = findBestIndexForTarget(target)
        if (idx == activeIndex && activationProgress > 0f) return
        activeIndex = idx
        activeCenterIndex = idx.toFloat()
        activeTouchY = blockOffsetY + slotHeight * idx + slotHeight / 2f
        animateActivation(0.5f)
    }

    fun clearScrollHighlight() {
        if (touchActive) return
        animateActivation(0f)
    }

    fun clearTouchState() {
        if (activeIndex == -1 && !touchActive && activationProgress == 0f) return
        touchActive = false
        animateActivation(0f)
        animateWave(0f)
    }

    // -------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = blockHeight.toInt() + paddingTop + paddingBottom
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0 || width == 0) return
        updateThemeColor()

        val baseCenterX = width / 2f

        val (waveSigma, maxNudge, maxWaveScale) = when (waveStyle) {
            Constants.WaveStyle.SUBTLE -> Triple(5.6f, 34f.dpToPx(), 0.34f)
            Constants.WaveStyle.DRAMATIC -> Triple(8.8f, 78f.dpToPx(), 1.02f)
            else -> Triple(7.0f, 62f.dpToPx(), 0.78f)
        }

        for (i in letters.indices) {
            val letter = letters[i]
            // Base Y position within the centered block
            val centerY = blockOffsetY + slotHeight * i + slotHeight / 2f

            // Gaussian weight centred on continuous pointer position for fluid interpolation
            val gaussian = if (activeCenterIndex >= 0f && waveProgress > 0f) {
                val d = i.toFloat() - activeCenterIndex
                exp(-(d * d) / (2f * waveSigma * waveSigma)).toFloat()
            } else 0f

            val wave = gaussian * waveProgress   // 0 when idle or fully faded

            // Scale: 1× at idle, up to (1 + maxWaveScale) at peak
            val scale = 1f + maxWaveScale * wave

            // Nudge: push letters toward the content (away from the screen edge)
            // showOnRight means scroller is on the right edge → nudge left (toward list)
            val nudge = if (showOnRight) -maxNudge * wave else maxNudge * wave
            val drawX = baseCenterX + nudge

            // Alpha: dim idle, bright at wave peak
            val idleAlpha = 0.45f
            val alpha = (idleAlpha + (1f - idleAlpha) * wave).coerceIn(0f, 1f)

            textPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            textPaint.textSize = letterTextSize * scale
            textPaint.color = if (letter == '★') starColor else textColor

            canvas.drawText(
                letter.toString(),
                drawX,
                centerY - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint
            )
        }
        textPaint.color = textColor

        drawPreviewBubble(canvas, baseCenterX)
    }

    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touchActive = true
                animateActivation(1f)
                animateWave(1f)
                updateSelection(event.y, emitIfSame = true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateSelection(event.y, emitIfSame = false)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                touchActive = false
                animateActivation(0f)
                animateWave(0f)   // wave fades independently after finger lifts
                onScrollingEnded?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // -------------------------------------------------------------------------

    private fun updateSelection(touchY: Float, emitIfSame: Boolean) {
        if (height <= 0) return
        activeTouchY = touchY.coerceIn(0f, height.toFloat())

        val blockY = (activeTouchY - blockOffsetY).coerceIn(0f, blockHeight - 0.001f)
        val rawCenterIndex = (blockY / slotHeight).coerceIn(0f, letters.lastIndex.toFloat())
        activeCenterIndex = if (activeCenterIndex < 0f) {
            rawCenterIndex
        } else {
            activeCenterIndex + (rawCenterIndex - activeCenterIndex) * 0.62f
        }
        val nextIndex = ((blockY / slotHeight).toInt()).coerceIn(0, letters.lastIndex)

        onScrollProgress?.invoke(blockY / blockHeight)

        if (nextIndex != activeIndex || emitIfSame) {
            activeIndex = nextIndex
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onLetterSelected?.invoke(letters[nextIndex])
        }
        invalidate()
    }

    private fun animateActivation(target: Float) {
        activationAnimator?.cancel()
        activationAnimator = ValueAnimator.ofFloat(activationProgress, target).apply {
            duration = if (target > activationProgress) 150L else 350L
            interpolator = if (target > activationProgress) OvershootInterpolator(1.2f) else DecelerateInterpolator(2f)
            addUpdateListener {
                activationProgress = (it.animatedValue as Float).coerceIn(0f, 1.2f)
                invalidate()
            }
            doOnEnd {
                if (!touchActive && target == 0f) {
                    activeIndex = -1
                    activeCenterIndex = -1f
                    activeTouchY = 0f
                    invalidate()
                }
            }
            start()
        }
    }

    private fun animateWave(target: Float) {
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(waveProgress, target).apply {
            if (target > waveProgress) {
                // Spring in: fast with gentle overshoot so the wave "blooms"
                duration = 220L
                interpolator = OvershootInterpolator(1.08f)
            } else {
                // Slow fluid fade-out — like a lava lamp settling back
                duration = 1100L
                interpolator = DecelerateInterpolator(3f)
            }
            addUpdateListener {
                waveProgress = (it.animatedValue as Float).coerceIn(0f, 1.3f)
                invalidate()
            }
            start()
        }
    }

    // -------------------------------------------------------------------------

    private fun drawPreviewBubble(canvas: Canvas, letterCenterX: Float) {
        if (!touchActive || activeIndex == -1 || activationProgress <= 0.05f) return

        val bubbleSize = 44f.dpToPx()
        val halfBubble = bubbleSize / 2f
        val gap = 6f.dpToPx()

        if (height < bubbleSize) return

        val letterCenterY = blockOffsetY + slotHeight * activeIndex + slotHeight / 2f
        val centerY = letterCenterY.coerceIn(halfBubble, height - halfBubble)

        // Bubble floats outside the strip toward the content area
        val bubbleCenterX = if (showOnRight) {
            -halfBubble - gap
        } else {
            width + halfBubble + gap
        }

        val scale = 0.7f + 0.3f * activationProgress
        val half = bubbleSize * scale / 2f

        previewRect.set(
            bubbleCenterX - half, centerY - half,
            bubbleCenterX + half, centerY + half
        )

        previewPaint.alpha = (230 * activationProgress).toInt().coerceIn(0, 230)
        canvas.drawRoundRect(previewRect, half, half, previewPaint)

        previewTextPaint.alpha = (255 * activationProgress).toInt().coerceIn(0, 255)
        canvas.drawText(
            letters[activeIndex].toString(),
            previewRect.centerX(),
            previewRect.centerY() - (previewTextPaint.descent() + previewTextPaint.ascent()) / 2f,
            previewTextPaint
        )
    }

    private fun findBestIndexForTarget(target: Char): Int {
        val exact = letters.indexOf(target)
        if (exact >= 0) return exact

        val after = letters.indexOfFirst { c -> c != '★' && c != '#' && c >= target }
        if (after >= 0) return after

        return letters.lastIndex.coerceAtLeast(0)
    }

    // -------------------------------------------------------------------------

    private fun updateThemeColor() {
        val tv = TypedValue()
        val theme = context.theme
        if (theme.resolveAttribute(R.attr.primaryColor, tv, true)) {
            textColor = tv.data
            textPaint.color = textColor
        }
        if (theme.resolveAttribute(R.attr.primaryShadeDarkColor, tv, true)) {
            previewPaint.color = tv.data
        } else {
            previewPaint.color = Color.DKGRAY
        }
    }

    private fun Float.spToPx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics
    )

    private fun Float.dpToPx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics
    )
}
