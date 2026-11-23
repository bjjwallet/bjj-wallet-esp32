@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

private suspend fun walletExport(swapped: Boolean = false): Map<WalletMode, WalletData> =
    WalletMode.entries.associateWith { mode ->
        val source = if (swapped) WalletMode.entries.first { it != mode } else mode
        val rows =
            JsonArray(
                PublicWalletFixtures.response(source).map {
                    JsonObject(
                        it.jsonObject +
                            ("is_main" to JsonPrimitive(if (mode == WalletMode.HIDDEN) 1 else 0))
                    )
                }
            )
        PublicAccountImporter(Bip32PublicKeyDeriver()).decode(rows, mode)
    }

class MultipleWalletsTest {
    @Test
    fun syncDifferentWalletsKeepsBalancesHistoryPendingAndIndicesSeparate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val first = walletExport()
        val second = walletExport(true)
        vault.commitHardwareSynchronization(
            first,
            vault.prepareAppSecurity(null),
            "Pessoal",
            "device-a",
        )
        val a = vault.snapshot().activeWalletId!!
        val asset = vault.snapshot().assets.first { it.coin == "bitcoin" }
        val pending =
            PendingSubmission(
                "a-pending",
                "public-signed-fixture",
                TransferQuote(asset, "public-destination", "0.1", "0.01"),
                WalletMode.STANDARD,
            )
        vault.update {
            it.copy(
                assets = it.assets.map { asset -> asset.copy(balance = "7") },
                accounts = it.accounts.map { account -> account.copy(nextReceiveIndex = 18) },
                transfers = listOf(Transfer("same-hash", asset.id, "0.1", "a-destination")),
                pendingSubmissions = listOf(pending),
            )
        }
        vault.commitHardwareSynchronization(
            second,
            walletName = "Empresa",
            sourceDeviceId = "device-b",
        )
        val b = vault.snapshot().activeWalletId!!
        assertNotEquals(a, b)
        assertEquals(2, vault.snapshot().savedWallets.size)
        assertTrue(vault.snapshot().assets.all { it.balance == "0" })
        assertTrue(vault.snapshot().transfers.isEmpty())
        assertTrue(vault.snapshot().pendingSubmissions.isEmpty())
        assertEquals("device-b", vault.snapshot().sourceDeviceId)
        val bAddress = vault.snapshot().assets.first { it.coin == "bitcoin" }.address
        vault.update {
            it.copy(transfers = listOf(Transfer("same-hash", asset.id, "0.2", "b-destination")))
        }
        vault.selectWallet(a)
        assertEquals("7", vault.snapshot().assets.first { it.coin == "bitcoin" }.balance)
        assertNotEquals(bAddress, vault.snapshot().assets.first { it.coin == "bitcoin" }.address)
        assertEquals(18L, vault.snapshot().accounts.first().nextReceiveIndex)
        assertEquals("a-destination", vault.snapshot().transfers.single().destination)
        assertEquals(listOf(pending), vault.snapshot().pendingSubmissions)
        assertEquals("device-a", vault.snapshot().sourceDeviceId)
        vault.commitHardwareSynchronization(first)
        assertEquals(2, vault.snapshot().savedWallets.size)
        assertEquals(a, vault.snapshot().activeWalletId)
        assertEquals("7", vault.snapshot().assets.first { it.coin == "bitcoin" }.balance)
        val restored = VaultRepository(storage, TestCrypto(), dispatcher)
        assertEquals(a, restored.snapshot().activeWalletId)
        restored.renameWallet(a, "Pessoal nova")
        assertEquals("Pessoal nova", restored.snapshot().savedWallets.single { it.id == a }.name)
        restored.selectWallet(b)
        assertEquals("b-destination", restored.snapshot().transfers.single().destination)
        assertTrue(restored.snapshot().pendingSubmissions.isEmpty())
    }

    @Test
    fun legacyVaultMigratesWithoutDiscardingDataAndFailedSwitchIsAtomic() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage()
        val data = walletExport().getValue(WalletMode.STANDARD)
        val legacy =
            VaultSnapshot(
                accounts = data.accounts,
                assets = data.assets,
                appSecurityConfigured = true,
            )
        storage.write(Json { encodeDefaults = true }.encodeToString(legacy))
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val migrated = vault.snapshot()
        assertEquals(legacy.accounts, migrated.accounts)
        assertEquals(legacy.assets, migrated.assets)
        assertEquals(1, migrated.savedWallets.size)
        vault.commitHardwareSynchronization(walletExport(), walletName = "Original")
        assertEquals(1, vault.snapshot().savedWallets.size)
        val a = vault.snapshot().activeWalletId!!
        vault.commitHardwareSynchronization(walletExport(true), walletName = "Outra")
        val before = vault.snapshot()
        storage.failWrites = true
        assertFails { vault.selectWallet(a) }
        storage.failWrites = false
        assertEquals(before, vault.snapshot())
        assertFailsWith<InputException> { vault.renameWallet(a, "\n") }
        assertFailsWith<InputException> { vault.selectWallet("missing") }
        val invalid = walletExport().toMutableMap()
        invalid[WalletMode.HIDDEN] = invalid.getValue(WalletMode.STANDARD)
        assertFails { vault.commitHardwareSynchronization(invalid, walletName = "Invalid") }
        assertEquals(before, vault.snapshot())
    }

    @Test
    fun synchronizeAnotherWalletThroughTheAppSelectsItAndResyncDoesNotDuplicate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val first = walletExport()
            val second = walletExport(true)
            vault.commitHardwareSynchronization(
                first,
                vault.prepareAppSecurity(null),
                "Pessoal",
                "test-device",
            )
            val originalId = vault.snapshot().activeWalletId
            var export = second
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override suspend fun discover() =
                        listOf(HardwareDevice("test-device", "Dispositivo"))

                    override suspend fun connect(deviceId: String) = Unit

                    override suspend fun requiresAccessPin() = false

                    override suspend fun synchronizePublicWallets() =
                        WalletSynchronization.Legacy(synchronizeWallets())

                    override suspend fun synchronizePublicWallets(
                        skipMoneroWatchWalletIds: Set<String>
                    ) = synchronizePublicWallets()

                    override suspend fun synchronizeWallets() = export

                    override suspend fun deviceInfo() = DeviceInfo("Dispositivo", "fixture", 0)

                    override suspend fun disconnect() = Unit
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()
                }
            val vm = WalletViewModel(vault, hardware, network)
            advanceUntilIdle()
            vm.beginWalletSynchronization("Empresa")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertEquals(originalId, vm.state.value.activeWalletId)
            vm.synchronize()
            advanceUntilIdle()
            vm.synchronizeWith("test-device")
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertEquals("Empresa", vm.state.value.activeWalletName)
            assertEquals(2, vm.state.value.savedWallets.size)
            assertNotEquals(originalId, vm.state.value.activeWalletId)
            export = first
            vm.beginWalletSynchronization("Pessoal atualizada")
            advanceUntilIdle()
            vm.synchronize()
            advanceUntilIdle()
            vm.synchronizeWith("test-device")
            advanceUntilIdle()
            assertEquals(originalId, vm.state.value.activeWalletId)
            assertEquals("Pessoal atualizada", vm.state.value.activeWalletName)
            assertEquals(2, vm.state.value.savedWallets.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectorClearsDraftAndBlocksSwitchWhileAnotherOperationRuns() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            vault.commitHardwareSynchronization(
                walletExport(),
                vault.prepareAppSecurity(null),
                "A",
                "device-a",
            )
            val a = vault.snapshot().activeWalletId!!
            vault.commitHardwareSynchronization(
                walletExport(true),
                walletName = "B",
                sourceDeviceId = "device-b",
            )
            val b = vault.snapshot().activeWalletId!!
            var gate: CompletableDeferred<Unit>? = null
            val routes = mutableListOf<String?>()
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override fun bindWalletDevice(deviceId: String?) {
                        routes += deviceId
                    }
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()

                    override suspend fun balance(asset: Asset): String {
                        gate?.await()
                        return "2"
                    }
                }
            val vm = WalletViewModel(vault, hardware, network)
            advanceUntilIdle()
            assertEquals("B", vm.state.value.activeWalletName)
            assertEquals(2, vm.state.value.savedWallets.size)
            val asset = vm.state.value.assets.first { it.coin == "bitcoin" }
            vault.update {
                it.copy(
                    assets =
                        it.assets.map { value ->
                            if (value.id == asset.id) value.copy(balance = "1") else value
                        }
                )
            }
            vm.selectWallet(a)
            advanceUntilIdle()
            vm.selectWallet(b)
            advanceUntilIdle()
            vm.open(vm.state.value.assets.first { it.coin == "bitcoin" })
            vm.send()
            vm.prepareTransfer("public-destination", "0.1")
            advanceUntilIdle()
            assertEquals(Screen.CONFIRM, vm.state.value.screen)
            vm.selectWallet(a)
            advanceUntilIdle()
            assertEquals(a, vm.state.value.activeWalletId)
            assertEquals("A", vm.state.value.activeWalletName)
            assertNull(vm.state.value.quote)
            assertNull(vm.state.value.transferDraft)
            assertNull(vm.state.value.selectedAsset)
            assertEquals("device-a", routes.last())
            gate = CompletableDeferred()
            vm.refreshBalances()
            runCurrent()
            assertTrue(vm.state.value.busy)
            vm.selectWallet(b)
            runCurrent()
            assertEquals(a, vm.state.value.activeWalletId)
            gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(a, vault.snapshot().activeWalletId)
            vm.renameWallet(a, "Renomeada")
            advanceUntilIdle()
            assertEquals("Renomeada", vm.state.value.activeWalletName)
            vm.lock()
            advanceUntilIdle()
            assertEquals("Renomeada", vm.state.value.activeWalletName)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
