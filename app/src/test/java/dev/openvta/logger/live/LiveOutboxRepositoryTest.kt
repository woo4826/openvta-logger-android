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

    @Test
    fun serverAckOnlyCleansMatchingRangeAndHash() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val first = repository.enqueue(
            kind = "telemetry",
            recordingId = "recording_01",
            seqStart = 1,
            seqEnd = 1,
            payloadHash = "sha256:first",
            payloadJson = """{"seq":1}""",
        )
        val second = repository.enqueue(
            kind = "telemetry",
            recordingId = "recording_01",
            seqStart = 2,
            seqEnd = 2,
            payloadHash = "sha256:second",
            payloadJson = """{"seq":2}""",
        )
        repository.markSent(first.id)
        repository.markSent(second.id)

        val acked = repository.applyServerAck(
            LiveServerAck(
                deviceId = "device_01",
                recordingId = "recording_01",
                ackedRanges = listOf(LiveSequenceRange(1, 2)),
                missingRanges = emptyList(),
                acceptedPayloads = listOf(
                    LiveAcknowledgedPayload(LiveSequenceRange(1, 1), "sha256:first"),
                    LiveAcknowledgedPayload(LiveSequenceRange(2, 2), "sha256:mismatch"),
                ),
            ),
        )

        assertEquals(1, acked)
        val pending = repository.listPending()
        assertEquals(1, pending.size)
        assertEquals(second.id, pending.single().id)
        assertEquals(LiveOutboxStatus.Sent, pending.single().status)
        assertEquals(0, repository.applyServerAck(LiveServerAck("device_01", "recording_stale", listOf(LiveSequenceRange(2, 2)))))
    }

    @Test
    fun missingRangesRequeueOnlyOverlappingEntriesForRequestedRecording() {
        val repository = LiveOutboxRepository(temporaryFolder.root)
        val first = repository.enqueue("telemetry", "recording_01", 1, 1, "sha256:first", """{"seq":1}""")
        val second = repository.enqueue("telemetry", "recording_01", 2, 2, "sha256:second", """{"seq":2}""")
        val otherRecording = repository.enqueue("telemetry", "recording_02", 2, 2, "sha256:other", """{"seq":2}""")
        repository.markAcked(first.id)
        repository.markAcked(second.id)
        repository.markAcked(otherRecording.id)

        val requeued = repository.requeueMissingRanges("recording_01", listOf(LiveSequenceRange(2, 2)))

        assertEquals(1, requeued)
        val pending = repository.listPending()
        assertEquals(1, pending.size)
        assertEquals(second.id, pending.single().id)
        assertEquals(LiveOutboxStatus.Pending, pending.single().status)
    }
}
