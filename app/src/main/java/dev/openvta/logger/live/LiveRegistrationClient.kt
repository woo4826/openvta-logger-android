package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

class LiveRegistrationClient {
    fun consumeToken(baseUrl: String, token: String, displayName: String, appVersion: String, clientDeviceKey: String): LiveRegistrationResult {
        val url = URL(baseUrl.trimEnd('/') + "/api/devices/registration/consume")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
        }
        val body = JSONObject()
            .put("token", token)
            .put("displayName", displayName)
            .put("appVersion", appVersion)
            .put("platform", "android")
            .put("clientDeviceKey", clientDeviceKey)
            .toString()
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
        val responseCode = connection.responseCode
        val responseText = if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val message = runCatching { JSONObject(errorText).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            throw IllegalStateException(message ?: "Live registration failed with HTTP $responseCode")
        }
        val response = JSONObject(responseText)
        val device = response.getJSONObject("device")
        val credentials = response.getJSONObject("credentials")
        return LiveRegistrationResult(
            tenantId = device.getString("tenantId"),
            deviceId = device.getString("id"),
            mqttCredential = credentials.getString("mqtt"),
            wssCredential = credentials.getString("wss"),
            apiCredential = credentials.getString("api"),
        )
    }
}

data class LiveRegistrationQrPayload(
    val baseUrl: String,
    val token: String,
) {
    companion object {
        fun parse(raw: String, fallbackBaseUrl: String = DEFAULT_BASE_URL): LiveRegistrationQrPayload {
            val trimmed = raw.trim()
            require(trimmed.isNotBlank()) { "Live QR is empty" }
            if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val type = json.optString("type")
                require(type == TYPE) { "not an OpenVTA Live QR" }
                return fromManual(
                    baseUrl = json.optString("baseUrl"),
                    token = json.optString("token"),
                )
            }

            runCatching { URI(trimmed) }.getOrNull()?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") {
                    val code = queryParameter(uri, "code") ?: uri.path
                        ?.trim('/')
                        ?.split('/')
                        ?.lastOrNull()
                        ?.takeIf { REGISTRATION_CODE.matches(it) }
                    if (code != null) {
                        val origin = buildString {
                            append(uri.scheme)
                            append("://")
                            append(uri.host)
                            if (uri.port > 0) append(":").append(uri.port)
                        }
                        return fromManual(queryParameter(uri, "baseUrl") ?: origin, code)
                    }
                }
            }

            val code = REGISTRATION_CODE.find(trimmed)?.value
            if (code != null) return fromManual(fallbackBaseUrl, code)
            throw IllegalArgumentException("Live QR must contain a 6 digit pairing code")
        }

        fun fromManual(baseUrl: String, token: String): LiveRegistrationQrPayload {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val normalizedToken = token.trim()
            require(normalizedBaseUrl.startsWith("https://") || normalizedBaseUrl.startsWith("http://")) { "Live server URL must start with http:// or https://" }
            require(REGISTRATION_CODE.matches(normalizedToken)) { "Live registration code must be 6 digits" }
            return LiveRegistrationQrPayload(baseUrl = normalizedBaseUrl, token = normalizedToken)
        }

        private fun queryParameter(uri: URI, name: String): String? =
            uri.rawQuery
                ?.split('&')
                ?.mapNotNull { part ->
                    val separator = part.indexOf('=')
                    if (separator < 0) return@mapNotNull null
                    val key = part.substring(0, separator).urlDecode()
                    val value = part.substring(separator + 1).urlDecode()
                    key to value
                }
                ?.firstOrNull { it.first == name }
                ?.second
                ?.takeIf { it.isNotBlank() }

        private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

        const val TYPE = "openvta-live-registration"
        const val DEFAULT_BASE_URL = "https://openvta-live.kro.kr"
        private val REGISTRATION_CODE = Regex("""\b\d{6}\b""")
    }
}

data class LiveRegistrationResult(
    val tenantId: String,
    val deviceId: String,
    val mqttCredential: String,
    val wssCredential: String,
    val apiCredential: String,
)

data class LiveCredentialRotationPayload(
    val baseUrl: String?,
    val tenantId: String?,
    val deviceId: String,
    val mqttCredential: String,
    val wssCredential: String,
    val apiCredential: String,
) {
    fun applyTo(settings: AppSettings): AppSettings {
        require(settings.liveDeviceId == deviceId) { "Credential payload is for a different device" }
        return settings.copy(
            liveEnabled = true,
            liveBaseUrl = baseUrl ?: settings.liveBaseUrl,
            liveTenantId = tenantId ?: settings.liveTenantId,
            liveDeviceId = deviceId,
            liveMqttCredential = mqttCredential,
            liveWssCredential = wssCredential,
            liveApiCredential = apiCredential,
        )
    }

    companion object {
        fun parse(raw: String): LiveCredentialRotationPayload {
            val trimmed = raw.trim()
            require(trimmed.isNotBlank()) { "Live credential payload is empty" }
            val json = JSONObject(trimmed)
            require(json.optString("type") == TYPE) { "not an OpenVTA Live credential rotation payload" }
            val credentials = json.getJSONObject("credentials")
            val baseUrl = json.optString("baseUrl").takeIf { it.isNotBlank() }?.trim()?.trimEnd('/')
            if (baseUrl != null) {
                require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) { "Live server URL must start with http:// or https://" }
            }
            return LiveCredentialRotationPayload(
                baseUrl = baseUrl,
                tenantId = json.optString("tenantId").takeIf { it.isNotBlank() },
                deviceId = json.getString("deviceId").trim(),
                mqttCredential = credentials.getString("mqtt").trim(),
                wssCredential = credentials.getString("wss").trim(),
                apiCredential = credentials.getString("api").trim(),
            ).also { payload ->
                require(payload.deviceId.isNotBlank()) { "Live device id is required" }
                require(payload.mqttCredential.isNotBlank()) { "MQTT credential is required" }
                require(payload.wssCredential.isNotBlank()) { "WSS credential is required" }
                require(payload.apiCredential.isNotBlank()) { "API credential is required" }
            }
        }

        const val TYPE = "openvta-live-credential-rotation"
    }
}
