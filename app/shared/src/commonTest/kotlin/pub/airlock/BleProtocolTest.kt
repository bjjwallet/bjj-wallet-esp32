package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.ble.*
import pub.airlock.domain.*

class BleProtocolTest {
    @Test
    fun decodesUtf8AndDelimiterSplitAtEveryByte() {
        val decoder = BleFrameDecoder()
        val text = "{\"name\":\"AirLock 日本 🔒\"}"
        val outputs =
            (text + AirLockGatt.EOF).encodeToByteArray().flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(text), outputs)
    }

    @Test
    fun handlesMultipleFramesAndPartialTail() {
        val decoder = BleFrameDecoder()
        assertEquals(
            listOf("one", "two"),
            decoder.feed("one###EOF###two###EOF###thr".encodeToByteArray()),
        )
        assertEquals(listOf("three"), decoder.feed("ee###EOF###".encodeToByteArray()))
    }

    @Test
    fun rejectsOversizedFramesAndRecovers() {
        val decoder = BleFrameDecoder(maxFrameBytes = 10)
        assertFailsWith<IllegalStateException> { decoder.feed("x".repeat(20).encodeToByteArray()) }
        assertEquals(listOf("ok"), decoder.feed("ok###EOF###".encodeToByteArray()))
    }

    @Test
    fun correlatesRepliesAndUsesNegotiatedWriteLimit() = runTest {
        val transport = ReplyTransport()
        val rpc = BleRpcClient(transport, backgroundScope)
        assertEquals("pong", rpc.request("ping").jsonPrimitive.content)
        assertTrue(transport.writes.all { it.size <= 20 })
    }

    @Test
    fun errorsFailInsteadOfResolvingAsSuccessfulData() = runTest {
        val rpc = BleRpcClient(ReplyTransport(status = "error"), backgroundScope)
        assertFailsWith<HardwareRejectedException> { rpc.request("sign_tx") }
    }

    @Test
    fun timeoutDoesNotResendSigningRequest() = runTest {
        val transport = ReplyTransport(reply = false)
        val rpc = BleRpcClient(transport, backgroundScope)
        assertFailsWith<TimeoutCancellationException> {
            rpc.request("sign_tx", timeoutMillis = 100)
        }
        assertEquals(1, transport.requests)
    }

    @Test
    fun oneSubscriptionSurvivesDeviceInfoPinAndBothPublicExports() = runTest {
        val delegate = ReplyTransport()
        var subscriptions = 0
        var unsubscriptions = 0
        var deviceDisconnected = false
        val transport =
            object : BleTransport by delegate {
                override val notifications = flow {
                    subscriptions++
                    try {
                        emitAll(delegate.notifications)
                    } finally {
                        unsubscriptions++
                        deviceDisconnected = true
                    }
                }

                override suspend fun write(bytes: ByteArray) {
                    check(!deviceDisconnected) {
                        "Firmware disconnects when notifications are disabled"
                    }
                    delegate.write(bytes)
                }
            }
        val rpc = BleRpcClient(transport, backgroundScope)
        for (method in
            listOf(
                "get_device_info",
                "verify_pin",
                "get_device_info",
                "get_accounts",
                "get_accounts",
            )) {
            assertEquals("pong", rpc.request(method).jsonPrimitive.content)
            assertEquals(1, subscriptions)
            assertEquals(0, unsubscriptions)
        }
        assertEquals(5, delegate.requests)
        rpc.close()
        runCurrent()
        assertEquals(1, unsubscriptions)
    }

    @Test
    fun platformNotificationCancellationIsReportedAsDisconnect() = runTest {
        val transport =
            object : BleTransport {
                override val notifications =
                    flow<ByteArray> { throw CancellationException("Platform stream ended") }
                override val maximumWriteBytes = 20

                override suspend fun write(bytes: ByteArray) = Unit
            }
        val rpc = BleRpcClient(transport, backgroundScope)
        val error = assertFailsWith<InputException> { rpc.request("verify_pin") }
        assertEquals(InputError.DEVICE_DISCONNECTED, error.reason)
    }

    @Test
    fun rejectsDelimiterInjection() = runTest {
        val rpc = BleRpcClient(ReplyTransport(), backgroundScope)
        assertFailsWith<IllegalArgumentException> {
            rpc.request("sign_tx", buildJsonObject { put("memo", AirLockGatt.EOF) })
        }
    }
}

private class ReplyTransport(val status: String = "success", val reply: Boolean = true) :
    BleTransport {
    override val notifications = MutableSharedFlow<ByteArray>()
    override val maximumWriteBytes = 20
    val writes = mutableListOf<ByteArray>()
    var requests = 0
    private val decoder = BleFrameDecoder()

    override suspend fun write(bytes: ByteArray) {
        writes += bytes
        decoder.feed(bytes).forEach { frame ->
            requests++
            val id = Json.parseToJsonElement(frame).jsonObject["id"]!!
            if (reply) {
                notifications.emit(
                    "{\"id\":\"stale\",\"status\":\"success\",\"data\":\"wrong\"}###EOF###"
                        .encodeToByteArray()
                )
                notifications.emit(
                    "{\"id\":$id,\"status\":\"$status\",\"data\":\"pong\"}###EOF###"
                        .encodeToByteArray()
                )
            }
        }
    }
}
