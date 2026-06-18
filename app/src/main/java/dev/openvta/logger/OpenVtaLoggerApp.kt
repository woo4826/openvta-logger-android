package dev.openvta.logger

import android.app.Application
import dev.openvta.logger.data.RecordingRepository
import dev.openvta.logger.data.SecureSettingsRepository
import dev.openvta.logger.domain.RecordingStatus
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

    private val mutableStatus = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = mutableStatus

    fun updateStatus(transform: (RecordingStatus) -> RecordingStatus) {
        mutableStatus.update(transform)
    }
}
