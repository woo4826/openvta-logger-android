package com.temporal.vtalogger

import android.Manifest
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.rule.GrantPermissionRule
import com.temporal.vtalogger.recording.RecordingForegroundService
import org.junit.After
import org.junit.Assert.assertTrue
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

    @After
    fun stopRecordingIfNeeded() {
        val app = ApplicationProvider.getApplicationContext<VtaLoggerApp>()
        if (app.container.status.value.isRecording) {
            ContextCompat.startForegroundService(app, RecordingForegroundService.stopIntent(app))
            waitForRecordingState(app, expected = false, timeoutMillis = 5_000)
        }
    }

    @Test
    fun startAndStopFromDashboardCreatesClosedVtaSession() {
        val app = ApplicationProvider.getApplicationContext<VtaLoggerApp>()
        val before = app.container.recordingRepository.listSessions().map { it.id }.toSet()

        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        waitForRecordingState(app, expected = true)

        ContextCompat.startForegroundService(app, RecordingForegroundService.stopIntent(app))
        waitForRecordingState(app, expected = false)

        val created = app.container.recordingRepository
            .listSessions()
            .firstOrNull { it.id !in before }
        val session = checkNotNull(created) { "UI Start/Stop should create a new session" }

        assertTrue("VTA file should exist", session.vtaFile.isFile)
        assertTrue("VTA session should be closed with footer", session.vtaFile.readText().contains("%% End"))
    }

    private fun waitForRecordingState(
        app: VtaLoggerApp,
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
}
