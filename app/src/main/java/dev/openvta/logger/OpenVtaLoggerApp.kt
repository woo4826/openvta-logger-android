package dev.openvta.logger

import android.app.Application
import androidx.core.content.ContextCompat
import dev.openvta.logger.data.RecordingRepository
import dev.openvta.logger.data.SecureSettingsRepository
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

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this
    }

    companion object {
        lateinit var instance: OpenVtaLoggerApp
            private set
    }
}

class AppContainer(app: Application) {
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
        commandActionHandler = object : LiveCommandActionHandler {
            override fun startRecording(): LiveCommandResult {
                return runCatching {
                    ContextCompat.startForegroundService(app, RecordingForegroundService.startIntent(app))
                    LiveCommandResult.succeeded(mapOf("action" to "recording.start"))
                }.getOrElse {
                    recordingCommandFailure("recording.start", it)
                }
            }

            override fun stopRecording(): LiveCommandResult {
                return runCatching {
                    app.startService(RecordingForegroundService.stopIntent(app))
                    LiveCommandResult.succeeded(mapOf("action" to "recording.stop"))
                }.getOrElse {
                    recordingCommandFailure("recording.stop", it)
                }
            }
        },
    ).also { it.refreshCommandConnection() }

    fun updateStatus(transform: (RecordingStatus) -> RecordingStatus) {
        mutableStatus.update(transform)
    }

    private fun handleLiveCommandConnectionEvent(event: LiveCommandConnectionEvent) {
        val message = when (event) {
            is LiveCommandConnectionEvent.Connected -> "Live command channel connected"
            is LiveCommandConnectionEvent.Failed -> "Live command channel failed: ${event.throwable.message ?: event.throwable::class.java.simpleName}"
            is LiveCommandConnectionEvent.Closed -> "Live command channel closed: ${event.code} ${event.reason}".trim()
        }
        updateStatus { it.copy(lastMessage = message) }
    }

    private fun recordingCommandFailure(action: String, throwable: Throwable): LiveCommandResult {
        val message = throwable.message ?: "$action failed"
        updateStatus { it.copy(lastMessage = "Live command failed: $message") }
        return LiveCommandResult.failed(
            mapOf(
                "action" to action,
                "error" to message,
                "exception" to throwable::class.java.simpleName,
            ),
        )
    }
}
