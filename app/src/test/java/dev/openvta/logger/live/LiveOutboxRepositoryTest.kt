package dev.openvta.logger.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveOutboxRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun enqueuePersistsPendingEntryAndAckRemovesItFromPendingList() {
        val repository = LiveOutboxRepository(temporaryFolder.root)

        val entry = repository.enqueue(
            kind = "telemetry",
            recordingId = "recording_01",
            seqStart = 1,
            seqEnd = 10,
            payloadHash = "sha256:abc",
            payloadJson = """{"payload":true}""",
        )

        val pending = repository.listPending()
        assertEquals(1, pending.size)
        assertEquals(entry.id, pending.first().id)
        assertEquals("recording_01", pending.first().recordingId)
        assertTrue(pending.first().payloadJson.contains("payload"))

        repository.markSent(entry.id)
        assertEquals(LiveOutboxStatus.Sent, repository.listPending().first().status)

        repository.markFailed(entry.id)
        assertEquals(LiveOutboxStatus.Failed, repository.listPending().first().status)

        repository.markAcked(entry.id)

        assertEquals(emptyList<LiveOutboxEntry>(), repository.listPending())
    }
}
