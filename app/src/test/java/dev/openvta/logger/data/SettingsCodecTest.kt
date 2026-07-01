package dev.openvta.logger.data

import dev.openvta.logger.domain.AppSettings
import dev.openvta.logger.domain.ImuEnhancementPresets
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun settingsRoundTripPreservesFtpValues() {
        val settings = AppSettings(
            driverId = "CC00",
            ftpHost = "ftp.example.test",
            ftpPort = 2121,
            ftpUser = "RoadData",
            ftpPassword = "secret",
            passiveMode = false,
            keepLocalFiles = false,
            darkMode = true,
            imuPresetId = "imu_heading_10hz",
            liveEnabled = true,
            liveBaseUrl = "https://openvta-live.kro.kr",
            liveTenantId = "tenant_01",
            liveDeviceId = "device_01",
            liveMqttCredential = "mqtt_secret",
            liveWssCredential = "wss_secret",
            liveApiCredential = "api_secret",
        )

        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))

        assertEquals(settings, decoded)
    }

    @Test
    fun unknownImuPresetFallsBackToRawGps() {
        val decoded = SettingsCodec.decode(
            """
            driverId=CC00
            imuPresetId=unknown
            """.trimIndent(),
        )

        assertEquals(ImuEnhancementPresets.DEFAULT_ID, decoded.imuPresetId)
    }

}
