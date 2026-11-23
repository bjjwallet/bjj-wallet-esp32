@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*
import pub.airlock.ui.publicExportEntries

class OfflineWalletTest {
    private val deriver = Bip32PublicKeyDeriver()

    private suspend fun data(mode: WalletMode = WalletMode.STANDARD) =
        PublicAccountImporter(deriver).decode(PublicWalletFixtures.response(mode), mode)

    @Test
    fun addressesAndIndicesSurviveRestartResyncAndConcurrentCreation() = runTest {
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val data = data()
        vault.merge(WalletMode.STANDARD, data)
        val account = data.accounts.first()
        val pair = coroutineScope {
            listOf(
                    async { vault.addPublicAddress(account.mode, account.id, deriver) },
                    async { vault.addPublicAddress(account.mode, account.id, deriver) },
                )
                .awaitAll()
        }
        assertEquals(
            setOf(1L, 2L),
            pair.map { it.derivationPath.substringAfterLast('/').toLong() }.toSet(),
        )
        assertEquals(2, pair.map { it.address }.distinct().size)
        vault.update { s ->
            s.copy(
                assets =
                    s.assets.map {
                        if (it.id == data.assets.first().id) it.copy(balance = "1.25") else it
                    }
            )
        }
        val reopened = VaultRepository(storage, TestCrypto(), dispatcher)
        reopened.merge(WalletMode.STANDARD, data)
        assertEquals("1.25", reopened.assets(account.mode).first().balance)
        val next = reopened.addPublicAddress(account.mode, account.id, deriver)
        assertTrue(next.derivationPath.endsWith("/3"))
        assertEquals(4L, reopened.accounts(account.mode).first().nextReceiveIndex)
    }

    @Test
    fun failedWriteDoesNotSkipAnAddressAndOtherModeCannotAllocateIt() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
        val data = data()
        vault.merge(WalletMode.STANDARD, data)
        val account = data.accounts.first()
        storage.failWrites = true
        assertFailsWith<IllegalStateException> {
            vault.addPublicAddress(account.mode, account.id, deriver)
        }
        storage.failWrites = false
        assertFailsWith<InputException> {
            vault.addPublicAddress(WalletMode.HIDDEN, account.id, deriver)
        }
        val first = vault.addPublicAddress(account.mode, account.id, deriver)
        assertEquals(PublicWalletFixtures.standard.first().addresses[1].address, first.address)
        assertEquals(2L, vault.accounts(account.mode).first().nextReceiveIndex)
    }

    @Test
    fun poolExhaustionLeavesStateUnchangedAndExtendedNetworksKeepWorking() = runTest {
        val vault =
            VaultRepository(MemoryStorage(), TestCrypto(), StandardTestDispatcher(testScheduler))
        vault.merge(WalletMode.STANDARD, data())
        vault.enableAccount(WalletMode.STANDARD, "solana")
        val sol = vault.accounts(WalletMode.STANDARD).first { it.coin == "solana" }
        repeat(2) { vault.addPublicAddress(sol.mode, sol.id, deriver) }
        val before = vault.snapshot()
        assertEquals(
            InputError.PUBLIC_DATA_SYNC_REQUIRED,
            assertFailsWith<InputException> { vault.addPublicAddress(sol.mode, sol.id, deriver) }
                .reason,
        )
        assertEquals(before, vault.snapshot())
        val btc = vault.accounts(WalletMode.STANDARD).first { it.coin == "bitcoin" }
        assertTrue(vault.addPublicAddress(btc.mode, btc.id, deriver).address.startsWith("bc1"))
    }

    @Test
    fun bothWalletsKeepIndependentKeysActivationAndCounters() = runTest {
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        vault.merge(WalletMode.STANDARD, data(WalletMode.STANDARD))
        vault.merge(WalletMode.HIDDEN, data(WalletMode.HIDDEN))
        vault.enableAccount(WalletMode.STANDARD, "dogecoin")
        val normal = vault.accounts(WalletMode.STANDARD).first()
        val hidden = vault.accounts(WalletMode.HIDDEN).first()
        val address = vault.addPublicAddress(normal.mode, normal.id, deriver)
        val reopened = VaultRepository(storage, TestCrypto(), dispatcher)
        assertNotEquals(normal.xpub, hidden.xpub)
        assertEquals(6, reopened.accounts(WalletMode.STANDARD).size)
        assertEquals(5, reopened.accounts(WalletMode.HIDDEN).size)
        assertEquals(1L, reopened.accounts(WalletMode.HIDDEN).first().nextReceiveIndex)
        assertNotEquals(
            address.address,
            reopened.addPublicAddress(hidden.mode, hidden.id, deriver).address,
        )
        assertFalse(reopened.assets(WalletMode.HIDDEN).any { it.accountId == normal.id })
        val raw = storage.value!!
        for (forbidden in
            listOf("\"seed\"", "\"mnemonic\"", "\"privateKey\"", "\"passphrase\"", "xprv")) {
            assertFalse(forbidden in raw)
        }
    }

    @Test
    fun differentWalletCannotSilentlyReplacePreviouslySynchronizedKeys() = runTest {
        val vault =
            VaultRepository(MemoryStorage(), TestCrypto(), StandardTestDispatcher(testScheduler))
        val standard = data()
        vault.merge(WalletMode.STANDARD, standard)
        val before = vault.snapshot()
        val otherXpub = PublicWalletFixtures.hidden.first().xpub
        val changed =
            standard.copy(
                accounts =
                    standard.accounts.mapIndexed { i, a ->
                        if (i == 0) a.copy(xpub = otherXpub) else a
                    }
            )
        assertEquals(
            InputError.PUBLIC_DATA_CHANGED,
            assertFailsWith<InputException> { vault.merge(WalletMode.STANDARD, changed) }.reason,
        )
        assertEquals(before, vault.snapshot())
    }

    @Test
    fun twoWalletSyncCommitsTogetherOrLeavesBothUnchanged() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
        val standard = data()
        val hidden = data(WalletMode.HIDDEN)
        val invalidHidden =
            hidden.copy(
                assets =
                    hidden.assets.mapIndexed { index, a ->
                        if (index == 0) a.copy(id = standard.assets.first().id) else a
                    }
            )
        assertFailsWith<IllegalArgumentException> {
            vault.mergeWallets(
                mapOf(WalletMode.STANDARD to standard, WalletMode.HIDDEN to invalidHidden)
            )
        }
        assertTrue(vault.snapshot().accounts.isEmpty())
        assertNull(storage.value)
        val wallets = mapOf(WalletMode.STANDARD to standard, WalletMode.HIDDEN to hidden)
        storage.failWrites = true
        assertFailsWith<IllegalStateException> { vault.mergeWallets(wallets) }
        assertTrue(vault.snapshot().accounts.isEmpty())
        storage.failWrites = false
        vault.mergeWallets(wallets)
        assertEquals(38, vault.snapshot().accounts.size)
    }

    @Test
    fun oneSyncMakesBothWalletsAvailableWhenUnlockingTheirPins() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            var synchronizations = 0
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override suspend fun synchronizePublicWallets() =
                        WalletSynchronization.Legacy(synchronizeWallets())

                    override suspend fun synchronizePublicWallets(
                        skipMoneroWatchWalletIds: Set<String>
                    ) = synchronizePublicWallets()

                    override suspend fun synchronizeWallets(): Map<WalletMode, WalletData> {
                        synchronizations++
                        return WalletMode.entries.associateWith { data(it) }
                    }
                }
            val vm = WalletViewModel(vault, hardware, ProductionWalletNetwork(), deriver)
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            vm.state.first { !it.busy }
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertEquals(1, synchronizations)
            assertEquals(38, vault.snapshot().accounts.size)
            vm.lock()
            advanceUntilIdle()
            val reopened =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    hardware,
                    ProductionWalletNetwork(),
                    deriver,
                )
            advanceUntilIdle()
            reopened.unlock("normal123")
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, reopened.state.value.screen)
            assertTrue(reopened.state.value.accounts.all { it.mode == WalletMode.STANDARD })
            assertEquals(
                PublicWalletFixtures.standard.first().addresses.first().address,
                reopened.state.value.assets.first().address,
            )
            assertEquals(1, synchronizations)
            reopened.lock()
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun restartedViewModelActivatesAccountsAndCreatesAddressesWithNoHardwareCalls() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            vault.configurePins("normal123", "hidden456")
            vault.merge(WalletMode.STANDARD, data())
            val disconnected =
                object : HardwareWallet {
                    override val simulated = false

                    override suspend fun synchronize(mode: WalletMode): WalletData =
                        error("Hardware must not be accessed")

                    override suspend fun deviceInfo(): DeviceInfo =
                        error("Hardware must not be accessed")

                    override suspend fun discover(): List<HardwareDevice> =
                        error("Hardware must not be accessed")

                    override suspend fun disconnect() = Unit
                }
            val vm =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    disconnected,
                    ProductionWalletNetwork(),
                    deriver,
                )
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            vm.showAddAccount()
            advanceUntilIdle()
            assertEquals(
                CoinCatalog.coins.size - vm.state.value.accounts.size,
                vm.state.value.availableCoins.size,
            )
            val before = vm.state.value
            assertTrue(
                publicExportEntries(before.accounts, before.assets).none { it.symbol == "DOGE" }
            )
            for (coin in listOf("dogecoin", "solana", "monero")) {
                vm.addAccount(vm.state.value.availableCoins.first { it.coin == coin })
                advanceUntilIdle()
            }
            val after = vm.state.value
            val exports = publicExportEntries(after.accounts, after.assets)
            val dogecoin = after.accounts.single { it.coin == "dogecoin" }
            assertEquals(dogecoin.xpub, exports.single { it.id == dogecoin.id }.value)
            assertTrue(exports.single { it.id == dogecoin.id }.isKey)
            val solana = after.accounts.single { it.coin == "solana" }
            val address = solana.cachedAddresses.single { it.index == 0L }.address
            assertEquals(address, exports.single { it.id == solana.id }.value)
            val solanaUsdc = exports.single { it.networkName == solana.name && it.symbol == "USDC" }
            assertEquals(address, solanaUsdc.value)
            assertFalse(solanaUsdc.isKey)
            assertNull(exports.single { it.symbol == "XMR" }.value)
            vm.open(dogecoin)
            vm.addAsset()
            vm.state.first { !it.busy }
            assertNull(vm.state.value.error)
            assertEquals(2, vm.state.value.assets.count { it.coin == "dogecoin" })
            vm.open(vm.state.value.accounts.first { it.coin == "ethereum" })
            vm.addAsset()
            vm.state.first { !it.busy }
            val newNative =
                vm.state.value.assets.single {
                    it.coin == "ethereum" &&
                        it.contract.isEmpty() &&
                        it.derivationPath.endsWith("/1")
                }
            val tokens =
                vm.state.value.assets.filter {
                    it.coin == "ethereum" &&
                        it.address == newNative.address &&
                        it.contract.isNotEmpty()
                }
            assertEquals(
                Stablecoins.forNetwork("ethereum").map { it.symbol }.toSet(),
                tokens.map { it.symbol }.toSet(),
            )
            assertTrue(
                tokens.all { it.balance == "0" && it.derivationPath == newNative.derivationPath }
            )
            // Receiving public data never introduces a local signer.
            assertEquals(
                InputError.INTEGRATION_UNAVAILABLE,
                assertFailsWith<InputException> {
                        disconnected.sign(
                            TransferQuote(newNative, "unused", "0", "0"),
                            "normal123",
                            WalletMode.STANDARD,
                        )
                    }
                    .reason,
            )
            vm.lock()
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
