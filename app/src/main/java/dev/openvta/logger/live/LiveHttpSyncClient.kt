package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.RecordingSession
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

interface LiveSyncClient {
    fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean
}

interface LiveVtaUploadClient {
    fun uploadVta(settings: AppSettings, session: RecordingSession): Boolean
}

class LiveHttpSyncClient(
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
) : LiveSyncClient, LiveVtaUploadClient {
    override fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean {
        if (!LiveProtocol.isConfigured(settings)) return false
        val envelope = JSONObject(entry.payloadJson)
        return when (envelope.optString("stream", entry.kind)) {
            "telemetry" -> sendTelemetry(settings, envelope)
            "status" -> sendStatus(settings, envelope)
            else -> false
        }
    }

    override fun uploadVta(settings: AppSettings, session: RecordingSession): Boolean {
        if (!LiveProtocol.isConfigured(settings) || !session.vtaFile.isFile) return false
        var offset = 0L
        var chunkIndex = 1
        session.vtaFile.inputStream().use { input ->
            val buffer = ByteArray(chunkSizeBytes)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val chunk = buffer.copyOf(read)
                val chunkId = "chunk-" + chunkIndex.toString().padStart(6, '0')
                val body = JSONObject()
                    .put("seqStart", offset)
                    .put("seqEnd", offset + read - 1)
                    .put("sha256", LiveProtocol.sha256(chunk))
                    .put("sizeBytes", read)
                    .put("contentBase64", Base64.getEncoder().encodeToString(chunk))
                if (!postJson(settings, "recordings/${session.id}/chunks/$chunkId", body)) return false
                offset += read
                chunkIndex += 1
            }
        }
        return true
    }

    private fun sendTelemetry(settings: AppSettings, envelope: JSONObject): Boolean {
        val recordingId = envelope.getString("recordingId")
        val points = envelope.getJSONObject("payload").getJSONArray("points")
        for (index in 0 until points.length()) {
            val body = JSONObject()
                .put("recordingId", recordingId)
                .put("point", points.getJSONObject(index))
            if (!postJson(settings, "telemetry", body)) return false
        }
        return true
    }

    private fun sendStatus(settings: AppSettings, envelope: JSONObject): Boolean {
        val payload = envelope.getJSONObject("payload")
        val body = JSONObject()
            .put("status", payload.getString("status"))
            .put("recordingId", payload.optString("recordingId", envelope.optString("recordingId")))
        if (payload.has("at")) body.put("at", payload.getString("at"))
        return postJson(settings, "status", body)
    }

    private fun postJson(settings: AppSettings, path: String, body: JSONObject): Boolean {
        val url = URL(settings.liveBaseUrl.trimEnd('/') + "/api/devices/${settings.liveDeviceId}/$path")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("authorization", "Bearer ${settings.liveApiCredential}")
            setRequestProperty("content-type", "application/json")
        }
        return try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }
            code in 200..299
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE_BYTES = 256 * 1024
    }
}
