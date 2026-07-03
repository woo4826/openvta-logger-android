package dev.openvta.logger.upload

import dev.openvta.logger.domain.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadWorkerTest {
    @Test
    fun ftpUploadDoesNotDeleteLocalFilesWhenLiveServerStillNeedsThem() {
        val settings = AppSettings(
            keepLocalFiles = false,
            liveEnabled = true,
            liveBaseUrl = "https://openvta-live.kro.kr",
            liveTenantId = "tenant_01",
            liveDeviceId = "device_01",
            liveApiCredential = "api_secret",
        )

        assertFalse(shouldDeleteLocalFilesAfterFtp(settings))
    }

    @Test
    fun ftpUploadCanDeleteLocalFilesWhenLiveIsNotConfigured() {
        assertTrue(shouldDeleteLocalFilesAfterFtp(AppSettings(keepLocalFiles = false)))
        assertFalse(shouldDeleteLocalFilesAfterFtp(AppSettings(keepLocalFiles = true)))
    }
}
