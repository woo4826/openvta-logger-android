package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
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

    @Test
    fun parsesCredentialRotationPayload() {
        val payload = LiveCredentialRotationPayload.parse(
            """
            {
              "type": "openvta-live-credential-rotation",
              "baseUrl": "https://openvta-live.kro.kr/",
              "tenantId": "tenant-1",
              "deviceId": "device-1",
              "credentials": {
                "mqtt": "mqtt-new",
                "wss": "wss-new",
                "api": "api-new"
              }
            }
            """.trimIndent(),
        )

        assertEquals("https://openvta-live.kro.kr", payload.baseUrl)
        assertEquals("tenant-1", payload.tenantId)
        assertEquals("device-1", payload.deviceId)
        assertEquals("mqtt-new", payload.mqttCredential)
        assertEquals("wss-new", payload.wssCredential)
        assertEquals("api-new", payload.apiCredential)
    }

    @Test
    fun rejectsRegistrationPayloadAsCredentialRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveCredentialRotationPayload.parse("""{"type":"openvta-live-registration","baseUrl":"https://openvta-live.kro.kr/","token":"123456"}""")
        }
    }

    @Test
    fun appliesCredentialRotationOnlyToCurrentDevice() {
        val payload = LiveCredentialRotationPayload(
            baseUrl = null,
            tenantId = null,
            deviceId = "device-1",
            mqttCredential = "mqtt-new",
            wssCredential = "wss-new",
            apiCredential = "api-new",
        )
        val settings = AppSettings(
            liveEnabled = true,
            liveBaseUrl = "https://openvta-live.kro.kr",
            liveTenantId = "tenant-1",
            liveDeviceId = "device-1",
            liveMqttCredential = "mqtt-old",
            liveWssCredential = "wss-old",
            liveApiCredential = "api-old",
        )

        val updated = payload.applyTo(settings)

        assertEquals("tenant-1", updated.liveTenantId)
        assertEquals("mqtt-new", updated.liveMqttCredential)
        assertEquals("wss-new", updated.liveWssCredential)
        assertEquals("api-new", updated.liveApiCredential)
        assertThrows(IllegalArgumentException::class.java) {
            payload.applyTo(settings.copy(liveDeviceId = "device-2"))
        }
    }
}
