package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

open class LiveCommandClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    private val reconnectScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "openvta-live-command-reconnect").apply { isDaemon = true }
    },
    private val onConnectionEvent: (LiveCommandConnectionEvent) -> Unit = {},
) {
    private var activeKey: String? = null
    private var activeSocket: WebSocket? = null
    private var lastSettings: AppSettings? = null
    private var lastExecutor: LiveCommandExecutor? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var reconnectAttempt = 0
    private var manualClose = false

    @Synchronized
    open fun ensureConnected(settings: AppSettings, executor: LiveCommandExecutor) {
        if (!LiveProtocol.isConfigured(settings) || settings.liveWssCredential.isBlank()) {
            close()
            return
        }
        manualClose = false
        lastSettings = settings
        lastExecutor = executor
        val url = LiveEndpointConfig.wssUrl(settings) ?: return
        val key = "${settings.liveDeviceId}|$url"
        if (activeSocket != null && activeKey == key) return
        closeActiveSocket("refresh")
        onConnectionEvent(LiveCommandConnectionEvent.Connecting(url, reconnectAttempt > 0))
        val request = Request.Builder()
            .url(url)
            .header("authorization", "Bearer ${settings.liveWssCredential}")
            .build()
        activeSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(this@LiveCommandClient) {
                        reconnectAttempt = 0
                        reconnectFuture?.cancel(false)
                        reconnectFuture = null
                    }
                    onConnectionEvent(LiveCommandConnectionEvent.Connected(url))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleServerMessage(text, { payload -> webSocket.send(payload) }, executor)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val shouldReconnect = markDisconnected(webSocket)
                    if (!shouldReconnect) return
                    onConnectionEvent(LiveCommandConnectionEvent.Failed(url, t))
                    scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val shouldReconnect = markDisconnected(webSocket)
                    if (!shouldReconnect) return
                    onConnectionEvent(LiveCommandConnectionEvent.Closed(url, code, reason))
                    scheduleReconnect()
                }
            },
        )
        activeKey = key
    }

    @Synchronized
    open fun close() {
        manualClose = true
        reconnectFuture?.cancel(false)
        reconnectFuture = null
        reconnectAttempt = 0
        closeActiveSocket("manual-close")
    }

    @Synchronized
    private fun closeActiveSocket(reason: String) {
        activeSocket?.close(1000, reason)
        activeSocket = null
        activeKey = null
    }

    @Synchronized
    private fun markDisconnected(webSocket: WebSocket): Boolean {
        if (activeSocket != webSocket) return false
        activeSocket = null
        activeKey = null
        return !manualClose
    }

    private fun scheduleReconnect() {
        val settings = synchronized(this) { lastSettings }
        val executor = synchronized(this) { lastExecutor }
        if (settings == null || executor == null || !LiveProtocol.isConfigured(settings) || settings.liveWssCredential.isBlank()) return
        val delayMillis = synchronized(this) {
            if (manualClose) return
            val current = reconnectFuture
            if (current != null && !current.isDone) return
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPT)
            reconnectDelayMillis(reconnectAttempt)
        }
        val future = reconnectScheduler.schedule(
            { ensureConnected(settings, executor) },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
        synchronized(this) { reconnectFuture = future }
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

sealed interface LiveCommandConnectionEvent {
    val url: String

    data class Connecting(override val url: String, val retrying: Boolean) : LiveCommandConnectionEvent
    data class Connected(override val url: String) : LiveCommandConnectionEvent
    data class Failed(override val url: String, val throwable: Throwable) : LiveCommandConnectionEvent
    data class Closed(override val url: String, val code: Int, val reason: String) : LiveCommandConnectionEvent
}

internal fun reconnectDelayMillis(attempt: Int): Long =
    when (attempt.coerceAtLeast(1)) {
        1 -> 1_000L
        2 -> 2_000L
        3 -> 5_000L
        else -> 10_000L
    }

private const val MAX_RECONNECT_ATTEMPT = 4

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
