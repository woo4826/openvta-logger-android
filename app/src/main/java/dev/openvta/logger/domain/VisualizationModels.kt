package dev.openvta.logger.domain

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
    val source: TracePointSource = TracePointSource.RawGps,
    val confidence: Double = 1.0,
    val derivedFromRawIndex: Int? = null,
)

data class SensorTracePoint(
    val index: Long,
    val elapsedSeconds: Double,
    val orientationXDegrees: Double? = null,
    val orientationYDegrees: Double? = null,
    val orientationZDegrees: Double? = null,
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,
    val timestampNanos: Long? = null,
    val accuracy: Int? = null,
    val gyroXRadPerSecond: Double? = null,
    val gyroYRadPerSecond: Double? = null,
    val gyroZRadPerSecond: Double? = null,
    val rotationAzimuthDegrees: Double? = null,
    val rotationPitchDegrees: Double? = null,
    val rotationRollDegrees: Double? = null,
)

data class VtaTrace(
    val sourceName: String,
    val gpsPoints: List<GpsTracePoint>,
    val sensorPoints: List<SensorTracePoint>,
    val enhancedGpsPoints: List<GpsTracePoint> = emptyList(),
    val enhancementPresetId: String = ImuEnhancementPresets.DEFAULT_ID,
) {
    val displayGpsPoints: List<GpsTracePoint> = (gpsPoints + enhancedGpsPoints).sortedWith(
        compareBy<GpsTracePoint>(
            { it.epochMillis ?: Long.MAX_VALUE },
            { it.elapsedRealtimeNanos ?: Long.MAX_VALUE },
            { it.source.ordinal },
        ),
    )
    val maxSpeedKmh: Double = displayGpsPoints.maxOfOrNull { it.speedKmh } ?: 0.0
    val averageSpeedKmh: Double = displayGpsPoints.map { it.speedKmh }.filter { it > 0.0 }.averageOrZero()
    val maxAltitudeMeters: Double = displayGpsPoints.maxOfOrNull { it.altitudeMeters } ?: 0.0
    val averageAccuracyMeters: Double? = displayGpsPoints.mapNotNull { it.accuracyMeters }.averageOrNull()
    val latestGps: GpsTracePoint? = displayGpsPoints.lastOrNull()
    val latestRawGps: GpsTracePoint? = gpsPoints.lastOrNull()
    val pointCount: Int = displayGpsPoints.size
    val rawGpsCount: Int = gpsPoints.size
    val enhancedPointCount: Int = enhancedGpsPoints.size
    val sensorCount: Int = sensorPoints.size
    val enhancementPreset: ImuEnhancementPreset = ImuEnhancementPresets.find(enhancementPresetId)
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
        val enhancedGpsPoints = mutableListOf<GpsTracePoint>()
        val sensorPoints = mutableListOf<SensorTracePoint>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("$") -> parseGps(line)?.let(gpsPoints::add)
                line.startsWith("@") -> parseEnhancedGps(line)?.let(enhancedGpsPoints::add)
                line.startsWith("#") -> parseSensor(line)?.let(sensorPoints::add)
            }
        }
        val presetId = lines.firstOrNull { it.startsWith("%% ImuPresetId:") }
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: lines.asSequence()
                .map { it.trim() }
                .filter { it.startsWith("@") }
                .mapNotNull { it.removePrefix("@").split(",").getOrNull(13)?.takeIf(String::isNotBlank) }
                .firstOrNull()
        return VtaTrace(
            sourceName = sourceName,
            gpsPoints = gpsPoints,
            sensorPoints = sensorPoints,
            enhancedGpsPoints = enhancedGpsPoints,
            enhancementPresetId = ImuEnhancementPresets.find(presetId).id,
        )
    }

    private fun parseGps(line: String): GpsTracePoint? {
        val parts = line.removePrefix("$").split(",")
        if (parts.size < 8) return null
        return parseGpsParts(parts, TracePointSource.RawGps, confidence = 1.0, derivedFromRawIndex = null)
    }

    private fun parseEnhancedGps(line: String): GpsTracePoint? {
        val parts = line.removePrefix("@").split(",")
        if (parts.size < 14) return null
        return parseGpsParts(
            parts = parts,
            source = TracePointSource.fromStoredName(parts.getOrNull(11)),
            confidence = parts.getOrNull(12)?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.75,
            derivedFromRawIndex = parts.getOrNull(14)?.toIntOrNull(),
        )
    }

    private fun parseGpsParts(
        parts: List<String>,
        source: TracePointSource,
        confidence: Double,
        derivedFromRawIndex: Int?,
    ): GpsTracePoint? {
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
            source = source,
            confidence = confidence,
            derivedFromRawIndex = derivedFromRawIndex,
        )
    }

    private fun parseSensor(line: String): SensorTracePoint? {
        val parts = line.removePrefix("#").split(",")
        if (parts.size < 9) return null
        return SensorTracePoint(
            index = parts[0].toLongOrNull() ?: return null,
            elapsedSeconds = parts[1].toDoubleOrNull() ?: 0.0,
            orientationXDegrees = parts.getOrNull(3)?.toDoubleOrNull(),
            orientationYDegrees = parts.getOrNull(4)?.toDoubleOrNull(),
            orientationZDegrees = parts.getOrNull(5)?.toDoubleOrNull(),
            accelX = parts[6].toDoubleOrNull() ?: 0.0,
            accelY = parts[7].toDoubleOrNull() ?: 0.0,
            accelZ = parts[8].toDoubleOrNull() ?: 0.0,
            timestampNanos = parts.getOrNull(9)?.toLongOrNull(),
            accuracy = parts.getOrNull(10)?.toIntOrNull(),
            gyroXRadPerSecond = parts.getOrNull(11)?.toDoubleOrNull(),
            gyroYRadPerSecond = parts.getOrNull(12)?.toDoubleOrNull(),
            gyroZRadPerSecond = parts.getOrNull(13)?.toDoubleOrNull(),
            rotationAzimuthDegrees = parts.getOrNull(14)?.toDoubleOrNull(),
            rotationPitchDegrees = parts.getOrNull(15)?.toDoubleOrNull(),
            rotationRollDegrees = parts.getOrNull(16)?.toDoubleOrNull(),
        )
    }
}

internal fun GpsTracePoint.timelineNanosOrFallback(): Long {
    elapsedRealtimeNanos?.let { return it }
    epochMillis?.let { return it * 1_000_000L }
    return (time.filter(Char::isDigit).toLongOrNull() ?: 0L) * 1_000_000_000L
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
