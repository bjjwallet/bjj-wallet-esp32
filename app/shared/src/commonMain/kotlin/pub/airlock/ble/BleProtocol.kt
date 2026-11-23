package pub.airlock.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import pub.airlock.domain.InputError
import pub.airlock.domain.InputException

object AirLockGatt {
    const val SERVICE = "0000beef-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "0000bef0-0000-1000-8000-00805f9b34fb"
    const val WRITE = "0000bef1-0000-1000-8000-00805f9b34fb"
    const val CHUNK_SIZE = 180
    const val EOF = "###EOF###"
}

/** Byte framing preserves UTF-8 across arbitrary BLE packet boundaries. */
class BleFrameDecoder(private val maxFrameBytes: Int = 1_048_576) {
    private val marker = AirLockGatt.EOF.encodeToByteArray()
    private val pending = ArrayList<Byte>()

    fun feed(chunk: ByteArray): List<String> {
        val messages = mutableListOf<String>()
        for (byte in chunk) {
            pending.add(byte)
            if (pending.size > maxFrameBytes + marker.size) {
                reset()
                error("BLE frame exceeds limit")
            }
            if (
                pending.size >= marker.size &&
                    marker.indices.all { pending[pending.size - marker.size + it] == marker[it] }
            ) {
                val payloadSize = pending.size - marker.size
                val bytes = ByteArray(payloadSize) { pending[it] }
                reset()
                messages.add(bytes.decodeToString(throwOnInvalidSequence = true))
            }
        }
        return messages
    }

    fun reset() = pending.clear()
}

interface BleTransport {
    /** Must fail the flow on disconnect so a waiting request fails immediately. */
    val notifications: Flow<ByteArray>
    val maximumWriteBytes: Int

    suspend fun prepareNotifications() = Unit

    suspend fun write(bytes: ByteArray)
}

class HardwareRejectedException(message: String, val code: String? = null) : Exception(message)

/** One notification collector per BLE session. Requests never unsubscribe the GATT stream. */
class BleRpcClient(private val transport: BleTransport, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var sequence = 0L
    private val replies = Channel<JsonObject>(8)
    private var listener: Job? = null

    private suspend fun startNotifications() {
        if (listener != null) return
        transport.prepareNotifications()
        listener =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val decoder = BleFrameDecoder()
                try {
                    transport.notifications.collect { chunk ->
                        for (message in decoder.feed(chunk)) replies.send(
                            Json.parseToJsonElement(message).jsonObject
                        )
                    }
                    replies.close(InputException(InputError.DEVICE_DISCONNECTED))
                } catch (e: Exception) {
                    // A cancelled platform stream is a link failure, not user cancellation of this
                    // request.
                    replies.close(
                        if (e is CancellationException)
                            InputException(InputError.DEVICE_DISCONNECTED)
                        else e
                    )
                }
            }
    }

    fun close() {
        replies.close(InputException(InputError.DEVICE_DISCONNECTED))
        listener?.cancel()
    }

    suspend fun request(
        method: String,
        params: JsonObject = buildJsonObject {},
        timeoutMillis: Long = 5_000,
    ): JsonElement =
        mutex.withLock {
            require(method.isNotBlank())
            require(timeoutMillis > 0)
            val id = "kmp-${++sequence}"
            val payload =
                buildJsonObject {
                        put("id", id)
                        put("method", method)
                        put("params", params)
                    }
                    .toString()
            require(!payload.contains(AirLockGatt.EOF)) {
                "Payload contains reserved frame delimiter"
            }
            val bytes = (payload + AirLockGatt.EOF).encodeToByteArray()
            val chunkSize = minOf(AirLockGatt.CHUNK_SIZE, transport.maximumWriteBytes)
            require(chunkSize > 0)
            // Never retry a PIN, signing, account creation, or device control request
            // automatically.
            withTimeout(timeoutMillis) {
                startNotifications()
                var offset = 0
                while (offset < bytes.size) {
                    val end = minOf(offset + chunkSize, bytes.size)
                    transport.write(bytes.copyOfRange(offset, end))
                    offset = end
                }
                awaitResponse(id, method)
            }
        }

    private suspend fun awaitResponse(id: String, method: String): JsonElement {
        while (true) {
            val response = replies.receive()
            if (response["id"]?.jsonPrimitive?.content != id) continue
            if (response["status"]?.jsonPrimitive?.content != "success")
                throw HardwareRejectedException(
                    "Hardware rejected $method",
                    (response["error"] as? JsonPrimitive)?.content,
                )
            return response["data"] ?: JsonNull
        }
    }
}
