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
    private val syncClient: LiveSyncClient = LiveHybridSyncClient(),
    private val vtaUploadClient: LiveVtaUploadClient = LiveHttpSyncClient(),
    private val commandClient: LiveCommandClient = LiveCommandClient(),
    private val commandActionHandler: LiveCommandActionHandler = NoopLiveCommandActionHandler,
    private val executor: Executor = defaultExecutor(),
) : LiveCommandExecutor {
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

    fun flushPending(): Int {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return 0
        refreshCommandConnection(settings)
        var acked = 0
        for (entry in outboxRepository.listPending()) {
            outboxRepository.markSent(entry.id)
            val delivered = runCatching { syncClient.send(settings, entry) }.getOrDefault(false)
            if (!delivered) {
                outboxRepository.markFailed(entry.id)
                break
            }
            outboxRepository.markAcked(entry.id)
            acked += 1
        }
        return acked
    }

    fun refreshCommandConnection() {
        val settings = loadSettings()
        if (LiveProtocol.isConfigured(settings)) refreshCommandConnection(settings)
    }

    fun pendingCount(): Int = outboxRepository.listPending().size

    override fun execute(command: LiveDeviceCommand): LiveCommandResult =
        when (command.type) {
            "recording.start" -> commandActionHandler.startRecording()
            "recording.stop" -> commandActionHandler.stopRecording()
            "device.request-sync",
            "device.request-missing-range-upload" -> LiveCommandResult.succeeded(mapOf("flushed" to flushPending(), "recordingId" to command.recordingId))
            "device.ping" -> LiveCommandResult.succeeded(mapOf("at" to System.currentTimeMillis()))
            else -> LiveCommandResult.failed(mapOf("error" to "unsupported command type", "type" to command.type))
        }

    private fun refreshCommandConnection(settings: AppSettings) {
        runCatching { commandClient.ensureConnected(settings, this) }
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

    private fun flushAsync(uploadSession: RecordingSession? = null) {
        executor.execute {
            runCatching { flushPending() }
            if (uploadSession != null) {
                runCatching {
                    val settings = loadSettings()
                    if (LiveProtocol.isConfigured(settings)) {
                        vtaUploadClient.uploadVta(settings, uploadSession)
                    }
                }
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
