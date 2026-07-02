package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveProtocolTest {
    private val settings = AppSettings(
        liveEnabled = true,
        liveTenantId = "tenant_01",
        liveDeviceId = "device_01",
        liveBaseUrl = "https://openvta-live.kro.kr",
        liveApiCredential = "api_secret",
    )
    private val session = RecordingSession(
        id = "recording_01",
        driverId = "CC00",
        startedAtMillis = 1_577_836_800_000L,
        vtaFile = File("recording_01.Vta"),
        zipFile = File("recording_01.Zip"),
    )

    @Test
    fun configuredRequiresExplicitOptInAndServerDeviceIdentity() {
        assertTrue(LiveProtocol.isConfigured(settings))
        assertEquals(false, LiveProtocol.isConfigured(settings.copy(liveEnabled = false)))
        assertEquals(false, LiveProtocol.isConfigured(settings.copy(liveDeviceId = "")))
        assertEquals(false, LiveProtocol.isConfigured(settings.copy(liveApiCredential = "")))
    }

    @Test
    fun telemetryEnvelopeContainsProtocolFieldsAndHash() {
        val envelope = LiveProtocol.telemetryEnvelope(
            settings = settings,
            session = session,
            sample = GpsSample(
                timeMillis = 1_577_836_800_000L,
                latitude = 37.5665,
                longitude = 126.9780,
                altitudeMeters = 42.0,
                speedMetersPerSecond = 10f,
                bearingDegrees = 180f,
                satelliteCount = 7,
                accuracyMeters = 3.25f,
                provider = "gps",
                elapsedRealtimeNanos = 123456789L,
            ),
            seq = 1,
            sentAtMillis = 1_577_836_801_000L,
        )

        assertEquals(1, envelope.seqStart)
        assertEquals(1, envelope.seqEnd)
        assertTrue(envelope.payloadHash.startsWith("sha256:"))
        assertTrue(envelope.payloadJson.contains("\"tenantId\":\"tenant_01\""))
        assertTrue(envelope.payloadJson.contains("\"deviceId\":\"device_01\""))
        assertTrue(envelope.payloadJson.contains("\"stream\":\"telemetry\""))
        assertTrue(envelope.payloadJson.contains("\"lat\":37.566500000"))
    }

    @Test
    fun chunkMetadataAndManifestUseRecordingStreams() {
        val chunk = LiveVtaChunkMetadata(
            chunkId = "chunk-000001",
            seqStart = 0,
            seqEnd = 7,
            sha256 = LiveProtocol.sha256("VTA\nGPS\n"),
            sizeBytes = 8,
        )

        val chunkMeta = LiveProtocol.chunkMetadataEnvelope(settings, session, chunk, sentAtMillis = 1_577_836_802_000L)
        val manifest = LiveProtocol.manifestEnvelope(
            settings = settings,
            session = session,
            chunks = listOf(chunk),
            finalVtaSha256 = chunk.sha256,
            sentAtMillis = 1_577_836_803_000L,
        )

        assertTrue(chunkMeta.payloadJson.contains("\"stream\":\"chunk-meta\""))
        assertTrue(chunkMeta.payloadJson.contains("\"chunkId\":\"chunk-000001\""))
        assertTrue(chunkMeta.payloadJson.contains("\"upload\""))
        assertTrue(manifest.payloadJson.contains("\"stream\":\"manifest\""))
        assertTrue(manifest.payloadJson.contains("\"finalVtaSha256\""))
    }
}
