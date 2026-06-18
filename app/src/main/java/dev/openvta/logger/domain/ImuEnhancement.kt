package dev.openvta.logger.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ImuEnhancementAlgorithm {
    RawGps,
    Linear,
    Hermite,
    ComplementaryHeading,
    DeadReckoningBlend,
}

data class ImuEnhancementPreset(
    val id: String,
    val displayName: String,
    val algorithm: ImuEnhancementAlgorithm,
    val outputHz: Int,
    val description: String,
) {
    val isRaw: Boolean = algorithm == ImuEnhancementAlgorithm.RawGps
}

object ImuEnhancementPresets {
    const val DEFAULT_ID = "raw_gps"

    val all: List<ImuEnhancementPreset> = listOf(
        ImuEnhancementPreset(
            id = DEFAULT_ID,
            displayName = "Raw GPS",
            algorithm = ImuEnhancementAlgorithm.RawGps,
            outputHz = 1,
            description = "Use only hardware GPS fixes. This is the compatibility baseline.",
        ),
        ImuEnhancementPreset(
            id = "linear_5hz",
            displayName = "Linear 5Hz",
            algorithm = ImuEnhancementAlgorithm.Linear,
            outputHz = 5,
            description = "Time-aligned interpolation between GPS fixes for conservative smoothing.",
        ),
        ImuEnhancementPreset(
            id = "hermite_10hz",
            displayName = "Hermite 10Hz",
            algorithm = ImuEnhancementAlgorithm.Hermite,
            outputHz = 10,
            description = "Cubic interpolation constrained by GPS speed and bearing.",
        ),
        ImuEnhancementPreset(
            id = "imu_heading_10hz",
            displayName = "IMU heading 10Hz",
            algorithm = ImuEnhancementAlgorithm.ComplementaryHeading,
            outputHz = 10,
            description = "Blends GPS-constrained interpolation with gyro/rotation-vector heading hints.",
        ),
        ImuEnhancementPreset(
            id = "imu_deadreckon_10hz",
            displayName = "IMU dead reckon 10Hz",
            algorithm = ImuEnhancementAlgorithm.DeadReckoningBlend,
            outputHz = 10,
            description = "Short-window inertial projection blended back to the next GPS fix to bound drift.",
        ),
    )

    fun find(id: String?): ImuEnhancementPreset = all.firstOrNull { it.id == id } ?: all.first()
}

object VtaTraceEnhancer {
    fun enhance(trace: VtaTrace, presetId: String?): VtaTrace {
        val preset = ImuEnhancementPresets.find(presetId)
        if (preset.isRaw || trace.gpsPoints.size < 2 || trace.enhancedGpsPoints.isNotEmpty()) {
            return trace.copy(enhancementPresetId = preset.id)
        }
        val enhanced = enhanceDerivedPoints(trace.gpsPoints, trace.sensorPoints, preset)
        return trace.copy(
            enhancedGpsPoints = enhanced,
            enhancementPresetId = preset.id,
        )
    }

    fun enhanceRawPoints(
        rawPoints: List<GpsTracePoint>,
        sensors: List<SensorTracePoint>,
        preset: ImuEnhancementPreset,
    ): List<GpsTracePoint> {
        if (preset.isRaw || rawPoints.size < 2) return rawPoints
        return (rawPoints.map { it.asRawPoint() } + enhanceDerivedPoints(rawPoints, sensors, preset))
            .sortedBy { it.timelineNanosOrFallback() }
    }

    fun enhanceDerivedPoints(
        rawPoints: List<GpsTracePoint>,
        sensors: List<SensorTracePoint>,
        preset: ImuEnhancementPreset,
    ): List<GpsTracePoint> {
        if (preset.isRaw || rawPoints.size < 2) return emptyList()
        val result = mutableListOf<GpsTracePoint>()
        val sorted = rawPoints.sortedBy { it.timelineNanosOrFallback() }
        sorted.zipWithNext().forEachIndexed { intervalIndex, (start, end) ->
            result.addAll(enhanceInterval(start, end, sensors, preset, intervalIndex))
        }
        return result
    }

    fun enhanceInterval(
        start: GpsTracePoint,
        end: GpsTracePoint,
        sensors: List<SensorTracePoint>,
        preset: ImuEnhancementPreset,
        rawStartIndex: Int? = null,
    ): List<GpsTracePoint> {
        if (preset.isRaw) return emptyList()
        return intervalSamples(start, end, preset.outputHz)
            .filter { it > 0.0 && it < 1.0 }
            .map { fraction ->
                interpolate(start, end, sensors, preset, fraction)
                    .copy(derivedFromRawIndex = rawStartIndex)
                    .withUtcSecondFromEpoch()
            }
    }

    private fun interpolate(
        start: GpsTracePoint,
        end: GpsTracePoint,
        sensors: List<SensorTracePoint>,
        preset: ImuEnhancementPreset,
        fraction: Double,
    ): GpsTracePoint {
        if (fraction >= 1.0) return end.asRawPoint()
        return when (preset.algorithm) {
            ImuEnhancementAlgorithm.RawGps -> end.asRawPoint()
            ImuEnhancementAlgorithm.Linear -> linearPoint(start, end, fraction, TracePointSource.LinearInterpolation)
            ImuEnhancementAlgorithm.Hermite -> hermitePoint(start, end, fraction, TracePointSource.HermiteInterpolation)
            ImuEnhancementAlgorithm.ComplementaryHeading -> complementaryHeadingPoint(start, end, sensors, fraction)
            ImuEnhancementAlgorithm.DeadReckoningBlend -> deadReckoningBlendPoint(start, end, sensors, fraction)
        }
    }

    private fun intervalSamples(start: GpsTracePoint, end: GpsTracePoint, outputHz: Int): List<Double> {
        val startNanos = start.timelineNanosOrFallback()
        val endNanos = end.timelineNanosOrFallback()
        val durationSeconds = ((endNanos - startNanos).coerceAtLeast(1L)) / 1_000_000_000.0
        val steps = max(1, (durationSeconds * outputHz).roundToInt())
        return (0..steps).map { it.toDouble() / steps }
    }

    private fun linearPoint(
        start: GpsTracePoint,
        end: GpsTracePoint,
        fraction: Double,
        source: TracePointSource,
    ): GpsTracePoint {
        return start.copy(
            date = start.date,
            time = start.time,
            latitude = lerp(start.latitude, end.latitude, fraction),
            longitude = lerp(start.longitude, end.longitude, fraction),
            altitudeMeters = lerp(start.altitudeMeters, end.altitudeMeters, fraction),
            speedKmh = lerp(start.speedKmh, end.speedKmh, fraction),
            bearingDegrees = interpolateAngle(start.bearingDegrees, end.bearingDegrees, fraction),
            satelliteCount = end.satelliteCount,
            accuracyMeters = blendAccuracy(start.accuracyMeters, end.accuracyMeters, fraction),
            provider = start.provider ?: end.provider,
            elapsedRealtimeNanos = lerpLong(start.elapsedRealtimeNanos, end.elapsedRealtimeNanos, fraction),
            epochMillis = lerpLong(start.epochMillis, end.epochMillis, fraction),
            source = source,
            confidence = confidenceBetween(start, end, fraction),
            derivedFromRawIndex = null,
        )
    }

    private fun hermitePoint(
        start: GpsTracePoint,
        end: GpsTracePoint,
        fraction: Double,
        source: TracePointSource,
    ): GpsTracePoint {
        val frame = LocalFrame.from(start, end)
        val durationSeconds = durationSeconds(start, end)
        val startVelocity = velocityMetersPerSecond(start)
        val endVelocity = velocityMetersPerSecond(end)
        val p0x = 0.0
        val p0y = 0.0
        val p1x = frame.toLocalX(end)
        val p1y = frame.toLocalY(end)
        val h00 = 2 * fraction * fraction * fraction - 3 * fraction * fraction + 1
        val h10 = fraction * fraction * fraction - 2 * fraction * fraction + fraction
        val h01 = -2 * fraction * fraction * fraction + 3 * fraction * fraction
        val h11 = fraction * fraction * fraction - fraction * fraction
        val x = h00 * p0x + h10 * startVelocity.first * durationSeconds + h01 * p1x + h11 * endVelocity.first * durationSeconds
        val y = h00 * p0y + h10 * startVelocity.second * durationSeconds + h01 * p1y + h11 * endVelocity.second * durationSeconds
        val latLon = frame.toLatLon(x, y)
        return linearPoint(start, end, fraction, source).copy(
            latitude = latLon.first,
            longitude = latLon.second,
        )
    }

    private fun complementaryHeadingPoint(
        start: GpsTracePoint,
        end: GpsTracePoint,
        sensors: List<SensorTracePoint>,
        fraction: Double,
    ): GpsTracePoint {
        val base = hermitePoint(start, end, fraction, TracePointSource.ImuHeading)
        val sensorHeading = nearestSensorHeading(sensors, base.timelineNanosOrFallback())
        val heading = sensorHeading ?: base.bearingDegrees
        val projected = projectFrom(
            start = start,
            headingDegrees = blendAngles(base.bearingDegrees, heading, 0.35),
            distanceMeters = lerp(start.speedKmh, end.speedKmh, fraction).coerceAtLeast(0.0) / 3.6 * durationSeconds(start, base),
        )
        return base.copy(
            latitude = lerp(base.latitude, projected.first, 0.25),
            longitude = lerp(base.longitude, projected.second, 0.25),
            bearingDegrees = blendAngles(base.bearingDegrees, heading, 0.35),
            confidence = base.confidence * if (sensorHeading == null) 0.85 else 0.95,
        )
    }

    private fun deadReckoningBlendPoint(
        start: GpsTracePoint,
        end: GpsTracePoint,
        sensors: List<SensorTracePoint>,
        fraction: Double,
    ): GpsTracePoint {
        val base = hermitePoint(start, end, fraction, TracePointSource.ImuDeadReckoning)
        val targetNanos = base.timelineNanosOrFallback()
        val startNanos = start.timelineNanosOrFallback()
        val heading = nearestSensorHeading(sensors, targetNanos) ?: base.bearingDegrees
        val acceleration = averageForwardAcceleration(sensors, startNanos, targetNanos)
        val elapsedSeconds = durationSeconds(start, base)
        val startSpeed = start.speedKmh / 3.6
        val boundedAccel = acceleration.coerceIn(-3.0, 3.0)
        val distance = (startSpeed * elapsedSeconds + 0.5 * boundedAccel * elapsedSeconds * elapsedSeconds).coerceAtLeast(0.0)
        val projected = projectFrom(start, heading, distance)
        val driftBlend = (0.35 * (1.0 - abs(fraction - 0.5))).coerceIn(0.10, 0.35)
        return base.copy(
            latitude = lerp(base.latitude, projected.first, driftBlend),
            longitude = lerp(base.longitude, projected.second, driftBlend),
            bearingDegrees = blendAngles(base.bearingDegrees, heading, 0.35),
            speedKmh = ((startSpeed + boundedAccel * elapsedSeconds).coerceAtLeast(0.0) * 3.6),
            confidence = base.confidence * 0.80,
        )
    }

    private fun nearestSensorHeading(sensors: List<SensorTracePoint>, targetNanos: Long): Double? {
        return sensors
            .filter { it.timestampNanos != null }
            .minByOrNull { abs((it.timestampNanos ?: 0L) - targetNanos) }
            ?.headingDegrees()
    }

    private fun averageForwardAcceleration(sensors: List<SensorTracePoint>, startNanos: Long, endNanos: Long): Double {
        val values = sensors.filter { sensor ->
            val timestamp = sensor.timestampNanos ?: return@filter false
            timestamp in startNanos..endNanos
        }
        if (values.isEmpty()) return 0.0
        return values.map { sensor ->
            val horizontalMagnitude = hypot(sensor.accelX, sensor.accelY)
            val verticalAdjusted = max(0.0, sqrt(sensor.accelX * sensor.accelX + sensor.accelY * sensor.accelY + sensor.accelZ * sensor.accelZ) - 9.80665)
            if (horizontalMagnitude > 0.05) horizontalMagnitude.coerceAtMost(4.0) else verticalAdjusted.coerceAtMost(4.0)
        }.averageOrZero()
    }

    private fun GpsTracePoint.asRawPoint(): GpsTracePoint = copy(
        source = TracePointSource.RawGps,
        confidence = 1.0,
        derivedFromRawIndex = null,
    )

    private fun SensorTracePoint.headingDegrees(): Double? {
        val rotationHeading = rotationAzimuthDegrees?.normalizeDegrees()
        if (rotationHeading != null && rotationHeading.isFinite()) return rotationHeading
        val orientationHeading = orientationZDegrees?.normalizeDegrees()
        if (orientationHeading != null && orientationHeading.isFinite()) return orientationHeading
        return null
    }

    private fun durationSeconds(start: GpsTracePoint, end: GpsTracePoint): Double {
        return ((end.timelineNanosOrFallback() - start.timelineNanosOrFallback()).coerceAtLeast(1L)) / 1_000_000_000.0
    }

    private fun velocityMetersPerSecond(point: GpsTracePoint): Pair<Double, Double> {
        val speed = point.speedKmh / 3.6
        val bearingRadians = point.bearingDegrees.toRadians()
        return speed * sin(bearingRadians) to speed * cos(bearingRadians)
    }

    private fun projectFrom(start: GpsTracePoint, headingDegrees: Double, distanceMeters: Double): Pair<Double, Double> {
        val frame = LocalFrame.from(start, start.copy(latitude = start.latitude + 0.0001))
        val headingRadians = headingDegrees.toRadians()
        val x = distanceMeters * sin(headingRadians)
        val y = distanceMeters * cos(headingRadians)
        return frame.toLatLon(x, y)
    }

    private fun GpsTracePoint.withUtcSecondFromEpoch(): GpsTracePoint {
        val millis = epochMillis ?: return this
        val date = Date(millis)
        return copy(
            date = UTC_DATE_FORMAT.get()!!.format(date),
            time = UTC_TIME_FORMAT.get()!!.format(date),
        )
    }

    private fun blendAccuracy(start: Double?, end: Double?, fraction: Double): Double? = when {
        start != null && end != null -> lerp(start, end, fraction)
        start != null -> start
        else -> end
    }

    private fun confidenceBetween(start: GpsTracePoint, end: GpsTracePoint, fraction: Double): Double {
        val accuracyPenalty = listOfNotNull(start.accuracyMeters, end.accuracyMeters).averageOrNull()
            ?.let { (it / 50.0).coerceIn(0.0, 0.35) }
            ?: 0.15
        val midpointPenalty = (1.0 - abs(fraction - 0.5) * 2.0) * 0.15
        return (1.0 - accuracyPenalty - midpointPenalty).coerceIn(0.45, 1.0)
    }

    private data class LocalFrame(
        val originLatitude: Double,
        val originLongitude: Double,
        val metersPerDegreeLatitude: Double,
        val metersPerDegreeLongitude: Double,
    ) {
        fun toLocalX(point: GpsTracePoint): Double = (point.longitude - originLongitude) * metersPerDegreeLongitude
        fun toLocalY(point: GpsTracePoint): Double = (point.latitude - originLatitude) * metersPerDegreeLatitude
        fun toLatLon(xMeters: Double, yMeters: Double): Pair<Double, Double> {
            return originLatitude + yMeters / metersPerDegreeLatitude to originLongitude + xMeters / metersPerDegreeLongitude
        }

        companion object {
            fun from(start: GpsTracePoint, end: GpsTracePoint): LocalFrame {
                val averageLatitude = ((start.latitude + end.latitude) / 2.0).toRadians()
                return LocalFrame(
                    originLatitude = start.latitude,
                    originLongitude = start.longitude,
                    metersPerDegreeLatitude = 111_320.0,
                    metersPerDegreeLongitude = max(1.0, 111_320.0 * cos(averageLatitude)),
                )
            }
        }
    }
}

private val UTC_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat {
        return SimpleDateFormat("ddMMyyyy", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }
}

private val UTC_TIME_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat {
        return SimpleDateFormat("HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
    }
}

enum class TracePointSource(val label: String) {
    RawGps("GPS"),
    LinearInterpolation("Linear"),
    HermiteInterpolation("Hermite"),
    ImuHeading("IMU heading"),
    ImuDeadReckoning("IMU dead reckoning");

    companion object {
        fun fromStoredName(value: String?): TracePointSource {
            return entries.firstOrNull { it.name == value || it.label == value } ?: LinearInterpolation
        }
    }
}

private fun lerp(start: Double, end: Double, fraction: Double): Double = start + (end - start) * fraction

private fun lerpLong(start: Long?, end: Long?, fraction: Double): Long? = when {
    start != null && end != null -> (start.toDouble() + (end - start).toDouble() * fraction).roundToLong()
    start != null -> start
    else -> end
}

private fun Double.roundToLong(): Long = if (this >= 0) {
    (this + 0.5).toLong()
} else {
    (this - 0.5).toLong()
}

private fun Double.toRadians(): Double = this / 180.0 * PI

private fun Double.normalizeDegrees(): Double {
    val mod = this % 360.0
    return if (mod < 0) mod + 360.0 else mod
}

private fun interpolateAngle(start: Double, end: Double, fraction: Double): Double {
    val delta = ((end - start + 540.0) % 360.0) - 180.0
    return (start + delta * fraction).normalizeDegrees()
}

private fun blendAngles(start: Double, end: Double, weight: Double): Double = interpolateAngle(start, end, weight)

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
