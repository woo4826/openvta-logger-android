package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class LiveCommandClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) {
    private var activeKey: String? = null
    private var activeSocket: WebSocket? = null

    @Synchronized
    fun ensureConnected(settings: AppSettings, executor: LiveCommandExecutor) {
        if (!LiveProtocol.isConfigured(settings) || settings.liveWssCredential.isBlank()) return
        val url = LiveEndpointConfig.wssUrl(settings) ?: return
        val key = "${settings.liveDeviceId}|$url"
        if (activeSocket != null && activeKey == key) return
        close()
        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer ${settings.liveWssCredential}")
            .build()
        activeSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text, { payload -> webSocket.send(payload) }, executor)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    synchronized(this@LiveCommandClient) {
                        if (activeSocket == webSocket) {
                            activeSocket = null
                            activeKey = null
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(this@LiveCommandClient) {
                        if (activeSocket == webSocket) {
                            activeSocket = null
                            activeKey = null
                        }
                    }
                }
            },
        )
        activeKey = key
    }

    @Synchronized
    fun close() {
        activeSocket?.close(1000, "refresh")
        activeSocket = null
        activeKey = null
    }

    fun handleServerMessage(
        raw: String,
        send: (String) -> Unit,
        executor: LiveCommandExecutor,
    ): LiveCommandResult? {
        val event = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (event.optString("event") != "command.created") return null
        val payload = event.optJSONObject("payload") ?: return null
        val command = LiveDeviceCommand(
            id = payload.optString("id"),
            commandId = payload.optString("commandId", payload.optString("id")),
            type = payload.optString("type"),
            recordingId = payload.optString("recordingId").takeIf { it.isNotBlank() },
            payload = payload.optJSONObject("payload"),
        )
        send(commandStatusJson("command.ack", command.commandId, "acked", null))
        val result = runCatching { executor.execute(command) }
            .getOrElse { LiveCommandResult.failed(mapOf("error" to (it.message ?: "command failed"))) }
        send(commandStatusJson("command.result", command.commandId, result.status, result.result))
        return result
    }

    private fun commandStatusJson(
        type: String,
        commandId: String,
        status: String,
        result: Map<String, Any?>?,
    ): String {
        val body = JSONObject()
            .put("type", type)
            .put("commandId", commandId)
            .put("status", status)
            .put("at", Instant.now().toString())
        if (result != null) body.put("result", JSONObject(result))
        return body.toString()
    }
}

interface LiveCommandExecutor {
    fun execute(command: LiveDeviceCommand): LiveCommandResult
}

interface LiveCommandActionHandler {
    fun startRecording(): LiveCommandResult
    fun stopRecording(): LiveCommandResult
}

object NoopLiveCommandActionHandler : LiveCommandActionHandler {
    override fun startRecording(): LiveCommandResult =
        LiveCommandResult.failed(mapOf("error" to "recording.start is not wired"))

    override fun stopRecording(): LiveCommandResult =
        LiveCommandResult.failed(mapOf("error" to "recording.stop is not wired"))
}

data class LiveDeviceCommand(
    val id: String,
    val commandId: String,
    val type: String,
    val recordingId: String?,
    val payload: JSONObject?,
)

data class LiveCommandResult(
    val status: String,
    val result: Map<String, Any?>,
) {
    companion object {
        fun succeeded(result: Map<String, Any?> = emptyMap()) = LiveCommandResult("succeeded", result)
        fun failed(result: Map<String, Any?> = emptyMap()) = LiveCommandResult("failed", result)
    }
}
