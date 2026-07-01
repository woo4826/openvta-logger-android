package dev.openvta.logger.live

import dev.openvta.logger.data.SecureSettingsRepository
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession

class LiveUpstreamManager(
    private val settingsRepository: SecureSettingsRepository,
    private val outboxRepository: LiveOutboxRepository,
) {
    fun onRecordingStarted(session: RecordingSession) {
        val settings = settingsRepository.load()
        if (!LiveProtocol.isConfigured(settings)) return
        val envelope = LiveProtocol.statusEnvelope(settings, session, "recording")
        outboxRepository.enqueue("status", session.id, 0, 0, envelope.payloadHash, envelope.payloadJson)
    }

    fun enqueueGps(session: RecordingSession, sample: GpsSample, seq: Long) {
        val settings = settingsRepository.load()
        if (!LiveProtocol.isConfigured(settings)) return
        val envelope = LiveProtocol.telemetryEnvelope(settings, session, sample, seq)
        outboxRepository.enqueue("telemetry", session.id, envelope.seqStart, envelope.seqEnd, envelope.payloadHash, envelope.payloadJson)
    }

    fun onRecordingStopped(session: RecordingSession) {
        val settings = settingsRepository.load()
        if (!LiveProtocol.isConfigured(settings)) return
        val envelope = LiveProtocol.statusEnvelope(settings, session, "completed")
        outboxRepository.enqueue("status", session.id, 0, 0, envelope.payloadHash, envelope.payloadJson)
    }

    fun pendingCount(): Int = outboxRepository.listPending().size
}
