package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMqttSyncClientTest {
    private val settings = AppSettings(
        liveEnabled = true,
        liveBaseUrl = "https://openvta-live.kro.kr",
        liveTenantId = "tenant_01",
        liveDeviceId = "device_01",
        liveMqttCredential = "mqtt_secret",
        liveApiCredential = "api_secret",
    )

    @Test
    fun publishesTelemetryToPerDeviceTopicWithMqttCredential() {
        val publisher = CapturingPublisher()
        val entry = LiveOutboxEntry(
            id = "entry_01",
            kind = "telemetry",
            recordingId = "recording_01",
            seqStart = 1,
            seqEnd = 1,
            payloadHash = "sha256:abc",
            payloadJson = """{"stream":"telemetry","recordingId":"recording_01","payload":{"points":[]}}""",
            status = LiveOutboxStatus.Pending,
            createdAtMillis = 1L,
        )

        assertTrue(LiveMqttSyncClient(publisher).send(settings, entry))

        assertEquals("ssl://openvta-live.kro.kr:8883", publisher.serverUri)
        assertEquals("device_01", publisher.username)
        assertEquals("mqtt_secret", publisher.password)
        assertEquals("vta/tenant_01/device_01/telemetry", publisher.topic)
        assertEquals(1, publisher.qos)
        assertEquals("vta/tenant_01/device_01/status", publisher.will?.topic)
    }

    @Test
    fun publishesChunkMetadataToRecordingTopic() {
        val publisher = CapturingPublisher()
        val entry = LiveOutboxEntry(
            id = "entry_02",
            kind = "chunk-meta",
            recordingId = "recording_01",
            seqStart = 0,
            seqEnd = 99,
            payloadHash = "sha256:def",
            payloadJson = """{"stream":"chunk-meta","recordingId":"recording_01","payload":{"chunkId":"chunk-000001"}}""",
            status = LiveOutboxStatus.Pending,
            createdAtMillis = 1L,
        )

        assertTrue(LiveMqttSyncClient(publisher).send(settings, entry))

        assertEquals("vta/tenant_01/device_01/recording/recording_01/chunk-meta", publisher.topic)
    }

    @Test
    fun hybridUsesHttpBeforeMqttFallback() {
        val calls = mutableListOf<String>()
        val entry = LiveOutboxEntry(
            id = "entry_03",
            kind = "telemetry",
            recordingId = "recording_01",
            seqStart = 1,
            seqEnd = 1,
            payloadHash = "sha256:abc",
            payloadJson = """{"stream":"telemetry","recordingId":"recording_01","payload":{"points":[]}}""",
            status = LiveOutboxStatus.Pending,
            createdAtMillis = 1L,
        )
        val client = LiveHybridSyncClient(
            mqttClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean {
                    calls += "mqtt"
                    error("mqtt unavailable")
                }
            },
            httpFallback = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean {
                    calls += "http"
                    return true
                }
            },
        )

        assertTrue(client.send(settings, entry))
        assertEquals(listOf("http"), calls)
    }

    private class CapturingPublisher : LiveMqttPublisher {
        var serverUri = ""
        var username = ""
        var password = ""
        var topic = ""
        var qos = -1
        var will: LiveMqttWill? = null

        override fun publish(
            serverUri: String,
            clientId: String,
            username: String,
            password: String,
            topic: String,
            payload: String,
            qos: Int,
            will: LiveMqttWill?,
        ): Boolean {
            this.serverUri = serverUri
            this.username = username
            this.password = password
            this.topic = topic
            this.qos = qos
            this.will = will
            return true
        }
    }
}
