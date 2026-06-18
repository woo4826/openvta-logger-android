package com.temporal.vtalogger.data

import com.temporal.vtalogger.domain.AppSettings
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
        )

        val decoded = SettingsCodec.decode(SettingsCodec.encode(settings))

        assertEquals(settings, decoded)
    }

}
