package dev.openvta.logger

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.openvta.logger.data.RecordingRepository
import dev.openvta.logger.data.SecureSettingsRepository
import dev.openvta.logger.domain.LiveConnectionState
import dev.openvta.logger.domain.RecordingStatus
import dev.openvta.logger.live.LiveCommandActionHandler
import dev.openvta.logger.live.LiveCommandClient
import dev.openvta.logger.live.LiveCommandConnectionEvent
import dev.openvta.logger.live.LiveCommandResult
import dev.openvta.logger.live.LiveUpstreamManager
import dev.openvta.logger.live.RoomLiveOutboxRepository
import dev.openvta.logger.recording.RecordingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class OpenVtaLoggerApp : Application() {
    lateinit var container: AppContainer
        private set
    @Volatile
    private var foregroundActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    foregroundActivityCount += 1
                    if (::container.isInitialized) {
                        container.liveUpstreamManager.refreshCommandConnection()
                        container.liveUpstreamManager.retryPending()
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        container = AppContainer(this)
        instance = this
    }

    fun isAppInForeground(): Boolean = foregroundActivityCount > 0

    companion object {
        lateinit var instance: OpenVtaLoggerApp
            private set
    }
}

class AppContainer(app: OpenVtaLoggerApp) {
    val settingsRepository = SecureSettingsRepository(app)
    val recordingRepository = RecordingRepository(app)
    val liveTraceStore = dev.openvta.logger.data.LiveTraceStore()
    val liveOutboxRepository = RoomLiveOutboxRepository(app)
    private val mutableStatus = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = mutableStatus
    private val liveCommandClient = LiveCommandClient(
        onConnectionEvent = ::handleLiveCommandConnectionEvent,
    )
    val liveUpstreamManager = LiveUpstreamManager(
        loadSettings = settingsRepository::load,
        outboxRepository = liveOutboxRepository,
        commandClient = liveCommandClient,
        resolveRecordingSession = recordingRepository::loadSession,
        onTransferStatus = { message ->
            updateStatus {
                it.copy(
                    lastMessage = message,
                    liveLastTransferAtMillis = System.currentTimeMillis(),
                    liveLastTransferMessage = message,
                )
            }
        },
        commandActionHandler = object : LiveCommandActionHandler {
            override fun startRecording(): LiveCommandResult {
                if (status.value.isRecording) {
                    return LiveCommandResult.succeeded(mapOf("action" to "recording.start", "alreadyRecording" to true))
                }
                remoteStartPreflightFailure(app)?.let { return it }
                return runCatching {
                    ContextCompat.startForegroundService(app, RecordingForegroundService.startIntent(app))
                    LiveCommandResult.succeeded(mapOf("action" to "recording.start"))
                }.getOrElse {
                    recordingCommandFailure("recording.start", it)
                }
            }

            override fun stopRecording(): LiveCommandResult {
                if (!status.value.isRecording) {
                    return LiveCommandResult.idleStopNoop()
                }
                return runCatching {
                    app.startService(RecordingForegroundService.stopIntent(app))
                    LiveCommandResult.succeeded(mapOf("action" to "recording.stop"))
                }.getOrElse {
                    recordingCommandFailure("recording.stop", it)
                }
            }
        },
    ).also { it.refreshCommandConnection() }

    init {
        registerLiveNetworkRetry(app)
    }

    fun updateStatus(transform: (RecordingStatus) -> RecordingStatus) {
        mutableStatus.update(transform)
    }

    private fun handleLiveCommandConnectionEvent(event: LiveCommandConnectionEvent) {
        val state = when (event) {
            is LiveCommandConnectionEvent.Connecting -> if (event.retrying) LiveConnectionState.Reconnecting else LiveConnectionState.Connecting
            is LiveCommandConnectionEvent.Connected -> LiveConnectionState.Connected
            is LiveCommandConnectionEvent.Failed -> LiveConnectionState.Reconnecting
            is LiveCommandConnectionEvent.Closed -> LiveConnectionState.Reconnecting
        }
        val message = when (event) {
            is LiveCommandConnectionEvent.Connecting -> if (event.retrying) "Live command channel reconnecting" else "Live command channel connecting"
            is LiveCommandConnectionEvent.Connected -> "Live command channel connected"
            is LiveCommandConnectionEvent.Failed -> "Live command channel failed: ${event.throwable.message ?: event.throwable::class.java.simpleName}"
            is LiveCommandConnectionEvent.Closed -> "Live command channel closed: ${event.code} ${event.reason}".trim()
        }
        updateStatus { it.copy(lastMessage = message, liveConnectionState = state) }
    }

    private fun registerLiveNetworkRetry(app: OpenVtaLoggerApp) {
        val connectivityManager = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        liveUpstreamManager.refreshCommandConnection()
                        liveUpstreamManager.retryPending()
                    }
                },
            )
        }
    }

    private fun recordingCommandFailure(action: String, throwable: Throwable): LiveCommandResult {
        val message = throwable.message ?: "$action failed"
        return recordingCommandFailure(action, message, throwable::class.java.simpleName)
    }

    private fun recordingCommandFailure(
        action: String,
        message: String,
        exception: String,
        extra: Map<String, Any?> = emptyMap(),
    ): LiveCommandResult {
        updateStatus { it.copy(lastMessage = "Live command failed: $message") }
        return LiveCommandResult.failed(
            mapOf(
                "action" to action,
                "error" to message,
                "exception" to exception,
            ) + extra,
        )
    }

    private fun remoteStartPreflightFailure(app: OpenVtaLoggerApp): LiveCommandResult? {
        if (!app.isAppInForeground()) {
            return recordingCommandFailure(
                action = "recording.start",
                message = "Open the Android app before starting recording remotely. Android blocks location recording from background.",
                exception = "ForegroundServiceStartNotAllowedException",
                extra = mapOf("requiresForeground" to true, "userAction" to "open_app"),
            )
        }
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            return recordingCommandFailure(
                action = "recording.start",
                message = "Location permission is required to start recording remotely.",
                exception = "MissingLocationPermission",
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return recordingCommandFailure(
                action = "recording.start",
                message = "Notification permission is required to start recording remotely.",
                exception = "MissingNotificationPermission",
            )
        }
        return null
    }
}
