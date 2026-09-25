package com.example.a180d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleRenderStateTest {

    private val settings = ZoneSettings(age = 34, restingHr = 54)
    private val now = 1_000_000L

    private fun stateFor(
        bpm: Int? = 142,
        ageMs: Long = 0L,
        connection: ConnectionState = ConnectionState.CONNECTED,
        zoneSettings: ZoneSettings? = settings,
    ) = bubbleRenderState(
        sample = bpm?.let { HeartRateSample(it, now - ageMs) },
        connectionState = connection,
        nowMs = now,
        settings = zoneSettings,
    )

    // ---- The staleness rule (CLAUDE.md: "Staleness — non-negotiable") ------

    @Test
    fun `a fresh reading shows its digits`() {
        val state = stateFor(bpm = 142, ageMs = 0)
        assertEquals("142", state.bpmText)
        assertEquals(1f, state.alpha, 0f)
        assertEquals(BubbleGlyph.HEART_SOLID, state.glyph)
    }

    @Test
    fun `a reading exactly at the 5s threshold is still live`() {
        val state = stateFor(bpm = 142, ageMs = BUBBLE_STALE_AFTER_MS)
        assertEquals("142", state.bpmText)
        assertEquals(1f, state.alpha, 0f)
    }

    @Test
    fun `one millisecond past the threshold drops the digits`() {
        val state = stateFor(bpm = 142, ageMs = BUBBLE_STALE_AFTER_MS + 1)
        assertEquals("--", state.bpmText)
        assertEquals(STALE_ALPHA, state.alpha, 0f)
        assertEquals(ZONE_COLOR_NONE_ARGB, state.ringArgb)
        assertNull("a stale bubble must not keep beating", state.pulsePeriodMs)
    }

    @Test
    fun `a stale reading never holds the last value forward`() {
        val live = stateFor(bpm = 171, ageMs = 0)
        val stale = stateFor(bpm = 171, ageMs = 30_000)
        assertEquals("171", live.bpmText)
        assertNotEquals(live.bpmText, stale.bpmText)
        assertEquals("--", stale.bpmText)
    }

    @Test
    fun `the stale glyph is hollow and the ring is grey`() {
        val state = stateFor(ageMs = 10_000)
        assertEquals(BubbleGlyph.HEART_OUTLINE, state.glyph)
        assertEquals(ZONE_COLOR_NONE_ARGB, state.ringArgb)
    }

    // ---- Connection states -------------------------------------------------

    @Test
    fun `waiting for the first sample pulses but shows no number`() {
        val state = stateFor(bpm = null)
        assertEquals("--", state.bpmText)
        assertEquals(BubbleGlyph.HEART_OUTLINE, state.glyph)
        assertEquals(1f, state.alpha, 0f)
        assertEquals(1_000L, state.pulsePeriodMs)
    }

    @Test
    fun `reconnecting wins over a sample that is still fresh`() {
        val state = stateFor(bpm = 142, ageMs = 0, connection = ConnectionState.RECONNECTING)
        assertEquals(BubbleGlyph.RECONNECT, state.glyph)
        assertEquals("--", state.bpmText)
    }

    @Test
    fun `an unrecoverable link shows the alert glyph in the error colour`() {
        val state = stateFor(connection = ConnectionState.LINK_LOST_UNRECOVERABLE)
        assertEquals(BubbleGlyph.ALERT, state.glyph)
        assertEquals("--", state.bpmText)
        assertEquals(0xFFEF5350.toInt(), state.ringArgb)
        assertNull(state.pulsePeriodMs)
    }

    @Test
    fun `unrecoverable outranks reconnecting`() {
        val state = stateFor(bpm = null, connection = ConnectionState.LINK_LOST_UNRECOVERABLE)
        assertEquals(BubbleGlyph.ALERT, state.glyph)
    }

    // ---- Zone colouring ----------------------------------------------------

    @Test
    fun `the ring takes the zone colour of the current reading`() {
        // age 34, resting 54 -> HRmax 184.2, HRR 130.2.
        // Z1 floor 119.1, Z2 132.1, Z3 145.1, Z4 158.2, Z5 171.2.
        assertEquals(ZONE_COLOR_ARGB[0], stateFor(bpm = 125).ringArgb)
        assertEquals(ZONE_COLOR_ARGB[1], stateFor(bpm = 138).ringArgb)
        assertEquals(ZONE_COLOR_ARGB[2], stateFor(bpm = 151).ringArgb)
        assertEquals(ZONE_COLOR_ARGB[3], stateFor(bpm = 164).ringArgb)
        assertEquals(ZONE_COLOR_ARGB[4], stateFor(bpm = 177).ringArgb)
    }

    @Test
    fun `without profile settings the ring falls back to grey but the number still shows`() {
        val state = stateFor(bpm = 142, zoneSettings = null)
        assertEquals("142", state.bpmText)
        assertEquals(ZONE_COLOR_NONE_ARGB, state.ringArgb)
    }

    @Test
    fun `zone index mapping matches the dial`() {
        assertEquals(-1, zoneSegmentIndex(null))
        assertEquals(0, zoneSegmentIndex(0.4))
        assertEquals(0, zoneSegmentIndex(1.0))
        assertEquals(2, zoneSegmentIndex(3.2))
        assertEquals(4, zoneSegmentIndex(5.0))
        assertEquals(4, zoneSegmentIndex(9.9))
    }

    // ---- Pulse -------------------------------------------------------------

    @Test
    fun `the pulse period tracks the beat rate`() {
        assertEquals(500L, stateFor(bpm = 120).pulsePeriodMs)
        assertEquals(300L, stateFor(bpm = 200).pulsePeriodMs)
    }

    @Test
    fun `the pulse period stays inside sane bounds at the edges of the clamp`() {
        val fast = stateFor(bpm = 230).pulsePeriodMs!!
        val slow = stateFor(bpm = 25).pulsePeriodMs!!
        assertTrue("$fast should not animate faster than 250ms", fast >= 250L)
        assertTrue("$slow should not animate slower than 2s", slow <= 2_000L)
    }

    // ---- Edge snapping -----------------------------------------------------

    @Test
    fun `a bubble left of centre snaps to the left margin`() {
        assertEquals(24, snapToNearestEdgeX(currentX = 100, viewWidth = 300, screenWidth = 1080, marginPx = 24))
    }

    @Test
    fun `a bubble right of centre snaps flush to the right margin`() {
        assertEquals(756, snapToNearestEdgeX(currentX = 700, viewWidth = 300, screenWidth = 1080, marginPx = 24))
    }

    @Test
    fun `a bubble dead centre prefers the left edge`() {
        assertEquals(24, snapToNearestEdgeX(currentX = 390, viewWidth = 300, screenWidth = 1080, marginPx = 24))
    }

    @Test
    fun `a view wider than the screen still lands on screen`() {
        val x = snapToNearestEdgeX(currentX = 0, viewWidth = 1200, screenWidth = 1080, marginPx = 24)
        assertEquals(24, x)
    }

    // ---- Dismiss magnet ----------------------------------------------------

    private fun magnet(x: Int, y: Int, radius: Int = 300) = isWithinDismissMagnet(
        bubbleCentreX = x,
        bubbleCentreY = y,
        targetCentreX = 540,
        targetCentreY = 2100,
        magnetRadiusPx = radius,
    )

    @Test
    fun `dead centre on the target arms`() {
        assertTrue(magnet(540, 2100))
    }

    @Test
    fun `exactly on the radius still arms`() {
        assertTrue("straight up", magnet(540, 2100 - 300))
        assertTrue("straight across", magnet(540 - 300, 2100))
    }

    @Test
    fun `one pixel past the radius does not arm`() {
        assertFalse(magnet(540, 2100 - 301))
        assertFalse(magnet(540 + 301, 2100))
    }

    @Test
    fun `the magnet is circular, not square`() {
        // A corner offset of 250 on both axes is well inside a 300-wide box but
        // 354px away as the crow flies, so a box test would wrongly arm here.
        assertFalse(magnet(540 + 250, 2100 + 250))
        // 212 on both axes is 300px away — the diagonal edge of the circle.
        assertTrue(magnet(540 + 212, 2100 + 212))
    }

    @Test
    fun `a bubble parked at the top of the screen never arms`() {
        assertFalse(magnet(540, 200))
        assertFalse(magnet(60, 100))
    }

    @Test
    fun `a zero radius arms only on an exact hit`() {
        assertTrue(magnet(540, 2100, radius = 0))
        assertFalse(magnet(541, 2100, radius = 0))
    }
}
