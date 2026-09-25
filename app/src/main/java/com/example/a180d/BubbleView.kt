package com.example.a180d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withScale

/**
 * The floating BPM pill.
 *
 * A plain [View] drawing straight onto a [Canvas] rather than a `ComposeView`:
 * hosting Compose in a `WindowManager` overlay means installing the
 * ViewTree lifecycle/saved-state/view-model owners by hand, which is a lot of
 * plumbing for a rounded rect, a ring and one text run. It also matches the
 * app's existing habit of hand-drawing its glyphs.
 *
 * Renders exactly what [BubbleRenderState] says — all the staleness and
 * connection logic lives there, where it can be tested.
 */
class BubbleView(context: Context) : View(context) {

    private var state: BubbleRenderState = BubbleRenderState(
        bpmText = "--",
        ringArgb = ZONE_COLOR_NONE_ARGB,
        glyph = BubbleGlyph.HEART_OUTLINE,
        alpha = 1f,
        pulsePeriodMs = null,
    )

    /** Scales the pill up and deepens its shadow while the user drags it. */
    var dragging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** True while the bubble is magnetised to the dismiss target — it shrinks, as though being swallowed. */
    var armed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val pillWidth = dp(PILL_WIDTH_DP)
    private val pillHeight = dp(PILL_HEIGHT_DP)
    private val shadowPad = dp(SHADOW_PAD_DP)
    private val ringWidth = dp(RING_WIDTH_DP)
    private val glyphSize = dp(GLYPH_SIZE_DP)
    private val glyphGap = dp(GLYPH_GAP_DP)

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = PILL_BACKGROUND_ARGB
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ON_SURFACE_ARGB
        textSize = sp(BPM_TEXT_SP)
        typeface = boldNumerals(context)
    }

    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(GLYPH_STROKE_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pillRect = RectF()
    private val glyphRect = RectF()
    private val heartPath = Path()
    private val scaledHeartPath = Path()
    private val heartMatrix = Matrix()

    init {
        // setShadowLayer is only reliably honoured on a software layer, and this
        // view is a few hundred pixels square, so the cost is trivial.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        buildHeartPath()
        isFocusable = true
        contentDescription = context.getString(R.string.bubble_no_reading)
    }

    fun update(newState: BubbleRenderState) {
        if (newState != state) {
            state = newState
            contentDescription = if (newState.bpmText == "--") {
                context.getString(R.string.bubble_no_reading)
            } else {
                context.getString(R.string.bubble_reading, newState.bpmText)
            }
            invalidate()
        }
    }

    /**
     * The controller drives taps from its own touch listener (it also handles
     * drag and long-press), so the click has to be routed back through here for
     * accessibility services to be able to activate the bubble at all.
     */
    override fun performClick(): Boolean = super.performClick()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            (pillWidth + shadowPad * 2).toInt(),
            (pillHeight + shadowPad * 2).toInt(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val scale = when {
            armed -> ARMED_SCALE
            dragging -> DRAG_SCALE
            else -> 1f
        }
        val alpha = (state.alpha.coerceIn(0f, 1f) * 255).toInt()

        val saved = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        canvas.scale(scale, scale, width / 2f, height / 2f)

        pillRect.set(shadowPad, shadowPad, shadowPad + pillWidth, shadowPad + pillHeight)
        val radius = pillHeight / 2f

        backgroundPaint.setShadowLayer(
            dp(if (dragging) DRAG_SHADOW_DP else SHADOW_RADIUS_DP),
            0f,
            dp(SHADOW_DY_DP),
            SHADOW_ARGB,
        )
        canvas.drawRoundRect(pillRect, radius, radius, backgroundPaint)

        ringPaint.color = state.ringArgb
        val inset = ringWidth / 2f
        pillRect.inset(inset, inset)
        canvas.drawRoundRect(pillRect, radius - inset, radius - inset, ringPaint)
        pillRect.inset(-inset, -inset)

        drawContent(canvas)
        canvas.restoreToCount(saved)

        scheduleNextPulseFrame()
    }

    /** Glyph + digits are centred as one group, so 2- and 3-digit readings both sit square in a fixed-width pill. */
    private fun drawContent(canvas: Canvas) {
        val textWidth = textPaint.measureText(state.bpmText)
        val contentWidth = glyphSize + glyphGap + textWidth
        val startX = pillRect.centerX() - contentWidth / 2f
        val centerY = pillRect.centerY()

        glyphRect.set(startX, centerY - glyphSize / 2f, startX + glyphSize, centerY + glyphSize / 2f)
        drawGlyph(canvas)

        // Baseline from the font metrics rather than a fudge factor, so the
        // digits sit optically centred whatever the fallback typeface is.
        val metrics = textPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(state.bpmText, startX + glyphSize + glyphGap, baseline, textPaint)
    }

    private fun drawGlyph(canvas: Canvas) {
        val tint = state.ringArgb
        glyphFillPaint.color = tint
        glyphStrokePaint.color = tint

        when (state.glyph) {
            BubbleGlyph.HEART_SOLID -> drawHeart(canvas, glyphFillPaint, beating = true)
            BubbleGlyph.HEART_OUTLINE -> drawHeart(canvas, glyphStrokePaint, beating = true)
            BubbleGlyph.RECONNECT -> drawReconnect(canvas)
            BubbleGlyph.ALERT -> drawAlert(canvas)
        }
    }

    private fun drawHeart(canvas: Canvas, paint: Paint, beating: Boolean) {
        val beat = if (beating) pulseScale() else 1f
        heartMatrix.reset()
        heartMatrix.setScale(glyphSize / HEART_VIEWBOX, glyphSize / HEART_VIEWBOX)
        heartMatrix.postTranslate(glyphRect.left, glyphRect.top)
        heartPath.transform(heartMatrix, scaledHeartPath)

        canvas.withScale(beat, beat, glyphRect.centerX(), glyphRect.centerY()) {
            drawPath(scaledHeartPath, paint)
        }
    }

    private fun drawReconnect(canvas: Canvas) {
        val inset = glyphStrokePaint.strokeWidth / 2f
        glyphRect.inset(inset, inset)
        // A lit arc sweeping the circle, rotating once every RECONNECT_SPIN_MS.
        val rotation = (SystemClock.uptimeMillis() % RECONNECT_SPIN_MS) / RECONNECT_SPIN_MS.toFloat() * 360f
        canvas.drawArc(glyphRect, rotation, RECONNECT_SWEEP_DEGREES, false, glyphStrokePaint)
        glyphRect.inset(-inset, -inset)
    }

    private fun drawAlert(canvas: Canvas) {
        val cx = glyphRect.centerX()
        val top = glyphRect.top + glyphSize * 0.16f
        canvas.drawLine(cx, top, cx, top + glyphSize * 0.42f, glyphStrokePaint)
        canvas.drawCircle(cx, glyphRect.bottom - glyphSize * 0.13f, glyphStrokePaint.strokeWidth / 2f, glyphFillPaint)
    }

    /** Swells to [PULSE_PEAK] over the first half of [PULSE_DURATION_MS] and settles back over the second. */
    private fun pulseScale(): Float {
        val period = state.pulsePeriodMs ?: return 1f
        val t = (SystemClock.uptimeMillis() % period).toFloat()
        val half = PULSE_DURATION_MS / 2f
        return when {
            t < half -> 1f + (PULSE_PEAK - 1f) * (t / half)
            t < PULSE_DURATION_MS -> PULSE_PEAK - (PULSE_PEAK - 1f) * ((t - half) / half)
            else -> 1f
        }
    }

    /**
     * Animates only while something is actually moving, and only for the frames
     * that need it — between beats it sleeps until the next one is due instead
     * of burning 60fps for a static pill.
     */
    private fun scheduleNextPulseFrame() {
        if (state.glyph == BubbleGlyph.RECONNECT) {
            postInvalidateOnAnimation()
            return
        }
        val period = state.pulsePeriodMs ?: return
        val t = SystemClock.uptimeMillis() % period
        if (t < PULSE_DURATION_MS) postInvalidateOnAnimation() else postInvalidateDelayed(period - t)
    }

    /** Material's heart, on a 24x24 grid; scaled to [glyphSize] at draw time. */
    private fun buildHeartPath() {
        heartPath.reset()
        heartPath.moveTo(12f, 21.35f)
        heartPath.lineTo(10.55f, 20.03f)
        heartPath.cubicTo(5.4f, 15.36f, 2f, 12.28f, 2f, 8.5f)
        heartPath.cubicTo(2f, 5.42f, 4.42f, 3f, 7.5f, 3f)
        heartPath.cubicTo(9.24f, 3f, 10.91f, 3.81f, 12f, 5.09f)
        heartPath.cubicTo(13.09f, 3.81f, 14.76f, 3f, 16.5f, 3f)
        heartPath.cubicTo(19.58f, 3f, 22f, 5.42f, 22f, 8.5f)
        heartPath.cubicTo(22f, 12.28f, 18.6f, 15.36f, 13.45f, 20.04f)
        heartPath.close()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun boldNumerals(context: Context): Typeface {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }.getOrNull()
            ?: return Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // space_grotesk.ttf is a variable font; ask for weight 700 explicitly
        // rather than relying on synthetic bolding.
        return Typeface.create(base, 700, false)
    }

    companion object {
        const val PILL_WIDTH_DP = 84f
        const val PILL_HEIGHT_DP = 44f
        /** Slack around the pill so the drop shadow and the drag scale-up aren't clipped. */
        const val SHADOW_PAD_DP = 12f

        private const val RING_WIDTH_DP = 2f
        private const val GLYPH_SIZE_DP = 14f
        private const val GLYPH_GAP_DP = 6f
        private const val GLYPH_STROKE_DP = 1.8f
        private const val BPM_TEXT_SP = 23f
        private const val SHADOW_RADIUS_DP = 8f
        private const val DRAG_SHADOW_DP = 16f
        private const val SHADOW_DY_DP = 3f

        private const val HEART_VIEWBOX = 24f
        private const val DRAG_SCALE = 1.08f
        private const val ARMED_SCALE = 0.9f
        private const val PULSE_PEAK = 1.18f
        private const val PULSE_DURATION_MS = 140L
        private const val RECONNECT_SPIN_MS = 1_200L
        private const val RECONNECT_SWEEP_DEGREES = 280f

        /** Dark `surface` at 92%, kept dark in light mode too — a light pill over a light app disappears. */
        private val PILL_BACKGROUND_ARGB = Color.argb(235, 24, 28, 34)
        private val ON_SURFACE_ARGB = Color.argb(255, 243, 241, 236)
        private val SHADOW_ARGB = Color.argb(150, 0, 0, 0)
    }
}
