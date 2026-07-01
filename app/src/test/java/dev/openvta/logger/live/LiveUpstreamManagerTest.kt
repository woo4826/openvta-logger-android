package dev.openvta.logger.live

import dev.openvta.logger.domain.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class LiveUpstreamManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun flushPendingAcksDeliveredEntries() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val settings = AppSettings(
            liveEnabled = true,
            liveBaseUrl = "https://openvta-live.kro.kr",
            liveTenantId = "tenant_01",
            liveDeviceId = "device_01",
            liveApiCredential = "api_secret",
        )
        val deliveredIds = mutableListOf<String>()
        val manager = LiveUpstreamManager(
            loadSettings = { settings },
            outboxRepository = repository,
            syncClient = object : LiveSyncClient {
                override fun send(settings: AppSettings, entry: LiveOutboxEntry): Boolean {
                    deliveredIds += entry.id
                    return true
                }
            },
            executor = Executor { it.run() },
        )

        repository.enqueue(
            kind = "status",
            recordingId = "recording_01",
            seqStart = 0,
            seqEnd = 0,
            payloadHash = "sha256:abc",
            payloadJson = """{"stream":"status","recordingId":"recording_01","payload":{"status":"completed","recordingId":"recording_01"}}""",
        )

        assertEquals(1, manager.flushPending())
        assertEquals(1, deliveredIds.size)
        assertEquals(0, manager.pendingCount())
    }
}
