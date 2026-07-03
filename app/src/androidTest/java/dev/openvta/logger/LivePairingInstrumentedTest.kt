package dev.openvta.logger

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.journeyapps.barcodescanner.CaptureActivity
import dev.openvta.logger.domain.AppSettings
import org.hamcrest.CoreMatchers.allOf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LivePairingInstrumentedTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .applicationContext as OpenVtaLoggerApp
        app.container.settingsRepository.save(AppSettings())
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun directCodePairingConsumesRegistrationCodeAndPersistsLiveCredentials() {
        RegistrationServer().use { server ->
            navigateToLiveSettings()

            compose.onNodeWithTag("live-base-url-field").performScrollTo()
            compose.onNodeWithTag("live-base-url-field").performTextClearance()
            compose.onNodeWithTag("live-base-url-field").performTextInput("http://127.0.0.1:${server.port}")
            compose.onNodeWithTag("live-registration-code-field").performScrollTo()
            compose.onNodeWithTag("live-registration-code-field").performTextInput("123456")
            compose.onNodeWithTag("live-register-code-button").performScrollTo()
            compose.onNodeWithTag("live-register-code-button").performClick()

            val app = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext as OpenVtaLoggerApp
            compose.waitUntil(timeoutMillis = 10_000) {
                app.container.settingsRepository.load().liveDeviceId == "device-instrumented"
            }

            val request = checkNotNull(server.request) { "Registration request was not captured" }
            assertEquals("POST", request.method)
            assertEquals("/api/devices/registration/consume", request.path)
            val body = JSONObject(request.body)
            assertEquals("123456", body.getString("token"))
            assertEquals("android", body.getString("platform"))

            val settings = app.container.settingsRepository.load()
            assertTrue(settings.liveEnabled)
            assertEquals("http://127.0.0.1:${server.port}", settings.liveBaseUrl)
            assertEquals("tenant-instrumented", settings.liveTenantId)
            assertEquals("device-instrumented", settings.liveDeviceId)
            assertEquals("mqtt-instrumented", settings.liveMqttCredential)
            assertEquals("wss-instrumented", settings.liveWssCredential)
            assertEquals("api-instrumented", settings.liveApiCredential)
        }
    }

    @Test
    fun credentialRotationPayloadUpdatesExistingLiveCredentials() {
        RegistrationServer().use { server ->
            navigateToLiveSettings()

            compose.onNodeWithTag("live-base-url-field").performScrollTo()
            compose.onNodeWithTag("live-base-url-field").performTextClearance()
            compose.onNodeWithTag("live-base-url-field").performTextInput("http://127.0.0.1:${server.port}")
            compose.onNodeWithTag("live-registration-code-field").performScrollTo()
            compose.onNodeWithTag("live-registration-code-field").performTextInput("123456")
            compose.onNodeWithTag("live-register-code-button").performScrollTo()
            compose.onNodeWithTag("live-register-code-button").performClick()

            val app = InstrumentationRegistry.getInstrumentation()
                .targetContext
                .applicationContext as OpenVtaLoggerApp
            compose.waitUntil(timeoutMillis = 10_000) {
                app.container.settingsRepository.load().liveDeviceId == "device-instrumented"
            }

            val payload = JSONObject()
                .put("type", "openvta-live-credential-rotation")
                .put("baseUrl", "http://127.0.0.1:${server.port}")
                .put("tenantId", "tenant-instrumented")
                .put("deviceId", "device-instrumented")
                .put(
                    "credentials",
                    JSONObject()
                        .put("mqtt", "mqtt-rotated")
                        .put("wss", "wss-rotated")
                        .put("api", "api-rotated"),
                )
                .toString()

            compose.onNodeWithTag("live-credential-rotation-payload-field").performScrollTo()
            compose.onNodeWithTag("live-credential-rotation-payload-field").performTextInput(payload)
            compose.onNodeWithTag("live-apply-credential-rotation-button").performScrollTo()
            compose.onNodeWithTag("live-apply-credential-rotation-button").performClick()

            compose.waitUntil(timeoutMillis = 10_000) {
                app.container.settingsRepository.load().liveApiCredential == "api-rotated"
            }
            val settings = app.container.settingsRepository.load()
            assertEquals("tenant-instrumented", settings.liveTenantId)
            assertEquals("device-instrumented", settings.liveDeviceId)
            assertEquals("mqtt-rotated", settings.liveMqttCredential)
            assertEquals("wss-rotated", settings.liveWssCredential)
            assertEquals("api-rotated", settings.liveApiCredential)
        }
    }

    @Test
    fun liveQrScanActionLaunchesScannerHandoff() {
        navigateToLiveSettings()
        intending(hasComponent(CaptureActivity::class.java.name))
            .respondWith(android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()))

        compose.onNodeWithTag("live-scan-qr-button").performScrollTo()
        compose.onNodeWithTag("live-scan-qr-button").performClick()
        intended(
            allOf(
                hasComponent(CaptureActivity::class.java.name),
                hasAction("com.google.zxing.client.android.SCAN"),
                hasExtra("SCAN_FORMATS", "QR_CODE"),
                hasExtra("PROMPT_MESSAGE", "Scan OpenVTA Live QR"),
                hasExtra("BEEP_ENABLED", false),
                hasExtra("SCAN_ORIENTATION_LOCKED", false),
            ),
        )
    }

    @Test
    fun liveQrImageActionLaunchesImagePickerHandoff() {
        navigateToLiveSettings()
        intending(allOf(hasAction(Intent.ACTION_GET_CONTENT), hasType("image/*")))
            .respondWith(android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, Intent()))

        compose.onNodeWithTag("live-qr-image-button").performScrollTo()
        compose.onNodeWithTag("live-qr-image-button").performClick()
        intended(allOf(hasAction(Intent.ACTION_GET_CONTENT), hasType("image/*")))
    }

    private fun navigateToLiveSettings() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settings-section-live").performClick()
    }

    private class RegistrationServer : Closeable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = thread(name = "live-registration-test-server") {
            runCatching {
                serverSocket.accept().use(::handle)
            }
        }
        @Volatile
        var request: RegistrationRequest? = null
            private set

        val port: Int = serverSocket.localPort

        private fun handle(socket: Socket) {
            socket.soTimeout = 10_000
            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyChars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val next = input.read(bodyChars, read, contentLength - read)
                if (next < 0) break
                read += next
            }
            val parts = requestLine.orEmpty().split(' ')
            request = RegistrationRequest(
                method = parts.getOrElse(0) { "" },
                path = parts.getOrElse(1) { "" },
                body = String(bodyChars, 0, read),
            )

            val payload = """
                {
                  "device": {
                    "tenantId": "tenant-instrumented",
                    "id": "device-instrumented"
                  },
                  "credentials": {
                    "mqtt": "mqtt-instrumented",
                    "wss": "wss-instrumented",
                    "api": "api-instrumented"
                  }
                }
            """.trimIndent()
            val response = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
                append(payload)
            }
            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(2_000)
        }
    }

    private data class RegistrationRequest(
        val method: String,
        val path: String,
        val body: String,
    )
}
