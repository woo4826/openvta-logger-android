package dev.openvta.logger.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveRegistrationQrPayloadTest {
    @Test
    fun parsesOpenVtaLiveRegistrationQr() {
        val payload = LiveRegistrationQrPayload.parse(
            """{"type":"openvta-live-registration","baseUrl":"https://openvta-live.kro.kr/","token":"123456"}""",
        )

        assertEquals("https://openvta-live.kro.kr", payload.baseUrl)
        assertEquals("123456", payload.token)
    }

    @Test
    fun parsesPairingUrlQr() {
        val payload = LiveRegistrationQrPayload.parse("https://openvta-live.kro.kr/pair/654321")

        assertEquals("https://openvta-live.kro.kr", payload.baseUrl)
        assertEquals("654321", payload.token)
    }

    @Test
    fun parsesPlainCodeQrWithFallbackBaseUrl() {
        val payload = LiveRegistrationQrPayload.parse(" 456789 ", fallbackBaseUrl = "https://openvta-live.kro.kr")

        assertEquals("https://openvta-live.kro.kr", payload.baseUrl)
        assertEquals("456789", payload.token)
    }

    @Test
    fun rejectsTokenOnlyQr() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveRegistrationQrPayload.parse("""{"type":"openvta-live-registration","token":"123456"}""")
        }
    }

    @Test
    fun parsesManualRegistrationCode() {
        val payload = LiveRegistrationQrPayload.fromManual(
            baseUrl = " https://openvta-live.kro.kr/ ",
            token = " 123456 ",
        )

        assertEquals("https://openvta-live.kro.kr", payload.baseUrl)
        assertEquals("123456", payload.token)
    }
}
