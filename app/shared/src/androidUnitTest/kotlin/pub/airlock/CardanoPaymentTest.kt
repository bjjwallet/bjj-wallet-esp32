package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.CardanoBlake2b
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*
import pub.airlock.platform.AndroidEd25519Verifier

class CardanoPaymentTest {
    @Test
    fun quotesPureAdaAndChecksTheFirmwareWitnessBeforeSubmitting() = runTest {
        val sender =
            "addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlft05dz3c7revpf7jx0xnlcjz3g69mq4afdhv"
        val destination =
            "addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x"
        val witness =
            "7ea09a34aebb13c9841c71397b1cabfec5ddf950405293dee496cac2f437480acc2b37e666c34921416762da462a02dd7301582e9c650ba29bb5c5595ac74c5fae69d7dcb0acb071e8ac2bb26d6e14e3d5edb57823d830288fafc224728c070b"
        val id = "c65e5892f1b66f42b3e21ea7cd28cb972d962de2cbb8b243b3cbaa73d98079e0"
        val asset = Asset("ada", "account", "cardano", "ADA", "Cardano", sender, "ada.svg", 6, "5")
        var slot = 203351000
        var submitted = 0
        var confirmed = false
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals("api.koios.rest", request.url.host)
                    val response =
                        when (request.url.encodedPath) {
                            "/api/v1/tip" -> """[{"abs_slot":$slot,"epoch_no":668}]"""
                            "/api/v1/epoch_params" -> {
                                assertEquals("668", request.url.parameters["_epoch_no"])
                                """[{"epoch_no":668,"min_fee_a":44,"min_fee_b":155381,"coins_per_utxo_size":"4310","max_tx_size":16384}]"""
                            }
                            "/api/v1/address_utxos" -> {
                                assertEquals(JsonPrimitive(true), request.bodyJson()["_extended"])
                                assertEquals(
                                    JsonArray(listOf(JsonPrimitive(sender))),
                                    request.bodyJson()["_addresses"],
                                )
                                val hash = "1".repeat(64)
                                val other = "2".repeat(64)
                                """[{"address":"$sender","tx_hash":"$other","tx_index":1,"value":"10000000","asset_list":[{}],"datum_hash":null,"inline_datum":null,"reference_script":null},
                        {"address":"$sender","tx_hash":"$hash","tx_index":0,"value":"5000000","asset_list":[],"datum_hash":null,"inline_datum":null,"reference_script":null}]"""
                            }
                            "/api/v1/tx_status" -> {
                                assertEquals(
                                    JsonArray(listOf(JsonPrimitive(id))),
                                    request.bodyJson()["_tx_hashes"],
                                )
                                if (confirmed) """[{"tx_hash":"$id","num_confirmations":1}]"""
                                else "[]"
                            }
                            "/api/v1/submittx" -> {
                                assertEquals(HttpMethod.Post, request.method)
                                val bytes =
                                    (request.body as OutgoingContent.ByteArrayContent).bytes()
                                assertEquals(
                                    "application/cbor",
                                    request.body.contentType.toString(),
                                )
                                assertEquals(300, bytes.size)
                                assertTrue(bytes.hex().endsWith("f5f6"))
                                submitted++
                                "\" $id \"".replace(" ", "")
                            }
                            else -> error("Unexpected Koios endpoint")
                        }
                    respond(response)
                }
            )
        try {
            val network =
                ProductionWalletNetwork(client, AndroidEd25519Verifier) { VaultSnapshot() }
            val quote = network.estimate(asset, destination, "2")
            assertEquals("0.168581", quote.fee)
            assertEquals(2831419L, quote.cardanoPlan!!.change)
            assertEquals(id, network.transactionId(witness, quote))
            assertEquals(
                InputError.INVALID_QUOTE,
                assertFailsWith<InputException> {
                        network.broadcast(witness.replaceRange(0, 2, "00"), quote)
                    }
                    .reason,
            )
            slot = 203351900
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { network.broadcast(witness, quote) }.reason,
            )
            assertEquals(0, submitted)
            slot--
            assertEquals(id, network.broadcast(witness, quote))
            confirmed = true
            assertEquals(id, network.broadcast(witness, quote))
            assertEquals(1, submitted)
            assertEquals(TransferStatus.CONFIRMED, network.lastSubmissionStatus)
        } finally {
            client.close()
        }
    }

    @Test
    fun matchesIndependentBlake2bVectors() {
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            CardanoBlake2b.hash(byteArrayOf(), 32).hex(),
        )
        assertEquals(
            "9bd237b02a29e43bdd6738afa5b53ff0eee178d6210b618e4511aec8",
            CardanoBlake2b.hash("abc".encodeToByteArray(), 28).hex(),
        )
    }
}
