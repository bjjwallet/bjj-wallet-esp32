@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class StablecoinTest {
    private fun native(coin: String = "ethereum", balance: String = "1", index: Int = 0): Asset {
        val chain = CoinCatalog.get(coin)
        return Asset(
            "STANDARD:$coin:$index",
            "STANDARD:$coin",
            coin,
            chain.symbol,
            chain.name,
            "MOCK-$coin-$index",
            chain.icon,
            chain.decimals,
            balance,
            derivationPath = "m/0/$index",
        )
    }

    @Test
    fun allDeploymentsUseSeparateNativeFeesAndPreserveAddressDerivation() {
        for (token in Stablecoins.tokens) {
            val native = native(token.coin)
            val asset = token.asset(native).copy(balance = "100")
            assertEquals(native.address, asset.address)
            assertEquals(native.derivationPath, asset.derivationPath)
            assertEquals(token.decimals, asset.decimals)
            val unit = Amount.display("1", asset.decimals)
            val fee = if (native.decimals >= 8) "0.0000029" else "0.001"
            val quote =
                TransferQuote(
                    asset,
                    "MOCK-RECIPIENT",
                    Amount.display(
                        Amount.subtract(Amount.atomic("100", asset.decimals), "1"),
                        asset.decimals,
                    ),
                    fee,
                    cardanoPlan =
                        if (token.coin == "cardano")
                            CardanoTransactionPlan(emptyList(), 0, 1, 100000)
                        else null,
                )
            val balances = TransferAccounting.remaining(quote, listOf(native, asset))
            assertEquals(unit, balances[asset.id])
            assertEquals(
                if (token.coin == "cardano") "0.899"
                else if (native.decimals >= 8) "0.9999971" else "0.999",
                balances[native.id],
            )
            assertEquals(native.symbol, quote.feeCurrency.symbol)
        }
    }

    @Test
    fun sendingAllTokensStillRequiresNativeFeesAtTheSameAddressAndNetwork() {
        val native = native(balance = "0")
        val asset = Stablecoins.forNetwork(native.coin).first().asset(native).copy(balance = "100")
        val quote = TransferQuote(asset, "MOCK-RECIPIENT", "100", "0.0000029")
        val wrongAddress = native(index = 1)
        val wrongNetwork = native("arbitrum").copy(address = native.address)
        val error =
            assertFailsWith<InputException> {
                TransferAccounting.remaining(
                    quote,
                    listOf(native, asset, wrongAddress, wrongNetwork),
                )
            }
        assertEquals(InputError.INSUFFICIENT_FEE_BALANCE, error.reason)
        assertEquals(
            "0",
            TransferAccounting.remaining(quote, listOf(native.copy(balance = "1"), asset))[asset.id],
        )
        assertEquals(
            InputError.INSUFFICIENT_FEE_BALANCE,
            assertFailsWith<InputException> {
                    TransferAccounting.remaining(quote, listOf(asset, wrongAddress, wrongNetwork))
                }
                .reason,
        )
    }

    @Test
    fun rejectsWrongNetworkFeeSpoofedContractsAndExcessivePrecision() {
        val native = native()
        val asset =
            Stablecoins.forNetwork(native.coin)
                .single { it.symbol == "USDT" }
                .asset(native)
                .copy(balance = "100")
        val quote = TransferQuote(asset, "MOCK-RECIPIENT", "1", "0.0000029")
        assertEquals(
            InputError.INVALID_QUOTE,
            assertFailsWith<InputException> {
                    TransferAccounting.remaining(
                        quote.copy(feeCurrency = CoinCatalog.get("arbitrum")),
                        listOf(native, asset),
                    )
                }
                .reason,
        )
        val fake = asset.copy(contract = "0x0000000000000000000000000000000000000001")
        assertEquals(
            InputError.INTEGRATION_UNAVAILABLE,
            assertFailsWith<InputException> {
                    TransferAccounting.remaining(quote.copy(asset = fake), listOf(native, fake))
                }
                .reason,
        )
        assertEquals(
            InputError.DECIMAL_PLACES,
            assertFailsWith<InputException> {
                    TransferAccounting.remaining(
                        quote.copy(amount = "1.0000001"),
                        listOf(native, asset),
                    )
                }
                .reason,
        )
        val tron =
            Stablecoins.forNetwork("tron").single { it.symbol == "USDT" }.asset(native("tron"))
        val usdc = Stablecoins.forNetwork("ethereum").last().asset(native)
        assertEquals(
            listOf(asset, tron),
            Stablecoins.sources(asset, listOf(native, asset, tron, fake, usdc)),
        )
        assertNull(Stablecoins.definition(tron.copy(contract = tron.contract.lowercase())))
        assertNotNull(
            Stablecoins.definition(
                asset.copy(contract = asset.contract.uppercase().replace("0X", "0x"))
            )
        )
    }

    @Test
    fun migrationAddsMissingTokensOnceWithoutReplacingExistingBalancesOrOtherModes() = runTest {
        val storage = MemoryStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val native = native()
        val account =
            Account(
                native.accountId,
                native.coin,
                "Ethereum",
                "ETH",
                native.icon,
                WalletMode.STANDARD,
            )
        val token =
            Stablecoins.forNetwork(native.coin)
                .first()
                .asset(native)
                .copy(id = "existing-token", balance = "12.345678")
        val hidden = native.copy(id = "HIDDEN:ethereum:0", accountId = "HIDDEN:ethereum")
        vault.merge(WalletMode.STANDARD, WalletData(listOf(account), listOf(native, token)))
        vault.merge(
            WalletMode.HIDDEN,
            WalletData(
                listOf(account.copy(id = hidden.accountId, mode = WalletMode.HIDDEN)),
                listOf(hidden),
            ),
        )
        vault.registerStablecoins(WalletMode.STANDARD)
        vault.registerStablecoins(WalletMode.STANDARD)
        val assets = vault.assets(WalletMode.STANDARD)
        assertEquals(1 + Stablecoins.forNetwork(native.coin).size, assets.size)
        assertEquals(token, assets.first { it.id == token.id })
        assertEquals("0", assets.first { it.symbol == "USDC" }.balance)
        assertEquals(listOf(hidden), vault.assets(WalletMode.HIDDEN))
        assertEquals(
            vault.snapshot(),
            VaultRepository(storage, TestCrypto(), dispatcher).snapshot(),
        )
    }

    private fun flowTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.wallet(
        network: WalletNetwork = MockWalletNetwork(),
        hardware: HardwareWallet = DemoHardwareWallet(),
    ): Pair<WalletViewModel, MemoryStorage> {
        val storage = MemoryStorage()
        val vm =
            WalletViewModel(
                VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler)),
                hardware,
                network,
                DemoPublicKeyDeriver(),
            )
        advanceUntilIdle()
        vm.setup("normal123", "hidden456")
        advanceUntilIdle()
        vm.unlock("normal123")
        advanceUntilIdle()
        syncTestWallet(vm)
        advanceUntilIdle()
        return vm to storage
    }

    @Test
    fun switchesNetworkAndAddressInvalidatesQuoteAndPersistsBothDebits() = flowTest {
        var signed: TransferQuote? = null
        var broadcasts = 0
        val delegate = DemoHardwareWallet()
        val hardware =
            object : HardwareWallet by delegate {
                override suspend fun sign(
                    quote: TransferQuote,
                    paymentPin: String,
                    mode: WalletMode,
                ): String {
                    signed = quote
                    return delegate.sign(quote, paymentPin, mode)
                }
            }
        val networkDelegate = MockWalletNetwork()
        val network =
            object : WalletNetwork by networkDelegate {
                override suspend fun broadcast(signedTransaction: String): String {
                    broadcasts++
                    return networkDelegate.broadcast(signedTransaction)
                }
            }
        val (vm, storage) = wallet(network, hardware)
        val eth = vm.state.value.assets.first { it.coin == "ethereum" && it.symbol == "USDT" }
        val tron = vm.state.value.assets.first { it.coin == "tron" && it.symbol == "USDT" }
        vm.open(eth)
        vm.send()
        vm.prepareTransfer("MOCK-ETH-RECIPIENT", "1")
        advanceUntilIdle()
        assertEquals("ETH", vm.state.value.quote?.feeCurrency?.symbol)
        vm.back()
        assertNull(vm.state.value.quote)
        vm.selectTransferSource(tron.id)
        assertEquals(tron, vm.state.value.selectedAsset)
        assertEquals("tron", vm.state.value.selectedAccount?.coin)
        vm.prepareTransfer("MOCK-TRON-RECIPIENT", "5")
        advanceUntilIdle()
        assertEquals("TRX", vm.state.value.quote?.feeCurrency?.symbol)
        vm.confirmTransfer("abc123")
        vm.selectTransferSource(eth.id) // Busy: the signed origin must not change.
        vm.confirmTransfer("abc123")
        advanceUntilIdle()
        assertEquals(tron.contract, signed?.asset?.contract)
        assertEquals(tron.coin, signed?.asset?.coin)
        assertEquals("95", vm.state.value.selectedAsset?.balance)
        assertEquals(
            "249.999",
            vm.state.value.assets.first { it.coin == "tron" && it.contract.isEmpty() }.balance,
        )
        assertEquals(eth.balance, vm.state.value.assets.first { it.id == eth.id }.balance)
        assertEquals(1, broadcasts)
        vm.confirmTransfer("abc123")
        advanceUntilIdle()
        assertEquals(1, broadcasts)
        val restored =
            VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler)).snapshot()
        assertEquals("95", restored.assets.first { it.id == tron.id }.balance)
        assertEquals(
            "249.999",
            restored.assets.first { it.coin == "tron" && it.contract.isEmpty() }.balance,
        )
        assertEquals(tron.id, restored.transfers.single().assetId)
    }

    @Test
    fun addingAddressesDoesNotCountTokensAsAddressesAndSelectionRejectsOtherTokensOrModes() =
        flowTest {
            val (vm, _) = wallet()
            val account = vm.state.value.accounts.first { it.coin == "ethereum" }
            vm.open(account)
            vm.addAsset()
            advanceUntilIdle()
            val native = vm.state.value.assets.first { it.id == "STANDARD:ethereum:1" }
            assertTrue(
                vm.state.value.assets.any {
                    it.id == "${native.id}:USDT" && it.address == native.address
                }
            )
            assertEquals(
                2 * (1 + Stablecoins.forNetwork(account.coin).size),
                vm.state.value.assets.count { it.accountId == account.id },
            )
            val asset = vm.state.value.assets.first { it.id == "STANDARD:ethereum:0:USDT" }
            vm.open(asset)
            vm.send()
            vm.selectTransferSource("STANDARD:ethereum:0:USDC")
            assertEquals(asset, vm.state.value.selectedAsset)
            vm.selectTransferSource("HIDDEN:ethereum:0:USDT")
            assertEquals(asset, vm.state.value.selectedAsset)
            vm.selectTransferSource("${native.id}:USDT")
            assertEquals("0", vm.state.value.selectedAsset?.balance)
            vm.lock()
            vm.unlock("hidden456")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            assertTrue(vm.state.value.assets.all { it.balance == "0" })
            assertTrue(vm.state.value.assets.all { it.accountId.startsWith("HIDDEN:") })
            assertNull(vm.state.value.quote)
        }

    @Test
    fun mismatchedEstimateCannotReachHardwareOrBroadcast() = flowTest {
        var signed = false
        var broadcast = false
        val hardware =
            object : HardwareWallet by DemoHardwareWallet() {
                override suspend fun sign(
                    quote: TransferQuote,
                    paymentPin: String,
                    mode: WalletMode,
                ): String {
                    signed = true
                    return "MOCK_SIGNED"
                }
            }
        val network =
            object : WalletNetwork by MockWalletNetwork() {
                override suspend fun estimate(asset: Asset, destination: String, amount: String) =
                    TransferQuote(asset, "DIFFERENT-RECIPIENT", amount, "0.0000029")

                override suspend fun broadcast(signedTransaction: String): String {
                    broadcast = true
                    return "tx"
                }
            }
        val (vm, _) = wallet(network, hardware)
        vm.open(vm.state.value.assets.first { it.symbol == "USDT" })
        vm.send()
        vm.prepareTransfer("MOCK-RECIPIENT", "1")
        advanceUntilIdle()
        assertEquals(InputError.INVALID_QUOTE, vm.state.value.error)
        assertEquals(Screen.SEND, vm.state.value.screen)
        assertNull(vm.state.value.quote)
        vm.confirmTransfer("abc123")
        advanceUntilIdle()
        assertFalse(signed)
        assertFalse(broadcast)
    }
}
