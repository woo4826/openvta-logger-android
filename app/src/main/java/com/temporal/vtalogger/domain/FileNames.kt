package com.temporal.vtalogger.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object FileNames {
    private val unsafeChars = Regex("[^A-Za-z0-9_-]")

    fun sanitizeDriverId(driverId: String): String {
        val sanitized = driverId.trim().replace(unsafeChars, "_").trim('_')
        return sanitized.ifEmpty { "UNKNOWN" }.take(32)
    }

    fun sessionBaseName(
        startedAtMillis: Long,
        driverId: String,
        prefix: String = "VTA",
        timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
    ): String {
        val dateFormat = SimpleDateFormat("ddMMyyyy_HHmmss", Locale.US).apply { this.timeZone = timeZone }
        return prefix + dateFormat.format(Date(startedAtMillis)) + "_" + sanitizeDriverId(driverId)
    }
}
