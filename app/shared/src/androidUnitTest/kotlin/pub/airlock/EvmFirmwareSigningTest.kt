package pub.airlock

import kotlin.test.*
import kotlinx.serialization.json.*
import pub.airlock.data.network.EvmEncoding
import pub.airlock.domain.*

/**
 * Transactions signed by the portable ESP32 signer from a public BIP39 fixture, never broadcast.
 */
class EvmFirmwareSigningTest {
    @Test
    fun everyEvmAssetHasAFirmwareSignatureBoundToItsChainContractAmountAndRecipient() {
        val stream =
            javaClass.classLoader!!.getResourceAsStream("evm_signing_vectors.json")
                ?: error("Missing firmware fixtures; regenerate with airlock_brz_tests")
        val rows = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        val expected =
            CoinCatalog.coins
                .filter { it.coin in EvmNetworks.chainIds }
                .flatMap { native ->
                    listOf(native.coin to native.symbol) +
                        Stablecoins.forNetwork(native.coin).map { native.coin to it.symbol }
                }
                .toSet()
        val checked = mutableSetOf<Pair<String, String>>()
        for (value in rows) {
            val row = value.jsonObject
            val chain = row.getValue("chain_id").jsonPrimitive.long
            val coin = EvmNetworks.chainIds.entries.single { it.value == chain }.key
            val native = CoinCatalog.get(coin)
            val symbol = row.getValue("symbol").jsonPrimitive.content
            var asset =
                Asset(
                    "fixture",
                    "fixture",
                    coin,
                    native.symbol,
                    native.name,
                    row.getValue("sender").jsonPrimitive.content,
                    native.icon,
                    native.decimals,
                    derivationPath = row.getValue("path").jsonPrimitive.content,
                )
            if (row.getValue("contract").jsonPrimitive.content.isNotEmpty()) {
                val token = Stablecoins.forNetwork(coin).single { it.symbol == symbol }
                assertEquals(
                    "0x" + row.getValue("contract").jsonPrimitive.content,
                    token.contract.lowercase(),
                )
                asset = token.asset(asset)
            }
            assertEquals(symbol, asset.symbol)
            assertTrue(checked.add(coin to symbol), "Duplicate fixture")
            val amount = Amount.display("1250000000000000000", asset.decimals)
            val to = "0x1111111111111111111111111111111111111111"
            val payment = EvmEncoding.payment(asset, to, amount)
            val plan =
                EvmTransactionPlan(
                    chain,
                    "0x0",
                    "0x3b9aca00",
                    "0xc350",
                    payment.first,
                    payment.second,
                    payment.third,
                    l1Fee = "12345".takeIf { coin == "base" },
                    operatorFee = "0".takeIf { coin == "base" },
                )
            val fee = Amount.display(if (coin == "base") "50000000012345" else "50000000000000", 18)
            val quote = TransferQuote(asset, to, amount, fee, evmPlan = plan)
            val raw = row.getValue("raw").jsonPrimitive.content
            assertTrue(EvmEncoding.transactionId(raw, quote).startsWith("0x"))
            assertFailsWith<InputException> {
                EvmEncoding.transactionId(raw, quote.copy(destination = asset.address))
            }
            assertFailsWith<InputException> {
                EvmEncoding.transactionId(raw, quote.copy(amount = "1"))
            }
            assertFailsWith<InputException> {
                EvmEncoding.transactionId(raw, quote.copy(fee = "0"))
            }
            assertFailsWith<InputException> {
                EvmEncoding.transactionId(raw, quote.copy(evmPlan = plan.copy(chainId = 31337)))
            }
        }
        assertEquals(expected, checked)
    }
}
