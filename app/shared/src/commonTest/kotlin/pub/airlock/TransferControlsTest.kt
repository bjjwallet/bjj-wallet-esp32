@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*
import pub.airlock.ui.atomicAmountEdit

class TransferControlsTest {
    private fun native(coin: String, balance: String = "1"): Asset {
        val definition = CoinCatalog.get(coin)
        return Asset(
            coin,
            coin,
            coin,
            definition.symbol,
            definition.name,
            "public-address",
            definition.icon,
            definition.decimals,
            balance,
        )
    }

    private fun state(asset: Asset, provider: NetworkProvider) =
        WalletUiState(
            mode = WalletMode.STANDARD,
            selectedAccount =
                Account(
                    asset.accountId,
                    asset.coin,
                    asset.name,
                    asset.symbol,
                    asset.icon,
                    WalletMode.STANDARD,
                ),
            selectedAsset = asset,
            networkSettings = NetworkSettings(provider),
        )

    @Test
    fun sendUsesDirectRoutesAndOtherwiseRequiresACompatibleSelectedApi() {
        for (provider in NetworkProvider.available) {
            for (coin in EvmNetworks.rpcUrls.keys + "solana" + "cardano") {
                val asset = native(coin)
                assertTrue(ProviderCoverage.hasDirectSendRoute(asset), coin)
                assertTrue(state(asset, provider).canSendSelectedAsset, "$provider/$coin")
                assertTrue(state(asset, provider).selectedAssetHasSendSupport)
            }
        }
        assertTrue(state(native("bitcoin", "0"), NetworkProvider.BLOCKSTREAM).canSendSelectedAsset)
        assertFalse(ProviderCoverage.hasDirectSendRoute(native("bitcoin")))
        assertTrue(
            ProviderCoverage.selectedApiSupportsSend(NetworkProvider.BLOCKSTREAM, native("bitcoin"))
        )
        assertFalse(state(native("litecoin"), NetworkProvider.BLOCKSTREAM).canSendSelectedAsset)
        assertFalse(state(native("bitcoincash"), NetworkProvider.MEMPOOL).canSendSelectedAsset)
        assertTrue(state(native("litecoin"), NetworkProvider.FREEDOM).canSendSelectedAsset)
        assertFalse(state(native("bitcoin"), NetworkProvider.MORALIS).canSendSelectedAsset)
        for (coin in listOf("tron", "ripple", "sui", "stellar", "ton")) for (provider in
            NetworkProvider.available) {
            assertTrue(state(native(coin), provider).canSendSelectedAsset, "$provider/$coin")
        }
    }

    @Test
    fun aTokenNeedsAnImplementedPaymentRouteRatherThanOnlyAnRpcForItsChain() {
        val eth = native("ethereum")
        val usdc = Stablecoins.forNetwork("ethereum").single { it.symbol == "USDC" }.asset(eth)
        assertTrue(state(usdc, NetworkProvider.BLOCKSTREAM).canSendSelectedAsset)
        val unknown = usdc.copy(contract = "0x" + "f".repeat(40))
        assertFalse(state(unknown, NetworkProvider.BLOCKSTREAM).canSendSelectedAsset)
        assertFalse(state(unknown, NetworkProvider.AIRLOCK).selectedAssetHasSendSupport)
        assertFalse(state(eth.copy(decimals = 6), NetworkProvider.BLOCKSTREAM).canSendSelectedAsset)
        val suiUsdc = Stablecoins.forNetwork("sui").single().asset(native("sui"))
        assertTrue(state(suiUsdc, NetworkProvider.MORALIS).canSendSelectedAsset)
    }

    @Test
    fun amountLimitPreservesExactPrecisionForEveryCoinAndToken() {
        for (coin in CoinCatalog.coins) {
            val precisions =
                listOf(coin.decimals) + Stablecoins.forNetwork(coin.coin).map { it.decimals }
            for (precision in precisions) {
                val exact = "9007199254740993"
                val maximum = Amount.display(exact, precision)
                assertEquals(
                    exact,
                    atomicAmountEdit(
                            TextFieldValue(exact, TextRange(exact.length)),
                            precision,
                            maximum,
                            false,
                        )
                        .text,
                )
                val error =
                    assertFailsWith<InputException> {
                        atomicAmountEdit(
                            TextFieldValue(Amount.add(exact, "1")),
                            precision,
                            maximum,
                            false,
                        )
                    }
                assertEquals(InputError.INSUFFICIENT_BALANCE, error.reason)
            }
        }
    }

    @Test
    fun zeroBalanceAllowsClearingAndRejectsOneAtomicUnitUnlessTestModeIsEnabled() {
        assertEquals("", atomicAmountEdit(TextFieldValue(), 8, "0", false).text)
        assertEquals("0", atomicAmountEdit(TextFieldValue("0"), 8, "0", false).text)
        assertEquals(
            InputError.INSUFFICIENT_BALANCE,
            assertFailsWith<InputException> {
                    atomicAmountEdit(TextFieldValue("1", TextRange(1)), 8, "0", false)
                }
                .reason,
        )
        assertEquals("1", atomicAmountEdit(TextFieldValue("1", TextRange(1)), 8, "0", true).text)
        TransferAmountPolicy.requireWithinBalance("100", native("bitcoin", "0"), testMode = true)
        assertFailsWith<InputException> { atomicAmountEdit(TextFieldValue("-1"), 8, "0", true) }
    }

    @Test
    fun moneroUsesUnlockedBalanceAndDoesNotTreatUnknownUnlockedFundsAsAvailable() {
        val asset = native("monero", "10").copy(unlockedBalance = "1")
        assertEquals("1", TransferAmountPolicy.availableBalance(asset))
        TransferAmountPolicy.requireWithinBalance("1", asset, false)
        assertEquals(
            InputError.INSUFFICIENT_BALANCE,
            assertFailsWith<InputException> {
                    TransferAmountPolicy.requireWithinBalance("1.000000000001", asset, false)
                }
                .reason,
        )
        assertEquals("0", TransferAmountPolicy.availableBalance(asset.copy(unlockedBalance = null)))
        assertFailsWith<InputException> {
            TransferAmountPolicy.requireWithinBalance(
                "0.000000000001",
                asset.copy(unlockedBalance = null),
                false,
            )
        }
    }

    @Test
    fun testInputModeDoesNotFabricateFundsOrBypassFeeAccounting() {
        val asset = native("bitcoin", "0")
        TransferAmountPolicy.requireWithinBalance("1", asset, true)
        val quote = TransferQuote(asset, "recipient", "1", "0.00001")
        assertEquals(
            InputError.INSUFFICIENT_BALANCE,
            assertFailsWith<InputException> { TransferAccounting.remaining(quote, listOf(asset)) }
                .reason,
        )
    }

    @Test
    fun viewModelRejectsAnAmountAboveBalanceBeforeEstimatingOrSigning() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val asset = native("bitcoin", "1")
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            vault.update {
                it.copy(
                    appSecurityConfigured = true,
                    accounts =
                        listOf(
                            Account(
                                asset.accountId,
                                asset.coin,
                                asset.name,
                                asset.symbol,
                                asset.icon,
                                WalletMode.STANDARD,
                            )
                        ),
                    assets = listOf(asset),
                )
            }
            var estimates = 0
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun estimate(
                        asset: Asset,
                        destination: String,
                        amount: String,
                    ): TransferQuote {
                        estimates++
                        error("No estimate may run above the available balance")
                    }
                }
            val vm =
                WalletViewModel(
                    vault,
                    object : HardwareWallet by DemoHardwareWallet() {
                        override val simulated = false
                    },
                    network,
                    DemoPublicKeyDeriver(),
                    transferTestMode = false,
                )
            advanceUntilIdle()
            vm.open(vm.state.value.assets.single())
            vm.send()
            vm.prepareTransfer("public-recipient", "1.00000001")
            advanceUntilIdle()
            assertEquals(InputError.INSUFFICIENT_BALANCE, vm.state.value.error)
            assertEquals(0, estimates)
            assertNull(vm.state.value.quote)
            assertEquals(Screen.SEND, vm.state.value.screen)
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.MORALIS, moralisKey = "test_key"),
                "https://api.airlock.pub",
                keepAssetOpen = true,
            )
            advanceUntilIdle()
            vm.prepareTransfer("public-recipient", "0.1")
            advanceUntilIdle()
            assertEquals(InputError.NETWORK_UNSUPPORTED, vm.state.value.error)
            assertEquals(0, estimates)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
