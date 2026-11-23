@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pub.airlock.ble.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

private fun syncConfig(words: Int = 12, passphrase: Boolean = false) = buildJsonObject {
    put("word_count", words)
    put("passphrase_enabled", passphrase)
    put("imported", false)
}

private val syncManifest = buildJsonArray {
    add(
        buildJsonObject {
            put("id", "cold-a")
            put("name", "Mesmo nome")
            put("config", syncConfig())
        }
    )
    add(
        buildJsonObject {
            put("id", "cold-b")
            put("name", "Mesmo nome")
            put("config", syncConfig(24, true))
        }
    )
}

private fun syncRows(wallet: String, mode: WalletMode): JsonArray {
    val source = if (wallet == "cold-a") mode else WalletMode.entries.first { it != mode }
    // New grouped responses may omit the legacy row mode field.
    return JsonArray(
        PublicWalletFixtures.response(source).map { JsonObject(it.jsonObject - "is_main") }
    )
}

private fun syncGroups(mode: WalletMode, manifest: JsonArray = syncManifest): JsonArray =
    JsonArray(
        manifest
            .map { row ->
                val descriptor = row.jsonObject
                buildJsonObject {
                    put("wallet_id", descriptor.getValue("id"))
                    put("wallet_name", descriptor.getValue("name"))
                    put("config", descriptor.getValue("config"))
                    put("accounts", syncRows(descriptor.getValue("id").jsonPrimitive.content, mode))
                }
            }
            .let { if (mode == WalletMode.HIDDEN) it.reversed() else it }
    )

private class StableSyncConnection : HardwareConnection {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var connected = false
    var authorized = false
    var disconnects = 0
    var manifest: JsonElement = syncManifest
    var mutate: (WalletMode, JsonArray) -> JsonElement = { _, rows -> rows }
    var version: JsonElement? = JsonPrimitive(1)
    var moneroVersion: Int? = null
    var rejectEcash = false

    override suspend fun discover() = listOf(HardwareDevice("device", "Cold"))

    override suspend fun connect(id: String) {
        check(!connected)
        connected = true
        authorized = false
    }

    override suspend fun disconnect() {
        if (connected) disconnects++
        connected = false
    }

    override suspend fun request(method: String, params: JsonObject): JsonElement {
        check(connected)
        calls += method to params
        return when (method) {
            "get_device_info" ->
                buildJsonObject {
                    put("name", "Cold")
                    put("pin_required", true)
                    version?.let { put("wallet_sync_version", it) }
                    moneroVersion?.let { put("monero_sync_version", it) }
                }
            "verify_pin" -> {
                authorized = true
                buildJsonObject { put("accepted", true) }
            }
            "get_wallets" -> {
                check(authorized)
                manifest
            }
            "get_accounts" -> {
                check(authorized)
                if (rejectEcash && JsonPrimitive("ecash") in params.getValue("coins").jsonArray)
                    throw HardwareRejectedException(
                        "Optional account unavailable",
                        "PUBLIC_DATA_INVALID",
                    )
                val mode = WalletMode.valueOf(params.getValue("mode").jsonPrimitive.content)
                if (params["all_wallets"] == JsonPrimitive(true))
                    mutate(mode, syncGroups(mode, manifest.jsonArray))
                else if (params["wallet_id"] != null)
                    syncRows(params.getValue("wallet_id").jsonPrimitive.content, mode)
                else PublicWalletFixtures.response(mode)
            }
            "monero_get_watch_wallet" -> {
                check(authorized && moneroVersion == 1)
                buildJsonObject {
                    put("wallet_id", params.getValue("wallet_id"))
                    put("mode", params.getValue("mode"))
                    put("network", "stagenet")
                    put(
                        "address",
                        "5Aur6tRd1TTFK3wYL2hLWQFGt87ZTeRkufS6JoQZu6MEFDokAQeGWmu9MA3GFq1yVLSJQbKJqVAn9F9DLYGpRzRAETHfXkW",
                    )
                    put("private_view_key", "01" + "00".repeat(31))
                    put("restore_height", 0)
                }
            }
            else -> error("Unexpected method")
        }
    }
}

private suspend fun download(
    connection: StableSyncConnection = StableSyncConnection()
): WalletSynchronization.Stable {
    val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
    hardware.connect("device")
    assertTrue(hardware.requiresAccessPin())
    assertTrue(hardware.unlockAccess("Aa1234"))
    return hardware.synchronizePublicWallets() as WalletSynchronization.Stable
}

class StableWalletSyncTest {
    @Test
    fun compatibleColdImportsMoneroDuringWalletSyncAndRespectsOptOut() = runTest {
        val connection =
            StableSyncConnection().apply {
                moneroVersion = 1
                manifest =
                    JsonArray(
                        syncManifest.map { row ->
                            val descriptor = row.jsonObject
                            if (descriptor["id"] == JsonPrimitive("cold-b")) {
                                val config =
                                    descriptor.getValue("config").jsonObject +
                                        ("monero_enabled" to JsonPrimitive(false))
                                JsonObject(descriptor + ("config" to JsonObject(config)))
                            } else descriptor
                        }
                    )
            }
        val synced = download(connection)
        val request = connection.calls.single { it.first == "monero_get_watch_wallet" }.second
        assertEquals(setOf("wallet_id", "mode"), request.keys)
        assertEquals(JsonPrimitive("cold-a"), request["wallet_id"])
        assertEquals(JsonPrimitive("STANDARD"), request["mode"])
        val vault =
            VaultRepository(MemoryStorage(), TestCrypto(), StandardTestDispatcher(testScheduler))
        vault.commitPublicWalletSynchronization(synced, sourceDeviceId = "device")
        val snapshot = vault.snapshot()
        val imported = snapshot.savedWallets.single { it.coldWalletId == "cold-a" }
        assertEquals(
            MoneroNetwork.STAGENET,
            imported.moneroWatchWallets.getValue(WalletMode.STANDARD).network,
        )
        val account = imported.accounts.single { it.coin == "monero" }
        assertEquals(WalletMode.STANDARD, account.mode)
        assertFalse(account.pendingPublicData)
        assertEquals(
            imported.moneroWatchWallets.getValue(account.mode).address,
            imported.assets.single { it.accountId == account.id }.address,
        )
        assertTrue(imported.moneroScanHeights.isEmpty())
        val disabled = snapshot.savedWallets.single { it.coldWalletId == "cold-b" }
        assertTrue(disabled.moneroWatchWallets.isEmpty())
        assertTrue(disabled.assets.none { it.coin == "monero" })
    }

    @Test
    fun capabilityManifestAndBothModesUseOneAuthorizedConnection() = runTest {
        val connection = StableSyncConnection()
        val result = download(connection)
        assertEquals(
            listOf(
                "get_device_info",
                "verify_pin",
                "get_device_info",
                "get_wallets",
                "get_accounts",
                "get_accounts",
            ),
            connection.calls.map { it.first },
        )
        assertEquals(1, connection.disconnects)
        assertFalse(connection.connected)
        assertEquals(listOf("cold-a", "cold-b"), result.wallets.map { it.descriptor.id })
        assertEquals(listOf("Mesmo nome", "Mesmo nome"), result.wallets.map { it.descriptor.name })
        assertFalse(result.wallets.first().descriptor.config.passphraseEnabled)
        assertTrue(result.wallets.last().descriptor.config.passphraseEnabled)
        for (mode in WalletMode.entries) {
            val params =
                connection.calls
                    .single {
                        it.first == "get_accounts" && it.second["mode"] == JsonPrimitive(mode.name)
                    }
                    .second
            assertEquals(JsonPrimitive(true), params["all_wallets"])
            assertEquals(JsonPrimitive(true), params["include_all_addresses"])
            assertEquals(
                (PublicNetworks.entries + PublicNetworks.optional).map { it.hardwareCoin },
                params.getValue("coins").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(
                setOf("all_wallets", "mode", "include_all_addresses", "coins"),
                params.keys,
            )
            val a = result.wallets.first().modes.getValue(mode)
            val b = result.wallets.last().modes.getValue(mode)
            assertTrue(a.accounts.all { it.mode == mode })
            assertTrue(b.accounts.all { it.mode == mode })
            assertNotEquals(a.accounts.first().xpub, b.accounts.first().xpub)
        }
    }

    @Test
    fun singleWalletQueryUsesStableIdAndFlatAccounts() = runTest {
        val connection = StableSyncConnection()
        val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
        hardware.connect("device")
        hardware.unlockAccess("Aa1234")
        val data = hardware.synchronizeWallet("cold-b", WalletMode.HIDDEN)
        val query = connection.calls.single { it.first == "get_accounts" }.second
        assertEquals(JsonPrimitive("cold-b"), query["wallet_id"])
        assertFalse("all_wallets" in query)
        assertEquals(16, data.accounts.size)
        assertTrue(data.accounts.all { it.mode == WalletMode.HIDDEN })
        assertEquals(1, connection.disconnects)
    }

    @Test
    fun firmwareWithoutEcashStillSynchronizesBothModes() = runTest {
        val connection = StableSyncConnection().apply { rejectEcash = true }
        val result = download(connection)
        assertEquals(2, result.wallets.size)
        val requests = connection.calls.filter { it.first == "get_accounts" }.map { it.second }
        for (mode in WalletMode.entries) {
            val attempts = requests.filter { it["mode"] == JsonPrimitive(mode.name) }
            assertEquals(3, attempts.size)
            assertContains(attempts.first().getValue("coins").jsonArray, JsonPrimitive("ecash"))
            assertFalse(attempts[1].getValue("coins").jsonArray.contains(JsonPrimitive("cardano")))
            assertEquals(
                attempts.first().getValue("coins").jsonArray -
                    setOf(JsonPrimitive("ecash"), JsonPrimitive("cardano")),
                attempts.last().getValue("coins").jsonArray.toList(),
            )
        }
    }

    @Test
    fun legacyDevicesKeepThePreviousFlatProtocolAndUnknownVersionsFailClosed() = runTest {
        val connection = StableSyncConnection().apply { version = null }
        val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
        hardware.connect("device")
        hardware.unlockAccess("Aa1234")
        assertIs<WalletSynchronization.Legacy>(hardware.synchronizePublicWallets())
        assertFalse(connection.calls.any { it.first == "get_wallets" })
        assertTrue(
            connection.calls
                .filter { it.first == "get_accounts" }
                .all { "all_wallets" !in it.second && "wallet_id" !in it.second }
        )
        val unknown = StableSyncConnection().apply { version = JsonPrimitive(2) }
        assertFailsWith<InputException> { download(unknown) }
        assertFalse(unknown.calls.any { it.first == "get_accounts" })
        assertEquals(1, unknown.disconnects)
    }

    @Test
    fun missingDuplicateOrChangedGroupsAndWrongRowModesAreRejected() = runTest {
        val mutations: List<(WalletMode, JsonArray) -> JsonElement> =
            listOf(
                { mode, rows ->
                    if (mode == WalletMode.HIDDEN) JsonArray(rows.dropLast(1)) else rows
                },
                { _, rows -> JsonArray(listOf(rows.first(), rows.first())) },
                { _, rows ->
                    JsonArray(
                        listOf(
                            JsonObject(
                                rows.first().jsonObject +
                                    ("wallet_name" to JsonPrimitive("Changed"))
                            )
                        ) + rows.drop(1)
                    )
                },
                { mode, rows ->
                    val first = rows.first().jsonObject
                    val accounts = first.getValue("accounts").jsonArray
                    val wrong =
                        JsonArray(
                            accounts.map {
                                JsonObject(
                                    it.jsonObject +
                                        ("is_main" to
                                            JsonPrimitive(
                                                if (mode == WalletMode.STANDARD) 1 else 0
                                            ))
                                )
                            }
                        )
                    JsonArray(listOf(JsonObject(first + ("accounts" to wrong))) + rows.drop(1))
                },
                { _, rows ->
                    JsonArray(
                        listOf(
                            JsonObject(
                                rows.first().jsonObject +
                                    ("transactionPhrase" to JsonPrimitive("forbidden"))
                            )
                        ) + rows.drop(1)
                    )
                },
            )
        for (mutation in mutations) {
            val connection = StableSyncConnection().apply { mutate = mutation }
            assertFailsWith<InputException> { download(connection) }
            assertEquals(1, connection.disconnects)
        }
        val duplicateManifest =
            StableSyncConnection().apply {
                manifest = JsonArray(listOf(syncManifest.first(), syncManifest.first()))
            }
        assertFailsWith<InputException> { download(duplicateManifest) }
        assertFalse(duplicateManifest.calls.any { it.first == "get_accounts" })
    }

    @Test
    fun sameNamesStaySeparateAndStableIdsSurviveRenamingRestartAndResync() = runTest {
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val result = download()
        vault.commitPublicWalletSynchronization(
            result,
            vault.prepareAppSecurity(null),
            sourceDeviceId = "device",
        )
        val a = vault.snapshot().savedWallets.single { it.coldWalletId == "cold-a" }
        val b = vault.snapshot().savedWallets.single { it.coldWalletId == "cold-b" }
        assertNotEquals(a.id, b.id)
        assertEquals(a.name, b.name)
        assertNotEquals(a.accounts.first().xpub, b.accounts.first().xpub)
        val asset = vault.snapshot().assets.first()
        vault.update {
            it.copy(
                assets = it.assets.map { asset -> asset.copy(balance = "7") },
                transfers = listOf(Transfer("a-history", asset.id, "0.1", "a-destination")),
            )
        }
        vault.selectWallet(b.id)
        assertTrue(vault.snapshot().transfers.isEmpty())
        assertTrue(vault.snapshot().assets.all { it.balance == "0" })
        val renamed =
            WalletSynchronization.Stable(
                result.wallets.map {
                    it.copy(descriptor = it.descriptor.copy(name = "Renamed ${it.descriptor.id}"))
                }
            )
        vault.commitPublicWalletSynchronization(renamed)
        assertEquals(b.id, vault.snapshot().activeWalletId)
        assertEquals(2, vault.snapshot().savedWallets.size)
        val restored = VaultRepository(storage, TestCrypto(), dispatcher)
        restored.selectWallet(a.id)
        assertEquals("7", restored.snapshot().assets.first().balance)
        assertEquals("a-history", restored.snapshot().transfers.single().id)
        assertEquals(
            "Renamed cold-a",
            restored.snapshot().savedWallets.single { it.id == a.id }.name,
        )
        assertEquals(
            PublicWalletConfig(24, true, false),
            restored.snapshot().savedWallets.single { it.id == b.id }.config,
        )
        val before = restored.snapshot()
        val changed =
            renamed.wallets.last().let { wallet ->
                wallet.copy(
                    modes =
                        wallet.modes +
                            (WalletMode.HIDDEN to
                                wallet.modes.getValue(WalletMode.HIDDEN).let { data ->
                                    data.copy(
                                        accounts =
                                            data.accounts.map { it.copy(xpub = "changed-key") }
                                    )
                                })
                )
            }
        assertFailsWith<InputException> {
            restored.commitPublicWalletSynchronization(
                WalletSynchronization.Stable(listOf(renamed.wallets.first(), changed))
            )
        }
        assertEquals(before, restored.snapshot())
        storage.failWrites = true
        assertFails { restored.commitPublicWalletSynchronization(result) }
        storage.failWrites = false
        assertEquals(before, restored.snapshot())
    }

    @Test
    fun legacyPublicDataIsAdoptedOnceWithoutChangingLocalIdsOrLosingBalances() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
        val result = download()
        vault.commitHardwareSynchronization(
            result.wallets.first().modes,
            vault.prepareAppSecurity(null),
            "Legada",
        )
        val legacyId = vault.snapshot().activeWalletId
        vault.update { it.copy(assets = it.assets.map { asset -> asset.copy(balance = "8") }) }
        vault.commitPublicWalletSynchronization(result)
        assertEquals(2, vault.snapshot().savedWallets.size)
        assertEquals(legacyId, vault.snapshot().activeWalletId)
        assertEquals(
            "cold-a",
            vault.snapshot().savedWallets.single { it.id == legacyId }.coldWalletId,
        )
        assertEquals("8", vault.snapshot().assets.first().balance)
    }

    @Test
    fun appCommitsAllGroupsTogetherAndUsesColdNamesAndIdsInTheSelector() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            val connection =
                StableSyncConnection().apply {
                    mutate = { _, rows ->
                        val before =
                            storage.value?.let { Json.decodeFromString<VaultSnapshot>(it) }
                                ?: VaultSnapshot()
                        assertTrue(before.savedWallets.isEmpty())
                        assertTrue(before.accounts.isEmpty())
                        assertFalse(before.appSecurityConfigured)
                        assertNull(before.appPin)
                        rows
                    }
                }
            val deriver = Bip32PublicKeyDeriver(dispatcher)
            val hardware = ProductionHardwareWallet(connection, deriver)
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()
                }
            val vm = WalletViewModel(vault, hardware, network, deriver)
            advanceUntilIdle()
            vm.synchronize()
            advanceUntilIdle()
            vm.synchronizeWith("device")
            advanceUntilIdle()
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            vm.unlockHardware("Ab0012340001")
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertEquals(2, vm.state.value.savedWallets.size)
            assertTrue(vm.state.value.savedWallets.all { it.name == "Mesmo nome" })
            assertEquals(
                setOf("cold-a", "cold-b"),
                vault.snapshot().savedWallets.map { it.coldWalletId }.toSet(),
            )
            assertTrue(vault.unlockApp("app123"))
            val b = vault.snapshot().savedWallets.single { it.coldWalletId == "cold-b" }
            vm.selectWallet(b.id)
            advanceUntilIdle()
            assertEquals(b.id, vm.state.value.activeWalletId)
            assertEquals(
                b.assets
                    .filter { asset ->
                        b.accounts.any {
                            it.id == asset.accountId && it.mode == WalletMode.STANDARD && it.enabled
                        }
                    }
                    .map { it.address }
                    .toSet(),
                vm.state.value.assets.map { it.address }.toSet(),
            )
            assertFalse(storage.value.orEmpty().contains("Ab0012340001"))
            vm.lock()
            advanceUntilIdle()
            vm.unlock("app123")
            advanceUntilIdle()
            assertEquals(b.id, vm.state.value.activeWalletId)
            assertEquals(2, vm.state.value.savedWallets.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hiddenDownloadFailureCannotCommitAnyGroupOrAppSecurity() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val connection =
                StableSyncConnection().apply {
                    mutate = { mode, rows ->
                        if (mode == WalletMode.HIDDEN) JsonArray(rows.dropLast(1)) else rows
                    }
                }
            val deriver = Bip32PublicKeyDeriver(dispatcher)
            val hardware = ProductionHardwareWallet(connection, deriver)
            val vm = WalletViewModel(vault, hardware, MockWalletNetwork(), deriver)
            advanceUntilIdle()
            vm.synchronize()
            advanceUntilIdle()
            vm.synchronizeWith("device")
            advanceUntilIdle()
            assertEquals(Screen.APP_SECURITY, vm.state.value.screen)
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            assertEquals(Screen.HARDWARE_PIN, vm.state.value.screen)
            vm.unlockHardware("Aa1234")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            val snapshot = vault.snapshot()
            assertTrue(snapshot.accounts.isEmpty())
            assertTrue(snapshot.assets.isEmpty())
            assertTrue(snapshot.savedWallets.isEmpty())
            assertNull(snapshot.appPin)
            assertFalse(snapshot.appSecurityConfigured)
            assertEquals(1, connection.disconnects)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
