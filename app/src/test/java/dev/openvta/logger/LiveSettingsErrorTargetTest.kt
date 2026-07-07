package dev.openvta.logger

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSettingsErrorTargetTest {
    @Test
    fun classifiesPairingServerUrlErrors() {
        assertEquals(
            LivePairingErrorTarget.ServerUrl,
            livePairingErrorTarget("Live code registration failed: Live server URL must start with http:// or https://"),
        )
        assertEquals(
            LivePairingErrorTarget.ServerUrl,
            livePairingErrorTarget("Live QR registration failed: base URL is invalid"),
        )
    }

    @Test
    fun classifiesPairingCodeAndQrErrorsAsRegistrationCodeErrors() {
        assertEquals(
            LivePairingErrorTarget.RegistrationCode,
            livePairingErrorTarget("Live code registration failed: Live registration code must be 6 digits"),
        )
        assertEquals(
            LivePairingErrorTarget.RegistrationCode,
            livePairingErrorTarget("Live QR registration failed: Live QR must contain a 6 digit pairing code"),
        )
    }
}
