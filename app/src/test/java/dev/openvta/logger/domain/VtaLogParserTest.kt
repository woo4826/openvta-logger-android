package dev.openvta.logger.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class VtaLogParserTest {
    @Test
    fun parsesLegacyAndExtendedGpsRows() {
        val trace = VtaLogParser.parse(
            "sample.Vta",
            listOf(
                "%% VTALogger Version: 1.02a",
                "% \$UTCDate,UTCTime,Latitude,Longitude,Altitude,Speed,Bearing,NumSat",
                "$17062026,152258,-33.875,151.224998333,0,26,0,6",
                "$17062026,152259,-33.875000000,151.224998333,12,31,180,7,5.00,gps,435015307830",
                "@17062026,152259,-33.875050000,151.225050000,12,32,181,7,5.50,gps,435015807830,ImuHeading,0.820,imu_heading_10hz,0",
            ),
        )

        assertEquals(2, trace.gpsPoints.size)
        assertEquals(1, trace.enhancedGpsPoints.size)
        assertEquals(26.0, trace.gpsPoints[0].speedKmh, 0.001)
        assertEquals(1_781_709_778_000L, trace.gpsPoints[0].epochMillis)
        assertEquals(null, trace.gpsPoints[0].accuracyMeters)
        assertEquals(null, trace.gpsPoints[0].provider)
        assertEquals(5.0, trace.gpsPoints[1].accuracyMeters ?: 0.0, 0.001)
        assertEquals("gps", trace.gpsPoints[1].provider)
        assertEquals(435015307830L, trace.gpsPoints[1].elapsedRealtimeNanos)
        assertEquals(1_781_709_779_000L, trace.gpsPoints[1].epochMillis)
        assertEquals(32.0, trace.maxSpeedKmh, 0.001)
        assertEquals(32.0, trace.enhancedGpsPoints[0].speedKmh, 0.001)
        assertEquals(TracePointSource.ImuHeading, trace.enhancedGpsPoints[0].source)
        assertEquals(0.82, trace.enhancedGpsPoints[0].confidence, 0.001)
        assertEquals("imu_heading_10hz", trace.enhancementPresetId)
        assertEquals(trace.enhancedGpsPoints[0], trace.latestGps)
    }

    @Test
    fun parsesSensorRows() {
        val trace = VtaLogParser.parse(
            "sample.Vta",
            listOf("#12,1.500,0,-0.083,0.000,0.000,0.100,9.700,0.812,123,2"),
        )

        assertEquals(1, trace.sensorPoints.size)
        assertEquals(12L, trace.sensorPoints.first().index)
        assertEquals(9.7, trace.sensorPoints.first().accelY, 0.001)
    }
}
