package dev.openvta.logger.live

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class LiveRegistrationClient {
    fun consumeToken(baseUrl: String, token: String, displayName: String, appVersion: String): LiveRegistrationResult {
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
        fun parse(raw: String): LiveRegistrationQrPayload {
            val json = JSONObject(raw)
            val type = json.optString("type")
            require(type == TYPE) { "not an OpenVTA Live QR" }
            return fromManual(
                baseUrl = json.optString("baseUrl"),
                token = json.optString("token"),
            )
        }

        fun fromManual(baseUrl: String, token: String): LiveRegistrationQrPayload {
            val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
            val normalizedToken = token.trim()
            require(normalizedBaseUrl.startsWith("https://") || normalizedBaseUrl.startsWith("http://")) { "Live server URL must start with http:// or https://" }
            require(normalizedToken.isNotBlank()) { "Live registration code is required" }
            return LiveRegistrationQrPayload(baseUrl = normalizedBaseUrl, token = normalizedToken)
        }

        const val TYPE = "openvta-live-registration"
    }
}

data class LiveRegistrationResult(
    val tenantId: String,
    val deviceId: String,
    val mqttCredential: String,
    val wssCredential: String,
    val apiCredential: String,
)
