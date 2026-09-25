package com.example.a180d

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat

/**
 * The drop target that appears at the bottom of the screen while the bubble is
 * being dragged: a gradient scrim with an X in a circle, and a "Hide" label.
 *
 * Purely decorative — its window is `FLAG_NOT_TOUCHABLE`, so it never receives
 * a touch. [BubbleController] owns the hit test and tells it when it is
 * [armed]; dropping is just the bubble's own ACTION_UP landing inside the
 * magnet radius.
 */
class DismissTargetView(context: Context) : View(context) {

    /** True while releasing would actually dismiss. Drives the grow + red ring. */
    var armed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                animateArm(value)
            }
        }

    private var armProgress = 0f
    private var armAnimator: ValueAnimator? = null

    private val idleRadius = dp(IDLE_RADIUS_DP)
    private val armedRadius = dp(ARMED_RADIUS_DP)
    private val circleBottomMargin = dp(CIRCLE_BOTTOM_MARGIN_DP)

    // Transparent until onSizeChanged builds the gradient: a bare Paint defaults
    // to opaque black, which would flash a solid bar across the bottom of the
    // screen on any frame that draws before the shader exists.
    private val scrimPaint = Paint().apply { color = Color.TRANSPARENT }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CIRCLE_FILL_ARGB
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RING_WIDTH_DP)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(CROSS_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_ARGB
        textSize = sp(LABEL_SP)
        textAlign = Paint.Align.CENTER
        typeface = labelTypeface(context)
    }

    private val label = context.getString(R.string.bubble_dismiss_label)
    private val argbEvaluator = ArgbEvaluator()

    init {
        // Never announced or focused: the bubble carries the equivalent
        // "Hide bubble" accessibility action instead.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Weighted so nearly all the darkening sits near the bottom, keeping
        // the app underneath legible while still backing the target.
        scrimPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, SCRIM_MID_ARGB, SCRIM_BOTTOM_ARGB),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

        val cx = width / 2f
        val cy = height - circleBottomMargin
        val radius = idleRadius + (armedRadius - idleRadius) * armProgress

        circlePaint.alpha = (255 * (IDLE_CIRCLE_ALPHA + (1f - IDLE_CIRCLE_ALPHA) * armProgress)).toInt()
        canvas.drawCircle(cx, cy, radius, circlePaint)

        ringPaint.color = argbEvaluator.evaluate(armProgress, RING_IDLE_ARGB, RING_ARMED_ARGB) as Int
        canvas.drawCircle(cx, cy, radius, ringPaint)

        crossPaint.color = argbEvaluator.evaluate(armProgress, CROSS_IDLE_ARGB, RING_ARMED_ARGB) as Int
        val arm = radius * CROSS_ARM_FRACTION
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, crossPaint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm, cy + arm, crossPaint)

        canvas.drawText(label, cx, height - dp(LABEL_BASELINE_FROM_BOTTOM_DP), labelPaint)
    }

    private fun animateArm(toArmed: Boolean) {
        armAnimator?.cancel()
        armAnimator = ValueAnimator.ofFloat(armProgress, if (toArmed) 1f else 0f).apply {
            duration = ARM_ANIM_MS
            addUpdateListener {
                armProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun labelTypeface(context: Context): Typeface {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.manrope) }.getOrNull()
            ?: return Typeface.DEFAULT
        return Typeface.create(base, 600, false)
    }

    companion object {
        /** Height of the target window; everything above it is untouched. */
        const val TARGET_HEIGHT_DP = 220f

        /** Circle centre, measured up from the bottom of the usable area. Shared with [BubbleController]'s hit test. */
        const val CIRCLE_BOTTOM_MARGIN_DP = 90f

        /** How close the bubble's centre must get before releasing dismisses. */
        const val MAGNET_RADIUS_DP = 100f

        private const val IDLE_RADIUS_DP = 28f
        private const val ARMED_RADIUS_DP = 34f
        private const val RING_WIDTH_DP = 1.5f
        private const val CROSS_WIDTH_DP = 2.5f
        private const val LABEL_SP = 12f
        private const val LABEL_BASELINE_FROM_BOTTOM_DP = 30f
        private const val CROSS_ARM_FRACTION = 0.32f
        private const val IDLE_CIRCLE_ALPHA = 0.65f
        private const val ARM_ANIM_MS = 140L

        private val SCRIM_MID_ARGB = Color.argb(64, 0, 0, 0)
        private val SCRIM_BOTTOM_ARGB = Color.argb(140, 0, 0, 0)
        private val CIRCLE_FILL_ARGB = Color.argb(255, 31, 36, 44)
        private val RING_IDLE_ARGB = Color.argb(51, 255, 255, 255)
        private val RING_ARMED_ARGB = Color.argb(255, 239, 83, 80)
        private val CROSS_IDLE_ARGB = Color.argb(224, 243, 241, 236)
        private val LABEL_ARGB = Color.argb(255, 198, 203, 210)
    }
}
