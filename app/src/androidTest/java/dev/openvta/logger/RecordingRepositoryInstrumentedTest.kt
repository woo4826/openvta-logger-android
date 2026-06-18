package dev.openvta.logger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openvta.logger.data.RecordingRepository
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.SensorSample
import dev.openvta.logger.domain.SensorSnapshot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingRepositoryInstrumentedTest {
    @Test
    fun repositoryCreatesVtaAndZipInAppPrivateStorage() {
        val context = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        val repository = RecordingRepository(context)
        val session = repository.createSession("Test Driver", 1_577_836_800_000L)

        repository.appendGps(
            session,
            GpsSample(
                timeMillis = 1_577_836_800_000L,
                latitude = -33.8688,
                longitude = 151.2093,
                altitudeMeters = 42.0,
                speedMetersPerSecond = 1f,
                bearingDegrees = 10f,
                satelliteCount = 5,
                accuracyMeters = 4f,
                provider = "gps",
                elapsedRealtimeNanos = 1L,
            ),
        )
        repository.appendSensor(
            session,
            SensorSample(
                index = 1,
                elapsedSeconds = 0.1,
                snapshot = SensorSnapshot(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f)),
                accel = floatArrayOf(7f, 8f, 9f),
                sensorTimestampNanos = 2L,
                sensorAccuracy = 3,
            ),
        )
        val closed = repository.closeSession(session)
        val zip = repository.zipSession(closed)

        assertTrue(closed.vtaFile.absolutePath.contains("/files/vta/sessions/"))
        assertTrue(closed.vtaFile.readText().contains("%% End"))
        assertTrue(zip.isFile)
    }
}
