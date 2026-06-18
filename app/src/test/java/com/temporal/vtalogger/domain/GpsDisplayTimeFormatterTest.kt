package com.temporal.vtalogger.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDisplayTimeFormatterTest {
    @Test
    fun formatsLocalDisplayTimeWithRelativeAge() {
        val point = GpsTracePoint(
            date = "10062026",
            time = "072200",
            latitude = 37.5665,
            longitude = 126.9780,
            altitudeMeters = 30.0,
            speedKmh = 20.0,
            bearingDegrees = 90.0,
            satelliteCount = 8,
            accuracyMeters = 4.0,
            provider = "gps",
            elapsedRealtimeNanos = 1L,
            epochMillis = 1_781_076_120_000L,
        )

        val formatted = GpsDisplayTimeFormatter.format(
            point = point,
            nowMillis = 1_781_076_300_000L,
            zoneId = ZoneId.of("Asia/Seoul"),
        )

        assertEquals("2026.06.10 16:22 (3min ago)", formatted)
    }

    @Test
    fun fallsBackToLegacyDateWhenEpochIsMissing() {
        val point = GpsTracePoint(
            date = "10062026",
            time = "162200",
            latitude = 37.5665,
            longitude = 126.9780,
            altitudeMeters = 30.0,
            speedKmh = 20.0,
            bearingDegrees = 90.0,
            satelliteCount = 8,
            accuracyMeters = null,
            provider = null,
            elapsedRealtimeNanos = null,
        )

        assertEquals("10062026 162200", GpsDisplayTimeFormatter.format(point))
    }
}
