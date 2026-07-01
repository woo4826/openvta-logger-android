package dev.openvta.logger.live

import java.io.File
import java.util.Properties
import java.util.UUID

class LiveOutboxRepository(rootDir: File) {
    private val outboxDir = File(rootDir, "vta/live-outbox").apply { mkdirs() }

    fun enqueue(
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

    fun listPending(): List<LiveOutboxEntry> =
        outboxDir.listFiles { file -> file.extension == "properties" }
            ?.mapNotNull(::read)
            ?.filter { it.status == LiveOutboxStatus.Pending || it.status == LiveOutboxStatus.Sent }
            ?.sortedBy { it.createdAtMillis }
            ?: emptyList()

    fun markAcked(id: String) {
        val entry = read(File(outboxDir, "$id.properties")) ?: return
        write(entry.copy(status = LiveOutboxStatus.Acked))
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
        )
    }
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
)

enum class LiveOutboxStatus {
    Pending,
    Sent,
    Acked,
    Failed,
}
