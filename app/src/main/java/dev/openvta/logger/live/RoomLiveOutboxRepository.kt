package dev.openvta.logger.live

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.UUID

class RoomLiveOutboxRepository(context: Context) : LiveOutboxStore {
    private val dao = Room.databaseBuilder(
        context.applicationContext,
        LiveOutboxDatabase::class.java,
        "openvta-live-outbox.db",
    )
        .allowMainThreadQueries()
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
        .liveOutboxDao()

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
        dao.upsert(entry.toEntity())
        return entry
    }

    override fun listPending(): List<LiveOutboxEntry> =
        dao.listPending(
            listOf(
                LiveOutboxStatus.Pending.name,
                LiveOutboxStatus.Sent.name,
                LiveOutboxStatus.Failed.name,
            ),
        ).map { it.toEntry() }.sortedWith(liveOutboxEntryComparator)

    override fun summary(): LiveOutboxSummary = LiveOutboxSummary.from(dao.listAll().map { it.toEntry() })

    override fun markSent(id: String) {
        updateStatus(id, LiveOutboxStatus.Sent)
    }

    override fun markAcked(id: String) {
        updateStatus(id, LiveOutboxStatus.Acked)
    }

    override fun markFailed(id: String) {
        updateStatus(id, LiveOutboxStatus.Failed)
    }

    override fun applyServerAck(ack: LiveServerAck): Int {
        var acked = 0
        for (entry in dao.listAll().map { it.toEntry() }.sortedWith(liveOutboxEntryComparator)) {
            if (entry.status == LiveOutboxStatus.Acked || !entry.isAcknowledgedBy(ack)) continue
            updateStatus(entry.id, LiveOutboxStatus.Acked)
            acked += 1
        }
        return acked
    }

    override fun requeueMissingRanges(recordingId: String, ranges: List<LiveSequenceRange>): Int {
        if (ranges.isEmpty()) return 0
        var requeued = 0
        for (entry in dao.listAll().map { it.toEntry() }.sortedWith(liveOutboxEntryComparator)) {
            if (entry.recordingId != recordingId || !entry.overlapsAny(ranges) || entry.status == LiveOutboxStatus.Pending) continue
            updateStatus(entry.id, LiveOutboxStatus.Pending)
            requeued += 1
        }
        return requeued
    }

    private fun updateStatus(id: String, status: LiveOutboxStatus) {
        dao.updateStatus(id, status.name, System.currentTimeMillis())
    }
}

@Entity(tableName = "live_outbox")
data class LiveOutboxEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val recordingId: String,
    val seqStart: Long,
    val seqEnd: Long,
    val payloadHash: String,
    val payloadJson: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Dao
interface LiveOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: LiveOutboxEntity)

    @Query("SELECT * FROM live_outbox WHERE status IN (:statuses) ORDER BY createdAtMillis ASC")
    fun listPending(statuses: List<String>): List<LiveOutboxEntity>

    @Query("SELECT * FROM live_outbox ORDER BY createdAtMillis ASC")
    fun listAll(): List<LiveOutboxEntity>

    @Query("UPDATE live_outbox SET status = :status, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    fun updateStatus(id: String, status: String, updatedAtMillis: Long)
}

@Database(entities = [LiveOutboxEntity::class], version = 1, exportSchema = false)
abstract class LiveOutboxDatabase : RoomDatabase() {
    abstract fun liveOutboxDao(): LiveOutboxDao
}

private fun LiveOutboxEntry.toEntity(): LiveOutboxEntity =
    LiveOutboxEntity(
        id = id,
        kind = kind,
        recordingId = recordingId,
        seqStart = seqStart,
        seqEnd = seqEnd,
        payloadHash = payloadHash,
        payloadJson = payloadJson,
        status = status.name,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

private fun LiveOutboxEntity.toEntry(): LiveOutboxEntry =
    LiveOutboxEntry(
        id = id,
        kind = kind,
        recordingId = recordingId,
        seqStart = seqStart,
        seqEnd = seqEnd,
        payloadHash = payloadHash,
        payloadJson = payloadJson,
        status = runCatching { LiveOutboxStatus.valueOf(status) }.getOrDefault(LiveOutboxStatus.Pending),
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )
