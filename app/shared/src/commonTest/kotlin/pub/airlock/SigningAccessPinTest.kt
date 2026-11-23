package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.ble.*
import pub.airlock.data.*
import pub.airlock.domain.*

class SigningAccessPinTest {
    @Test
    fun eachNewSigningConnectionVerifiesTheAccessPinBeforeSignTxWithoutCachingIt() = runTest {
        val vector = utxoVectors().first()
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        val events = mutableListOf<String>()
        var verified = false
        val connection =
            object : HardwareConnection {
                override suspend fun discover() = emptyList<HardwareDevice>()

                override suspend fun connect(id: String) {
                    verified = false
                    events += "connect"
                }

                override suspend fun disconnect() {
                    verified = false
                    events += "disconnect"
                }

                override suspend fun request(method: String, params: JsonObject): JsonElement {
                    events += method
                    return when (method) {
                        "verify_pin" -> {
                            assertEquals("Aa1234", params.getValue("pin").jsonPrimitive.content)
                            verified = true
                            buildJsonObject { put("accepted", true) }
                        }
                        "get_device_info" ->
                            buildJsonObject {
                                put("utxo_signing_version", 1)
                                put("pin_required", true)
                            }
                        "sign_tx" -> {
                            if (!verified)
                                throw HardwareRejectedException(
                                    "Access PIN required",
                                    "PIN_REQUIRED",
                                )
                            assertEquals(
                                "Public transaction phrase",
                                params.getValue("password").jsonPrimitive.content,
                            )
                            assertFalse(
                                params.containsKey("pin"),
                                "Access PIN must not become the transaction phrase",
                            )
                            JsonPrimitive(raw)
                        }
                        else -> error("Unexpected method")
                    }
                }
            }
        val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
        hardware.bindWalletDevice("fixture")
        repeat(2) {
            assertEquals(
                raw,
                hardware.withSigningAccessPin("Aa1234") {
                    hardware.sign(quote, "Public transaction phrase", WalletMode.STANDARD)
                },
            )
        }
        assertEquals(
            List(2) { listOf("connect", "verify_pin", "get_device_info", "sign_tx", "disconnect") }
                .flatten(),
            events,
        )
        assertEquals(
            InputError.HARDWARE_PIN_REQUIRED,
            assertFailsWith<InputException> {
                    hardware.sign(quote, "Public transaction phrase", WalletMode.STANDARD)
                }
                .reason,
        )
        assertEquals(
            2,
            events.count { it == "verify_pin" },
            "No reusable PIN outside the signing coroutine",
        )
    }

    @Test
    fun incorrectOrLockedAccessPinNeverReachesTheSigner() = runTest {
        val quote = utxoQuote(utxoVectors().first())
        var locked = false
        var signs = 0
        val connection =
            object : HardwareConnection {
                override suspend fun discover() = emptyList<HardwareDevice>()

                override suspend fun connect(id: String) = Unit

                override suspend fun disconnect() = Unit

                override suspend fun request(method: String, params: JsonObject): JsonElement {
                    if (method == "verify_pin") {
                        if (locked) throw HardwareRejectedException("Locked", "PIN_LOCKED")
                        return buildJsonObject { put("accepted", false) }
                    }
                    if (method == "sign_tx") signs++
                    error("No other RPC is allowed before authentication")
                }
            }
        val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
        hardware.bindWalletDevice("fixture")
        for (expected in listOf(InputError.INCORRECT_PIN, InputError.HARDWARE_PIN_LOCKED)) {
            assertEquals(
                expected,
                assertFailsWith<InputException> {
                        hardware.withSigningAccessPin("Aa1234") {
                            hardware.sign(quote, "Public transaction phrase", WalletMode.STANDARD)
                        }
                    }
                    .reason,
            )
            locked = true
        }
        assertEquals(0, signs)
    }
}
