package com.example.a180d

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateZonesTest {

    // age=30 -> hrMax=187, restingHr=60 -> hrReserve=127
    // Z1 floor (50%)=123.5, Z2 floor (60%)=136.2, Z5 floor (90%)=174.3, top (100%)=187

    @Test
    fun `at resting hr zone is zero`() {
        assertEquals(0.0, HeartRateZones.computeZone(bpm = 60, age = 30, restingHr = 60), 0.01)
    }

    @Test
    fun `below resting hr clamps to zero`() {
        assertEquals(0.0, HeartRateZones.computeZone(bpm = 40, age = 30, restingHr = 60), 0.01)
    }

    @Test
    fun `midway between resting and zone one floor is a fraction under one`() {
        // fraction = (90-60)/(123.5-60) = 0.4724
        assertEquals(0.4724, HeartRateZones.computeZone(bpm = 90, age = 30, restingHr = 60), 0.001)
    }

    @Test
    fun `just above zone one floor value is just above one`() {
        // fraction = (124-123.5)/(136.2-123.5) = 0.0394 -> zone = 1.0394
        assertEquals(1.0394, HeartRateZones.computeZone(bpm = 124, age = 30, restingHr = 60), 0.001)
    }

    @Test
    fun `within zone one interpolates fractionally`() {
        // fraction = (130-123.5)/(136.2-123.5) = 0.5118 -> zone = 1.5118
        assertEquals(1.5118, HeartRateZones.computeZone(bpm = 130, age = 30, restingHr = 60), 0.001)
    }

    @Test
    fun `at max hr clamps to five`() {
        assertEquals(5.0, HeartRateZones.computeZone(bpm = 187, age = 30, restingHr = 60), 0.01)
    }

    @Test
    fun `above max hr clamps to five`() {
        assertEquals(5.0, HeartRateZones.computeZone(bpm = 220, age = 30, restingHr = 60), 0.01)
    }

    @Test
    fun `invalid settings where resting hr exceeds max hr returns zero`() {
        assertEquals(0.0, HeartRateZones.computeZone(bpm = 150, age = 30, restingHr = 200), 0.01)
    }
}
