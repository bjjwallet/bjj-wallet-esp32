@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class AccountVisibilityTest {
    @Test
    fun moralisLoadsBitcoinDirectlyAndTokenInsideItsNetwork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val mode = WalletMode.STANDARD
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val deriver = Bip32PublicKeyDeriver(dispatcher)
            vault.merge(
                mode,
                PublicAccountImporter(deriver).decode(PublicWalletFixtures.response(mode), mode),
            )
            vault.configureAppSecurity(null)
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override val simulated = false
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun balances(assets: List<Asset>) =
                        assets.associate { it.id to "1" }

                    override suspend fun transactionHistory(asset: Asset, cursor: String?) =
                        TransactionPage(
                            listOf(Transfer(cursor ?: "first", asset.id, "1", asset.address)),
                            if (cursor == null) "older" else null,
                        )
                }
            val vm = WalletViewModel(vault, hardware, network, deriver)
            advanceUntilIdle()
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.MORALIS, moralisKey = "test.key"),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()

            vm.open(vm.state.value.catalogAccounts.single { it.coin == "bitcoin" })
            advanceUntilIdle()
            var current = vm.state.value
            val bitcoin = assertNotNull(current.selectedAsset)
            assertEquals(Screen.ASSET, current.screen)
            assertEquals("BTC", bitcoin.symbol)
            assertEquals("1", bitcoin.balance)
            assertFalse(current.canSendSelectedAsset)
            assertEquals("first", current.moralisHistory[bitcoin.id]?.transfers?.single()?.id)
            vm.loadMoreMoralisHistory()
            advanceUntilIdle()
            assertEquals(
                listOf("first", "older"),
                vm.state.value.moralisHistory[bitcoin.id]?.transfers?.map { it.id },
            )
            vm.back()

            vm.open(vm.state.value.catalogAccounts.single { it.coin == "ethereum" })
            assertEquals(Screen.WALLETS, vm.state.value.screen)
            val usdc = vm.state.value.assets.single { it.coin == "ethereum" && it.symbol == "USDC" }
            vm.open(usdc)
            advanceUntilIdle()
            current = vm.state.value
            assertEquals(Screen.ASSET, current.screen)
            assertEquals(usdc.id, current.selectedAsset?.id)
            assertEquals("first", current.moralisHistory[usdc.id]?.transfers?.single()?.id)
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.AIRLOCK),
                "https://api.airlock.pub",
                keepAssetOpen = true,
            )
            advanceUntilIdle()
            assertEquals(usdc.id, vm.state.value.selectedAsset?.id)
            assertTrue(vm.state.value.moralisHistory.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ecashAppearsDirectlyAndOpensHistoryBeforeAndAfterAddressSync() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val mode = WalletMode.STANDARD
            val deriver = Bip32PublicKeyDeriver(dispatcher)
            val importer = PublicAccountImporter(deriver)
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val rows = PublicWalletFixtures.response(mode)
            vault.merge(mode, importer.decode(rows, mode))
            vault.configureAppSecurity(null)
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override val simulated = false
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun addressActivity(asset: Asset) =
                        AddressActivity(
                            "12.34",
                            listOf(
                                Transfer(
                                    "xec-transaction",
                                    asset.id,
                                    "12.34",
                                    "tx-hash",
                                    incoming = true,
                                )
                            ),
                        )
                }
            val vm = WalletViewModel(vault, hardware, network, deriver)
            advanceUntilIdle()
            val ecash = CoinCatalog.get("ecash")
            assertEquals(
                listOf(NetworkProvider.FREEDOM),
                NetworkProvider.available.filter { ProviderCoverage.canReadNative(it, ecash.coin) },
            )
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.FREEDOM),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            vm.showAddAccount()
            advanceUntilIdle()
            assertContains(vm.state.value.availableCoins, ecash)
            vm.addAccount(ecash)
            advanceUntilIdle()
            vm.back()
            vm.open(vm.state.value.catalogAccounts.single { it.coin == ecash.coin })
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertNull(vm.state.value.selectedAsset)

            // Reuse the public fixture key with the eCash path and a separately computed CashAddr
            // checksum.
            val bchKey = PublicWalletFixtures.standard.single { it.coin == "bitcoincash" }.xpub
            val address = "ecash:qqyx49mu0kkn9ftfj6hje6g2wfer34yfnqdxfumtxd"
            val row = buildJsonObject {
                put("coin", "ecash")
                put("symbol", "XEC")
                put("curve", "secp256k1")
                put("address", address)
                put("path", PublicNetworks.ecash.path)
                put("xpub", bchKey)
                put("decimals", 2)
                put("is_main", 0)
            }
            vault.merge(mode, importer.decode(JsonArray(rows + row), mode))
            vm.initialize()
            advanceUntilIdle()
            vm.open(vm.state.value.catalogAccounts.single { it.coin == ecash.coin })
            advanceUntilIdle()
            val current = vm.state.value
            assertEquals(Screen.ASSET, current.screen)
            val asset = assertNotNull(current.selectedAsset)
            assertEquals(address, asset.address)
            assertEquals("12.34", asset.balance)
            assertEquals("xec-transaction", current.ecashHistory[asset.id]?.single()?.id)
            vm.back()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun savedWalletKeepsEnabledAccountsAndSeparatesModes() {
        val accounts =
            listOf(
                Account("btc", "bitcoin", "Bitcoin", "BTC", "", WalletMode.STANDARD),
                Account("eth", "ethereum", "Ethereum", "ETH", "", WalletMode.STANDARD),
                Account(
                    "disabled",
                    "solana",
                    "Solana",
                    "SOL",
                    "",
                    WalletMode.STANDARD,
                    enabled = false,
                ),
                Account("hidden", "ethereum", "Ethereum", "ETH", "", WalletMode.HIDDEN),
            )
        val assets =
            listOf(
                Asset("btc", "btc", "bitcoin", "BTC", "Bitcoin", "public", "", 8),
                Asset("eth", "eth", "ethereum", "ETH", "Ethereum", "public", "", 18, "7"),
                Asset(
                    "usdc",
                    "eth",
                    "ethereum",
                    "USDC",
                    "USDC",
                    "public",
                    "",
                    6,
                    "12",
                    contract = "public-contract",
                ),
                Asset("sol", "disabled", "solana", "SOL", "Solana", "public", "", 9),
                Asset("hidden", "hidden", "ethereum", "ETH", "Ethereum", "public", "", 18),
            )
        val snapshot = VaultSnapshot(accounts = accounts, assets = assets)
        for (provider in NetworkProvider.available) {
            val wallet = ProviderCoverage.wallet(snapshot, WalletMode.STANDARD, provider)
            assertEquals(setOf("btc", "eth"), wallet.accounts.map { it.id }.toSet())
            assertEquals(setOf("btc", "eth", "usdc"), wallet.assets.map { it.id }.toSet())
            assertEquals("7", wallet.assets.first { it.id == "eth" }.balance)
            assertEquals(
                listOf("hidden"),
                ProviderCoverage.wallet(snapshot, WalletMode.HIDDEN, provider).accounts.map {
                    it.id
                },
            )
            assertTrue(ProviderCoverage.canSubmit(provider, "ethereum"))
        }
    }

    @Test
    fun catalogStaysVisibleWhileProviderControlsSending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val mode = WalletMode.STANDARD
            val deriver = Bip32PublicKeyDeriver(dispatcher)
            vault.merge(
                mode,
                PublicAccountImporter(deriver).decode(PublicWalletFixtures.response(mode), mode),
            )
            val litecoinAsset = vault.snapshot().assets.single { it.coin == "litecoin" }
            val savedTransfer = Transfer("ltc-transaction", litecoinAsset.id, "1", "recipient")
            vault.update { it.copy(transfers = listOf(savedTransfer)) }
            vault.configureAppSecurity(null)
            val queried = mutableListOf<String>()
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun prices(symbols: List<String>) = emptyMap<String, String>()

                    override suspend fun balance(asset: Asset): String {
                        queried += "${asset.coin}:${asset.symbol}"
                        return "2"
                    }
                }
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override val simulated = false
                }
            val vm = WalletViewModel(vault, hardware, network, deriver)
            advanceUntilIdle()

            assertEquals(
                CoinCatalog.coins.map { it.coin },
                vm.state.value.catalogAccounts.map { it.coin },
            )
            vm.open(vm.state.value.catalogAccounts.single { it.coin == "bitcoin" })
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertEquals("BTC", vm.state.value.selectedAsset?.symbol)
            vm.openAccountAddresses()
            assertTrue(vm.state.value.showingAddresses)
            vm.back()
            assertEquals(Screen.ASSET, vm.state.value.screen)
            vm.back()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)

            vm.showAddAccount()
            advanceUntilIdle()
            val litecoin = CoinCatalog.get("litecoin")
            assertContains(vm.state.value.availableCoins, litecoin)
            assertFalse(vm.state.value.supportsAccount(litecoin.coin))
            vm.addAccount(litecoin)
            advanceUntilIdle()
            assertTrue(vault.accounts(mode).any { it.coin == litecoin.coin })
            assertContains(vm.state.value.transfers, savedTransfer)
            vm.back()
            vm.refreshBalances()
            advanceUntilIdle()
            // All native direct routes and registered tokens remain readable with a Bitcoin
            // explorer selected.
            val expected =
                vm.state.value.assets
                    .filter { asset ->
                        (asset.coin == "bitcoin" && asset.contract.isEmpty()) ||
                            EvmNetworks.supportsPayment(asset) ||
                            LedgerNetworks.supportsPayment(asset)
                    }
                    .map { "${it.coin}:${it.symbol}" }
            assertEquals(expected.toSet(), queried.toSet())
            assertEquals(expected.size, queried.size)
            assertTrue(
                queried.containsAll(listOf("ethereum:USDC", "ethereum:BRZ", "bsc:BRZ", "bsc:USDT"))
            )

            vm.open(vm.state.value.catalogAccounts.single { it.coin == litecoin.coin })
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertFalse(vm.state.value.canSendSelectedAsset)
            assertFalse(vm.state.value.selectedAssetHasSendSupport)
            vm.send()
            assertEquals(Screen.ASSET, vm.state.value.screen)
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.FREEDOM),
                "https://api.airlock.pub",
                keepAssetOpen = true,
            )
            advanceUntilIdle()
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertEquals(litecoin.symbol, vm.state.value.selectedAsset?.symbol)
            assertTrue(vm.state.value.canSendSelectedAsset)
            vm.back()

            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.AIRLOCK),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            val usdc = Stablecoins.forNetwork("ethereum").single { it.symbol == "USDC" }
            assertTrue(vm.state.value.supportsToken(usdc))
            val usdcAsset = vm.state.value.assets.single(usdc::matches)
            assertTrue(vm.state.value.supportsToken(Stablecoins.ethereumBrz))
            val ethereum = vm.state.value.catalogAccounts.single { it.coin == "ethereum" }
            vm.open(ethereum)
            assertEquals(Screen.WALLETS, vm.state.value.screen)
            vm.open(usdcAsset)
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertTrue(vm.state.value.canSendSelectedAsset)
            vm.back()
            val brz = vm.state.value.assets.single(Stablecoins.ethereumBrz::matches)
            vm.open(brz)
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertEquals(brz, vm.state.value.selectedAsset)
            vm.openAccountAddresses()
            vm.addAsset()
            advanceUntilIdle()
            assertEquals(2, vm.state.value.selectedCurrencyAssets.size)
            vm.open(vm.state.value.selectedCurrencyAssets.last())
            assertEquals(Screen.ASSET, vm.state.value.screen)
            vm.back()
            assertEquals(Screen.WALLETS, vm.state.value.screen)
            assertFalse(vm.state.value.showingAddresses)
            vm.back()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)

            vm.saveNetworkSettings(NetworkSettings(), "https://api.airlock.pub")
            advanceUntilIdle()
            assertEquals(
                CoinCatalog.coins.map { it.coin },
                vm.state.value.catalogAccounts.map { it.coin },
            )
            val stellar = vm.state.value.catalogAccounts.single { it.coin == "stellar" }
            assertFalse(stellar.enabled)
            vm.open(stellar)
            assertEquals(Screen.WALLETS, vm.state.value.screen)
            vm.openCatalogAsset("USDC")
            assertEquals(Screen.ASSET, vm.state.value.screen)
            assertEquals("USDC", vm.state.value.selectedCatalogSymbol)
            assertFalse(vm.state.value.canSendSelectedAsset)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
