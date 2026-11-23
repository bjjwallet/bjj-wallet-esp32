package pub.airlock

import io.ktor.http.HttpMethod
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.domain.*

class AdaBalancePolygonPaymentTest {
    @Test
    fun readsLovelaceOnlyForTheRequestedCardanoAddress() = runTest {
        val address =
            "addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlft05dz3c7revpf7jx0xnlcjz3g69mq4afdhv"
        val asset = Asset("ada", "account", "cardano", "ADA", "Cardano", address, "ada.svg", 6)
        var response = """[{"address":"$address","balance":"1234567"}]"""
        val harness =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                assertEquals("api.koios.rest", request.url.host)
                assertEquals("/api/v1/address_info", request.url.encodedPath)
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    JsonArray(listOf(JsonPrimitive(address))),
                    request.bodyJson()["_addresses"],
                )
                Reply(response)
            }
        try {
            assertEquals(mapOf("ada" to "1.234567"), harness.network.balances(listOf(asset)))
            assertEquals(NetworkProvider.KOIOS, harness.network.lastProvider)
            response = """[{"address":"$address","balance":"0"}]"""
            assertEquals("0", harness.network.balance(asset))
            response = """[{"address":"different","balance":"1234567"}]"""
            assertEquals(
                InputError.PROVIDER_UNAVAILABLE,
                assertFailsWith<InputException> { harness.network.balance(asset) }.reason,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun readsCardanoTokensByPolicyAndAssetName() = runTest {
        val address =
            "addr1qy8ac7qqy0vtulyl7wntmsxc6wex80gvcyjy33qffrhm7sh927ysx5sftuw0dlft05dz3c7revpf7jx0xnlcjz3g69mq4afdhv"
        val ada = Asset("ada", "account", "cardano", "ADA", "Cardano", address, "ada.svg", 6)
        val tokens = Stablecoins.missing(listOf(ada))
        assertEquals(setOf("SNEK", "MIN", "IAG", "WMTX", "DJED"), tokens.map { it.symbol }.toSet())
        assertTrue(
            tokens.all {
                it.address == ada.address &&
                    ProviderCoverage.canRead(NetworkProvider.BLOCKSTREAM, it)
            }
        )
        val asset = tokens.single { it.symbol == "MIN" }
        val (policy, name) = asset.contract.take(56) to asset.contract.drop(56)
        val balance =
            """{"address":"$address","policy_id":"$policy","asset_name":"$name","quantity":"1234567"}"""
        var response = "[$balance]"
        val harness =
            ProviderHarness(NetworkProvider.BLOCKSTREAM) { request ->
                assertEquals("/api/v1/address_assets", request.url.encodedPath)
                assertEquals("eq.$policy", request.url.parameters["policy_id"])
                assertEquals("eq.$name", request.url.parameters["asset_name"])
                assertEquals(
                    JsonArray(listOf(JsonPrimitive(address))),
                    request.bodyJson()["_addresses"],
                )
                Reply(response)
            }
        try {
            assertEquals("1.234567", harness.network.balance(asset))
            response = "[]"
            assertEquals("0", harness.network.balance(asset))
            response = "[${balance.replace(policy, "0".repeat(56))}]"
            assertEquals(
                InputError.PROVIDER_UNAVAILABLE,
                assertFailsWith<InputException> { harness.network.balance(asset) }.reason,
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun verifiesFirmwareSignedPolygonPaymentBeforeSendingToRpc() = runTest {
        verifyNativeEvmPayment(
            "polygon",
            "POL",
            "Polygon",
            "polygon.drpc.org",
            137,
            "0xf86d80843b9aca0082c350941111111111111111111111111111111111111111881158e460913d000080820136a07103550a3ed7d227177be599cfe912af35e579c6e0bdf06eab8c96d847885680a0613a61ac8eca05a28dc228473fafa114f71afaa89a11d040ebd53af8d362aeee",
        )
    }

    @Test
    fun verifiesFirmwareSignedEthereumPaymentBeforeSendingToRpc() = runTest {
        verifyNativeEvmPayment(
            "ethereum",
            "ETH",
            "Ethereum",
            "ethereum-rpc.publicnode.com",
            1,
            "0xf86b80843b9aca0082c350941111111111111111111111111111111111111111881158e460913d00008025a099347bc444214606b8d78c8b6d05e20a2b40ef1736b60e70c7eb016162679d24a078f9626b3e313d38e165ee35bcf4981454227e676cc7b7f6ccea805d77daef2b",
        )
    }

    private suspend fun verifyNativeEvmPayment(
        coin: String,
        symbol: String,
        name: String,
        host: String,
        chainId: Long,
        signed: String,
    ) {
        // Fixtures are produced by the firmware test from a public mnemonic.
        val sender = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
        val recipient = "0x1111111111111111111111111111111111111111"
        val asset =
            Asset(coin, "account", coin, symbol, name, sender, "${symbol.lowercase()}.svg", 18, "2")
        val expectedChain = "0x${chainId.toString(16)}"
        var hash = ""
        var chain = expectedChain
        var broadcasts = 0
        val harness =
            ProviderHarness(NetworkProvider.AIRLOCK) { request ->
                assertEquals(host, request.url.host)
                when (request.methodName()) {
                    "eth_chainId" -> rpc(chain)
                    "eth_getTransactionCount" -> rpc("0x0")
                    "eth_gasPrice" -> rpc("0x3b9aca00")
                    "eth_estimateGas" -> rpc("0xa2c2")
                    "eth_getTransactionByHash",
                    "eth_getTransactionReceipt" -> rpc(JsonNull)
                    "eth_sendRawTransaction" -> {
                        assertEquals(
                            JsonArray(listOf(JsonPrimitive(signed))),
                            request.bodyJson()["params"],
                        )
                        broadcasts++
                        rpc(hash)
                    }
                    else -> error("Unexpected RPC")
                }
            }
        try {
            val quote = harness.network.estimate(asset, recipient, "1.25")
            assertEquals(chainId, quote.evmPlan?.chainId)
            assertEquals("0.00005", quote.fee)
            hash = harness.network.transactionId(signed, quote)
            assertEquals(
                InputError.INVALID_QUOTE,
                assertFailsWith<InputException> {
                        harness.network.broadcast(
                            signed.replace(recipient.removePrefix("0x"), "2".repeat(40)),
                            quote,
                        )
                    }
                    .reason,
            )
            chain = "0x38"
            assertEquals(
                InputError.NETWORK_MISMATCH,
                assertFailsWith<InputException> { harness.network.broadcast(signed, quote) }.reason,
            )
            assertEquals(0, broadcasts)
            chain = expectedChain
            assertEquals(hash, harness.network.broadcast(signed, quote))
            assertEquals(1, broadcasts)
        } finally {
            harness.close()
        }
    }
}
