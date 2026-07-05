package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.RecordingSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class LiveUpstreamManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun refreshCommandConnectionClosesClientWhenLiveIsDisabled() {
        var settings = liveSettings().copy(liveWssCredential = "wss_secret")
        val commandClient = RecordingCommandClient()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = LiveOutboxRepository(temporaryFolder.root),
            commandClient = commandClient,
            executor = Executor { it.run() },
        )

        manager.refreshCommandConnection()
        settings = settings.copy(liveEnabled = false)
        manager.refreshCommandConnection()

        assertEquals(1, commandClient.ensureConnectedCalls)
        assertEquals(1, commandClient.closeCalls)
    }

    @Test
    fun flushPendingAcksDeliveredEntries() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = AppSettings(
            liveEnabled = true,
            liveBaseUrl = "https://openvta-live.kro.kr",
            liveTenantId = "tenant_01",
            liveDeviceId = "device_01",
            liveApiCredential = "api_secret",
        )
        val deliveredIds = mutableListOf<String>()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    deliveredIds += entry.id
                    return LiveSyncResult.delivered()
                }
            },
            executor = Executor { it.run() },
        )

        repository.enqueue(
            kind = "status",
            recordingId = "recording_01",
            seqStart = 0,
            seqEnd = 0,
            payloadHash = "sha256:abc",
            payloadJson = """{"stream":"status","recordingId":"recording_01","payload":{"status":"completed","recordingId":"recording_01"}}""",
        )

        assertEquals(1, manager.flushPending())
        assertEquals(1, deliveredIds.size)
        assertEquals(0, manager.pendingCount())
    }

    @Test
    fun flushPendingKeepsRangedEntriesUntilServerAck() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult =
                    LiveSyncResult.delivered()
            },
            executor = Executor { it.run() },
        )

        repository.enqueue("telemetry", "recording_01", 1, 1, "sha256:abc", """{"stream":"telemetry"}""")

        assertEquals(0, manager.flushPending())
        assertEquals(1, manager.pendingCount())
        assertEquals(LiveOutboxStatus.Sent, repository.listPending().single().status)
    }

    @Test
    fun flushPendingAppliesServerAckForMatchingRangeAndHash() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult =
                    LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
            },
            executor = Executor { it.run() },
        )

        repository.enqueue("telemetry", "recording_01", 1, 1, "sha256:abc", """{"stream":"telemetry"}""")

        assertEquals(1, manager.flushPending())
        assertEquals(0, manager.pendingCount())
    }

    @Test
    fun flushPendingDoesNotLetOldFailedEntryBlockFreshTelemetry() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings()
        val sentIds = mutableListOf<String>()
        val oldFailed = repository.enqueue("telemetry", "old_recording", 1, 1, "sha256:old", """{"stream":"telemetry"}""")
        repository.markFailed(oldFailed.id)
        val fresh = repository.enqueue("telemetry", "fresh_recording", 1, 1, "sha256:fresh", """{"stream":"telemetry"}""")
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    sentIds += entry.id
                    if (entry.id == oldFailed.id) return LiveSyncResult.failed()
                    return LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
                }
            },
            executor = Executor { it.run() },
        )

        assertEquals(1, manager.flushPending())

        assertEquals(listOf(fresh.id, oldFailed.id), sentIds)
        val remaining = repository.listPending()
        assertEquals(1, remaining.size)
        assertEquals(oldFailed.id, remaining.single().id)
        assertEquals(LiveOutboxStatus.Failed, remaining.single().status)
    }

    @Test
    fun missingRangeCommandRequeuesOnlyRequestedRangeBeforeFlushing() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings()
        val sentSeqs = mutableListOf<Long>()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    sentSeqs += entry.seqStart
                    return LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
                }
            },
            executor = Executor { it.run() },
        )
        val first = repository.enqueue("telemetry", "recording_01", 1, 1, "sha256:first", """{"seq":1}""")
        val second = repository.enqueue("telemetry", "recording_01", 2, 2, "sha256:second", """{"seq":2}""")
        repository.markAcked(first.id)
        repository.markAcked(second.id)

        val result = manager.execute(
            LiveDeviceCommand(
                id = "id_01",
                commandId = "cmd_01",
                type = "device.request-missing-range-upload",
                recordingId = "recording_01",
                payload = JSONObject("""{"missingRanges":[[2,2]]}"""),
            ),
        )

        assertEquals("succeeded", result.status)
        assertEquals(1, result.result["requeued"])
        assertEquals(1, result.result["flushed"])
        assertEquals(listOf(2L), sentSeqs)
        assertEquals(0, manager.pendingCount())
    }

    @Test
    fun recordingStopPublishesStatusChunkMetadataAndManifest() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = AppSettings(
            liveEnabled = true,
            liveBaseUrl = "https://openvta-live.kro.kr",
            liveTenantId = "tenant_01",
            liveDeviceId = "device_01",
            liveMqttCredential = "mqtt_secret",
            liveApiCredential = "api_secret",
        )
        val deliveredKinds = mutableListOf<String>()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    deliveredKinds += entry.kind
                    return LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
                }
            },
            vtaUploadClient = object : LiveVtaUploadClient {
                override fun uploadVta(settings: AppSettings, session: RecordingSession): Boolean = true
            },
            commandClient = LiveCommandClient(),
            executor = Executor { it.run() },
        )
        val vtaFile = temporaryFolder.newFile("recording_01.Vta").apply { writeText("VTA\nGPS\n") }
        val session = RecordingSession(
            id = "recording_01",
            driverId = "CC00",
            startedAtMillis = 1L,
            vtaFile = vtaFile,
            zipFile = File(temporaryFolder.root, "recording_01.Zip"),
        )

        manager.onRecordingStopped(session)

        assertEquals(listOf("status", "chunk-meta", "manifest"), deliveredKinds)
        assertEquals(0, manager.pendingCount())
    }

    @Test
    fun recordingStopKeepsVtaMetadataPendingWhenVtaUploadFails() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings().copy(liveMqttCredential = "mqtt_secret")
        val deliveredKinds = mutableListOf<String>()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    deliveredKinds += entry.kind
                    return LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
                }
            },
            vtaUploadClient = object : LiveVtaUploadClient {
                override fun uploadVta(settings: AppSettings, session: RecordingSession): Boolean = false
            },
            executor = Executor { it.run() },
        )
        val vtaFile = temporaryFolder.newFile("recording_02.Vta").apply { writeText("VTA\nGPS\n") }
        val session = RecordingSession(
            id = "recording_02",
            driverId = "CC00",
            startedAtMillis = 1L,
            vtaFile = vtaFile,
            zipFile = File(temporaryFolder.root, "recording_02.Zip"),
        )

        manager.onRecordingStopped(session)

        assertEquals(listOf("status"), deliveredKinds)
        val pending = repository.listPending()
        assertEquals(listOf("chunk-meta", "manifest"), pending.map { it.kind })
        assertEquals(listOf(LiveOutboxStatus.Pending, LiveOutboxStatus.Pending), pending.map { it.status })
    }

    @Test
    fun genericRetryDoesNotAckVtaMetadataBeforeVtaUploadSucceeds() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings().copy(liveMqttCredential = "mqtt_secret")
        val deliveredKinds = mutableListOf<String>()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    deliveredKinds += entry.kind
                    return LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
                }
            },
            vtaUploadClient = object : LiveVtaUploadClient {
                override fun uploadVta(settings: AppSettings, session: RecordingSession): Boolean = false
            },
            executor = Executor { it.run() },
        )
        val vtaFile = temporaryFolder.newFile("recording_03.Vta").apply { writeText("VTA\nGPS\n") }
        val session = RecordingSession(
            id = "recording_03",
            driverId = "CC00",
            startedAtMillis = 1L,
            vtaFile = vtaFile,
            zipFile = File(temporaryFolder.root, "recording_03.Zip"),
        )

        manager.onRecordingStopped(session)
        manager.flushPending()

        assertEquals(listOf("status"), deliveredKinds)
        val pending = repository.listPending()
        assertEquals(listOf("chunk-meta", "manifest"), pending.map { it.kind })
        assertEquals(listOf(LiveOutboxStatus.Pending, LiveOutboxStatus.Pending), pending.map { it.status })
    }

    @Test
    fun retryPendingUploadsVtaBeforeAckingPendingVtaMetadata() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = liveSettings().copy(liveMqttCredential = "mqtt_secret")
        val deliveredKinds = mutableListOf<String>()
        val uploadedRecordings = mutableListOf<String>()
        val vtaFile = temporaryFolder.newFile("recording_04.Vta").apply { writeText("VTA\nGPS\n") }
        val session = RecordingSession(
            id = "recording_04",
            driverId = "CC00",
            startedAtMillis = 1L,
            vtaFile = vtaFile,
            zipFile = File(temporaryFolder.root, "recording_04.Zip"),
        )
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
                    deliveredKinds += entry.kind
                    return LiveSyncResult.delivered(
                        LiveServerAck(
                            deviceId = settings.liveDeviceId,
                            recordingId = entry.recordingId,
                            ackedRanges = listOf(LiveSequenceRange(entry.seqStart, entry.seqEnd)),
                            acceptedPayloads = listOf(LiveAcknowledgedPayload(LiveSequenceRange(entry.seqStart, entry.seqEnd), entry.payloadHash)),
                        ),
                    )
                }
            },
            vtaUploadClient = object : LiveVtaUploadClient {
                private var first = true

                override fun uploadVta(settings: AppSettings, session: RecordingSession): Boolean {
                    uploadedRecordings += session.id
                    if (first) {
                        first = false
                        return false
                    }
                    return true
                }
            },
            resolveRecordingSession = { recordingId -> if (recordingId == session.id) session else null },
            executor = Executor { it.run() },
        )

        manager.onRecordingStopped(session)
        manager.retryPending()

        assertEquals(listOf("recording_04", "recording_04"), uploadedRecordings)
        assertEquals(listOf("status", "chunk-meta", "manifest"), deliveredKinds)
        assertEquals(0, manager.pendingCount())
    }

    private fun liveSettings(): AppSettings = AppSettings(
        liveEnabled = true,
        liveBaseUrl = "https://openvta-live.kro.kr",
        liveTenantId = "tenant_01",
        liveDeviceId = "device_01",
        liveApiCredential = "api_secret",
    )

    private class RecordingCommandClient : LiveCommandClient() {
        var ensureConnectedCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun ensureConnected(settings: AppSettings, executor: LiveCommandExecutor) {
            ensureConnectedCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
