@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package pub.airlock.ble

import com.juul.kable.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import pub.airlock.domain.*

fun interface BluetoothAccess {
    suspend fun ensureAllowed()
}

interface HardwareConnection {
    suspend fun discover(): List<HardwareDevice>

    suspend fun connect(id: String)

    suspend fun connectMonero(id: String, onPairing: () -> Unit) = connect(id)

    suspend fun request(method: String, params: JsonObject = buildJsonObject {}): JsonElement

    suspend fun disconnect()
}

/** GATT encryption/pairing is enforced by the firmware's encrypted read/write characteristics. */
class KableHardwareConnection(
    private val access: BluetoothAccess,
    private val diagnostics: (String) -> Unit = {},
) : HardwareConnection {
    private fun trace(event: String) {
        runCatching { diagnostics(event) }
    }

    private val discovered = mutableMapOf<String, Advertisement>()
    private var peripheral: Peripheral? = null
    private var rpc: BleRpcClient? = null
    private var sessionScope: CoroutineScope? = null
    private val service = Uuid.parse(AirLockGatt.SERVICE)
    private val notify = characteristicOf(service, Uuid.parse(AirLockGatt.NOTIFY))
    private val write = characteristicOf(service, Uuid.parse(AirLockGatt.WRITE))

    override suspend fun discover(): List<HardwareDevice> {
        access.ensureAllowed()
        discovered.clear()
        try {
            withTimeoutOrNull(8_000) {
                Scanner { filters { match { services = listOf(service) } } }
                    .advertisements
                    .collect { advertisement ->
                        if (advertisement.isConnectable != false)
                            discovered[advertisement.identifier.toString()] = advertisement
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw InputException(InputError.BLUETOOTH_UNAVAILABLE)
        }
        requireInput(discovered.isNotEmpty(), InputError.DEVICE_DISCONNECTED)
        return discovered.map { (id, advertisement) ->
            HardwareDevice(
                id,
                advertisement.name ?: advertisement.peripheralName ?: "BJJ-Cold-Wallet",
            )
        }
    }

    override suspend fun connect(id: String) = connect(id, onPairing = null)

    override suspend fun connectMonero(id: String, onPairing: () -> Unit) = connect(id, onPairing)

    private suspend fun connect(id: String, onPairing: (() -> Unit)?) {
        trace("ble.connect.start")
        access.ensureAllowed()
        disconnect()
        // Advertisements are process-local; the saved device ID survives app restarts.
        // Rediscover only that device instead of requiring public-wallet synchronization.
        val advertisement =
            discovered[id]
                ?: withTimeoutOrNull(12_000) {
                        Scanner { filters { match { services = listOf(service) } } }
                            .advertisements
                            .first { it.identifier.toString() == id && it.isConnectable != false }
                    }
                    ?.also { discovered[id] = it }
                ?: throw InputException(InputError.DEVICE_DISCONNECTED)
        val device = Peripheral(advertisement)
        peripheral = device
        try {
            withTimeout(60_000) {
                device.connect()
                onPairing?.invoke()
                // Pair explicitly before the encrypted read. On some Android phones,
                // read-triggered pairing only posts a notification and expires unseen.
                pairAirLockLink(
                    device,
                    if (onPairing != null) InputError.MONERO_PAIRING_FAILED
                    else InputError.DEVICE_DISCONNECTED,
                )
                device.read(notify) // The firmware requires an authenticated, encrypted link.
                configureAirLockLink(device)
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            sessionScope = scope
            rpc =
                BleRpcClient(
                    object : BleTransport {
                        private var ready = CompletableDeferred<Unit>()
                        override val maximumWriteBytes =
                            20 // Fits the default ATT MTU on both platforms.

                        override suspend fun prepareNotifications() {
                            ready = CompletableDeferred()
                        }

                        override val notifications: Flow<ByteArray> = channelFlow {
                            val watcher = launch {
                                device.state.first { it is State.Disconnected }
                                throw InputException(InputError.DEVICE_DISCONNECTED)
                            }
                            try {
                                device
                                    .observe(notify, onSubscription = { ready.complete(Unit) })
                                    .collect { send(it) }
                            } catch (e: Exception) {
                                ready.completeExceptionally(e)
                                throw e
                            } finally {
                                watcher.cancel()
                            }
                        }

                        override suspend fun write(bytes: ByteArray) {
                            ready.await()
                            device.write(write, bytes, WriteType.WithResponse)
                        }
                    },
                    scope,
                )
            trace("ble.connect.ready")
        } catch (_: TimeoutCancellationException) {
            device.close()
            peripheral = null
            throw InputException(InputError.DEVICE_DISCONNECTED)
        } catch (e: CancellationException) {
            device.close()
            peripheral = null
            throw e
        } catch (e: InputException) {
            device.close()
            peripheral = null
            throw e
        } catch (_: Exception) {
            device.close()
            peripheral = null
            throw InputException(InputError.DEVICE_DISCONNECTED)
        }
    }

    override suspend fun request(method: String, params: JsonObject): JsonElement =
        try {
            if (method in setOf("get_device_info", "sign_tx", "verify_pin"))
                trace("ble.$method.start")
            val awaitsApproval =
                method in
                    setOf(
                        "sign_tx",
                        "bsc_sign_brz",
                        "eth_sign_brz",
                        "eth_sign_native",
                        "evm_sign_token",
                        "evm_sign_transfer",
                        "polygon_sign_pol",
                        "solana_sign_transfer",
                        "cardano_sign_transfer",
                        "monero_get_watch_wallet",
                        "monero_get_receive_address",
                        "monero_sign_key_images",
                        "monero_sign_transfer",
                    )
            val bulkSync = method == "get_accounts" && params["all_wallets"] == JsonPrimitive(true)
            (rpc ?: throw InputException(InputError.DEVICE_DISCONNECTED))
                .request(
                    method,
                    params,
                    timeoutMillis = if (awaitsApproval || bulkSync) 180_000 else 60_000,
                )
                .also {
                    if (method in setOf("get_device_info", "sign_tx", "verify_pin"))
                        trace("ble.$method.ok")
                }
        } catch (e: HardwareRejectedException) {
            if (method in setOf("get_device_info", "sign_tx", "verify_pin")) {
                val code = e.code?.takeIf { Regex("[A-Z_]{1,64}").matches(it) } ?: "REJECTED"
                trace("ble.$method.rejected.$code")
            }
            throw e
        } catch (_: TimeoutCancellationException) {
            trace("ble.request.timeout")
            throw InputException(InputError.DEVICE_DISCONNECTED)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive)
                throw InputException(InputError.DEVICE_DISCONNECTED)
            throw e
        }

    override suspend fun disconnect() {
        trace("ble.disconnect")
        rpc?.close()
        sessionScope?.cancel()
        sessionScope = null
        rpc = null
        peripheral?.close()
        peripheral = null
    }
}

internal expect suspend fun configureAirLockLink(peripheral: Peripheral)

internal expect suspend fun pairAirLockLink(peripheral: Peripheral, failure: InputError)
