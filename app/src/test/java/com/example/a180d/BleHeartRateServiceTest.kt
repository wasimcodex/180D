package com.example.a180d

import org.junit.Assert.assertEquals
import org.junit.Test

class BleHeartRateServiceTest {

    @Test
    fun `formatElapsed at zero seconds`() {
        assertEquals("00:00", BleHeartRateService.formatElapsed(nowMs = 1_000L, startMs = 1_000L))
    }

    @Test
    fun `formatElapsed under a minute`() {
        assertEquals("00:39", BleHeartRateService.formatElapsed(nowMs = 40_000L, startMs = 1_000L))
    }

    @Test
    fun `formatElapsed past a minute pads seconds`() {
        // 60s + 5s = 65s -> 01:05
        assertEquals("01:05", BleHeartRateService.formatElapsed(nowMs = 66_000L, startMs = 1_000L))
    }

    @Test
    fun `formatElapsed past an hour includes hours`() {
        // 1h 2m 3s
        val elapsedMs = (3_600L + 2 * 60 + 3) * 1000
        assertEquals("1:02:03", BleHeartRateService.formatElapsed(nowMs = elapsedMs, startMs = 0L))
    }

    @Test
    fun `formatElapsed clamps negative duration to zero`() {
        assertEquals("00:00", BleHeartRateService.formatElapsed(nowMs = 1_000L, startMs = 5_000L))
    }
}
