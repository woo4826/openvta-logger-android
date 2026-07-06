package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.time.Instant

class LiveMqttSyncClient(
    private val publisher: LiveMqttPublisher = PahoLiveMqttPublisher(),
    private val onServerAck: (String) -> Unit = {},
) : LiveSyncClient {
    override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
        if (!LiveProtocol.isConfigured(settings) || settings.liveMqttCredential.isBlank()) return LiveSyncResult.failed()
        val serverUri = LiveEndpointConfig.mqttServerUri(settings) ?: return LiveSyncResult.failed()
        val topic = LiveEndpointConfig.mqttTopic(settings, entry) ?: return LiveSyncResult.failed()
        val ackSubscription = LiveEndpointConfig.mqttAckTopic(settings)?.let { ackTopic ->
            LiveMqttSubscription(topic = ackTopic, qos = 1, onMessage = onServerAck)
        }
        val delivered = publisher.publish(
            serverUri = serverUri,
            clientId = "openvta-android-${settings.liveDeviceId}",
            username = settings.liveDeviceId,
            password = settings.liveMqttCredential,
            topic = topic,
            payload = entry.payloadJson,
            qos = LiveEndpointConfig.mqttQos(entry),
            will = mqttWill(settings),
            ackSubscription = ackSubscription,
        )
        return if (delivered) LiveSyncResult.delivered() else LiveSyncResult.failed()
    }
}

class LiveHybridSyncClient(
    private val mqttClient: LiveSyncClient = LiveMqttSyncClient(),
    private val httpFallback: LiveSyncClient = LiveHttpSyncClient(),
) : LiveSyncClient {
    constructor(
        onMqttServerAck: (String) -> Unit,
        httpFallback: LiveSyncClient = LiveHttpSyncClient(),
        publisher: LiveMqttPublisher = PahoLiveMqttPublisher(),
    ) : this(
        mqttClient = LiveMqttSyncClient(publisher = publisher, onServerAck = onMqttServerAck),
        httpFallback = httpFallback,
    )

    override fun send(settings: AppSettings, entry: LiveOutboxEntry): LiveSyncResult {
        val httpResult = runCatching { httpFallback.send(settings, entry) }.getOrDefault(LiveSyncResult.failed())
        if (httpResult.delivered) return httpResult
        return runCatching { mqttClient.send(settings, entry) }.getOrDefault(LiveSyncResult.failed())
    }
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
        ackSubscription: LiveMqttSubscription?,
    ): Boolean
}

data class LiveMqttWill(
    val topic: String,
    val payload: String,
    val qos: Int,
)

data class LiveMqttSubscription(
    val topic: String,
    val qos: Int,
    val onMessage: (String) -> Unit,
)

class PahoLiveMqttPublisher : LiveMqttPublisher {
    private var clientKey: String? = null
    private var client: MqttClient? = null
    private var subscribedAckTopic: String? = null
    private var onAckMessage: ((String) -> Unit)? = null

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
        ackSubscription: LiveMqttSubscription?,
    ): Boolean {
        val activeClient = ensureConnected(serverUri, clientId, username, password, will)
        subscribeToAck(activeClient, ackSubscription)
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
        val key = "$serverUri|$clientId|$username|${password.hashCode()}"
        val existing = client
        if (existing != null && clientKey == key && existing.isConnected) return existing
        runCatching { existing?.disconnectForcibly(500, 500) }
        subscribedAckTopic = null
        onAckMessage = null
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

    private fun subscribeToAck(client: MqttClient, subscription: LiveMqttSubscription?) {
        if (subscription == null) return
        onAckMessage = subscription.onMessage
        client.setCallback(
            object : MqttCallback {
                override fun connectionLost(cause: Throwable?) = Unit

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != subscription.topic) return
                    val payload = message?.payload ?: return
                    onAckMessage?.invoke(String(payload, Charsets.UTF_8))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            },
        )
        if (subscribedAckTopic != subscription.topic) {
            client.subscribe(subscription.topic, subscription.qos)
            subscribedAckTopic = subscription.topic
        }
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
