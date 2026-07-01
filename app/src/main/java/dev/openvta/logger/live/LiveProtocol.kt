package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

object LiveProtocol {
    fun isConfigured(settings: AppSettings): Boolean =
        settings.liveEnabled &&
            settings.liveBaseUrl.isNotBlank() &&
            settings.liveTenantId.isNotBlank() &&
            settings.liveDeviceId.isNotBlank() &&
            settings.liveApiCredential.isNotBlank()

    fun telemetryEnvelope(
        settings: AppSettings,
        session: RecordingSession,
        sample: GpsSample,
        seq: Long,
        sentAtMillis: Long = System.currentTimeMillis(),
    ): LiveEnvelope {
        val recordedAt = Instant.ofEpochMilli(sample.timeMillis).toString()
        val accuracyJson = sample.accuracyMeters?.let { String.format(Locale.US, ""","accuracy":%.3f""", it) } ?: ""
        val pointJson = String.format(
            Locale.US,
            """{"seq":%d,"recordedAt":"%s","lat":%.9f,"lon":%.9f,"speed":%.3f,"heading":%.3f,"altitude":%.3f,"satelliteCount":%d%s,"source":"RawGps"}""",
            seq,
            recordedAt,
            sample.latitude,
            sample.longitude,
            sample.speedMetersPerSecond * 3.6,
            sample.bearingDegrees,
            sample.altitudeMeters,
            sample.satelliteCount,
            accuracyJson,
        )
        val payloadJson = """{"points":[$pointJson]}"""
        val payloadHash = sha256(payloadJson)
        val envelopeJson = """{"v":1,"msgId":"android-${session.id}-$seq","tenantId":"${settings.liveTenantId}","deviceId":"${settings.liveDeviceId}","recordingId":"${session.id}","stream":"telemetry","seqStart":$seq,"seqEnd":$seq,"recordedAtStart":"$recordedAt","recordedAtEnd":"$recordedAt","sentAt":"${Instant.ofEpochMilli(sentAtMillis)}","payloadHash":"$payloadHash","payload":$payloadJson}"""
        return LiveEnvelope(session.id, seq, seq, payloadHash, envelopeJson)
    }

    fun statusEnvelope(
        settings: AppSettings,
        session: RecordingSession,
        status: String,
        sentAtMillis: Long = System.currentTimeMillis(),
    ): LiveEnvelope {
        val payloadJson = """{"status":"$status","recordingId":"${session.id}","at":"${Instant.ofEpochMilli(sentAtMillis)}"}"""
        val payloadHash = sha256(payloadJson)
        val envelopeJson = """{"v":1,"msgId":"android-${session.id}-$status-$sentAtMillis","tenantId":"${settings.liveTenantId}","deviceId":"${settings.liveDeviceId}","recordingId":"${session.id}","stream":"status","sentAt":"${Instant.ofEpochMilli(sentAtMillis)}","payloadHash":"$payloadHash","payload":$payloadJson}"""
        return LiveEnvelope(session.id, 0, 0, payloadHash, envelopeJson)
    }

    fun sha256(payload: String): String {
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    fun sha256(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}

data class LiveEnvelope(
    val recordingId: String,
    val seqStart: Long,
    val seqEnd: Long,
    val payloadHash: String,
    val payloadJson: String,
)
