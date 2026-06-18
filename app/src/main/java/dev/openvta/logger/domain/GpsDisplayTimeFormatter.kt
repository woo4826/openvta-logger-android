package dev.openvta.logger.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object GpsDisplayTimeFormatter {
    private val displayFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

    fun format(
        point: GpsTracePoint,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val epochMillis = point.epochMillis ?: return "${point.date} ${point.time}"
        val displayTime = Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .format(displayFormatter)
        return "$displayTime (${relative(epochMillis, nowMillis)})"
    }

    private fun relative(epochMillis: Long, nowMillis: Long): String {
        val deltaMillis = nowMillis - epochMillis
        val future = deltaMillis < 0
        val absoluteSeconds = abs(deltaMillis) / 1000
        val value = when {
            absoluteSeconds < 60 -> return if (future) "just now" else "just now"
            absoluteSeconds < 3_600 -> "${absoluteSeconds / 60}min"
            absoluteSeconds < 86_400 -> "${absoluteSeconds / 3_600}h"
            else -> "${absoluteSeconds / 86_400}d"
        }
        return if (future) "in $value" else "$value ago"
    }
}
