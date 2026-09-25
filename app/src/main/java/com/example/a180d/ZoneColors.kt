package com.example.a180d

/**
 * The zone -> colour mapping, shared by the in-app dial, the session history
 * chips and the floating bubble.
 *
 * Deliberately plain ARGB ints with no Compose or Android imports: the bubble
 * draws on a raw [android.graphics.Canvas] and [BubbleRenderState] is a pure
 * unit-tested mapping, so neither can depend on `androidx.compose.ui.graphics`.
 * `MainActivity` wraps these back into Compose `Color`s for its own use.
 */
val ZONE_COLOR_ARGB = intArrayOf(
    0xFF4FC3F7.toInt(), // Zone 1
    0xFF66BB6A.toInt(), // Zone 2
    0xFFFFEE58.toInt(), // Zone 3
    0xFFFFA726.toInt(), // Zone 4
    0xFFEF5350.toInt(), // Zone 5
)

/** Deepened per-zone colours for text sitting on a light background, where the raw zone hue (esp. yellow) is too washed out to read. */
val ZONE_CHIP_TEXT_LIGHT_ARGB = intArrayOf(
    0xFF0288D1.toInt(),
    0xFF2E7D32.toInt(),
    0xFF8D6E00.toInt(),
    0xFFE65100.toInt(),
    0xFFC62828.toInt(),
)

/** Stand-in when there is no usable reading — no zone, or one too old to show. */
const val ZONE_COLOR_NONE_ARGB: Int = 0xFF9E9E9E.toInt()

/** -1 when there's no reading yet; otherwise 0..4 into [ZONE_COLOR_ARGB], using the same fraction-of-arc mapping as the dial's marker. */
fun zoneSegmentIndex(zone: Double?): Int {
    if (zone == null) return -1
    val fraction = ((zone - 1.0) / 5.0).coerceIn(0.0, 1.0)
    return (fraction * ZONE_COLOR_ARGB.size).toInt().coerceIn(0, ZONE_COLOR_ARGB.size - 1)
}

fun zoneArgb(zone: Double?): Int {
    val index = zoneSegmentIndex(zone)
    return if (index >= 0) ZONE_COLOR_ARGB[index] else ZONE_COLOR_NONE_ARGB
}
