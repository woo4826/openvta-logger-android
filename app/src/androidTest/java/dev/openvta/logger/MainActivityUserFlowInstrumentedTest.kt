package dev.openvta.logger

import android.Manifest
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.RecordingStatus
import dev.openvta.logger.live.LiveDeviceCommand
import dev.openvta.logger.recording.RecordingForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class MainActivityUserFlowInstrumentedTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        app.startService(RecordingForegroundService.stopIntent(app))
        waitForRecordingState(app, expected = false, timeoutMillis = 5_000)
        app.container.liveUpstreamManager.disconnectCommandConnection()
        app.container.settingsRepository.save(AppSettings())
        app.container.updateStatus { RecordingStatus() }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @After
    fun stopRecordingIfNeeded() {
        val app = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        if (app.container.status.value.isRecording) {
            ContextCompat.startForegroundService(app, RecordingForegroundService.stopIntent(app))
            waitForRecordingState(app, expected = false, timeoutMillis = 5_000)
        }
    }

    @Test
    fun startAndStopFromDashboardCreatesClosedVtaSession() {
        val app = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        val before = app.container.recordingRepository.listSessions().map { it.id }.toSet()

        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        waitForRecordingState(app, expected = true)

        ContextCompat.startForegroundService(app, RecordingForegroundService.stopIntent(app))
        waitForRecordingState(app, expected = false)

        val session = waitForNewSession(app, before, "UI Start/Stop should create a new session")

        assertTrue("VTA file should exist", session.vtaFile.isFile)
        assertTrue("VTA session should be closed with footer", session.vtaFile.readText().contains("%% End"))
    }

    @Test
    fun remoteCommandsStartAndStopRecordingWithoutCrash() {
        val app = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        val before = app.container.recordingRepository.listSessions().map { it.id }.toSet()

        val start = app.container.liveUpstreamManager.execute(
            LiveDeviceCommand(
                id = "remote-start-id",
                commandId = "remote-start-command",
                type = "recording.start",
                recordingId = null,
                payload = null,
            ),
        )
        assertEquals("succeeded", start.status)
        waitForRecordingState(app, expected = true)

        val stop = app.container.liveUpstreamManager.execute(
            LiveDeviceCommand(
                id = "remote-stop-id",
                commandId = "remote-stop-command",
                type = "recording.stop",
                recordingId = null,
                payload = null,
            ),
        )
        assertEquals("succeeded", stop.status)
        waitForRecordingState(app, expected = false)

        val session = waitForNewSession(app, before, "Remote Start/Stop should create a new session")

        assertTrue("VTA file should exist", session.vtaFile.isFile)
        assertTrue("VTA session should be closed with footer", session.vtaFile.readText().contains("%% End"))
    }

    @Test
    fun backgroundRemoteStartFailsWithoutStartingLocationService() {
        val app = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        pressHomeAndWaitForBackground(app)

        val start = app.container.liveUpstreamManager.execute(
            LiveDeviceCommand(
                id = "remote-background-start-id",
                commandId = "remote-background-start-command",
                type = "recording.start",
                recordingId = null,
                payload = null,
            ),
        )

        assertEquals("failed", start.status)
        assertEquals(false, app.container.status.value.isRecording)
        assertEquals(true, start.result["requiresForeground"])
    }

    @Test
    fun remoteStopWhileIdleReturnsExplicitNoOpResult() {
        val app = ApplicationProvider.getApplicationContext<OpenVtaLoggerApp>()
        if (app.container.status.value.isRecording) {
            ContextCompat.startForegroundService(app, RecordingForegroundService.stopIntent(app))
            waitForRecordingState(app, expected = false)
        }

        val stop = app.container.liveUpstreamManager.execute(
            LiveDeviceCommand(
                id = "remote-idle-stop-id",
                commandId = "remote-idle-stop-command",
                type = "recording.stop",
                recordingId = null,
                payload = null,
            ),
        )

        assertEquals("succeeded", stop.status)
        assertEquals(false, app.container.status.value.isRecording)
        assertEquals(true, stop.result["noOp"])
        assertEquals("noop", stop.result["outcome"])
        assertEquals("idle", stop.result["state"])
        assertEquals("already_idle", stop.result["reason"])
        assertEquals(true, stop.result["alreadyIdle"])
    }

    private fun pressHomeAndWaitForBackground(app: OpenVtaLoggerApp) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        instrumentation.waitForIdleSync()
        waitForForegroundState(app, expected = false, timeoutMillis = 10_000)
    }

    private fun waitForRecordingState(
        app: OpenVtaLoggerApp,
        expected: Boolean,
        timeoutMillis: Long = 10_000,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.container.status.value.isRecording == expected) return
            Thread.sleep(100)
        }
        error("Timed out waiting for isRecording=$expected")
    }

    private fun waitForForegroundState(
        app: OpenVtaLoggerApp,
        expected: Boolean,
        timeoutMillis: Long = 10_000,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.isAppInForeground() == expected) return
            Thread.sleep(100)
        }
        error("Timed out waiting for foreground=$expected")
    }

    private fun waitForNewSession(
        app: OpenVtaLoggerApp,
        before: Set<String>,
        message: String,
        timeoutMillis: Long = 5_000,
    ): dev.openvta.logger.domain.RecordingSession {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            app.container.recordingRepository
                .listSessions()
                .firstOrNull { it.id !in before }
                ?.let { return it }
            Thread.sleep(100)
        }
        val sessions = app.container.recordingRepository.listSessions().map { it.id }
        val status = app.container.status.value
        error("$message; before=$before sessions=$sessions statusRecording=${status.isRecording} currentSession=${status.currentSession?.id} message=${status.lastMessage}")
    }
}
