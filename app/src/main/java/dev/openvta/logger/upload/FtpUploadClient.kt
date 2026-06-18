package dev.openvta.logger.upload

import dev.openvta.logger.domain.AppSettings
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileInputStream
import java.time.Duration

class FtpUploadClient(
    private val connectTimeoutMillis: Int = 10_000,
    private val defaultTimeoutMillis: Int = 10_000,
    private val dataTimeoutMillis: Int = 30_000,
) {
    fun upload(settings: AppSettings, file: File) {
        require(settings.ftpHost.isNotBlank()) { "FTP host is not configured" }
        require(settings.ftpUser.isNotBlank()) { "FTP user is not configured" }
        require(file.isFile) { "Upload file does not exist: ${file.absolutePath}" }

        val client = FTPClient().apply {
            connectTimeout = connectTimeoutMillis
            defaultTimeout = defaultTimeoutMillis
            setDataTimeout(Duration.ofMillis(dataTimeoutMillis.toLong()))
        }
        try {
            client.connect(settings.ftpHost, settings.ftpPort)
            if (settings.passiveMode) {
                client.enterLocalPassiveMode()
            } else {
                client.enterLocalActiveMode()
            }
            check(client.login(settings.ftpUser, settings.ftpPassword)) {
                "FTP login failed: ${client.replyString}"
            }
            check(client.setFileType(FTP.BINARY_FILE_TYPE)) {
                "FTP binary mode failed: ${client.replyString}"
            }
            FileInputStream(file).use { input ->
                check(client.storeFile(file.name, input)) {
                    "FTP upload failed: ${client.replyString}"
                }
            }
        } finally {
            runCatching {
                if (client.isConnected) {
                    client.logout()
                    client.disconnect()
                }
            }
        }
    }
}
