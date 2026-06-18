package dev.openvta.logger.data

import dev.openvta.logger.domain.GpsSample
import dev.openvta.logger.domain.GpsTracePoint
import dev.openvta.logger.domain.SensorSample
import dev.openvta.logger.domain.SensorTracePoint
import dev.openvta.logger.domain.VtaTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LiveTraceStore(
    private val maxGpsPoints: Int = 500,
    private val maxEnhancedGpsPoints: Int = 2_000,
    private val maxSensorPoints: Int = 500,
) {
    private val dateFormat = SimpleDateFormat("ddMMyyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val timeFormat = SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val gpsPoints = ArrayDeque<GpsTracePoint>()
    private val enhancedGpsPoints = ArrayDeque<GpsTracePoint>()
    private val sensorPoints = ArrayDeque<SensorTracePoint>()
    private val lock = Any()

    fun clear() {
        synchronized(lock) {
            gpsPoints.clear()
            enhancedGpsPoints.clear()
            sensorPoints.clear()
        }
    }

    fun appendGps(sample: GpsSample): VtaTrace {
        synchronized(lock) {
            val date = Date(sample.timeMillis)
            gpsPoints.addLast(
                GpsTracePoint(
                    date = dateFormat.format(date),
                    time = timeFormat.format(date),
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    altitudeMeters = sample.altitudeMeters,
                    speedKmh = sample.speedMetersPerSecond.toDouble() * 3.6,
                    bearingDegrees = sample.bearingDegrees.toDouble(),
                    satelliteCount = sample.satelliteCount,
                    accuracyMeters = sample.accuracyMeters?.toDouble(),
                    provider = sample.provider,
                    elapsedRealtimeNanos = sample.elapsedRealtimeNanos,
                    epochMillis = sample.timeMillis,
                ),
            )
            trim(gpsPoints, maxGpsPoints)
            return snapshotLocked()
        }
    }

    fun appendEnhancedGps(points: List<GpsTracePoint>): VtaTrace {
        synchronized(lock) {
            points.forEach(enhancedGpsPoints::addLast)
            trim(enhancedGpsPoints, maxEnhancedGpsPoints)
            return snapshotLocked()
        }
    }

    fun appendSensor(sample: SensorSample) {
        synchronized(lock) {
            sensorPoints.addLast(
                SensorTracePoint(
                    index = sample.index,
                    elapsedSeconds = sample.elapsedSeconds,
                    orientationXDegrees = sample.snapshot.orientationX().toDouble(),
                    orientationYDegrees = sample.snapshot.orientationY().toDouble(),
                    orientationZDegrees = sample.snapshot.orientationZ().toDouble(),
                    accelX = sample.accelX().toDouble(),
                    accelY = sample.accelY().toDouble(),
                    accelZ = sample.accelZ().toDouble(),
                    timestampNanos = sample.sensorTimestampNanos,
                    accuracy = sample.sensorAccuracy,
                    gyroXRadPerSecond = sample.snapshot.gyroX().toDouble(),
                    gyroYRadPerSecond = sample.snapshot.gyroY().toDouble(),
                    gyroZRadPerSecond = sample.snapshot.gyroZ().toDouble(),
                    rotationAzimuthDegrees = sample.snapshot.rotationAzimuth().toDouble(),
                    rotationPitchDegrees = sample.snapshot.rotationPitch().toDouble(),
                    rotationRollDegrees = sample.snapshot.rotationRoll().toDouble(),
                ),
            )
            trim(sensorPoints, maxSensorPoints)
        }
    }

    fun snapshot(): VtaTrace = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): VtaTrace = VtaTrace(
        sourceName = "Live recording",
        gpsPoints = gpsPoints.toList(),
        sensorPoints = sensorPoints.toList(),
        enhancedGpsPoints = enhancedGpsPoints.toList(),
    )

    private fun <T> trim(points: ArrayDeque<T>, maxSize: Int) {
        while (points.size > maxSize) {
            points.removeFirst()
        }
    }
}
