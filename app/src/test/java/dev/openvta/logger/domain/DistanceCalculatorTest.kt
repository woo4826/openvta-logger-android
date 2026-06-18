package dev.openvta.logger.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceCalculatorTest {
    @Test
    fun distanceBetweenSamePointIsZero() {
        assertEquals(0.0, DistanceCalculator.metersBetween(1.0, 2.0, 1.0, 2.0), 0.001)
    }

    @Test
    fun distanceBetweenNearbySydneyPointsIsReasonable() {
        val distance = DistanceCalculator.metersBetween(-33.8688, 151.2093, -33.8695, 151.2100)
        assertEquals(101.0, distance, 15.0)
    }
}
