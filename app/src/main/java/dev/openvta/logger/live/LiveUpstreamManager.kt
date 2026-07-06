package dev.openvta.logger.live

import dev.openvta.logger.data.SecureSettingsRepository
import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class LiveUpstreamManager(
    private val loadSettings: () -> AppSettings,
    private val outboxRepository: LiveOutboxStore,
    syncClient: LiveSyncClient? = null,
    mqttPublisher: LiveMqttPublisher = PahoLiveMqttPublisher(),
    private val vtaUploadClient: LiveVtaUploadClient = LiveHttpSyncClient(),
    private val commandClient: LiveCommandClient = LiveCommandClient(),
    private val commandActionHandler: LiveCommandActionHandler = NoopLiveCommandActionHandler,
    private val resolveRecordingSession: (String) -> RecordingSession? = { null },
    private val onTransferStatus: (String) -> Unit = {},
    private val executor: Executor = defaultExecutor(),
) : LiveCommandExecutor {
    private val syncClient: LiveSyncClient = syncClient ?: LiveHybridSyncClient(
        onMqttServerAck = ::handleMqttServerAck,
        publisher = mqttPublisher,
    )

    constructor(
        settingsRepository: SecureSettingsRepository,
        outboxRepository: LiveOutboxStore,
        commandActionHandler: LiveCommandActionHandler = NoopLiveCommandActionHandler,
    ) : this(settingsRepository::load, outboxRepository, commandActionHandler = commandActionHandler)

    fun onRecordingStarted(session: RecordingSession) {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return
        refreshCommandConnection(settings)
        val envelope = LiveProtocol.statusEnvelope(settings, session, "recording")
        outboxRepository.enqueue("status", session.id, 0, 0, envelope.payloadHash, envelope.payloadJson)
        flushAsync()
    }

    fun enqueueGps(session: RecordingSession, sample: GpsSample, seq: Long) {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return
        refreshCommandConnection(settings)
        val envelope = LiveProtocol.telemetryEnvelope(settings, session, sample, seq)
        outboxRepository.enqueue("telemetry", session.id, envelope.seqStart, envelope.seqEnd, envelope.payloadHash, envelope.payloadJson)
        flushAsync()
    }

    fun onRecordingStopped(session: RecordingSession) {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return
        refreshCommandConnection(settings)
        val envelope = LiveProtocol.statusEnvelope(settings, session, "completed")
        outboxRepository.enqueue("status", session.id, 0, 0, envelope.payloadHash, envelope.payloadJson)
        runCatching { enqueueVtaMetadata(settings, session) }
        flushAsync(uploadSession = session)
    }

    fun flushPending(): Int = flushPending(allowVtaMetadataForRecordingId = null)

    private fun flushPending(allowVtaMetadataForRecordingId: String?): Int {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return 0
        refreshCommandConnection(settings)
        var acked = 0
        for (entry in outboxRepository.listPending()) {
            if (entry.status == LiveOutboxStatus.Sent) continue
            if (entry.isVtaMetadata() && entry.recordingId != allowVtaMetadataForRecordingId) continue
            outboxRepository.markSent(entry.id)
            val result = runCatching { syncClient.send(settings, entry) }.getOrDefault(LiveSyncResult.failed())
            if (!result.delivered) {
                outboxRepository.markFailed(entry.id)
                break
            }
            val serverAcked = result.serverAck?.let(outboxRepository::applyServerAck) ?: 0
            if (serverAcked > 0) {
                acked += serverAcked
            } else if (!entry.requiresServerAck()) {
                outboxRepository.markAcked(entry.id)
                acked += 1
            }
        }
        if (acked > 0) {
            val label = if (acked == 1) "payload" else "payloads"
            onTransferStatus("Live sent $acked $label")
        } else {
            val summary = outboxRepository.summary()
            if (summary.activeCount > 0) {
                onTransferStatus(liveTransferBacklogMessage(summary))
            }
        }
        return acked
    }

    fun handleServerAck(raw: String): Int {
        val ack = LiveProtocol.parseServerAck(raw) ?: return 0
        val settings = loadSettings()
        if (settings.liveDeviceId.isNotBlank() && ack.deviceId != settings.liveDeviceId) return 0
        return outboxRepository.applyServerAck(ack)
    }

    fun refreshCommandConnection() {
        val settings = loadSettings()
        if (LiveProtocol.isConfigured(settings)) {
            refreshCommandConnection(settings)
        } else {
            commandClient.close()
        }
    }

    fun disconnectCommandConnection() {
        commandClient.close()
    }

    fun retryPending(retryAwaitingAck: Boolean = false) {
        executor.execute {
            if (retryAwaitingAck) {
                runCatching { outboxRepository.requeueAwaitingAck() }
            }
            runCatching { flushPending() }
            runCatching { retryPendingVtaUploads() }
        }
    }

    fun pendingCount(): Int = outboxSummary().activeCount

    fun outboxSummary(): LiveOutboxSummary = outboxRepository.summary()

    override fun execute(command: LiveDeviceCommand): LiveCommandResult =
        when (command.type) {
            "recording.start" -> commandActionHandler.startRecording()
            "recording.stop" -> commandActionHandler.stopRecording()
            "device.request-sync" -> LiveCommandResult.succeeded(mapOf("flushed" to flushPending(), "recordingId" to command.recordingId))
            "device.request-missing-range-upload" -> handleMissingRangeUploadCommand(command)
            "device.ping" -> LiveCommandResult.succeeded(mapOf("at" to System.currentTimeMillis()))
            else -> LiveCommandResult.failed(mapOf("error" to "unsupported command type", "type" to command.type))
        }

    private fun handleMissingRangeUploadCommand(command: LiveDeviceCommand): LiveCommandResult {
        val recordingId = command.recordingId
        val ranges = LiveProtocol.parseMissingRanges(command.payload)
        val requeued = if (recordingId != null && ranges.isNotEmpty()) {
            outboxRepository.requeueMissingRanges(recordingId, ranges)
        } else {
            0
        }
        return LiveCommandResult.succeeded(
            mapOf(
                "requeued" to requeued,
                "flushed" to flushPending(),
                "recordingId" to recordingId,
            ),
        )
    }

    private fun refreshCommandConnection(settings: AppSettings) {
        runCatching { commandClient.ensureConnected(settings, this) }
    }

    private fun handleMqttServerAck(raw: String) {
        val acked = handleServerAck(raw)
        if (acked <= 0) return
        val label = if (acked == 1) "payload" else "payloads"
        onTransferStatus("Live acknowledged $acked $label")
    }

    private fun enqueueVtaMetadata(settings: AppSettings, session: RecordingSession) {
        val file = session.vtaFile
        if (!file.isFile || file.length() <= 0L) return
        val chunks = mutableListOf<LiveVtaChunkMetadata>()
        var offset = 0L
        var chunkIndex = 1
        file.inputStream().use { input ->
            val buffer = ByteArray(VTA_CHUNK_SIZE_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val bytes = buffer.copyOf(read)
                val chunk = LiveVtaChunkMetadata(
                    chunkId = "chunk-" + chunkIndex.toString().padStart(6, '0'),
                    seqStart = offset,
                    seqEnd = offset + read - 1,
                    sha256 = LiveProtocol.sha256(bytes),
                    sizeBytes = read.toLong(),
                )
                chunks += chunk
                val chunkMeta = LiveProtocol.chunkMetadataEnvelope(settings, session, chunk)
                outboxRepository.enqueue("chunk-meta", session.id, chunk.seqStart, chunk.seqEnd, chunkMeta.payloadHash, chunkMeta.payloadJson)
                offset += read
                chunkIndex += 1
            }
        }
        if (chunks.isEmpty()) return
        val manifest = LiveProtocol.manifestEnvelope(settings, session, chunks, LiveProtocol.sha256(file.readBytes()))
        outboxRepository.enqueue("manifest", session.id, chunks.first().seqStart, chunks.last().seqEnd, manifest.payloadHash, manifest.payloadJson)
    }

    private fun retryPendingVtaUploads(): Int {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return 0
        var acked = 0
        val recordingIds = outboxRepository.listPending()
            .filter { it.isVtaMetadata() }
            .map { it.recordingId }
            .distinct()
        for (recordingId in recordingIds) {
            val session = resolveRecordingSession(recordingId) ?: continue
            val uploaded = runCatching { vtaUploadClient.uploadVta(settings, session) }.getOrDefault(false)
            if (uploaded) {
                acked += flushPending(allowVtaMetadataForRecordingId = recordingId)
            }
        }
        return acked
    }

    private fun flushAsync(uploadSession: RecordingSession? = null) {
        executor.execute {
            if (uploadSession == null) {
                runCatching { flushPending() }
                return@execute
            }
            runCatching { flushPending() }
            val uploaded = runCatching {
                val settings = loadSettings()
                LiveProtocol.isConfigured(settings) && vtaUploadClient.uploadVta(settings, uploadSession)
            }.getOrDefault(false)
            if (uploaded) {
                runCatching { flushPending(allowVtaMetadataForRecordingId = uploadSession.id) }
            }
        }
    }

    companion object {
        private const val VTA_CHUNK_SIZE_BYTES = 256 * 1024

        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenVTA Live Upstream").apply { isDaemon = true }
        }
    }
}

private fun LiveOutboxEntry.isVtaMetadata(): Boolean = kind == "chunk-meta" || kind == "manifest"

private fun liveTransferBacklogMessage(summary: LiveOutboxSummary): String =
    when {
        summary.hasFailures -> "Live failed ${summary.failed} payloads; retry required"
        summary.awaitingAckCount > 0 -> "Live awaiting server ack for ${summary.awaitingAckCount} payloads; retry Live if the web view stays stale"
        else -> "Live pending ${summary.pending} payloads"
    }
