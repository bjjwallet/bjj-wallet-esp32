package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.domain.*
import pub.airlock.platform.AndroidEd25519Verifier

class SolanaPaymentTest {
    @Test
    fun verifiesSignatureAndOnlySubmitsTheApprovedUnexpiredMessage() = runTest {
        val sender = "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL"
        val recipient = "CktRuQ2mttgRGkXJtyksdKHjUdc2C4TgDzyB98oEzy8"
        val blockhash = "US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx"
        val message =
            "AQABA+m2BihBu5d60h3nHslhkAYzwm8hOE4BWwFKY3phSZVHAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHAQICAAEMAgAAAIB8gUoAAAAA"
        val signature =
            "b972b067c5ba0426747dba3c8fd50cc9aa9f8262e1fc3e3c717d3c45de9b5948c00021d6c131ff2d3ba2c0fb8f99f1d0f8b255335e590e13608ce40d59233201"
        val id =
            "4i3hgYQKVmhGK3Zv6Ya5xXW29mzatJ4JXXvu8XaZnG7zCkBJ5HrbDtX4WJ24T7YbkUoCtaVjyV1Hp3td47NsiA5n"
        val asset = Asset("sol", "account", "solana", "SOL", "Solana", sender, "sol.svg", 9, "2")
        var valid = false
        var broadcasts = 0
        var confirmed = false
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals("api.mainnet.solana.com", request.url.host)
                    val params = request.bodyJson()["params"]!!.jsonArray
                    val result =
                        when (request.methodName()) {
                            "getLatestBlockhash" ->
                                buildJsonObject {
                                    putJsonObject("value") { put("blockhash", blockhash) }
                                }
                            "getFeeForMessage" -> {
                                assertEquals(JsonPrimitive(message), params.first())
                                buildJsonObject { put("value", 5000) }
                            }
                            "getSignatureStatuses" ->
                                buildJsonObject {
                                    putJsonArray("value") {
                                        if (confirmed)
                                            addJsonObject {
                                                put("err", JsonNull)
                                                put("confirmationStatus", "confirmed")
                                            }
                                        else add(JsonNull)
                                    }
                                }
                            "isBlockhashValid" -> {
                                assertEquals(JsonPrimitive(blockhash), params.first())
                                buildJsonObject { put("value", valid) }
                            }
                            "sendTransaction" -> {
                                val transaction =
                                    Base64.decode(params.first().jsonPrimitive.content)
                                assertEquals(1, transaction.first().toInt())
                                assertEquals(
                                    signature,
                                    transaction.sliceArray(1..64).joinToString("") {
                                        (it.toInt() and 255).toString(16).padStart(2, '0')
                                    },
                                )
                                assertContentEquals(
                                    Base64.decode(message),
                                    transaction.copyOfRange(65, transaction.size),
                                )
                                broadcasts++
                                JsonPrimitive(id)
                            }
                            else -> error("Unexpected Solana RPC")
                        }
                    respond(rpc(result).body)
                }
            )
        try {
            val network =
                ProductionWalletNetwork(client, AndroidEd25519Verifier) { VaultSnapshot() }
            val quote = network.estimate(asset, recipient, "1.25")
            assertEquals("0.000005", quote.fee)
            assertEquals(id, network.transactionId(signature, quote))
            assertEquals(
                InputError.INVALID_QUOTE,
                assertFailsWith<InputException> {
                        network.broadcast(signature, quote.copy(amount = "1.26"))
                    }
                    .reason,
            )
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { network.broadcast(signature, quote) }.reason,
            )
            assertEquals(0, broadcasts)
            valid = true
            assertEquals(id, network.broadcast(signature, quote))
            confirmed = true
            assertEquals(id, network.broadcast(signature, quote))
            assertEquals(1, broadcasts)
            assertEquals(TransferStatus.CONFIRMED, network.lastSubmissionStatus)
        } finally {
            client.close()
        }
    }
}
