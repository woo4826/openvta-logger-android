package dev.openvta.logger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class VtaFormatterTest {
    private val formatter = VtaFormatter("test", TimeZone.getTimeZone("UTC"))

    @Test
    fun gpsRecordKeepsLegacyFieldsThenAppendsExtensions() {
        val line = formatter.formatGps(
            GpsSample(
                timeMillis = 1_577_836_800_000L,
                latitude = -33.8688,
                longitude = 151.2093,
                altitudeMeters = 42.0,
                speedMetersPerSecond = 10f,
                bearingDegrees = 180f,
                satelliteCount = 7,
                accuracyMeters = 3.25f,
                provider = "gps",
                elapsedRealtimeNanos = 123456789L,
            ),
        )

        assertEquals(
            "$01012020,000000,-33.868800000,151.209300000,42,36,180,7,3.25,gps,123456789",
            line,
        )
    }

    @Test
    fun sensorRecordKeepsLegacyFieldsThenAppendsExtensions() {
        val line = formatter.formatSensor(
            SensorSample(
                index = 5,
                elapsedSeconds = 1.23456,
                eventCode = 0,
                snapshot = SensorSnapshot(
                    orientation = floatArrayOf(1f, 2f, 3f),
                    magnetic = floatArrayOf(4f, 5f, 6f),
                ),
                accel = floatArrayOf(7f, 8f, 9f),
                sensorTimestampNanos = 987654321L,
                sensorAccuracy = 3,
            ),
        )

        assertEquals("#5,1.235,0,1.000,2.000,3.000,7.000,8.000,9.000,987654321,3,0.000,0.000,0.000,0.000,0.000,0.000", line)
    }

    @Test
    fun enhancedGpsRecordUsesSeparatePrefixAndSourceMetadata() {
        val line = formatter.formatEnhancedGps(
            GpsTracePoint(
                date = "01012020",
                time = "000000",
                latitude = -33.86881,
                longitude = 151.20931,
                altitudeMeters = 42.4,
                speedKmh = 36.0,
                bearingDegrees = 180.0,
                satelliteCount = 7,
                accuracyMeters = 3.25,
                provider = "gps",
                elapsedRealtimeNanos = 123456999L,
                source = TracePointSource.ImuHeading,
                confidence = 0.82,
                derivedFromRawIndex = 3,
            ),
            presetId = "imu_heading_10hz",
        )

        assertEquals(
            "@01012020,000000,-33.868810000,151.209310000,42,36,180,7,3.25,gps,123456999,ImuHeading,0.820,imu_heading_10hz,3",
            line,
        )
    }

    @Test
    fun headerDeclaresFormatVersionAndExtendedColumns() {
        val header = formatter.header()

        assertTrue(header.contains("%% FormatVersion: 3"))
        assertTrue(header.contains("AccuracyMeters,Provider,ElapsedRealtimeNanos"))
        assertTrue(header.contains("@UTCDate,UTCTime,Latitude,Longitude,Altitude,Speed,Bearing,NumSat,AccuracyMeters,Provider,ElapsedRealtimeNanos,Source,Confidence,ImuPresetId"))
        assertTrue(header.contains("SensorTimestampNanos,SensorAccuracy"))
        assertTrue(header.contains("GyroX,GyroY,GyroZ,RotAzimuth,RotPitch,RotRoll"))
    }
}
