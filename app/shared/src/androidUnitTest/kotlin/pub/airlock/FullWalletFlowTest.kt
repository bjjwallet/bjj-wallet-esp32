@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pub.airlock.ble.AirLockGatt
import pub.airlock.ble.BleFrameDecoder
import pub.airlock.ble.BleRpcClient
import pub.airlock.ble.BleTransport
import pub.airlock.ble.HardwareConnection
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.platform.AndroidPinCrypto
import pub.airlock.presentation.*

/** Production importer + PBKDF2; only BLE responses, biometrics, and storage are test doubles. */
class FullWalletFlowTest {
    private enum class Access {
        PIN,
        FACE_ID,
        FINGERPRINT,
        CANCELLED_BIOMETRIC,
        UNAVAILABLE_BIOMETRIC,
    }

    private class RecordingStorage : VaultStorage {
        val writes = mutableListOf<String>()

        override fun read() = VaultRead(writes.lastOrNull())

        override fun write(value: String) {
            writes += value
        }
    }

    private class Device(private val needsPin: Boolean) : HardwareConnection {
        var offline = false
        var verified = false
        var pinRequests = 0
        val exportedModes = mutableListOf<WalletMode>()
        val coldPin = "Aa708193625401"

        override suspend fun discover(): List<HardwareDevice> {
            check(!offline) { "Cached wallet must reopen without Bluetooth" }
            return listOf(HardwareDevice("full-flow-device", "Test cold wallet"))
        }

        override suspend fun connect(id: String) {
            check(!offline)
            verified = !needsPin
        }

        override suspend fun disconnect() = Unit

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            check(!offline) { "Reopening must not contact the cold wallet" }
            return when (method) {
                "get_device_info" ->
                    buildJsonObject {
                        put("name", "Test cold wallet")
                        put("version", "test")
                        put("pin_required", needsPin)
                    }
                "verify_pin" -> {
                    pinRequests++
                    verified = params.getValue("pin").jsonPrimitive.content == coldPin
                    buildJsonObject { put("accepted", verified) }
                }
                "get_accounts" -> {
                    check(verified) { "Public data requested before hardware authorization" }
                    assertTrue(params.getValue("include_all_addresses").jsonPrimitive.boolean)
                    val mode = WalletMode.valueOf(params.getValue("mode").jsonPrimitive.content)
                    exportedModes += mode
                    PublicWalletFixtures.response(mode)
                }
                else -> error("Unexpected method")
            }
        }
    }

    /** Firmware-like transport: disabling notifications terminates this connection. */
    private class WireConnection(private val device: Device, private val scope: CoroutineScope) :
        HardwareConnection {
        private var rpc: BleRpcClient? = null

        override suspend fun discover() = device.discover()

        override suspend fun connect(id: String) {
            device.connect(id)
            val events = MutableSharedFlow<ByteArray>()
            var disconnected = false
            val decoder = BleFrameDecoder()
            rpc =
                BleRpcClient(
                    object : BleTransport {
                        override val maximumWriteBytes = 20
                        override val notifications = flow {
                            try {
                                emitAll(events)
                            } finally {
                                disconnected = true
                            }
                        }

                        override suspend fun write(bytes: ByteArray) {
                            check(!disconnected) {
                                "Firmware disconnected after notification unsubscribe"
                            }
                            for (request in decoder.feed(bytes)) {
                                val frame = Json.parseToJsonElement(request).jsonObject
                                val data =
                                    device.request(
                                        frame.getValue("method").jsonPrimitive.content,
                                        frame.getValue("params").jsonObject,
                                    )
                                val reply =
                                    buildJsonObject {
                                            put("id", frame.getValue("id"))
                                            put("status", "success")
                                            put("data", data)
                                        }
                                        .toString() + AirLockGatt.EOF
                                val encoded = reply.encodeToByteArray()
                                var offset = 0
                                while (offset < encoded.size) {
                                    val end = minOf(offset + AirLockGatt.CHUNK_SIZE, encoded.size)
                                    events.emit(encoded.copyOfRange(offset, end))
                                    offset = end
                                }
                            }
                        }
                    },
                    scope,
                )
        }

        override suspend fun request(method: String, params: JsonObject): JsonElement =
            checkNotNull(rpc).request(method, params)

        override suspend fun disconnect() {
            rpc?.close()
            rpc = null
            device.disconnect()
        }
    }

    private class Biometrics(private val access: Access) : AppBiometrics {
        var prompts = 0

        override fun available() = access !in setOf(Access.PIN, Access.UNAVAILABLE_BIOMETRIC)

        override fun authenticate(reason: String, pinLabel: String, completion: (Boolean) -> Unit) {
            prompts++
            completion(access in setOf(Access.FACE_ID, Access.FINGERPRINT))
        }
    }

    private val network =
        object : WalletNetwork {
            override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()

            override suspend fun estimate(
                asset: Asset,
                destination: String,
                amount: String,
            ): TransferQuote = error("Not part of access flow")

            override suspend fun broadcast(signedTransaction: String): String =
                error("Not part of access flow")
        }

    private suspend fun TestScope.awaitIdle(vm: WalletViewModel) {
        advanceUntilIdle()
        vm.state.first { !it.busy }
        advanceUntilIdle()
    }

    private fun flow(needsHardwarePin: Boolean, access: Access, protectApp: Boolean = true) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val storage = RecordingStorage()
                val crypto = AndroidPinCrypto()
                val device = Device(needsHardwarePin)
                val hardware =
                    ProductionHardwareWallet(
                        WireConnection(device, backgroundScope),
                        Bip32PublicKeyDeriver(),
                    )
                val vault = VaultRepository(storage, crypto, dispatcher)
                val appPin = "OpenApp!2468"
                val vm = WalletViewModel(vault, hardware, network)
                advanceUntilIdle()
                assertEquals(Screen.SYNC, vm.state.value.screen)
                syncTestWallet(vm)
                awaitIdle(vm)
                if (!needsHardwarePin) {
                    assertEquals(Screen.SYNC, vm.state.value.screen)
                    assertEquals(InputError.HARDWARE_PIN_SETUP_REQUIRED, vm.state.value.error)
                    assertTrue(device.exportedModes.isEmpty())
                    assertFalse(vault.snapshot().appSecurityConfigured)
                    return@runTest
                }
                assertEquals(Screen.APP_SECURITY, vm.state.value.screen)
                vm.configureAppSecurity(if (protectApp) appPin else null)
                awaitIdle(vm)
                if (needsHardwarePin) {
                    assertEquals(Screen.HARDWARE_PIN, vm.state.value.screen)
                    assertTrue(device.exportedModes.isEmpty())
                    assertFalse(vault.snapshot().appSecurityConfigured)
                    vm.unlockHardware(device.coldPin)
                    awaitIdle(vm)
                }
                assertEquals(
                    Screen.ACCOUNTS,
                    vm.state.value.screen,
                    "Sync error: ${vm.state.value.error}",
                )
                assertTrue(vm.state.value.accounts.isNotEmpty())
                assertTrue(vm.state.value.assets.isNotEmpty())
                assertEquals(WalletMode.entries.toList(), device.exportedModes.toList())
                assertEquals(if (needsHardwarePin) 1 else 0, device.pinRequests)
                val initial = vault.snapshot()
                assertTrue(initial.appSecurityConfigured)
                if (protectApp) {
                    val record = assertNotNull(initial.appPin)
                    assertEquals(64, record.standardHash.length)
                    assertFalse(record.standardHash.contains(appPin))
                } else assertNull(initial.appPin)
                val expectedAccounts = vm.state.value.accounts
                val expectedAssets = vm.state.value.assets
                device.offline = true
                val prompts = Biometrics(access)
                // New repository and ViewModel model process restart, not just locking the old VM.
                val reopened =
                    WalletViewModel(
                        VaultRepository(storage, crypto, dispatcher),
                        ProductionHardwareWallet(
                            WireConnection(device, backgroundScope),
                            Bip32PublicKeyDeriver(),
                        ),
                        network,
                        biometrics = prompts,
                    )
                advanceUntilIdle()
                if (protectApp) {
                    assertEquals(Screen.LOGIN, reopened.state.value.screen)
                    assertTrue(reopened.state.value.accounts.isEmpty())
                    assertTrue(reopened.state.value.assets.isEmpty())
                    if (prompts.available()) {
                        reopened.unlockBiometric("Unlock with biometrics", "App access PIN")
                        awaitIdle(reopened)
                        assertEquals(1, prompts.prompts)
                    }
                    if (access !in setOf(Access.FACE_ID, Access.FINGERPRINT)) {
                        assertEquals(Screen.LOGIN, reopened.state.value.screen)
                        reopened.unlock(device.coldPin)
                        awaitIdle(reopened)
                        assertEquals(Screen.LOGIN, reopened.state.value.screen)
                        reopened.unlock(appPin)
                        awaitIdle(reopened)
                    }
                }
                assertEquals(Screen.ACCOUNTS, reopened.state.value.screen)
                assertEquals(expectedAccounts, reopened.state.value.accounts)
                assertEquals(expectedAssets, reopened.state.value.assets)
                assertEquals(if (needsHardwarePin) 1 else 0, device.pinRequests)
                assertEquals(WalletMode.entries.toList(), device.exportedModes.toList())
                for (write in storage.writes) {
                    assertFalse(
                        write.contains(device.coldPin),
                        "Cold wallet PIN leaked into a vault write",
                    )
                    assertFalse(write.contains(appPin), "App PIN must be stored only as a verifier")
                    assertFalse("hardwareCredentials" in Json.parseToJsonElement(write).jsonObject)
                }
                assertEquals(initial, VaultRepository(storage, crypto, dispatcher).snapshot())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test fun firstConnectionSyncAndReopenWithAppPin() = flow(true, Access.PIN)

    @Test fun firstConnectionSyncAndReopenWithFaceIdResult() = flow(true, Access.FACE_ID)

    @Test fun firstConnectionSyncAndReopenWithFingerprintResult() = flow(true, Access.FINGERPRINT)

    @Test fun cancelledBiometricsFallsBackToAppPin() = flow(true, Access.CANCELLED_BIOMETRIC)

    @Test fun unavailableBiometricsFallsBackToAppPin() = flow(true, Access.UNAVAILABLE_BIOMETRIC)

    @Test fun deviceWithoutPinCannotSynchronizeWithAppPin() = flow(false, Access.PIN)

    @Test fun deviceWithoutPinCannotSynchronizeWithBiometrics() = flow(false, Access.FINGERPRINT)

    @Test fun optionalAppProtectionCanBeSkipped() = flow(true, Access.PIN, protectApp = false)
}
