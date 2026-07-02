package dev.openvta.logger

import android.app.Application
import androidx.core.content.ContextCompat
import dev.openvta.logger.data.RecordingRepository
import dev.openvta.logger.data.SecureSettingsRepository
import dev.openvta.logger.domain.RecordingStatus
import dev.openvta.logger.live.LiveCommandActionHandler
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
    val liveUpstreamManager = LiveUpstreamManager(
        settingsRepository,
        liveOutboxRepository,
        commandActionHandler = object : LiveCommandActionHandler {
            override fun startRecording(): LiveCommandResult {
                ContextCompat.startForegroundService(app, RecordingForegroundService.startIntent(app))
                return LiveCommandResult.succeeded(mapOf("action" to "recording.start"))
            }

            override fun stopRecording(): LiveCommandResult {
                ContextCompat.startForegroundService(app, RecordingForegroundService.stopIntent(app))
                return LiveCommandResult.succeeded(mapOf("action" to "recording.stop"))
            }
        },
    ).also { it.refreshCommandConnection() }

    private val mutableStatus = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = mutableStatus

    fun updateStatus(transform: (RecordingStatus) -> RecordingStatus) {
        mutableStatus.update(transform)
    }
}
