package com.example.a180d

/**
 * Everything the floating bubble draws, derived from the live session state.
 *
 * Pure and free of Android imports so the staleness rule can be unit-tested —
 * see `BubbleRenderStateTest`. [BubbleView] only renders what this produces.
 */
data class BubbleRenderState(
    /** The digits, or "--" whenever there is no *currently valid* reading. */
    val bpmText: String,
    val ringArgb: Int,
    val glyph: BubbleGlyph,
    /** Whole-pill opacity. Stale readings drop to [STALE_ALPHA]. */
    val alpha: Float,
    /** Beat-animation period, or null when the glyph should sit still. */
    val pulsePeriodMs: Long?,
)

enum class BubbleGlyph { HEART_SOLID, HEART_OUTLINE, RECONNECT, ALERT }

/**
 * Matches `STALE_AFTER_MS` in MainActivity — "Staleness — non-negotiable" in
 * CLAUDE.md: never render a stale BPM as though it were current, and never
 * hold the last value forward.
 */
const val BUBBLE_STALE_AFTER_MS = 5_000L

/** Opacity of the whole pill once the reading has gone stale. */
const val STALE_ALPHA = 0.35f

/** Heartbeat of the "waiting for the first sample" pulse. */
private const val WAITING_PULSE_MS = 1_000L

private const val NO_READING = "--"
private const val ERROR_ARGB = 0xFFEF5350.toInt()

/**
 * A stale reading loses its digits outright rather than dimming them. The
 * in-app dial can afford to dim and label the age; at 76dp the bubble cannot,
 * so a faded number would be indistinguishable from a live one seen at a
 * glance. Ring colour, glyph and opacity all degrade alongside it.
 */
fun bubbleRenderState(
    sample: HeartRateSample?,
    connectionState: ConnectionState,
    nowMs: Long,
    settings: ZoneSettings?,
): BubbleRenderState = when {
    connectionState == ConnectionState.LINK_LOST_UNRECOVERABLE -> BubbleRenderState(
        bpmText = NO_READING,
        ringArgb = ERROR_ARGB,
        glyph = BubbleGlyph.ALERT,
        alpha = 1f,
        pulsePeriodMs = null,
    )

    connectionState == ConnectionState.RECONNECTING -> BubbleRenderState(
        bpmText = NO_READING,
        ringArgb = ZONE_COLOR_NONE_ARGB,
        glyph = BubbleGlyph.RECONNECT,
        alpha = 1f,
        pulsePeriodMs = null,
    )

    sample == null -> BubbleRenderState(
        bpmText = NO_READING,
        ringArgb = ZONE_COLOR_NONE_ARGB,
        glyph = BubbleGlyph.HEART_OUTLINE,
        alpha = 1f,
        pulsePeriodMs = WAITING_PULSE_MS,
    )

    nowMs - sample.timestampMs > BUBBLE_STALE_AFTER_MS -> BubbleRenderState(
        bpmText = NO_READING,
        ringArgb = ZONE_COLOR_NONE_ARGB,
        glyph = BubbleGlyph.HEART_OUTLINE,
        alpha = STALE_ALPHA,
        pulsePeriodMs = null,
    )

    else -> {
        val zone = settings?.let { HeartRateZones.computeZone(sample.bpm, it.age, it.restingHr) }
        BubbleRenderState(
            bpmText = sample.bpm.toString(),
            ringArgb = zoneArgb(zone),
            glyph = BubbleGlyph.HEART_SOLID,
            alpha = 1f,
            pulsePeriodMs = (60_000L / sample.bpm).coerceIn(250L, 2_000L),
        )
    }
}

/**
 * Whether the dragged bubble is close enough to the dismiss target that
 * releasing would hide it. Pure so the hit test can be exercised without a
 * display.
 */
fun isWithinDismissMagnet(
    bubbleCentreX: Int,
    bubbleCentreY: Int,
    targetCentreX: Int,
    targetCentreY: Int,
    magnetRadiusPx: Int,
): Boolean {
    val dx = (bubbleCentreX - targetCentreX).toLong()
    val dy = (bubbleCentreY - targetCentreY).toLong()
    val radius = magnetRadiusPx.toLong()
    // Squared comparison: no sqrt, and exactly-on-the-radius counts as inside.
    return dx * dx + dy * dy <= radius * radius
}

/**
 * Which edge a dragged bubble lands on, as a fraction of the usable width.
 * Pure so the snap can be tested without a display.
 */
fun snapToNearestEdgeX(currentX: Int, viewWidth: Int, screenWidth: Int, marginPx: Int): Int {
    val rightX = (screenWidth - viewWidth - marginPx).coerceAtLeast(marginPx)
    val centre = currentX + viewWidth / 2f
    return if (centre <= screenWidth / 2f) marginPx else rightX
}
