@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class AddUnsynchronizedCoinTest {
    @Test
    fun removingAndReaddingStaysInCatalogAndPreservesPublicData() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            val importer = PublicAccountImporter(Bip32PublicKeyDeriver(dispatcher))
            vault.merge(
                WalletMode.STANDARD,
                importer.decode(
                    PublicWalletFixtures.response(WalletMode.STANDARD),
                    WalletMode.STANDARD,
                ),
            )
            vault.merge(
                WalletMode.HIDDEN,
                importer.decode(PublicWalletFixtures.response(WalletMode.HIDDEN), WalletMode.HIDDEN),
            )
            vault.configureAppSecurity(null)
            val vm = WalletViewModel(vault, DemoHardwareWallet(), MockWalletNetwork())
            advanceUntilIdle()
            val before = vault.snapshot()
            val coin = CoinCatalog.get("bitcoin")
            vm.showAddAccount()
            advanceUntilIdle()
            vm.removeAccount(coin)
            advanceUntilIdle()
            assertEquals(Screen.ADD_ACCOUNT, vm.state.value.screen)
            assertTrue(coin in vm.state.value.availableCoins)
            assertTrue(vm.state.value.accounts.none { it.coin == coin.coin })
            assertTrue(vm.state.value.assets.none { it.coin == coin.coin })
            val removed = vault.snapshot()
            assertEquals(before.assets, removed.assets)
            assertEquals(before.transfers, removed.transfers)
            assertEquals(before.pendingSubmissions, removed.pendingSubmissions)
            assertEquals(
                before.accounts.filter { it.mode == WalletMode.HIDDEN },
                removed.accounts.filter { it.mode == WalletMode.HIDDEN },
            )
            assertFalse(
                VaultRepository(storage, TestCrypto(), dispatcher)
                    .publicAccounts(WalletMode.STANDARD)
                    .single { it.coin == coin.coin }
                    .enabled
            )
            vm.addAccount(coin)
            advanceUntilIdle()
            assertEquals(Screen.ADD_ACCOUNT, vm.state.value.screen)
            assertFalse(coin in vm.state.value.availableCoins)
            assertEquals(before.accounts, vault.snapshot().accounts)
            assertEquals(before.assets, vault.snapshot().assets)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun bitcoinOnlyVaultCanAddOtherCoinsAndTheySurviveRestart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            val imported =
                PublicAccountImporter(Bip32PublicKeyDeriver())
                    .decode(PublicWalletFixtures.response(WalletMode.STANDARD), WalletMode.STANDARD)
            val btc = imported.accounts.single { it.coin == "bitcoin" }
            vault.merge(
                WalletMode.STANDARD,
                WalletData(listOf(btc), imported.assets.filter { it.accountId == btc.id }),
            )
            vault.configureAppSecurity(null)
            val vm = WalletViewModel(vault, DemoHardwareWallet(), MockWalletNetwork())
            advanceUntilIdle()
            assertEquals(listOf("bitcoin"), vm.state.value.accounts.map { it.coin })
            vm.showAddAccount()
            advanceUntilIdle()
            for (coin in listOf("ethereum", "litecoin", "solana", "base")) {
                assertTrue(vm.state.value.availableCoins.any { it.coin == coin }, coin)
                vm.addAccount(vm.state.value.availableCoins.single { it.coin == coin })
                advanceUntilIdle()
                val account = vm.state.value.accounts.single { it.coin == coin }
                assertTrue(account.pendingPublicData)
                assertTrue(account.xpub.isEmpty())
                assertTrue(vm.state.value.assets.none { it.accountId == account.id })
                assertEquals(Screen.ADD_ACCOUNT, vm.state.value.screen)
                assertFalse(vm.state.value.availableCoins.any { it.coin == coin })
                vm.addAccount(CoinCatalog.get(coin))
                advanceUntilIdle()
                assertEquals(1, vm.state.value.accounts.count { it.coin == coin })
            }
            vm.back()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            val restored = VaultRepository(storage, TestCrypto(), dispatcher)
            assertEquals(
                setOf("bitcoin", "ethereum", "litecoin", "solana", "base"),
                restored.accounts(WalletMode.STANDARD).map { it.coin }.toSet(),
            )
            assertEquals(1, restored.snapshot().assets.size)
            restored.merge(WalletMode.STANDARD, imported)
            for (coin in listOf("ethereum", "litecoin", "solana", "base")) {
                val account = restored.accounts(WalletMode.STANDARD).single { it.coin == coin }
                assertFalse(account.pendingPublicData, coin)
                assertTrue(
                    restored.assets(WalletMode.STANDARD).any { it.accountId == account.id },
                    coin,
                )
            }
            assertFailsWith<InputException> {
                restored.merge(
                    WalletMode.STANDARD,
                    imported.copy(
                        accounts =
                            imported.accounts.map {
                                if (it.coin == "ethereum") it.copy(xpub = "different-public-key")
                                else it
                            }
                    ),
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun placeholderCannotGenerateAddressesOrOverrideExistingAssets() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
        vault.enableAccount(WalletMode.STANDARD, "litecoin")
        val account = vault.accounts(WalletMode.STANDARD).single()
        assertEquals(
            InputError.PUBLIC_DATA_SYNC_REQUIRED,
            assertFailsWith<InputException> {
                    vault.addPublicAddress(WalletMode.STANDARD, account.id, Bip32PublicKeyDeriver())
                }
                .reason,
        )
        assertTrue(vault.snapshot().assets.isEmpty())
        val imported =
            PublicAccountImporter(Bip32PublicKeyDeriver())
                .decode(PublicWalletFixtures.response(WalletMode.STANDARD), WalletMode.STANDARD)
        vault.update {
            it.copy(assets = listOf(imported.assets.single { a -> a.coin == "litecoin" }))
        }
        assertEquals(
            InputError.PUBLIC_DATA_CHANGED,
            assertFailsWith<InputException> { vault.merge(WalletMode.STANDARD, imported) }.reason,
        )
    }
}
