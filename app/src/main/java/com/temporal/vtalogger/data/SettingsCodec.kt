package com.temporal.vtalogger.data

import com.temporal.vtalogger.domain.AppSettings
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

object SettingsCodec {
    fun encode(settings: AppSettings): String {
        val properties = Properties()
        properties.setProperty("driverId", settings.driverId)
        properties.setProperty("ftpHost", settings.ftpHost)
        properties.setProperty("ftpPort", settings.ftpPort.toString())
        properties.setProperty("ftpUser", settings.ftpUser)
        properties.setProperty("ftpPassword", settings.ftpPassword)
        properties.setProperty("passiveMode", settings.passiveMode.toString())
        properties.setProperty("keepLocalFiles", settings.keepLocalFiles.toString())
        properties.setProperty("darkMode", settings.darkMode.toString())
        return StringWriter().use { writer ->
            properties.store(writer, "VTA Logger encrypted settings")
            writer.toString()
        }
    }

    fun decode(payload: String): AppSettings {
        val properties = Properties()
        properties.load(StringReader(payload))
        return AppSettings(
            driverId = properties.getProperty("driverId", "CC00"),
            ftpHost = properties.getProperty("ftpHost", ""),
            ftpPort = properties.getProperty("ftpPort", "21").toIntOrNull()?.coerceIn(1, 65535) ?: 21,
            ftpUser = properties.getProperty("ftpUser", ""),
            ftpPassword = properties.getProperty("ftpPassword", ""),
            passiveMode = properties.getProperty("passiveMode", "true").toBooleanStrictOrNull() ?: true,
            keepLocalFiles = properties.getProperty("keepLocalFiles", "true").toBooleanStrictOrNull() ?: true,
            darkMode = properties.getProperty("darkMode", "false").toBooleanStrictOrNull() ?: false,
        )
    }
}
