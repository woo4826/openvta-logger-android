package com.temporal.vtalogger.data

import com.temporal.vtalogger.domain.GpsSample
import com.temporal.vtalogger.domain.GpsTracePoint
import com.temporal.vtalogger.domain.SensorSample
import com.temporal.vtalogger.domain.SensorTracePoint
import com.temporal.vtalogger.domain.VtaTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LiveTraceStore(
    private val maxGpsPoints: Int = 500,
    private val maxSensorPoints: Int = 500,
) {
    private val dateFormat = SimpleDateFormat("ddMMyyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val timeFormat = SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val gpsPoints = ArrayDeque<GpsTracePoint>()
    private val sensorPoints = ArrayDeque<SensorTracePoint>()
    private val lock = Any()

    fun clear() {
        synchronized(lock) {
            gpsPoints.clear()
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

    fun appendSensor(sample: SensorSample) {
        synchronized(lock) {
            sensorPoints.addLast(
                SensorTracePoint(
                    index = sample.index,
                    elapsedSeconds = sample.elapsedSeconds,
                    accelX = sample.accelX().toDouble(),
                    accelY = sample.accelY().toDouble(),
                    accelZ = sample.accelZ().toDouble(),
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
    )

    private fun <T> trim(points: ArrayDeque<T>, maxSize: Int) {
        while (points.size > maxSize) {
            points.removeFirst()
        }
    }
}
