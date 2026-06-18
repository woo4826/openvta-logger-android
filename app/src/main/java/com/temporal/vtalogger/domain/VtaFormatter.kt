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
        appendLine("%% FormatVersion: 2")
        appendLine("% \$UTCDate,UTCTime,Latitude,Longitude,Altitude,Speed,Bearing,NumSat,AccuracyMeters,Provider,ElapsedRealtimeNanos")
        appendLine("% #Idx,Time,Event,OX,OY,OZ,GX,GY,GZ,SensorTimestampNanos,SensorAccuracy")
    }

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
        ).joinToString(",")
    }
}
