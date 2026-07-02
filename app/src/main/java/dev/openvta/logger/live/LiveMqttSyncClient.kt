package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.time.Instant

class LiveMqttSyncClient(
    private val publisher: LiveMqttPublisher = PahoLiveMqttPublisher(),
) : LiveSyncClient {
    override fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean {
        if (!LiveProtocol.isConfigured(settings) || settings.liveMqttCredential.isBlank()) return false
        val serverUri = LiveEndpointConfig.mqttServerUri(settings) ?: return false
        val topic = LiveEndpointConfig.mqttTopic(settings, entry) ?: return false
        return publisher.publish(
            serverUri = serverUri,
            clientId = "openvta-android-${settings.liveDeviceId}",
            username = settings.liveDeviceId,
            password = settings.liveMqttCredential,
            topic = topic,
            payload = entry.payloadJson,
            qos = LiveEndpointConfig.mqttQos(entry),
            will = mqttWill(settings),
        )
    }
}

class LiveHybridSyncClient(
    private val mqttClient: LiveSyncClient = LiveMqttSyncClient(),
    private val httpFallback: LiveSyncClient = LiveHttpSyncClient(),
) : LiveSyncClient {
    override fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean =
        httpFallback.send(settings, entry) || runCatching { mqttClient.send(settings, entry) }.getOrDefault(false)
}

interface LiveMqttPublisher {
    fun publish(
        serverUri: String,
        clientId: String,
        username: String,
        password: String,
        topic: String,
        payload: String,
        qos: Int,
        will: LiveMqttWill?,
    ): Boolean
}

data class LiveMqttWill(
    val topic: String,
    val payload: String,
    val qos: Int,
)

class PahoLiveMqttPublisher : LiveMqttPublisher {
    private var clientKey: String? = null
    private var client: MqttClient? = null

    @Synchronized
    override fun publish(
        serverUri: String,
        clientId: String,
        username: String,
        password: String,
        topic: String,
        payload: String,
        qos: Int,
        will: LiveMqttWill?,
    ): Boolean {
        val activeClient = ensureConnected(serverUri, clientId, username, password, will)
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            this.qos = qos
            isRetained = false
        }
        activeClient.publish(topic, message)
        return true
    }

    private fun ensureConnected(
        serverUri: String,
        clientId: String,
        username: String,
        password: String,
        will: LiveMqttWill?,
    ): MqttClient {
        val key = "$serverUri|$clientId|$username"
        val existing = client
        if (existing != null && clientKey == key && existing.isConnected) return existing
        runCatching { existing?.disconnectForcibly(500, 500) }
        val next = MqttClient(serverUri, clientId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            userName = username
            this.password = password.toCharArray()
            connectionTimeout = 10
            keepAliveInterval = 30
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            if (will != null) {
                setWill(will.topic, will.payload.toByteArray(Charsets.UTF_8), will.qos, false)
            }
        }
        next.connect(options)
        client = next
        clientKey = key
        return next
    }
}

private fun mqttWill(settings: AppSettings): LiveMqttWill? {
    val topic = LiveEndpointConfig.mqttTopic(
        settings,
        LiveOutboxEntry(
            id = "will",
            kind = "status",
            recordingId = "",
            seqStart = 0,
            seqEnd = 0,
            payloadHash = "",
            payloadJson = "{}",
            status = LiveOutboxStatus.Pending,
            createdAtMillis = 0,
        ),
    ) ?: return null
    val payload = """{"status":"offline","reason":"mqtt_lwt","at":"${Instant.now()}"}"""
    return LiveMqttWill(topic = topic, payload = payload, qos = 1)
}
