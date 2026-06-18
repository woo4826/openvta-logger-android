package dev.openvta.logger.upload

import dev.openvta.logger.domain.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

class FtpUploadClientTest {
    @Test
    fun blankFtpHostFailsBeforeNetworkConnection() {
        val file = File(createTempDirectory(prefix = "ftp-client-test").toFile(), "sample.Zip")
            .apply { writeText("payload") }

        val error = assertThrows(IllegalArgumentException::class.java) {
            FtpUploadClient().upload(AppSettings(ftpUser = "user"), file)
        }

        assertEquals("FTP host is not configured", error.message)
    }

    @Test
    fun uploadsFileToPassiveBinaryFtpServer() {
        val payload = "vta zip payload".toByteArray()
        val file = File(createTempDirectory(prefix = "ftp-client-test").toFile(), "sample.Zip")
            .apply { writeBytes(payload) }
        val server = FakePassiveFtpServer()

        server.use {
            FtpUploadClient().upload(
                AppSettings(
                    ftpHost = "127.0.0.1",
                    ftpPort = server.port,
                    ftpUser = "driver",
                    ftpPassword = "secret",
                    passiveMode = true,
                ),
                file,
            )
        }

        assertArrayEquals(payload, server.uploadedBytes())
        assertEquals(
            listOf("USER driver", "PASS secret", "TYPE I", "PASV", "STOR sample.Zip", "QUIT"),
            server.commands(),
        )
    }

    private class FakePassiveFtpServer : AutoCloseable {
        private val control = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val commands = LinkedBlockingQueue<String>()
        private val uploads = LinkedBlockingQueue<ByteArray>()
        private val worker = thread(name = "fake-passive-ftp-server") {
            control.soTimeout = 10_000
            control.accept().use { socket ->
                handleControl(socket)
            }
        }

        val port: Int = control.localPort

        fun commands(): List<String> {
            worker.join(5_000)
            return generateSequence { commands.poll() }.toList()
        }

        fun uploadedBytes(): ByteArray = uploads.poll(5, TimeUnit.SECONDS) ?: ByteArray(0)

        override fun close() {
            runCatching { control.close() }
            worker.join(5_000)
        }

        private fun handleControl(socket: Socket) {
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.send("220 fake ftp ready")

            var dataSocket: ServerSocket? = null
            while (true) {
                val command = reader.readLine() ?: break
                commands.add(command)
                when {
                    command.startsWith("USER ") -> writer.send("331 password required")
                    command.startsWith("PASS ") -> writer.send("230 logged in")
                    command == "TYPE I" -> writer.send("200 binary type set")
                    command == "PASV" -> {
                        dataSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        val p1 = dataSocket.localPort / 256
                        val p2 = dataSocket.localPort % 256
                        writer.send("227 entering passive mode (127,0,0,1,$p1,$p2)")
                    }
                    command.startsWith("STOR ") -> {
                        val passiveSocket = requireNotNull(dataSocket) { "PASV required before STOR" }
                        writer.send("150 opening data connection")
                        passiveSocket.use { server ->
                            server.soTimeout = 10_000
                            server.accept().use { data ->
                                uploads.add(data.getInputStream().readBytes())
                            }
                        }
                        writer.send("226 transfer complete")
                    }
                    command == "QUIT" -> {
                        writer.send("221 goodbye")
                        return
                    }
                    else -> writer.send("200 ok")
                }
            }
        }

        private fun PrintWriter.send(line: String) {
            print("$line\r\n")
            flush()
        }
    }
}
