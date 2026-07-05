package dev.openvta.logger.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveCommandClientTest {
    @Test
    fun handlesCommandCreatedWithAckAndResult() {
        val sent = mutableListOf<String>()
        val handled = mutableListOf<LiveDeviceCommand>()
        val result = LiveCommandClient().handleServerMessage(
            raw = """{"event":"command.created","payload":{"id":"id_01","commandId":"cmd_01","type":"recording.stop","payload":{"reason":"test"}}}""",
            send = { sent += it },
            executor = object : LiveCommandExecutor {
                override fun execute(command: LiveDeviceCommand): LiveCommandResult {
                    handled += command
                    return LiveCommandResult.succeeded(mapOf("stopped" to true))
                }
            },
        )

        assertEquals("succeeded", result?.status)
        assertEquals("recording.stop", handled.single().type)
        assertEquals(2, sent.size)
        val ack = JSONObject(sent[0])
        assertEquals("command.ack", ack.getString("type"))
        assertEquals("cmd_01", ack.getString("commandId"))
        assertEquals("acked", ack.getString("status"))
        val commandResult = JSONObject(sent[1])
        assertEquals("command.result", commandResult.getString("type"))
        assertEquals("succeeded", commandResult.getString("status"))
        assertNotNull(commandResult.getJSONObject("result"))
    }

    @Test
    fun sendsFailedResultWhenExecutorThrows() {
        val sent = mutableListOf<String>()
        val result = LiveCommandClient().handleServerMessage(
            raw = """{"event":"command.created","payload":{"id":"id_02","commandId":"cmd_02","type":"recording.start"}}""",
            send = { sent += it },
            executor = object : LiveCommandExecutor {
                override fun execute(command: LiveDeviceCommand): LiveCommandResult {
                    throw IllegalStateException("startForegroundService() not allowed")
                }
            },
        )

        assertEquals("failed", result?.status)
        assertEquals(2, sent.size)
        val commandResult = JSONObject(sent[1])
        assertEquals("command.result", commandResult.getString("type"))
        assertEquals("cmd_02", commandResult.getString("commandId"))
        assertEquals("failed", commandResult.getString("status"))
        assertEquals("startForegroundService() not allowed", commandResult.getJSONObject("result").getString("error"))
    }

    @Test
    fun reconnectBackoffIsCapped() {
        assertEquals(1_000L, reconnectDelayMillis(1))
        assertEquals(2_000L, reconnectDelayMillis(2))
        assertEquals(5_000L, reconnectDelayMillis(3))
        assertEquals(10_000L, reconnectDelayMillis(10))
    }
}
