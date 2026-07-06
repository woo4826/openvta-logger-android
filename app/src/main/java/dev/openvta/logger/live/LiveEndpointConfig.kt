package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import org.json.JSONObject
import java.net.URI

object LiveEndpointConfig {
    fun mqttServerUri(settings: AppSettings): String? {
        val base = parseBaseUri(settings.liveBaseUrl) ?: return null
        val host = base.host ?: return null
        return when (base.scheme?.lowercase()) {
            "https" -> "ssl://$host:${PRODUCTION_MQTT_TLS_PORT}"
            "http" -> "tcp://$host:${LOCAL_MQTT_PORT}"
            else -> null
        }
    }

    fun wssUrl(settings: AppSettings): String? {
        if (settings.liveDeviceId.isBlank() || settings.liveWssCredential.isBlank()) return null
        val base = parseBaseUri(settings.liveBaseUrl) ?: return null
        val host = base.host ?: return null
        val scheme = when (base.scheme?.lowercase()) {
            "https" -> "wss"
            "http" -> "ws"
            else -> return null
        }
        val port = gatewayPort(base)
        val authority = if (port > 0) "$host:$port" else host
        return "$scheme://$authority/ws/device-control?deviceId=${settings.liveDeviceId}&credential=${settings.liveWssCredential}"
    }

    fun mqttTopic(settings: AppSettings, entry: LiveOutboxEntry): String? {
        if (settings.liveTenantId.isBlank() || settings.liveDeviceId.isBlank()) return null
        val envelope = runCatching { JSONObject(entry.payloadJson) }.getOrNull()
        val stream = envelope?.optString("stream")?.takeIf { it.isNotBlank() } ?: entry.kind
        val base = "vta/${settings.liveTenantId}/${settings.liveDeviceId}"
        return when (stream) {
            "status", "heartbeat", "telemetry" -> "$base/$stream"
            "chunk-meta", "manifest" -> {
                val recordingId = envelope?.optString("recordingId")?.takeIf { it.isNotBlank() } ?: entry.recordingId
                if (recordingId.isBlank()) null else "$base/recording/$recordingId/$stream"
            }
            else -> null
        }
    }

    fun mqttAckTopic(settings: AppSettings): String? {
        if (settings.liveTenantId.isBlank() || settings.liveDeviceId.isBlank()) return null
        return "vta/${settings.liveTenantId}/${settings.liveDeviceId}/ack"
    }

    fun mqttQos(entry: LiveOutboxEntry): Int =
        if (entry.kind == "heartbeat") 0 else 1

    private fun parseBaseUri(value: String): URI? =
        runCatching { URI(value.trim()) }.getOrNull()

    private fun gatewayPort(base: URI): Int {
        val explicitPort = base.port
        if (base.scheme.equals("http", ignoreCase = true) && explicitPort > 0) return explicitPort + 1
        return explicitPort
    }

    private const val PRODUCTION_MQTT_TLS_PORT = 8883
    private const val LOCAL_MQTT_PORT = 1883
}
