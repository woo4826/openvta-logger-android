package dev.openvta.logger.live

import java.io.File
import java.util.Properties
import java.util.UUID

interface LiveOutboxStore {
    fun enqueue(
        kind: String,
        recordingId: String,
        seqStart: Long,
        seqEnd: Long,
        payloadHash: String,
        payloadJson: String,
    ): LiveOutboxEntry

    fun listPending(): List<LiveOutboxEntry>
    fun summary(): LiveOutboxSummary
    fun markSent(id: String)
    fun markAcked(id: String)
    fun markFailed(id: String)
    fun applyServerAck(ack: LiveServerAck): Int
    fun requeueMissingRanges(recordingId: String, ranges: List<LiveSequenceRange>): Int
    fun requeueAwaitingAck(): Int
}

class LiveOutboxRepository(rootDir: File) : LiveOutboxStore {
    private val outboxDir = File(rootDir, "vta/live-outbox").apply { mkdirs() }

    override fun enqueue(
        kind: String,
        recordingId: String,
        seqStart: Long,
        seqEnd: Long,
        payloadHash: String,
        payloadJson: String,
    ): LiveOutboxEntry {
        val entry = LiveOutboxEntry(
            id = UUID.randomUUID().toString(),
            kind = kind,
            recordingId = recordingId,
            seqStart = seqStart,
            seqEnd = seqEnd,
            payloadHash = payloadHash,
            payloadJson = payloadJson,
            status = LiveOutboxStatus.Pending,
            createdAtMillis = System.currentTimeMillis(),
        )
        write(entry)
        return entry
    }

    override fun listPending(): List<LiveOutboxEntry> =
        outboxDir.listFiles { file -> file.extension == "properties" }
            ?.mapNotNull(::read)
            ?.filter { it.status == LiveOutboxStatus.Pending || it.status == LiveOutboxStatus.Sent || it.status == LiveOutboxStatus.Failed }
            ?.sortedWith(liveOutboxEntryComparator)
            ?: emptyList()

    override fun summary(): LiveOutboxSummary = LiveOutboxSummary.from(listAllEntries())

    override fun markSent(id: String) {
        val entry = read(File(outboxDir, "$id.properties")) ?: return
        write(entry.copy(status = LiveOutboxStatus.Sent, updatedAtMillis = System.currentTimeMillis()))
    }

    override fun markAcked(id: String) {
        val entry = read(File(outboxDir, "$id.properties")) ?: return
        write(entry.copy(status = LiveOutboxStatus.Acked, updatedAtMillis = System.currentTimeMillis()))
    }

    override fun markFailed(id: String) {
        val entry = read(File(outboxDir, "$id.properties")) ?: return
        write(entry.copy(status = LiveOutboxStatus.Failed, updatedAtMillis = System.currentTimeMillis()))
    }

    override fun applyServerAck(ack: LiveServerAck): Int {
        var acked = 0
        for (entry in listAllEntries()) {
            if (entry.status == LiveOutboxStatus.Acked || !entry.isAcknowledgedBy(ack)) continue
            write(entry.copy(status = LiveOutboxStatus.Acked, updatedAtMillis = System.currentTimeMillis()))
            acked += 1
        }
        return acked
    }

    override fun requeueMissingRanges(recordingId: String, ranges: List<LiveSequenceRange>): Int {
        if (ranges.isEmpty()) return 0
        var requeued = 0
        for (entry in listAllEntries()) {
            if (entry.recordingId != recordingId || !entry.overlapsAny(ranges) || entry.status == LiveOutboxStatus.Pending) continue
            write(entry.copy(status = LiveOutboxStatus.Pending, updatedAtMillis = System.currentTimeMillis()))
            requeued += 1
        }
        return requeued
    }

    override fun requeueAwaitingAck(): Int {
        var requeued = 0
        for (entry in listAllEntries()) {
            if (entry.status != LiveOutboxStatus.Sent) continue
            write(entry.copy(status = LiveOutboxStatus.Pending, updatedAtMillis = System.currentTimeMillis()))
            requeued += 1
        }
        return requeued
    }

    private fun write(entry: LiveOutboxEntry) {
        val properties = Properties()
        properties.setProperty("id", entry.id)
        properties.setProperty("kind", entry.kind)
        properties.setProperty("recordingId", entry.recordingId)
        properties.setProperty("seqStart", entry.seqStart.toString())
        properties.setProperty("seqEnd", entry.seqEnd.toString())
        properties.setProperty("payloadHash", entry.payloadHash)
        properties.setProperty("payloadJson", entry.payloadJson)
        properties.setProperty("status", entry.status.name)
        properties.setProperty("createdAtMillis", entry.createdAtMillis.toString())
        properties.setProperty("updatedAtMillis", entry.updatedAtMillis.toString())
        File(outboxDir, "${entry.id}.properties").outputStream().use {
            properties.store(it, "OpenVTA Live outbox entry")
        }
    }

    private fun read(file: File): LiveOutboxEntry? {
        if (!file.isFile) return null
        val properties = Properties().apply { file.inputStream().use(::load) }
        return LiveOutboxEntry(
            id = properties.getProperty("id", file.nameWithoutExtension),
            kind = properties.getProperty("kind", "telemetry"),
            recordingId = properties.getProperty("recordingId", ""),
            seqStart = properties.getProperty("seqStart", "0").toLongOrNull() ?: 0L,
            seqEnd = properties.getProperty("seqEnd", "0").toLongOrNull() ?: 0L,
            payloadHash = properties.getProperty("payloadHash", ""),
            payloadJson = properties.getProperty("payloadJson", "{}"),
            status = properties.getProperty("status")?.let { runCatching { LiveOutboxStatus.valueOf(it) }.getOrNull() }
                ?: LiveOutboxStatus.Pending,
            createdAtMillis = properties.getProperty("createdAtMillis", "0").toLongOrNull() ?: 0L,
            updatedAtMillis = properties.getProperty("updatedAtMillis", "0").toLongOrNull() ?: 0L,
        )
    }

    private fun listAllEntries(): List<LiveOutboxEntry> =
        outboxDir.listFiles { file -> file.extension == "properties" }
            ?.mapNotNull(::read)
            ?.sortedWith(liveOutboxEntryComparator)
            ?: emptyList()
}

data class LiveOutboxEntry(
    val id: String,
    val kind: String,
    val recordingId: String,
    val seqStart: Long,
    val seqEnd: Long,
    val payloadHash: String,
    val payloadJson: String,
    val status: LiveOutboxStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
)

enum class LiveOutboxStatus {
    Pending,
    Sent,
    Acked,
    Failed,
}

data class LiveOutboxSummary(
    val pending: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val acked: Int = 0,
) {
    val activeCount: Int = pending + sent + failed
    val hasFailures: Boolean = failed > 0
    val awaitingAckCount: Int = sent

    companion object {
        fun from(entries: Iterable<LiveOutboxEntry>): LiveOutboxSummary {
            var pending = 0
            var sent = 0
            var failed = 0
            var acked = 0
            for (entry in entries) {
                when (entry.status) {
                    LiveOutboxStatus.Pending -> pending += 1
                    LiveOutboxStatus.Sent -> sent += 1
                    LiveOutboxStatus.Failed -> failed += 1
                    LiveOutboxStatus.Acked -> acked += 1
                }
            }
            return LiveOutboxSummary(pending = pending, sent = sent, failed = failed, acked = acked)
        }
    }
}

internal val liveOutboxEntryComparator: Comparator<LiveOutboxEntry> =
    compareBy<LiveOutboxEntry> { liveOutboxStatusOrder(it.status) }
        .thenBy { it.createdAtMillis }
        .thenBy { liveOutboxKindOrder(it.kind) }
        .thenBy { it.seqStart }
        .thenBy { it.seqEnd }
        .thenBy { it.id }

private fun liveOutboxStatusOrder(status: LiveOutboxStatus): Int =
    when (status) {
        LiveOutboxStatus.Pending,
        LiveOutboxStatus.Sent -> 0
        LiveOutboxStatus.Failed -> 1
        LiveOutboxStatus.Acked -> 2
    }

private fun liveOutboxKindOrder(kind: String): Int =
    when (kind) {
        "status" -> 0
        "telemetry" -> 1
        "chunk-meta" -> 2
        "manifest" -> 3
        else -> 4
    }

internal fun LiveOutboxEntry.range(): LiveSequenceRange = LiveSequenceRange(seqStart, seqEnd)

internal fun LiveOutboxEntry.requiresServerAck(): Boolean = seqStart != 0L || seqEnd != 0L

internal fun LiveOutboxEntry.isAcknowledgedBy(ack: LiveServerAck): Boolean {
    if (recordingId != ack.recordingId) return false
    val entryRange = range()
    if (ack.missingRanges.any { it.overlaps(entryRange) }) return false
    if (ack.ackedRanges.none { it.contains(entryRange) }) return false
    if (ack.acceptedPayloads.isEmpty()) return true
    return ack.acceptedPayloads.any { accepted ->
        accepted.range.contains(entryRange) && accepted.payloadHash == payloadHash
    }
}

internal fun LiveOutboxEntry.overlapsAny(ranges: List<LiveSequenceRange>): Boolean {
    val entryRange = range()
    return ranges.any { it.overlaps(entryRange) }
}
