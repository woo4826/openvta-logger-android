package dev.openvta.logger.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveRegistrationQrPayloadTest {
    @Test
    fun parsesOpenVtaLiveRegistrationQr() {
        val payload = LiveRegistrationQrPayload.parse(
            """{"type":"openvta-live-registration","baseUrl":"https://openvta-live.kro.kr/","token":"ovta_reg_123"}""",
        )

        assertEquals("https://openvta-live.kro.kr", payload.baseUrl)
        assertEquals("ovta_reg_123", payload.token)
    }

    @Test
    fun rejectsTokenOnlyQr() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveRegistrationQrPayload.parse("""{"type":"openvta-live-registration","token":"ovta_reg_123"}""")
        }
    }
}
