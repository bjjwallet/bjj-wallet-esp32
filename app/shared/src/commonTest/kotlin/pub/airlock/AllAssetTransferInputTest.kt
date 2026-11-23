package pub.airlock

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*
import pub.airlock.domain.*
import pub.airlock.presentation.WalletUiState
import pub.airlock.ui.atomicAmountEdit

/** Offline input coverage uses public reference addresses, never a user's wallet. */
class AllAssetTransferInputTest {
    private fun assets(): List<Asset> =
        CoinCatalog.coins.flatMap { coin ->
            val address =
                PublicWalletFixtures.standard
                    .firstOrNull { it.coin == coin.coin }
                    ?.addresses
                    ?.first()
                    ?.address
                    ?: when (coin.coin) {
                        "base",
                        "hyperevm",
                        "robinhood" ->
                            PublicWalletFixtures.standard
                                .single { it.coin == "ethereum" }
                                .addresses
                                .first()
                                .address
                        "ecash" -> "ecash:qpluxjhhlxfjwsymf9nmctvsdrwzwygadsh2pq0ang"
                        "cardano" ->
                            "addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlft05dz3c7revpf7jx0xnlcjz3g69mq4afdhv"
                        "stellar" -> "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"
                        "monero" ->
                            "5Aur6tRd1TTFK3wYL2hLWQFGt87ZTeRkufS6JoQZu6MEFDokAQeGWmu9MA3GFq1yVLSJQbKJqVAn9F9DLYGpRzRAETHfXkW"
                        else -> error("Missing public address for ${coin.coin}")
                    }
            val native =
                Asset(
                    coin.coin,
                    coin.coin,
                    coin.coin,
                    coin.symbol,
                    coin.name,
                    address,
                    coin.icon,
                    coin.decimals,
                    "100",
                )
            listOf(native) + Stablecoins.forNetwork(coin.coin).map { it.asset(native) }
        }

    @Test
    fun typingDeletingReplacingAndRestoringWorkForEveryAssetInEveryLanguage() {
        for (asset in assets()) for (language in AppLanguage.entries) {
            val context = "${asset.coin}/${asset.symbol}/${language.code}"
            var field = TextFieldValue()
            for (digit in "123456789") {
                val proposed = field.text + digit
                field =
                    atomicAmountEdit(
                        TextFieldValue(proposed, TextRange(proposed.length)),
                        asset.decimals,
                        "10000000000",
                        false,
                    )
                val decimal = AtomicAmountInput.decimal(field.text, asset.decimals, language.code)
                assertEquals(
                    field.text,
                    Amount.atomic(DecimalInput.canonical(decimal, asset.decimals), asset.decimals),
                    context,
                )
                assertEquals(
                    field.text,
                    AtomicAmountInput.restore(decimal, asset.decimals),
                    context,
                )
                assertEquals(TextRange(field.text.length), field.selection, context)
            }
            // Replace a selected middle range, then erase the entire draft one digit at a time.
            field =
                atomicAmountEdit(
                    TextFieldValue(field.text.replaceRange(2, 5, "90"), TextRange(4)),
                    asset.decimals,
                    "10000000000",
                    false,
                )
            assertEquals("12906789", field.text, context)
            while (field.text.isNotEmpty()) {
                val shortened = field.text.dropLast(1)
                field =
                    atomicAmountEdit(
                        TextFieldValue(shortened, TextRange(shortened.length)),
                        asset.decimals,
                        "10000000000",
                        false,
                    )
            }
            assertEquals(
                "",
                AtomicAmountInput.decimal(field.text, asset.decimals, language.code),
                context,
            )
        }
    }

    @Test
    fun recipientQrPreservesThePublicAddressForEveryNativeCoinAndToken() {
        for (asset in assets()) {
            assertEquals(
                asset.address,
                RecipientQr.address("  ${asset.address}\n", asset.coin),
                "${asset.coin}/${asset.symbol}",
            )
            assertNull(RecipientQr.address("https://example.com/${asset.address}", asset.coin))
            // Required payment instructions must never silently become a plain address.
            val body = asset.address.substringAfter(':')
            assertNull(RecipientQr.address("${asset.coin}:$body?req-unknown=1", asset.coin))
        }
    }

    @Test
    fun eachAssetChecksItsOwnAtomicBalanceWithAnExplicitTestModeOverride() {
        for (asset in assets()) {
            val balance = Amount.display("123456789", asset.decimals)
            val funded = asset.copy(balance = balance, unlockedBalance = balance)
            TransferAmountPolicy.requireWithinBalance(balance, funded, false)
            val over = Amount.display("123456790", asset.decimals)
            assertEquals(
                InputError.INSUFFICIENT_BALANCE,
                assertFailsWith<InputException> {
                        TransferAmountPolicy.requireWithinBalance(over, funded, false)
                    }
                    .reason,
                "${asset.coin}/${asset.symbol}",
            )
            TransferAmountPolicy.requireWithinBalance(over, funded, true)
            assertFailsWith<InputException> { AtomicAmountInput.edit("-1", asset.decimals) }
            assertFailsWith<InputException> { AtomicAmountInput.edit("1e3", asset.decimals) }
            println(
                "ASSET_INPUT_OK|${asset.coin}|${asset.symbol}|${asset.decimals}|${asset.contract.isNotEmpty()}"
            )
        }
    }

    @Test
    fun sendAvailabilityMatchesImplementedSigningAndSubmissionForEveryAsset() {
        val evm =
            setOf(
                "ethereum",
                "bsc",
                "avalanche",
                "ethereumclassic",
                "arbitrum",
                "polygon",
                "base",
                "hyperevm",
                "robinhood",
            )
        val utxo = setOf("bitcoin", "litecoin", "dogecoin", "dash", "bitcoincash", "ecash")
        for (asset in assets().filter { it.coin != "monero" }) for (provider in
            NetworkProvider.available) {
            val expected =
                asset.coin in evm ||
                    asset.coin in setOf("solana", "stellar", "tron", "ripple", "sui", "ton") ||
                    (asset.coin == "cardano" ||
                        (asset.contract.isEmpty() &&
                            (asset.coin in utxo &&
                                (provider == NetworkProvider.FREEDOM ||
                                    (asset.coin == "bitcoin" &&
                                        provider in
                                            setOf(
                                                NetworkProvider.BLOCKSTREAM,
                                                NetworkProvider.MEMPOOL,
                                            ))))))
            val state =
                WalletUiState(
                    mode = WalletMode.STANDARD,
                    selectedAsset = asset,
                    selectedAccount =
                        Account(
                            asset.accountId,
                            asset.coin,
                            asset.name,
                            asset.symbol,
                            asset.icon,
                            WalletMode.STANDARD,
                        ),
                    networkSettings = NetworkSettings(provider),
                )
            assertEquals(
                expected,
                ProviderCoverage.canSubmit(provider, asset),
                "Direct route ${asset.coin}/${asset.symbol}/$provider",
            )
            if (asset.contract.isEmpty())
                assertEquals(expected, ProviderCoverage.canSubmit(provider, asset.coin))
            else
                assertEquals(
                    expected,
                    ProviderCoverage.canSubmit(provider, Stablecoins.definition(asset)!!),
                )
            assertEquals(
                expected,
                state.canSendSelectedAsset,
                "${asset.coin}/${asset.symbol}/$provider",
            )
            assertEquals(
                expected,
                state.selectedAssetHasSendSupport,
                "${asset.coin}/${asset.symbol}/$provider",
            )
        }
    }
}
