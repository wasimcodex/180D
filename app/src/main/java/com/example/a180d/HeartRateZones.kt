package com.example.a180d

/**
 * Heart rate reserve (Karvonen) zone calculation, per CLAUDE.md. Uses the
 * Tanaka formula for max HR (208 - 0.7*age) rather than 220-age, and reports
 * a fractional zone (e.g. 1.7 = almost into Zone 2) so the number keeps
 * moving between whole zones instead of sitting static for minutes.
 */
object HeartRateZones {

    // Fraction of heart rate reserve at each zone's ceiling: Z1 50%, Z2 60%, Z3 70%, Z4 80%, Z5 90%, top 100%.
    private val ZONE_CEILING_FRACTIONS = doubleArrayOf(0.50, 0.60, 0.70, 0.80, 0.90, 1.00)

    /**
     * Returns a decimal zone in [0.0, 5.0]. Below the Zone 1 floor this is a
     * fraction of the way from resting HR to that floor (under 1.0). At or
     * above max HR this clamps to 5.0.
     */
    fun computeZone(bpm: Int, age: Int, restingHr: Int): Double {
        val hrMax = 208.0 - 0.7 * age
        val hrReserve = hrMax - restingHr
        if (hrReserve <= 0.0) return 0.0

        val boundaries = DoubleArray(ZONE_CEILING_FRACTIONS.size + 1)
        boundaries[0] = restingHr.toDouble()
        for (i in ZONE_CEILING_FRACTIONS.indices) {
            boundaries[i + 1] = hrReserve * ZONE_CEILING_FRACTIONS[i] + restingHr
        }

        val lastZoneIndex = boundaries.lastIndex - 1
        for (zoneIndex in 0..lastZoneIndex) {
            val lower = boundaries[zoneIndex]
            val upper = boundaries[zoneIndex + 1]
            if (bpm <= upper || zoneIndex == lastZoneIndex) {
                val fraction = if (upper > lower) (bpm - lower) / (upper - lower) else 0.0
                return (zoneIndex + fraction).coerceIn(0.0, 5.0)
            }
        }
        return 5.0
    }

    /**
     * The 6 BPM boundary points of the 5 display zones (Z1 floor..Z5 ceiling),
     * for shading a chart. The sub-Z1 band (resting HR up to the 50% mark) is
     * folded into Z1 rather than broken out on its own, matching how the dial
     * already renders it: fractions below 1.0 pin to the start of the Z1 arc
     * segment (see [computeZone]'s doc and the dial's fraction clamp).
     */
    fun zoneBandBoundariesBpm(age: Int, restingHr: Int): List<Double> {
        val hrMax = 208.0 - 0.7 * age
        val hrReserve = (hrMax - restingHr).coerceAtLeast(0.0)
        return listOf(
            restingHr.toDouble(),
            hrReserve * 0.60 + restingHr,
            hrReserve * 0.70 + restingHr,
            hrReserve * 0.80 + restingHr,
            hrReserve * 0.90 + restingHr,
            hrMax,
        )
    }
}
