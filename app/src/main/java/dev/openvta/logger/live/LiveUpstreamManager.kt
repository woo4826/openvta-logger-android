package dev.openvta.logger.live

import dev.openvta.logger.data.SecureSettingsRepository
import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class LiveUpstreamManager(
    private val loadSettings: () -> AppSettings,
    private val outboxRepository: LiveOutboxRepository,
    private val syncClient: LiveSyncClient = LiveHttpSyncClient(),
    private val vtaUploadClient: LiveVtaUploadClient = LiveHttpSyncClient(),
    private val executor: Executor = defaultExecutor(),
) {
    constructor(
        settingsRepository: SecureSettingsRepository,
        outboxRepository: LiveOutboxRepository,
    ) : this(settingsRepository::load, outboxRepository)

    fun onRecordingStarted(session: RecordingSession) {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return
        val envelope = LiveProtocol.statusEnvelope(settings, session, "recording")
        outboxRepository.enqueue("status", session.id, 0, 0, envelope.payloadHash, envelope.payloadJson)
        flushAsync()
    }

    fun enqueueGps(session: RecordingSession, sample: GpsSample, seq: Long) {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return
        val envelope = LiveProtocol.telemetryEnvelope(settings, session, sample, seq)
        outboxRepository.enqueue("telemetry", session.id, envelope.seqStart, envelope.seqEnd, envelope.payloadHash, envelope.payloadJson)
        flushAsync()
    }

    fun onRecordingStopped(session: RecordingSession) {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return
        val envelope = LiveProtocol.statusEnvelope(settings, session, "completed")
        outboxRepository.enqueue("status", session.id, 0, 0, envelope.payloadHash, envelope.payloadJson)
        flushAsync(uploadSession = session)
    }

    fun flushPending(): Int {
        val settings = loadSettings()
        if (!LiveProtocol.isConfigured(settings)) return 0
        var acked = 0
        for (entry in outboxRepository.listPending()) {
            val delivered = runCatching { syncClient.send(settings, entry) }.getOrDefault(false)
            if (!delivered) break
            outboxRepository.markAcked(entry.id)
            acked += 1
        }
        return acked
    }

    fun pendingCount(): Int = outboxRepository.listPending().size

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
        private fun defaultExecutor(): Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "OpenVTA Live Upstream").apply { isDaemon = true }
        }
    }
}
