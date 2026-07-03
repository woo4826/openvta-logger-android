package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors

class LiveHttpSyncClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val session = RecordingSession(
        id = "recording_01",
        driverId = "CC00",
        startedAtMillis = 1_577_836_800_000L,
        vtaFile = File("recording_01.Vta"),
        zipFile = File("recording_01.Zip"),
    )

    @Test
    fun sendsTelemetryAndStatusWithDeviceBearerCredential() {
        val server = MiniHttpServer(
            responseBodies = listOf(
                """{"serverAck":{"v":1,"type":"server.ack","deviceId":"device_01","recordingId":"recording_01","ackedRanges":[[1,1]],"missingRanges":[],"acceptedPayloads":[{"seqStart":1,"seqEnd":1,"payloadHash":"sha256:server"}],"serverReceivedAt":"2026-07-01T01:00:02.000Z"}}""",
                "{}",
            ),
        ).also { it.start() }

        try {
            val settings = AppSettings(
                liveEnabled = true,
                liveTenantId = "tenant_01",
                liveDeviceId = "device_01",
                liveBaseUrl = "http://127.0.0.1:${server.port}",
                liveApiCredential = "api_secret",
            )
            val telemetry = LiveProtocol.telemetryEnvelope(
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
                    accuracyMeters = null,
                    provider = "gps",
                    elapsedRealtimeNanos = 123456789L,
                ),
                seq = 1,
                sentAtMillis = 1_577_836_801_000L,
            )
            val status = LiveProtocol.statusEnvelope(settings, session, "completed", 1_577_836_802_000L)
            val client = LiveHttpSyncClient()

            val telemetryResult = client.send(settings, telemetry.toEntry("telemetry"))
            val statusResult = client.send(settings, status.toEntry("status"))
            assertTrue(telemetryResult.delivered)
            assertEquals(
                listOf(LiveAcknowledgedPayload(LiveSequenceRange(1, 1), "sha256:server")),
                telemetryResult.serverAck?.acceptedPayloads,
            )
            assertTrue(statusResult.delivered)

            val requests = server.requests
            assertEquals(2, requests.size)
            assertEquals("/api/devices/device_01/telemetry", requests[0].path)
            assertEquals("Bearer api_secret", requests[0].authorization)
            assertTrue(requests[0].body.contains("\"recordingId\":\"recording_01\""))
            assertTrue(requests[0].body.contains("\"point\""))
            assertFalse(requests[0].body.contains("\"accuracy\":null"))
            assertEquals("/api/devices/device_01/status", requests[1].path)
            assertTrue(requests[1].body.contains("\"status\":\"completed\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun uploadsVtaFileAsAuthenticatedChunk() {
        val server = MiniHttpServer(expectedRequests = 1).also { it.start() }
        try {
            val vtaFile = temporaryFolder.newFile("recording_01.Vta").apply {
                writeText("VTA\nGPS\n")
            }
            val settings = AppSettings(
                liveEnabled = true,
                liveTenantId = "tenant_01",
                liveDeviceId = "device_01",
                liveBaseUrl = "http://127.0.0.1:${server.port}",
                liveApiCredential = "api_secret",
            )

            assertTrue(LiveHttpSyncClient().uploadVta(settings, session.copy(vtaFile = vtaFile)))

            assertEquals(1, server.requests.size)
            val request = server.requests.first()
            assertEquals("/api/devices/device_01/recordings/recording_01/chunks/chunk-000001", request.path)
            assertEquals("Bearer api_secret", request.authorization)
            assertTrue(request.body.contains("\"contentBase64\""))
            assertTrue(request.body.contains("\"sizeBytes\":8"))
            assertTrue(request.body.contains(LiveProtocol.sha256(vtaFile.readBytes())))
        } finally {
            server.close()
        }
    }

    @Test
    fun acceptsVtaMetadataOutboxEntriesWithoutHttpPost() {
        val settings = AppSettings(
            liveEnabled = true,
            liveTenantId = "tenant_01",
            liveDeviceId = "device_01",
            liveBaseUrl = "http://127.0.0.1:9",
            liveApiCredential = "api_secret",
        )
        val chunk = LiveVtaChunkMetadata(
            chunkId = "chunk-000001",
            seqStart = 0,
            seqEnd = 7,
            sha256 = "sha256:abc",
            sizeBytes = 8,
        )
        val chunkMeta = LiveProtocol.chunkMetadataEnvelope(settings, session, chunk)
        val manifest = LiveProtocol.manifestEnvelope(settings, session, listOf(chunk), "sha256:def")
        val client = LiveHttpSyncClient()

        assertTrue(client.send(settings, chunkMeta.toEntry("chunk-meta")).delivered)
        assertTrue(client.send(settings, manifest.toEntry("manifest")).delivered)
    }

    private fun LiveEnvelope.toEntry(kind: String): LiveOutboxEntry = LiveOutboxEntry(
        id = "entry_$kind",
        kind = kind,
        recordingId = recordingId,
        seqStart = seqStart,
        seqEnd = seqEnd,
        payloadHash = payloadHash,
        payloadJson = payloadJson,
        status = LiveOutboxStatus.Pending,
        createdAtMillis = 1L,
    )

    private class MiniHttpServer(
        private val expectedRequests: Int,
        private val responseBodies: List<String> = List(expectedRequests) { "{}" },
    ) : Closeable {
        constructor(responseBodies: List<String>) : this(responseBodies.size, responseBodies)

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        val port: Int = socket.localPort

        fun start() {
            executor.execute {
                repeat(expectedRequests) { index ->
                    runCatching { handleOne(responseBodies.getOrElse(index) { "{}" }) }
                }
            }
        }

        private fun handleOne(responseBody: String) {
            socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                val requestLine = reader.readLine()
                val path = requestLine.split(" ").getOrElse(1) { "" }
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).lowercase(Locale.US)] = line.substring(separator + 1).trim()
                    }
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                requests.add(
                    CapturedRequest(
                        path = path,
                        authorization = headers["authorization"].orEmpty(),
                        body = String(body, 0, read),
                    ),
                )
                val response = responseBody.toByteArray(Charsets.UTF_8)
                client.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8),
                )
                client.getOutputStream().write(response)
            }
        }

        override fun close() {
            runCatching { socket.close() }
            executor.shutdownNow()
        }
    }
}

private data class CapturedRequest(
    val path: String,
    val authorization: String,
    val body: String,
)
