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
        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
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

data class LiveRegistrationResult(
    val tenantId: String,
    val deviceId: String,
    val mqttCredential: String,
    val wssCredential: String,
    val apiCredential: String,
)
