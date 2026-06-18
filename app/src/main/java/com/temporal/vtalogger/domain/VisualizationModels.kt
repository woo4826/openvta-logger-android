package com.temporal.vtalogger.domain

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class GpsTracePoint(
    val date: String,
    val time: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedKmh: Double,
    val bearingDegrees: Double,
    val satelliteCount: Int,
    val accuracyMeters: Double?,
    val provider: String?,
    val elapsedRealtimeNanos: Long?,
    val epochMillis: Long? = null,
)

data class SensorTracePoint(
    val index: Long,
    val elapsedSeconds: Double,
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,
)

data class VtaTrace(
    val sourceName: String,
    val gpsPoints: List<GpsTracePoint>,
    val sensorPoints: List<SensorTracePoint>,
) {
    val maxSpeedKmh: Double = gpsPoints.maxOfOrNull { it.speedKmh } ?: 0.0
    val averageSpeedKmh: Double = gpsPoints.map { it.speedKmh }.filter { it > 0.0 }.averageOrZero()
    val maxAltitudeMeters: Double = gpsPoints.maxOfOrNull { it.altitudeMeters } ?: 0.0
    val averageAccuracyMeters: Double? = gpsPoints.mapNotNull { it.accuracyMeters }.averageOrNull()
    val latestGps: GpsTracePoint? = gpsPoints.lastOrNull()
    val pointCount: Int = gpsPoints.size
    val sensorCount: Int = sensorPoints.size
}

data class SessionVisualization(
    val session: RecordingSession,
    val trace: VtaTrace,
)

object VtaLogParser {
    fun parse(file: File): VtaTrace {
        if (!file.isFile) {
            return VtaTrace(file.name, emptyList(), emptyList())
        }
        return parse(file.name, file.readLines())
    }

    fun parse(sourceName: String, lines: List<String>): VtaTrace {
        val gpsPoints = mutableListOf<GpsTracePoint>()
        val sensorPoints = mutableListOf<SensorTracePoint>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("$") -> parseGps(line)?.let(gpsPoints::add)
                line.startsWith("#") -> parseSensor(line)?.let(sensorPoints::add)
            }
        }
        return VtaTrace(sourceName, gpsPoints, sensorPoints)
    }

    private fun parseGps(line: String): GpsTracePoint? {
        val parts = line.removePrefix("$").split(",")
        if (parts.size < 8) return null
        return GpsTracePoint(
            date = parts[0],
            time = parts[1],
            latitude = parts[2].toDoubleOrNull() ?: return null,
            longitude = parts[3].toDoubleOrNull() ?: return null,
            altitudeMeters = parts[4].toDoubleOrNull() ?: 0.0,
            speedKmh = parts[5].toDoubleOrNull() ?: 0.0,
            bearingDegrees = parts[6].toDoubleOrNull() ?: 0.0,
            satelliteCount = parts[7].toIntOrNull() ?: 0,
            accuracyMeters = parts.getOrNull(8)?.toDoubleOrNull(),
            provider = parts.getOrNull(9)?.takeIf { it.isNotBlank() },
            elapsedRealtimeNanos = parts.getOrNull(10)?.toLongOrNull(),
            epochMillis = parseUtcMillis(parts[0], parts[1]),
        )
    }

    private fun parseSensor(line: String): SensorTracePoint? {
        val parts = line.removePrefix("#").split(",")
        if (parts.size < 9) return null
        return SensorTracePoint(
            index = parts[0].toLongOrNull() ?: return null,
            elapsedSeconds = parts[1].toDoubleOrNull() ?: 0.0,
            accelX = parts[6].toDoubleOrNull() ?: 0.0,
            accelY = parts[7].toDoubleOrNull() ?: 0.0,
            accelZ = parts[8].toDoubleOrNull() ?: 0.0,
        )
    }
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun parseUtcMillis(date: String, time: String): Long? {
    return runCatching {
        UTC_TRACE_TIME_FORMAT.get()?.parse("$date $time")?.time
    }.getOrNull()
}

private val UTC_TRACE_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat {
        return SimpleDateFormat("ddMMyyyy HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }
}
