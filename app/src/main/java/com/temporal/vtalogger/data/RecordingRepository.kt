package com.temporal.vtalogger.data

import android.content.Context
import com.temporal.vtalogger.BuildConfig
import com.temporal.vtalogger.domain.FileNames
import com.temporal.vtalogger.domain.GpsSample
import com.temporal.vtalogger.domain.GpsTracePoint
import com.temporal.vtalogger.domain.ImuEnhancementPresets
import com.temporal.vtalogger.domain.RecordingSession
import com.temporal.vtalogger.domain.SensorSample
import com.temporal.vtalogger.domain.UploadState
import com.temporal.vtalogger.domain.VtaFormatter
import com.temporal.vtalogger.domain.ZipFiles
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

class RecordingRepository(context: Context) {
    val sessionsDir: File = File(context.filesDir, "vta/sessions").apply { mkdirs() }
    private val formatter = VtaFormatter(BuildConfig.VERSION_NAME)
    private val lock = Any()
    private var activeWriterSessionId: String? = null
    private var activeWriter: BufferedWriter? = null
    private var bufferedLineCount = 0

    fun createSession(
        driverId: String,
        startedAtMillis: Long = System.currentTimeMillis(),
        imuPresetId: String = ImuEnhancementPresets.DEFAULT_ID,
    ): RecordingSession {
        val safeDriver = FileNames.sanitizeDriverId(driverId)
        val safePreset = ImuEnhancementPresets.find(imuPresetId).id
        val baseName = FileNames.sessionBaseName(startedAtMillis, safeDriver)
        val session = RecordingSession(
            id = baseName,
            driverId = safeDriver,
            startedAtMillis = startedAtMillis,
            vtaFile = File(sessionsDir, "$baseName.Vta"),
            zipFile = File(sessionsDir, "$baseName.Zip"),
            imuPresetId = safePreset,
        )
        synchronized(lock) {
            closeActiveWriterLocked()
            session.vtaFile.writeText(formatter.header() + formatter.imuPresetHeader(safePreset) + "\n")
            openActiveWriterLocked(session)
            writeMeta(session)
        }
        return session
    }

    fun appendGps(session: RecordingSession, sample: GpsSample) {
        appendLine(session, formatter.formatGps(sample), flushNow = true)
    }

    fun appendSensor(session: RecordingSession, sample: SensorSample) {
        appendLine(session, formatter.formatSensor(sample), flushNow = false)
    }

    fun appendEnhancedGps(session: RecordingSession, point: GpsTracePoint) {
        appendLine(session, formatter.formatEnhancedGps(point, session.imuPresetId), flushNow = false)
    }

    fun closeSession(session: RecordingSession, endedAtMillis: Long = System.currentTimeMillis()): RecordingSession {
        val closed = session.copy(endedAtMillis = endedAtMillis)
        synchronized(lock) {
            writeLineLocked(session, formatter.footer(), flushNow = true)
            closeActiveWriterLocked()
            writeMeta(closed)
        }
        return closed
    }

    fun zipSession(session: RecordingSession): File {
        synchronized(lock) {
            flushActiveWriterLocked()
            ZipFiles.zipSingleFile(session.vtaFile, session.zipFile)
            writeMeta(session)
        }
        return session.zipFile
    }

    fun updateUploadState(sessionId: String, state: UploadState, lastError: String? = null) {
        synchronized(lock) {
            val session = loadSession(sessionId) ?: return
            writeMeta(session.copy(uploadState = state, lastError = lastError))
        }
    }

    fun loadSession(sessionId: String): RecordingSession? {
        val meta = File(sessionsDir, "$sessionId.meta")
        if (!meta.isFile) return null
        val properties = Properties().apply { meta.inputStream().use(::load) }
        val vtaFile = File(sessionsDir, properties.getProperty("vtaFile"))
        val zipFile = File(sessionsDir, properties.getProperty("zipFile"))
        return RecordingSession(
            id = properties.getProperty("id", sessionId),
            driverId = properties.getProperty("driverId", "UNKNOWN"),
            startedAtMillis = properties.getProperty("startedAtMillis", "0").toLongOrNull() ?: 0L,
            endedAtMillis = properties.getProperty("endedAtMillis")?.toLongOrNull(),
            uploadState = properties.getProperty("uploadState")?.let { runCatching { UploadState.valueOf(it) }.getOrNull() }
                ?: UploadState.NotQueued,
            lastError = properties.getProperty("lastError").takeUnless { it.isNullOrBlank() },
            vtaFile = vtaFile,
            zipFile = zipFile,
            imuPresetId = ImuEnhancementPresets.find(
                properties.getProperty("imuPresetId"),
            ).id,
        )
    }

    fun listSessions(): List<RecordingSession> {
        return sessionsDir.listFiles { file -> file.extension == "meta" }
            ?.mapNotNull { loadSession(it.nameWithoutExtension) }
            ?.sortedByDescending { it.startedAtMillis }
            ?: emptyList()
    }

    private fun appendLine(session: RecordingSession, line: String, flushNow: Boolean) {
        synchronized(lock) {
            writeLineLocked(session, line, flushNow)
        }
    }

    private fun writeLineLocked(session: RecordingSession, line: String, flushNow: Boolean) {
        val writer = if (activeWriterSessionId == session.id) {
            activeWriter ?: openActiveWriterLocked(session)
        } else {
            null
        }

        if (writer != null) {
            writer.write(line)
            writer.newLine()
            bufferedLineCount += 1
            if (flushNow || bufferedLineCount >= BUFFERED_LINE_FLUSH_THRESHOLD) {
                flushActiveWriterLocked()
            }
        } else {
            session.vtaFile.appendText(line + "\n")
        }
    }

    private fun openActiveWriterLocked(session: RecordingSession): BufferedWriter {
        val writer = FileOutputStream(session.vtaFile, true).bufferedWriter()
        activeWriterSessionId = session.id
        activeWriter = writer
        bufferedLineCount = 0
        return writer
    }

    private fun flushActiveWriterLocked() {
        activeWriter?.flush()
        bufferedLineCount = 0
    }

    private fun closeActiveWriterLocked() {
        activeWriter?.close()
        activeWriter = null
        activeWriterSessionId = null
        bufferedLineCount = 0
    }

    private fun writeMeta(session: RecordingSession) {
        val properties = Properties()
        properties.setProperty("id", session.id)
        properties.setProperty("driverId", session.driverId)
        properties.setProperty("startedAtMillis", session.startedAtMillis.toString())
        session.endedAtMillis?.let { properties.setProperty("endedAtMillis", it.toString()) }
        properties.setProperty("uploadState", session.uploadState.name)
        properties.setProperty("vtaFile", session.vtaFile.name)
        properties.setProperty("zipFile", session.zipFile.name)
        properties.setProperty("lastError", session.lastError.orEmpty())
        properties.setProperty("imuPresetId", ImuEnhancementPresets.find(session.imuPresetId).id)
        File(sessionsDir, "${session.id}.meta").outputStream().use {
            properties.store(it, "VTA Logger session metadata")
        }
    }

    companion object {
        private const val BUFFERED_LINE_FLUSH_THRESHOLD = 25
    }
}
