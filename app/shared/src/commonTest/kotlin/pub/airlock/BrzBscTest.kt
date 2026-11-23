package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.*
import pub.airlock.domain.*

class BrzBscTest {
    private val recipient = "0x1111111111111111111111111111111111111111"
    private val sender = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val bscSigned =
        "0xf8a980843b9aca0082c350940295afd3d7e86068050d64509e515f2db71b491480b844a9059cbb00000000000000000000000011111111111111111111111111111111111111110000000000000000000000000000000000000000000000001158e460913d00008194a0bd96a09913595c21bd85f624ecd28dfa4c362ab3c6dd4225160bc0c3c5d00bbea066afad6e2f7e48362d88ceeaf61747f398113179c749134e0f4a48c730447568"
    private val ethSigned =
        "0xf8a880843b9aca0082c3509401d33fd36ec67c6ada32cf36b31e88ee190b183980b844a9059cbb00000000000000000000000011111111111111111111111111111111111111110000000000000000000000000000000000000000000000001158e460913d000025a0d0c7b54fb35188b8fd333833eb4375b1f60a3ec6324ef31154d3b3223c3f1b93a01b28534f4ad0051fe046f0cab674f960b2f3a03b04a1fdff13bb2f802328795b"

    @Test
    fun firmwareSignaturesUseFixedEvmRoutesAndKnownTransactionsAreNeverResubmitted() = runTest {
        for ((token, chain, signed) in
            listOf(
                Triple(Stablecoins.bscBrz, "smartchain", bscSigned),
                Triple(Stablecoins.ethereumBrz, "ethereum", ethSigned),
            )) {
            val definition = CoinCatalog.get(token.coin)
            val native =
                Asset(
                    "native",
                    "account",
                    token.coin,
                    definition.symbol,
                    definition.name,
                    sender,
                    definition.icon,
                    18,
                    "1",
                    derivationPath = PublicNetworks.path(token.coin, 0),
                )
            val brz = token.asset(native).copy(balance = "1.25")
            var hash = ""
            var broadcasts = 0
            var known = false
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body =
                            if (request.url.host == "api.airlock.pub") {
                                assertEquals("/balance/$chain/$sender", request.url.encodedPath)
                                assertEquals(token.contract, request.url.parameters["contract"])
                                buildJsonObject {
                                        put("chain", chain)
                                        put("address", sender)
                                        put("contract", token.contract)
                                        put("balance", "1.25")
                                    }
                                    .toString()
                            } else {
                                assertEquals(
                                    if (token.coin == "bsc") "bsc-dataseed.bnbchain.org"
                                    else "ethereum-rpc.publicnode.com",
                                    request.url.host,
                                )
                                assertEquals(HttpMethod.Post, request.method)
                                val reply =
                                    when (request.methodName()) {
                                        "eth_chainId" ->
                                            rpc(
                                                EvmEncoding.hex(
                                                    if (token.coin == "bsc") "56" else "1"
                                                )
                                            )
                                        "eth_call" -> {
                                            val call =
                                                request
                                                    .bodyJson()["params"]!!
                                                    .jsonArray
                                                    .first()
                                                    .jsonObject
                                            assertEquals(
                                                token.contract,
                                                call["to"]!!.jsonPrimitive.content,
                                            )
                                            rpc(EvmEncoding.hex("1250000000000000000"))
                                        }
                                        "eth_getTransactionCount" -> rpc("0x0")
                                        "eth_gasPrice" -> rpc(EvmEncoding.hex("1000000000"))
                                        "eth_estimateGas" -> {
                                            val payment =
                                                request
                                                    .bodyJson()["params"]!!
                                                    .jsonArray
                                                    .first()
                                                    .jsonObject
                                            assertEquals(
                                                token.contract.lowercase(),
                                                payment["to"]!!.jsonPrimitive.content,
                                            )
                                            assertEquals(
                                                "0x0",
                                                payment["value"]!!.jsonPrimitive.content,
                                            )
                                            rpc(
                                                EvmEncoding.hex("41666")
                                            ) // Integer 20% margin produces the signed limit of
                                            // 50000.
                                        }
                                        "eth_getTransactionByHash" ->
                                            rpc(
                                                if (known) buildJsonObject { put("hash", hash) }
                                                else JsonNull
                                            )
                                        "eth_getTransactionReceipt" ->
                                            rpc(
                                                if (known)
                                                    buildJsonObject {
                                                        put("transactionHash", hash)
                                                        put("status", "0x1")
                                                    }
                                                else JsonNull
                                            )
                                        "eth_sendRawTransaction" -> {
                                            assertEquals(
                                                signed,
                                                request
                                                    .bodyJson()["params"]!!
                                                    .jsonArray
                                                    .single()
                                                    .jsonPrimitive
                                                    .content,
                                            )
                                            broadcasts++
                                            rpc(hash)
                                        }
                                        else -> error("Unexpected EVM operation")
                                    }
                                reply.body
                            }
                        respond(
                            body,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                ) {
                    install(ContentNegotiation) { json() }
                }
            try {
                val network =
                    ProductionWalletNetwork(client) {
                        VaultSnapshot(networkSettings = NetworkSettings(NetworkProvider.AIRLOCK))
                    }
                assertEquals("1.25", network.balance(brz))
                assertEquals(EvmNetworks.paymentProvider(brz), network.lastProvider)
                val quote = network.estimate(brz, recipient, "1.25")
                assertEquals(if (chain == "ethereum") 1L else 56L, quote.evmPlan?.chainId)
                assertEquals(definition.symbol, quote.feeCurrency.symbol)
                assertEquals("0.00005", quote.fee)
                assertEquals(2, TransferAccounting.remaining(quote, listOf(brz, native)).size)
                val wrongChain = if (chain == "ethereum") bscSigned else ethSigned
                assertEquals(
                    InputError.INVALID_QUOTE,
                    assertFailsWith<InputException> { network.broadcast(wrongChain, quote) }.reason,
                )
                assertEquals(0, broadcasts)
                hash = network.transactionId(signed, quote)
                assertEquals(hash, network.broadcast(signed, quote))
                known = true
                assertEquals(hash, network.broadcast(signed, quote))
                assertEquals(TransferStatus.CONFIRMED, network.lastSubmissionStatus)
                assertEquals(1, broadcasts)
            } finally {
                client.close()
            }
        }
    }
}
