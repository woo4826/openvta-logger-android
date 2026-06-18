package com.temporal.vtalogger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImuEnhancementTest {
    @Test
    fun presetsExposeRawFiveHzAndTenHzChoices() {
        val presets = ImuEnhancementPresets.all

        assertTrue(presets.any { it.id == ImuEnhancementPresets.DEFAULT_ID && it.outputHz == 1 })
        assertTrue(presets.any { it.outputHz == 5 })
        assertTrue(presets.count { it.outputHz == 10 } >= 3)
    }

    @Test
    fun linearFiveHzAddsDerivedPointsAndKeepsRawEndpoints() {
        val trace = VtaTrace("sample", twoGpsPointsOneSecondApart(), emptyList())

        val enhanced = VtaTraceEnhancer.enhance(trace, "linear_5hz")

        assertEquals("linear_5hz", enhanced.enhancementPresetId)
        assertEquals(2, enhanced.gpsPoints.size)
        assertEquals(6, enhanced.displayGpsPoints.size)
        assertEquals(2, enhanced.rawGpsCount)
        assertEquals(4, enhanced.enhancedPointCount)
        assertEquals(TracePointSource.RawGps, enhanced.displayGpsPoints.first().source)
        assertEquals(TracePointSource.RawGps, enhanced.displayGpsPoints.last().source)
        assertEquals(TracePointSource.LinearInterpolation, enhanced.enhancedGpsPoints[0].source)
        assertEquals(37.00002, enhanced.enhancedGpsPoints[0].latitude, 0.00001)
    }

    @Test
    fun hermiteTenHzUsesSpeedBearingConstrainedInterpolation() {
        val trace = VtaTrace("sample", twoGpsPointsOneSecondApart(speedKmh = 36.0), emptyList())

        val enhanced = VtaTraceEnhancer.enhance(trace, "hermite_10hz")

        assertEquals(2, enhanced.gpsPoints.size)
        assertEquals(11, enhanced.displayGpsPoints.size)
        assertEquals(9, enhanced.enhancedPointCount)
        assertEquals(TracePointSource.HermiteInterpolation, enhanced.enhancedGpsPoints[0].source)
        assertEquals(TracePointSource.RawGps, enhanced.displayGpsPoints.last().source)
    }

    @Test
    fun imuHeadingPresetUsesSensorHeadingWhenAvailable() {
        val trace = VtaTrace(
            "sample",
            twoGpsPointsOneSecondApart(speedKmh = 18.0),
            listOf(
                SensorTracePoint(
                    index = 1,
                    elapsedSeconds = 0.5,
                    orientationZDegrees = 90.0,
                    accelX = 0.0,
                    accelY = 0.0,
                    accelZ = 9.8,
                    timestampNanos = 500_000_000L,
                    rotationAzimuthDegrees = 90.0,
                ),
            ),
        )

        val enhanced = VtaTraceEnhancer.enhance(trace, "imu_heading_10hz")

        assertEquals(TracePointSource.ImuHeading, enhanced.enhancedGpsPoints[0].source)
        assertTrue(enhanced.enhancedGpsPoints.any { it.source == TracePointSource.ImuHeading && it.confidence < 1.0 })
    }

    @Test
    fun deadReckoningPresetCreatesBoundedDerivedPoints() {
        val trace = VtaTrace(
            "sample",
            twoGpsPointsOneSecondApart(speedKmh = 5.0),
            listOf(
                SensorTracePoint(
                    index = 1,
                    elapsedSeconds = 0.5,
                    accelX = 1.0,
                    accelY = 0.2,
                    accelZ = 9.8,
                    timestampNanos = 500_000_000L,
                    rotationAzimuthDegrees = 45.0,
                ),
            ),
        )

        val enhanced = VtaTraceEnhancer.enhance(trace, "imu_deadreckon_10hz")

        assertEquals(11, enhanced.displayGpsPoints.size)
        assertTrue(enhanced.enhancedGpsPoints.all { it.source == TracePointSource.ImuDeadReckoning })
        assertEquals(37.00010, enhanced.displayGpsPoints.last().latitude, 0.000001)
    }

    private fun twoGpsPointsOneSecondApart(speedKmh: Double = 0.0): List<GpsTracePoint> = listOf(
        GpsTracePoint(
            date = "01012026",
            time = "000000",
            latitude = 37.00000,
            longitude = 127.00000,
            altitudeMeters = 10.0,
            speedKmh = speedKmh,
            bearingDegrees = 0.0,
            satelliteCount = 8,
            accuracyMeters = 5.0,
            provider = "gps",
            elapsedRealtimeNanos = 0L,
            epochMillis = 1_767_225_600_000L,
        ),
        GpsTracePoint(
            date = "01012026",
            time = "000001",
            latitude = 37.00010,
            longitude = 127.00000,
            altitudeMeters = 11.0,
            speedKmh = speedKmh,
            bearingDegrees = 0.0,
            satelliteCount = 8,
            accuracyMeters = 5.0,
            provider = "gps",
            elapsedRealtimeNanos = 1_000_000_000L,
            epochMillis = 1_767_225_601_000L,
        ),
    )
}
