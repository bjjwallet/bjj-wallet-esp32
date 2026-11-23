package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.ble.HardwareConnection
import pub.airlock.data.*
import pub.airlock.domain.*

class PublicAccountImporterTest {
    private val importer = PublicAccountImporter(Bip32PublicKeyDeriver())

    @Test
    fun legacyBothModeResponseIsSeparatedAndAllNetworksAreSaved() = runTest {
        val response =
            JsonArray(
                PublicWalletFixtures.response(WalletMode.STANDARD) +
                    PublicWalletFixtures.response(WalletMode.HIDDEN)
            )
        val standard = importer.decode(response, WalletMode.STANDARD)
        val hidden = importer.decode(response, WalletMode.HIDDEN)
        for ((mode, data) in listOf(WalletMode.STANDARD to standard, WalletMode.HIDDEN to hidden)) {
            assertEquals(16, data.accounts.size)
            assertEquals(5, data.accounts.count { it.enabled })
            assertEquals(16, data.assets.size)
            assertTrue(data.accounts.all { it.mode == mode })
            assertTrue(data.assets.all { it.balance == "0" })
            assertEquals(3, data.accounts.count { it.cachedAddresses.size == 5 })
        }
        assertTrue(
            standard.accounts
                .map { it.id }
                .intersect(hidden.accounts.map { it.id }.toSet())
                .isEmpty()
        )
        assertNotEquals(standard.accounts.first().xpub, hidden.accounts.first().xpub)
    }

    @Test
    fun refusesMissingNetworkAndMismatchedInitialAddress() = runTest {
        val response = PublicWalletFixtures.response(WalletMode.STANDARD)
        assertEquals(
            InputError.PUBLIC_DATA_INCOMPLETE,
            assertFailsWith<InputException> {
                    importer.decode(JsonArray(response.drop(1)), WalletMode.STANDARD)
                }
                .reason,
        )
        assertEquals(
            InputError.PUBLIC_DATA_INVALID,
            assertFailsWith<InputException> {
                    importer.decode(
                        response.editFirst("address", JsonPrimitive("untrusted-address")),
                        WalletMode.STANDARD,
                    )
                }
                .reason,
        )
    }

    @Test
    fun rejectsSecretFieldsDuplicateIndicesAndInvalidModeOrMetadata() = runTest {
        val response = PublicWalletFixtures.response(WalletMode.STANDARD)
        for ((field, value) in
            listOf(
                "seed" to JsonPrimitive("forbidden"),
                "private_key" to JsonPrimitive("forbidden"),
                "is_main" to JsonPrimitive(2),
                "decimals" to JsonPrimitive(99),
                "curve" to JsonPrimitive("ed25519"),
            )) {
            assertFailsWith<InputException> {
                importer.decode(response.editFirst(field, value), WalletMode.STANDARD)
            }
        }
        val ed = response.first { it.jsonObject["coin"]?.jsonPrimitive?.content == "solana" }
        assertFailsWith<InputException> {
            importer.decode(JsonArray(response + ed), WalletMode.STANDARD)
        }
        assertFailsWith<InputException> {
            importer.decode(JsonArray(response + response.first()), WalletMode.STANDARD)
        }
    }

    @Test
    fun productionSyncRequestsOnlyPublicDataAndAlwaysDisconnects() = runTest {
        for (valid in listOf(true, false)) {
            val connection =
                RecordingConnection(
                    if (valid) PublicWalletFixtures.response(WalletMode.HIDDEN)
                    else JsonArray(emptyList())
                )
            val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
            if (valid) {
                assertEquals(16, hardware.synchronize(WalletMode.HIDDEN).accounts.size)
                assertEquals("—", hardware.deviceInfo().platform)
            } else assertFailsWith<InputException> { hardware.synchronize(WalletMode.HIDDEN) }
            assertEquals(
                listOf("get_device_info", "get_accounts"),
                connection.calls.map { it.first },
            )
            val params = connection.calls.last().second
            assertEquals("HIDDEN", params["mode"]!!.jsonPrimitive.content)
            assertTrue(params["include_all_addresses"]!!.jsonPrimitive.boolean)
            assertEquals(
                (PublicNetworks.entries + PublicNetworks.optional).map { it.hardwareCoin },
                params["coins"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertTrue(connection.disconnected)
        }
    }

    @Test
    fun importsOptionalStellarAddressesAndRejectsAnInvalidChecksum() = runTest {
        val address = "GCILJZQ3CKBKBUJWW4TAM6Q37LJA5MQX6GMSFSQN75BPLWIZ33OPRG52"
        val fixture = PublicWalletFixtures.response(WalletMode.STANDARD)
        fun stellar(address: String) = buildJsonObject {
            put("coin", "stellar")
            put("symbol", "XLM")
            put("curve", "ed25519")
            put("address", address)
            put("address_index", 0)
            put("path", "m/44'/148'/0'")
            put("decimals", 7)
            put("is_main", 0)
        }
        val result = importer.decode(JsonArray(fixture + stellar(address)), WalletMode.STANDARD)
        assertEquals(address, result.assets.single { it.coin == "stellar" }.address)
        assertFalse(result.accounts.single { it.coin == "stellar" }.enabled)
        assertFailsWith<InputException> {
            importer.decode(
                JsonArray(fixture + stellar(address.dropLast(1) + "A")),
                WalletMode.STANDARD,
            )
        }
    }

    @Test
    fun oldFirmwareWithoutPoolImportsInitialAddressesButRequiresNewSyncForMore() = runTest {
        val data =
            importer.decode(
                PublicWalletFixtures.response(WalletMode.STANDARD, allAddresses = false),
                WalletMode.STANDARD,
            )
        val sol = data.accounts.first { it.coin == "solana" }
        assertEquals(1, sol.cachedAddresses.size)
        assertEquals(
            InputError.PUBLIC_DATA_SYNC_REQUIRED,
            assertFailsWith<InputException> { Bip32PublicKeyDeriver().derive(sol, 1) }.reason,
        )
    }

    @Test
    fun importsCardanoReceiveAddressAndRejectsInvalidChecksum() = runTest {
        val address =
            "addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlft05dz3c7revpf7jx0xnlcjz3g69mq4afdhv"
        val fixture = PublicWalletFixtures.response(WalletMode.STANDARD)
        fun cardano(value: String) = buildJsonObject {
            put("coin", "cardano")
            put("symbol", "ADA")
            put("curve", "ed25519ExtendedCardano")
            put("address", value)
            put("address_index", 0)
            put("path", "m/1852'/1815'/0'/0/0")
            put("decimals", 6)
            put("is_main", 0)
        }
        val result = importer.decode(JsonArray(fixture + cardano(address)), WalletMode.STANDARD)
        val ada = result.assets.single { it.coin == "cardano" }
        assertEquals(address, ada.address)
        assertEquals("ada.svg", ada.icon)
        assertFailsWith<InputException> {
            importer.decode(
                JsonArray(fixture + cardano(address.dropLast(1) + "q")),
                WalletMode.STANDARD,
            )
        }
    }

    @Test
    fun bothWalletsAreFetchedBeforeDisconnectingTheSameConnection() = runTest {
        val calls = mutableListOf<String>()
        var connected = false
        var disconnects = 0
        val connection =
            object : HardwareConnection {
                override suspend fun discover() = listOf(HardwareDevice("device", "AirLock"))

                override suspend fun connect(id: String) {
                    check(!connected)
                    connected = true
                }

                override suspend fun disconnect() {
                    connected = false
                    disconnects++
                }

                override suspend fun request(method: String, params: JsonObject): JsonElement {
                    check(connected)
                    calls += method
                    return if (method == "get_device_info")
                        buildJsonObject {
                            put("name", "BJJ-Cold-Wallet-Flips")
                            put("version", "1.2.3")
                            put("platform", "ESP-IDF / FreeRTOS")
                            put("hardware", "ESP32-S3 ES3N28P")
                            put("screen", "240x320")
                            put("CPU", "42.3 °C")
                        }
                    else
                        PublicWalletFixtures.response(
                            WalletMode.valueOf(params["mode"]!!.jsonPrimitive.content)
                        )
                }
            }
        val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
        hardware.connect("device")
        val wallets = hardware.synchronizeWallets()
        assertEquals(WalletMode.entries.toSet(), wallets.keys)
        assertEquals(listOf("get_device_info", "get_accounts", "get_accounts"), calls)
        assertEquals(1, disconnects)
        assertFalse(connected)
        assertEquals(
            DeviceInfo(
                name = "BJJ-Cold-Wallet-Flips",
                firmware = "1.2.3",
                battery = 0,
                platform = "ESP-IDF / FreeRTOS",
                hardware = "ESP32-S3 ES3N28P",
                display = "240x320",
                cpuTemperature = "42.3 °C",
                connected = false,
            ),
            hardware.deviceInfo(),
        )
        assertNotEquals(
            wallets.getValue(WalletMode.STANDARD).accounts.first().xpub,
            wallets.getValue(WalletMode.HIDDEN).accounts.first().xpub,
        )
    }
}

private fun JsonArray.editFirst(field: String, value: JsonElement) =
    JsonArray(listOf(JsonObject(first().jsonObject + (field to value))) + drop(1))

private class RecordingConnection(private val response: JsonElement) : HardwareConnection {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var disconnected = false

    override suspend fun discover() = listOf(HardwareDevice("test-device", "AirLock"))

    override suspend fun connect(id: String) = Unit

    override suspend fun disconnect() {
        disconnected = true
    }

    override suspend fun request(method: String, params: JsonObject): JsonElement {
        calls += method to params
        return if (method == "get_device_info")
            buildJsonObject {
                put("name", "AirLock")
                put("version", "test")
            }
        else response
    }
}
