package com.temporal.vtalogger

import android.app.Application
import com.temporal.vtalogger.data.RecordingRepository
import com.temporal.vtalogger.data.SecureSettingsRepository
import com.temporal.vtalogger.domain.RecordingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class VtaLoggerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        instance = this
    }

    companion object {
        lateinit var instance: VtaLoggerApp
            private set
    }
}

class AppContainer(app: Application) {
    val settingsRepository = SecureSettingsRepository(app)
    val recordingRepository = RecordingRepository(app)
    val liveTraceStore = com.temporal.vtalogger.data.LiveTraceStore()

    private val mutableStatus = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = mutableStatus

    fun updateStatus(transform: (RecordingStatus) -> RecordingStatus) {
        mutableStatus.update(transform)
    }
}
