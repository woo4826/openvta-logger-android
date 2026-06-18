package com.temporal.vtalogger.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class VtaFormatter(
    private val appVersion: String,
    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
) {
    private val dateFormat = SimpleDateFormat("ddMMyyyy", Locale.US).apply { timeZone = this@VtaFormatter.timeZone }
    private val timeFormat = SimpleDateFormat("HHmmss", Locale.US).apply { timeZone = this@VtaFormatter.timeZone }
    private val symbols = DecimalFormatSymbols(Locale.US)
    private val nineDecimals = DecimalFormat("0.000000000", symbols)
    private val threeDecimals = DecimalFormat("0.000", symbols)
    private val twoDecimals = DecimalFormat("0.00", symbols)
    private val noDecimals = DecimalFormat("0", symbols)

    fun header(): String = buildString {
        appendLine("%% VTALogger Kotlin Version: $appVersion")
        appendLine("%% FormatVersion: 3")
        appendLine("% \$UTCDate,UTCTime,Latitude,Longitude,Altitude,Speed,Bearing,NumSat,AccuracyMeters,Provider,ElapsedRealtimeNanos")
        appendLine("% @UTCDate,UTCTime,Latitude,Longitude,Altitude,Speed,Bearing,NumSat,AccuracyMeters,Provider,ElapsedRealtimeNanos,Source,Confidence,ImuPresetId,DerivedFromRawIndex")
        appendLine("% #Idx,Time,Event,OX,OY,OZ,GX,GY,GZ,SensorTimestampNanos,SensorAccuracy,GyroX,GyroY,GyroZ,RotAzimuth,RotPitch,RotRoll")
    }

    fun imuPresetHeader(presetId: String): String = "%% ImuPresetId: ${ImuEnhancementPresets.find(presetId).id}"

    fun footer(): String = "%% End"

    fun formatGps(sample: GpsSample): String {
        val date = Date(sample.timeMillis)
        val speedKmh = sample.speedMetersPerSecond.toDouble() * 3.6
        return listOf(
            "$" + dateFormat.format(date),
            timeFormat.format(date),
            nineDecimals.format(sample.latitude),
            nineDecimals.format(sample.longitude),
            noDecimals.format(sample.altitudeMeters),
            noDecimals.format(speedKmh),
            noDecimals.format(sample.bearingDegrees.toDouble()),
            noDecimals.format(sample.satelliteCount),
            sample.accuracyMeters?.let { twoDecimals.format(it.toDouble()) } ?: "",
            sample.provider,
            sample.elapsedRealtimeNanos.toString(),
        ).joinToString(",")
    }

    fun formatSensor(sample: SensorSample): String {
        return listOf(
            "#" + sample.index,
            threeDecimals.format(sample.elapsedSeconds),
            sample.eventCode.toString(),
            threeDecimals.format(sample.snapshot.orientationX()),
            threeDecimals.format(sample.snapshot.orientationY()),
            threeDecimals.format(sample.snapshot.orientationZ()),
            threeDecimals.format(sample.accelX()),
            threeDecimals.format(sample.accelY()),
            threeDecimals.format(sample.accelZ()),
            sample.sensorTimestampNanos.toString(),
            sample.sensorAccuracy.toString(),
            threeDecimals.format(sample.snapshot.gyroX()),
            threeDecimals.format(sample.snapshot.gyroY()),
            threeDecimals.format(sample.snapshot.gyroZ()),
            threeDecimals.format(sample.snapshot.rotationAzimuth()),
            threeDecimals.format(sample.snapshot.rotationPitch()),
            threeDecimals.format(sample.snapshot.rotationRoll()),
        ).joinToString(",")
    }

    fun formatEnhancedGps(point: GpsTracePoint, presetId: String): String {
        return listOf(
            "@" + point.date,
            point.time,
            nineDecimals.format(point.latitude),
            nineDecimals.format(point.longitude),
            noDecimals.format(point.altitudeMeters),
            noDecimals.format(point.speedKmh),
            noDecimals.format(point.bearingDegrees),
            noDecimals.format(point.satelliteCount),
            point.accuracyMeters?.let { twoDecimals.format(it) } ?: "",
            point.provider.orEmpty(),
            point.elapsedRealtimeNanos?.toString().orEmpty(),
            point.source.name,
            threeDecimals.format(point.confidence.coerceIn(0.0, 1.0)),
            ImuEnhancementPresets.find(presetId).id,
            point.derivedFromRawIndex?.toString().orEmpty(),
        ).joinToString(",")
    }
}
