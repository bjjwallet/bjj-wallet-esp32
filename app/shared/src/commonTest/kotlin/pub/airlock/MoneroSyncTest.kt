@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pub.airlock.ble.HardwareConnection
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class MoneroSyncTest {
    @Test
    fun twoBluetoothRequestsAreScopedAndOnlyVerifiedImagesUpdateTheBalance() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val mode = WalletMode.STANDARD
            val saved =
                SavedWallet(
                    "local",
                    "Test wallet",
                    sourceDeviceId = "cold",
                    coldWalletId = "cold-id",
                )
            val other =
                SavedWallet(
                    "other",
                    "Other wallet",
                    sourceDeviceId = "cold",
                    coldWalletId = "other-id",
                )
            val btcAccount = Account("STANDARD:bitcoin", "bitcoin", "Bitcoin", "BTC", "", mode)
            val btcAsset =
                Asset(
                    "STANDARD:bitcoin:0",
                    btcAccount.id,
                    "bitcoin",
                    "BTC",
                    "Bitcoin",
                    "btc-address",
                    "",
                    8,
                    "0.5",
                )
            vault.update {
                saved
                    .activate(
                        it.copy(savedWallets = listOf(saved, other), appSecurityConfigured = true)
                    )
                    .copy(accounts = listOf(btcAccount), assets = listOf(btcAsset))
            }
            vault.enableAccount(mode, "monero")
            val connection = MoneroConnection()
            val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver(dispatcher))
            val allowFirstScanToFinish = CompletableDeferred<Unit>()
            var verifierAcceptsImages = false
            var moneroScans = 0
            val scanner =
                object : MoneroScanner {
                    override suspend fun exportOutputs(wallet: MoneroWatchWallet) =
                        MoneroOutputs("0123", 1)

                    override suspend fun scan(wallet: MoneroWatchWallet): MoneroScan? {
                        moneroScans++
                        assertFalse(
                            connection.connected,
                            "Network scan runs after the Bluetooth session closes",
                        )
                        allowFirstScanToFinish.await()
                        if (!verifierAcceptsImages || wallet.keyImages.isEmpty()) return null
                        return MoneroScan("1.25", 11, null)
                    }
                }
            val providerQueries = mutableListOf<String>()
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun prices(symbols: List<String>): Map<String, String> {
                        providerQueries += symbols
                        return emptyMap()
                    }

                    override suspend fun balance(asset: Asset): String {
                        providerQueries += asset.symbol
                        return "1"
                    }
                }
            val vm = WalletViewModel(vault, hardware, network, moneroScanner = scanner)
            advanceUntilIdle()
            assertEquals(listOf("BTC"), providerQueries)
            providerQueries.clear()
            vm.open(vm.state.value.accounts.single { it.coin == "monero" })
            vm.refreshVisibleBalances()
            advanceUntilIdle()
            vm.showMoneroSync()
            vm.synchronizeMonero()
            advanceUntilIdle()
            connection.stallConnection = true
            vm.connectMonero("cold")
            runCurrent()
            assertEquals(HardwareOperation.CONNECT_MONERO, vm.state.value.hardwareOperation)
            advanceUntilIdle()
            assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
            assertEquals(InputError.MONERO_CONNECTION_FAILED, vm.state.value.error)
            assertFalse(vm.state.value.busy)
            assertTrue(connection.methods.isEmpty())
            connection.stallConnection = false
            connection.stallPairing = true
            vm.synchronizeMonero()
            advanceUntilIdle()
            vm.connectMonero("cold")
            runCurrent()
            assertEquals(HardwareOperation.PAIR_MONERO, vm.state.value.hardwareOperation)
            advanceUntilIdle()
            assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
            assertEquals(InputError.MONERO_PAIRING_FAILED, vm.state.value.error)
            assertFalse(vm.state.value.busy)
            assertFalse(connection.connected)
            assertTrue(connection.methods.isEmpty())
            connection.stallPairing = false
            vm.synchronizeMonero()
            advanceUntilIdle()
            vm.connectMonero("cold")
            advanceUntilIdle()
            assertEquals(Screen.MONERO_PIN, vm.state.value.screen)
            vm.back()
            advanceUntilIdle()
            assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
            assertFalse(connection.connected)
            assertTrue(connection.calls.isEmpty())
            suspend fun sync() {
                vm.synchronizeMonero()
                advanceUntilIdle()
                assertEquals(Screen.MONERO_DEVICE, vm.state.value.screen)
                assertEquals(listOf("cold"), vm.state.value.hardwareDevices.map { it.id })
                vm.synchronizeWith("cold")
                advanceUntilIdle()
                assertFalse(connection.connected)
                vm.connectMonero("cold")
                advanceUntilIdle()
                assertEquals(Screen.MONERO_PIN, vm.state.value.screen)
                vm.unlockHardware("Aa1234")
                advanceUntilIdle()
                assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
                assertFalse(connection.connected)
            }
            sync()
            assertTrue(vm.state.value.busy)
            assertTrue(vm.state.value.moneroWatchImported)
            val publicAsset = vm.state.value.assets.single { it.coin == "monero" }
            assertEquals(
                vault.snapshot().moneroWatchWallets.getValue(mode).address,
                publicAsset.address,
            )
            assertFalse(vm.state.value.selectedAccount!!.pendingPublicData)
            assertNull(vm.state.value.moneroHeight)
            allowFirstScanToFinish.complete(Unit)
            advanceUntilIdle()
            vm.synchronizeMonero()
            advanceUntilIdle()
            vm.connectMonero("cold")
            advanceUntilIdle()
            assertEquals(Screen.MONERO_PIN, vm.state.value.screen)
            val screens = mutableListOf<Screen>()
            val observer =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.state.collect { screens += it.screen }
                }
            vm.lock()
            advanceUntilIdle()
            observer.cancel()
            assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
            assertEquals("local", vm.state.value.activeWalletId)
            assertEquals("monero", vm.state.value.selectedAccount?.coin)
            assertFalse(connection.connected)
            assertTrue(
                screens.none {
                    it in setOf(Screen.SYNC, Screen.HARDWARE_SELECT, Screen.HARDWARE_PIN)
                }
            )
            assertEquals(listOf("monero_get_watch_wallet"), connection.calls.map { it.first })
            connection.supported = false
            vm.synchronizeMonero()
            advanceUntilIdle()
            vm.connectMonero("cold")
            advanceUntilIdle()
            assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
            assertEquals(InputError.MONERO_KEY_IMAGES_UNAVAILABLE, vm.state.value.error)
            assertTrue(vm.state.value.moneroNeedsImages)
            assertEquals(listOf("monero_get_watch_wallet"), connection.calls.map { it.first })
            connection.supported = true
            sync()
            assertEquals(InputError.MONERO_SCAN_PENDING, vm.state.value.error)
            assertTrue(vault.snapshot().moneroWatchWallets.getValue(mode).keyImages.isEmpty())
            assertEquals(publicAsset, vm.state.value.assets.single { it.coin == "monero" })
            assertNull(vm.state.value.moneroHeight)
            verifierAcceptsImages = true
            sync()
            assertEquals("1.25", vm.state.value.assets.single { it.coin == "monero" }.balance)
            assertEquals(btcAsset, vm.state.value.assets.single { it.coin == "bitcoin" })
            assertTrue(providerQueries.isEmpty())
            assertTrue(
                connection.methods.all {
                    it in
                        setOf(
                            "get_device_info",
                            "verify_pin",
                            "monero_get_watch_wallet",
                            "monero_sign_key_images",
                        )
                }
            )
            assertEquals(11L, vm.state.value.moneroHeight)
            assertTrue(
                vault
                    .snapshot()
                    .savedWallets
                    .single { it.id == "other" }
                    .moneroWatchWallets
                    .isEmpty()
            )
            assertEquals(
                listOf(
                    "monero_get_watch_wallet",
                    "monero_sign_key_images",
                    "monero_sign_key_images",
                ),
                connection.calls.map { it.first },
            )
            for ((method, params) in connection.calls) {
                assertEquals(JsonPrimitive("cold-id"), params["wallet_id"])
                assertEquals(JsonPrimitive("STANDARD"), params["mode"])
                assertEquals(
                    if (method == "monero_get_watch_wallet") null else JsonPrimitive("0123"),
                    params["outputs_data_hex"],
                )
            }
            val scansBeforeGeneralRefresh = moneroScans
            vm.back()
            vm.back()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            vm.refreshBalances()
            advanceUntilIdle()
            assertEquals(scansBeforeGeneralRefresh, moneroScans)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun addressOnlyImportPublishesReceiveAddressWithoutProvisioningTheScanner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val mode = WalletMode.STANDARD
            val saved =
                SavedWallet(
                    "local",
                    "Test wallet",
                    sourceDeviceId = "cold",
                    coldWalletId = "cold-id",
                )
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            vault.update {
                saved.activate(it.copy(savedWallets = listOf(saved), appSecurityConfigured = true))
            }
            vault.enableAccount(mode, "monero")
            val connection = MoneroConnection()
            val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver(dispatcher))
            val vm =
                WalletViewModel(
                    vault,
                    hardware,
                    MockWalletNetwork(),
                    moneroScanner =
                        MoneroScanner { error("Address-only sync must not run the scanner") },
                )
            advanceUntilIdle()
            vm.open(vm.state.value.accounts.single { it.coin == "monero" })
            vm.showMoneroSync()
            vm.synchronizeMoneroAddress()
            advanceUntilIdle()
            vm.connectMonero("cold")
            advanceUntilIdle()
            vm.unlockHardware("Aa1234")
            advanceUntilIdle()
            assertEquals(Screen.MONERO_SYNC, vm.state.value.screen)
            assertNull(vm.state.value.error)
            assertEquals(MoneroNetwork.MAINNET, vm.state.value.moneroNetwork)
            assertEquals("monero", vm.state.value.assets.single { it.coin == "monero" }.coin)
            assertFalse(vm.state.value.moneroWatchImported)
            assertNull(vm.state.value.moneroHeight)
            assertFalse(connection.connected)
            assertEquals(listOf("monero_get_receive_address"), connection.calls.map { it.first })
            assertTrue(vault.snapshot().moneroWatchWallets.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun moneroStoresSignedBytesBeforeBroadcastAndRetriesWithoutSigningAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val mode = WalletMode.STANDARD
            val saved =
                SavedWallet("local", "Monero", sourceDeviceId = "cold", coldWalletId = "cold-id")
            vault.update {
                saved.activate(it.copy(savedWallets = listOf(saved), appSecurityConfigured = true))
            }
            val watch =
                MoneroWatchWallet(
                    "5Aur6tRd1TTFK3wYL2hLWQFGt87ZTeRkufS6JoQZu6MEFDokAQeGWmu9MA3GFq1yVLSJQbKJqVAn9F9DLYGpRzRAETHfXkW",
                    "01" + "00".repeat(31),
                    10,
                    emptyList(),
                    MoneroNetwork.STAGENET,
                )
            val destination =
                "56RRyUZKZeh5bGxAoTvJ9QLeB9iRmwrBhYJ4GR1m5iq5Cqp7vAyxwZDK2QBM1tjuHi3EHF9RQxa3uSDSKD2UhpQuD6ztGDY"
            vault.saveMoneroWatchWallet(mode, watch)
            val connection = MoneroConnection()
            val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver(dispatcher))
            var attempts = 0
            val scanner =
                object : MoneroScanner {
                    override suspend fun scan(wallet: MoneroWatchWallet) =
                        MoneroScan("2", 14, null, "1.5")

                    override suspend fun prepareTransfer(
                        wallet: MoneroWatchWallet,
                        recipient: String,
                        amount: String,
                    ): MoneroUnsignedTransfer {
                        assertEquals(watch, wallet)
                        assertEquals(destination, recipient)
                        assertEquals("1000000000000", amount)
                        return MoneroUnsignedTransfer("abcd", "2500000000")
                    }

                    override suspend fun submitTransfer(txid: String, raw: String): TransferStatus {
                        assertFalse(connection.connected)
                        val pending = vault.snapshot().pendingSubmissions.single()
                        assertEquals(txid, pending.txId)
                        assertEquals(raw, pending.signedTransaction)
                        assertEquals("0123", raw)
                        if (++attempts == 1) throw InputException(InputError.SUBMISSION_PENDING)
                        return TransferStatus.SUBMITTED
                    }
                }
            assertTrue(vault.refreshMonero(mode, scanner = scanner))
            val vm = WalletViewModel(vault, hardware, MockWalletNetwork(), moneroScanner = scanner)
            advanceUntilIdle()
            vm.open(vm.state.value.assets.single { it.coin == "monero" })
            vm.send()
            vm.prepareTransfer(destination, "1")
            advanceUntilIdle()
            assertEquals(Screen.CONFIRM, vm.state.value.screen)
            vm.confirmTransfer("")
            advanceUntilIdle()
            assertEquals(Screen.MONERO_DEVICE, vm.state.value.screen)
            vm.connectMonero("cold")
            advanceUntilIdle()
            assertEquals(Screen.MONERO_PIN, vm.state.value.screen)
            vm.unlockHardware("Aa1234")
            advanceUntilIdle()
            assertEquals(InputError.SUBMISSION_PENDING, vm.state.value.error)
            val request = connection.calls.single { it.first == "monero_sign_transfer" }.second
            assertEquals(
                setOf(
                    "wallet_id",
                    "mode",
                    "network",
                    "recipient",
                    "amount_atomic",
                    "fee_atomic",
                    "unsigned_txset",
                ),
                request.keys,
            )
            assertEquals(JsonPrimitive("abcd"), request["unsigned_txset"])
            assertEquals(JsonPrimitive("2500000000"), request["fee_atomic"])
            val txid = vault.snapshot().pendingSubmissions.single().txId
            vm.retryPending(txid)
            advanceUntilIdle()
            assertEquals(2, attempts)
            assertEquals(1, connection.calls.count { it.first == "monero_sign_transfer" })
            assertTrue(vault.snapshot().pendingSubmissions.isEmpty())
            assertEquals(txid, vault.snapshot().transfers.single().id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectsMismatchedReplyAndIncompleteImages() = runTest {
        val connection = MoneroConnection()
        val hardware =
            ProductionHardwareWallet(
                connection,
                Bip32PublicKeyDeriver(StandardTestDispatcher(testScheduler)),
            )
        hardware.bindWalletDevice("cold")
        hardware.connect("cold")
        hardware.unlockAccess("Aa1234")
        connection.walletId = "other-id"
        assertEquals(
            InputError.PUBLIC_DATA_INVALID,
            assertFailsWith<InputException> {
                    hardware.moneroWatchWallet("cold-id", WalletMode.STANDARD)
                }
                .reason,
        )
        connection.walletId = "cold-id"
        assertEquals(
            InputError.PUBLIC_DATA_INVALID,
            assertFailsWith<InputException> {
                    hardware.moneroSignKeyImages(
                        "cold-id",
                        WalletMode.STANDARD,
                        MoneroOutputs("0123", 2),
                    )
                }
                .reason,
        )
    }
}

private class MoneroConnection : HardwareConnection {
    var connected = false
    var stallConnection = false
    var stallPairing = false
    var walletId = "cold-id"
    var supported = true
    private var authorized = false
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val methods = mutableListOf<String>()

    override suspend fun discover() =
        listOf(HardwareDevice("cold", "Cold"), HardwareDevice("unknown", "Other"))

    override suspend fun connect(id: String) {
        check(id == "cold")
        if (stallConnection) awaitCancellation()
        connected = true
        authorized = false
    }

    override suspend fun connectMonero(id: String, onPairing: () -> Unit) {
        connect(id)
        onPairing()
        if (stallPairing) awaitCancellation()
    }

    override suspend fun disconnect() {
        connected = false
        authorized = false
    }

    override suspend fun request(method: String, params: JsonObject): JsonElement {
        check(connected)
        methods += method
        return when (method) {
            "get_device_info" ->
                buildJsonObject {
                    if (supported) {
                        put("monero_sync_version", 1)
                        put("monero_receive_address_version", 1)
                        put("monero_signing_version", 1)
                        put("monero_network", "stagenet")
                    }
                    put("pin_required", true)
                }
            "verify_pin" ->
                buildJsonObject {
                    authorized = true
                    put("accepted", true)
                }
            "monero_sign_transfer" -> {
                check(authorized)
                calls += method to params
                buildJsonObject {
                    put("wallet_id", walletId)
                    put("mode", "STANDARD")
                    put("network", "stagenet")
                    put("txid", "c".repeat(64))
                    put("tx_hex", "0123")
                }
            }
            "monero_get_watch_wallet",
            "monero_get_receive_address",
            "monero_sign_key_images" -> {
                check(authorized)
                calls += method to params
                buildJsonObject {
                    put("wallet_id", walletId)
                    put("mode", "STANDARD")
                    if (method != "monero_sign_key_images") {
                        put("network", "mainnet")
                        put(
                            "address",
                            "4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge",
                        )
                        if (method == "monero_get_watch_wallet") {
                            put("private_view_key", "01" + "00".repeat(31))
                            put("restore_height", 10)
                        }
                    } else {
                        put("offset", 0)
                        putJsonArray("signed_key_images") {
                            addJsonObject {
                                put("key_image", "2".repeat(64))
                                put("signature", "3".repeat(128))
                            }
                        }
                    }
                }
            }
            else -> error("Unexpected hardware method")
        }
    }
}
