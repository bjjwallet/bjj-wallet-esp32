package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.CardanoPayment
import pub.airlock.domain.*
import pub.airlock.platform.AndroidEd25519Verifier

class CardanoTokenPaymentTest {
    private val vectors
        get() =
            javaClass.classLoader!!
                .getResourceAsStream("cardano_token_signing_vectors.json")!!
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonArray }

    @Test
    fun everyRegisteredCardanoTokenHasAVerifiedFirmwareSignatureAndBoundChange() {
        val checked = mutableSetOf<String>()
        for (value in vectors) {
            val row = value.jsonObject
            val request = row.getValue("request").jsonObject
            fun field(name: String) = request.getValue(name).jsonPrimitive.content
            val token = Stablecoins.forNetwork("cardano").single { it.symbol == field("asset") }
            val native =
                Asset(
                    "ada",
                    "account",
                    "cardano",
                    "ADA",
                    "Cardano",
                    field("sender"),
                    "ada.svg",
                    6,
                    "10",
                    derivationPath = PublicNetworks.cardano.path,
                )
            val asset = token.asset(native)
            val quote =
                TransferQuote(
                    asset,
                    field("recipient"),
                    Amount.display(field("amount_atomic"), token.decimals),
                    Amount.display(field("fee_atomic"), 6),
                    cardanoPlan =
                        CardanoTransactionPlan(
                            listOf(CardanoInput("1".repeat(64), 0, 5000000, 2500000)),
                            field("change_atomic").toLong(),
                            field("ttl").toLong(),
                            field("recipient_lovelace").toLong(),
                        ),
                )
            val witness = row.getValue("witness").jsonPrimitive.content
            assertEquals(
                64,
                CardanoPayment.transactionId(witness, quote, AndroidEd25519Verifier).length,
            )
            assertTrue(checked.add(token.symbol))
            for (modified in
                listOf(
                    quote.copy(amount = "1"),
                    quote.copy(destination = native.address),
                    quote.copy(fee = "0.2"),
                    quote.copy(asset = asset.copy(contract = "0".repeat(56))),
                    quote.copy(cardanoPlan = quote.cardanoPlan!!.copy(change = 2831420)),
                )) {
                assertFailsWith<InputException> {
                    CardanoPayment.transactionId(witness, modified, AndroidEd25519Verifier)
                }
            }
            assertFailsWith<InputException> {
                CardanoPayment.transactionId("00".repeat(96), quote, AndroidEd25519Verifier)
            }
            val remaining =
                TransferAccounting.remaining(
                    quote.copy(asset = asset.copy(balance = "10000000")),
                    listOf(native, asset.copy(balance = "10000000")),
                )
            assertEquals("7.831419", remaining[native.id])
        }
        assertEquals(Stablecoins.forNetwork("cardano").map { it.symbol }.toSet(), checked)
    }

    @Test
    fun estimatesEveryTokenWithNativeFeesMinimumAdaAndNoLossOfUnrelatedTokens() = runTest {
        val request = vectors.first().jsonObject.getValue("request").jsonObject
        val sender = request.getValue("sender").jsonPrimitive.content
        val recipient = request.getValue("recipient").jsonPrimitive.content
        for (token in Stablecoins.forNetwork("cardano")) {
            val native =
                Asset("ada", "account", "cardano", "ADA", "Cardano", sender, "ada.svg", 6, "10")
            val asset = token.asset(native)
            val client =
                HttpClient(
                    MockEngine { call ->
                        val result =
                            when (call.url.encodedPath) {
                                "/api/v1/tip" -> """[{"abs_slot":203351000,"epoch_no":668}]"""
                                "/api/v1/epoch_params" ->
                                    """[{"epoch_no":668,"min_fee_a":44,"min_fee_b":155381,"coins_per_utxo_size":"4310","max_tx_size":16384}]"""
                                "/api/v1/address_utxos" ->
                                    """[
                        {"address":"$sender","tx_hash":"${"2".repeat(64)}","tx_index":0,"value":"9000000","asset_list":[{"policy_id":"${"f".repeat(56)}","asset_name":"00","quantity":"999"}],"datum_hash":null,"inline_datum":null,"reference_script":null},
                        {"address":"$sender","tx_hash":"${"1".repeat(64)}","tx_index":0,"value":"5000000","asset_list":[{"policy_id":"${token.contract.take(56)}","asset_name":"${token.contract.drop(56)}","quantity":"2500000"}],"datum_hash":null,"inline_datum":null,"reference_script":null}]
                    """
                                        .trimIndent()
                                else -> error("Unexpected endpoint ${call.url.encodedPath}")
                            }
                        respond(result)
                    }
                )
            try {
                val quote =
                    ProductionWalletNetwork(client, AndroidEd25519Verifier)
                        .estimate(asset, recipient, Amount.display("1250000", asset.decimals))
                val plan = quote.cardanoPlan!!
                assertEquals(listOf("1".repeat(64)), plan.inputs.map { it.txHash })
                assertEquals(2500000L, plan.inputs.single().tokenQuantity)
                assertTrue(plan.recipientLovelace > 1000000)
                assertTrue(CardanoPayment.lovelace(quote.fee) > 168581)
                assertEquals(
                    5000000L,
                    plan.recipientLovelace + plan.change + CardanoPayment.lovelace(quote.fee),
                )
                assertEquals(CoinCatalog.get("cardano"), quote.feeCurrency)
            } finally {
                client.close()
            }
        }
    }
}
