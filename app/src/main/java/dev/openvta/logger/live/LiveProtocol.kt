package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.RecordingSession
import org.json.JSONArray
import org.json.JSONObject
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

    fun chunkMetadataEnvelope(
        settings: AppSettings,
        session: RecordingSession,
        chunk: LiveVtaChunkMetadata,
        sentAtMillis: Long = System.currentTimeMillis(),
    ): LiveEnvelope {
        val uploadPath = "/api/devices/${settings.liveDeviceId}/recordings/${session.id}/chunks/${chunk.chunkId}"
        val payload = JSONObject()
            .put("recordingId", session.id)
            .put("chunkId", chunk.chunkId)
            .put("seqStart", chunk.seqStart)
            .put("seqEnd", chunk.seqEnd)
            .put("sha256", chunk.sha256)
            .put("sizeBytes", chunk.sizeBytes)
            .put("compression", "none")
            .put("upload", JSONObject().put("method", "http").put("path", uploadPath))
        return envelope(
            settings = settings,
            session = session,
            stream = "chunk-meta",
            seqStart = chunk.seqStart,
            seqEnd = chunk.seqEnd,
            payload = payload,
            sentAtMillis = sentAtMillis,
        )
    }

    fun manifestEnvelope(
        settings: AppSettings,
        session: RecordingSession,
        chunks: List<LiveVtaChunkMetadata>,
        finalVtaSha256: String,
        status: String = "completed",
        sentAtMillis: Long = System.currentTimeMillis(),
    ): LiveEnvelope {
        val seqStart = chunks.minOfOrNull { it.seqStart } ?: 0L
        val seqEnd = chunks.maxOfOrNull { it.seqEnd } ?: 0L
        val segments = JSONArray()
        chunks.forEach { chunk ->
            segments.put(
                JSONObject()
                    .put("chunkId", chunk.chunkId)
                    .put("seqStart", chunk.seqStart)
                    .put("seqEnd", chunk.seqEnd)
                    .put("sha256", chunk.sha256)
                    .put("sizeBytes", chunk.sizeBytes),
            )
        }
        val payload = JSONObject()
            .put("recordingId", session.id)
            .put("status", status)
            .put("sampleCount", seqEnd)
            .put("segments", segments)
            .put("finalVtaSha256", finalVtaSha256)
        return envelope(
            settings = settings,
            session = session,
            stream = "manifest",
            seqStart = seqStart,
            seqEnd = seqEnd,
            payload = payload,
            sentAtMillis = sentAtMillis,
        )
    }

    private fun envelope(
        settings: AppSettings,
        session: RecordingSession,
        stream: String,
        seqStart: Long,
        seqEnd: Long,
        payload: JSONObject,
        sentAtMillis: Long,
    ): LiveEnvelope {
        val payloadJson = payload.toString()
        val payloadHash = sha256(payloadJson)
        val sentAt = Instant.ofEpochMilli(sentAtMillis).toString()
        val envelope = JSONObject()
            .put("v", 1)
            .put("msgId", "android-${session.id}-$stream-$seqStart-$seqEnd")
            .put("tenantId", settings.liveTenantId)
            .put("deviceId", settings.liveDeviceId)
            .put("recordingId", session.id)
            .put("stream", stream)
            .put("seqStart", seqStart)
            .put("seqEnd", seqEnd)
            .put("sentAt", sentAt)
            .put("payloadHash", payloadHash)
            .put("payload", payload)
        return LiveEnvelope(session.id, seqStart, seqEnd, payloadHash, envelope.toString())
    }

    fun sha256(payload: String): String {
        return sha256(payload.toByteArray(Charsets.UTF_8))
    }

    fun sha256(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun parseServerAck(raw: String): LiveServerAck? {
        val body = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (body.optString("type") != "server.ack") return null
        val deviceId = body.optString("deviceId").takeIf { it.isNotBlank() } ?: return null
        val recordingId = body.optString("recordingId").takeIf { it.isNotBlank() } ?: return null
        return LiveServerAck(
            deviceId = deviceId,
            recordingId = recordingId,
            ackedRanges = parseRanges(body.optJSONArray("ackedRanges")),
            missingRanges = parseRanges(body.optJSONArray("missingRanges")),
            acceptedPayloads = parseAcceptedPayloads(body.optJSONArray("acceptedPayloads")),
            serverReceivedAt = body.optString("serverReceivedAt").takeIf { it.isNotBlank() },
        )
    }

    fun parseMissingRanges(payload: JSONObject?): List<LiveSequenceRange> =
        parseRanges(payload?.optJSONArray("missingRanges"))

    private fun parseRanges(array: JSONArray?): List<LiveSequenceRange> {
        if (array == null) return emptyList()
        val ranges = mutableListOf<LiveSequenceRange>()
        for (index in 0 until array.length()) {
            val item = array.optJSONArray(index) ?: continue
            if (item.length() < 2) continue
            val start = item.optLong(0, -1)
            val end = item.optLong(1, -1)
            if (start >= 0 && end >= start) ranges += LiveSequenceRange(start, end)
        }
        return ranges
    }

    private fun parseAcceptedPayloads(array: JSONArray?): List<LiveAcknowledgedPayload> {
        if (array == null) return emptyList()
        val payloads = mutableListOf<LiveAcknowledgedPayload>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val start = item.optLong("seqStart", -1)
            val end = item.optLong("seqEnd", -1)
            val hash = item.optString("payloadHash").takeIf { it.isNotBlank() } ?: continue
            if (start >= 0 && end >= start) payloads += LiveAcknowledgedPayload(LiveSequenceRange(start, end), hash)
        }
        return payloads
    }
}

data class LiveEnvelope(
    val recordingId: String,
    val seqStart: Long,
    val seqEnd: Long,
    val payloadHash: String,
    val payloadJson: String,
)

data class LiveVtaChunkMetadata(
    val chunkId: String,
    val seqStart: Long,
    val seqEnd: Long,
    val sha256: String,
    val sizeBytes: Long,
)

data class LiveSequenceRange(
    val start: Long,
    val end: Long,
) {
    init {
        require(start >= 0) { "sequence range start must be non-negative" }
        require(end >= start) { "sequence range end must be greater than or equal to start" }
    }

    fun contains(other: LiveSequenceRange): Boolean = start <= other.start && end >= other.end

    fun overlaps(other: LiveSequenceRange): Boolean = start <= other.end && other.start <= end
}

data class LiveAcknowledgedPayload(
    val range: LiveSequenceRange,
    val payloadHash: String,
)

data class LiveServerAck(
    val deviceId: String,
    val recordingId: String,
    val ackedRanges: List<LiveSequenceRange>,
    val missingRanges: List<LiveSequenceRange> = emptyList(),
    val acceptedPayloads: List<LiveAcknowledgedPayload> = emptyList(),
    val serverReceivedAt: String? = null,
)
