package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.*
import pub.airlock.domain.*

class UtxoExplorerTest {
    private val asset = Asset("btc", "a", "bitcoin", "BTC", "Bitcoin", "bc1qexample", "", 8)

    @Test
    fun esploraCombinesConfirmedAndPendingWithoutRounding() = runTest {
        for (provider in listOf(NetworkProvider.BLOCKSTREAM, NetworkProvider.MEMPOOL)) {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals(
                            provider.serviceUrl("") + "/address/bc1qexample",
                            request.url.toString(),
                        )
                        respond(
                            """{"address":"bc1qexample","chain_stats":{"funded_txo_sum":900000001,"spent_txo_sum":100000000},"mempool_stats":{"funded_txo_sum":2,"spent_txo_sum":200000000}}""",
                            HttpStatusCode.OK,
                        )
                    }
                )
            val network =
                ProductionWalletNetwork(client) {
                    VaultSnapshot(networkSettings = NetworkSettings(provider))
                }
            assertEquals("6.00000003", network.balance(asset))
            client.close()
        }
    }

    @Test
    fun freedomUsesNetworkAndDecimalCoinAmounts() = runTest {
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals("/btc/address/bc1qexample", request.url.encodedPath)
                    respond(
                        """{"address":"bc1qexample","crypto":"btc","received":1.00000001,"sent":0.1}""",
                        HttpStatusCode.OK,
                    )
                }
            )
        val provider = UtxoExplorerProvider(ProviderTransport(client), NetworkProvider.FREEDOM)
        assertEquals("0.90000001", provider.balance(asset))
        client.close()
    }

    @Test
    fun freedomReadsEcashBalanceAndIncomingAndOutgoingHistory() = runTest {
        val address = "ecash:qqyx49mu0kkn9ftfj6hje6g2wfer34yfnqdxfumtxd"
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(listOf("xec", "address", address), request.url.segments)
                    respond(
                        """{
                "address":"${address.substringAfter(':')}","crypto":"xec","received":12.34,"sent":2.01,
                "transactions":[{"hash":"${"01".repeat(32)}","amount":-2.01,"block":null},
                    {"hash":"${"ab".repeat(32)}","amount":12.34,"block":"${"cd".repeat(32)}"}]
                }""",
                        HttpStatusCode.OK,
                    )
                }
            )
        val network =
            ProductionWalletNetwork(client) {
                VaultSnapshot(networkSettings = NetworkSettings(NetworkProvider.FREEDOM))
            }
        try {
            val activity =
                network.addressActivity(
                    Asset("xec", "a", "ecash", "XEC", "eCash", address, "xec.svg", 2)
                )
            assertEquals("10.33", activity.balance)
            assertEquals(listOf("2.01", "12.34"), activity.transfers.map { it.amount })
            assertEquals(listOf(false, true), activity.transfers.map { it.incoming })
            assertEquals(
                listOf(TransferStatus.SUBMITTED, TransferStatus.CONFIRMED),
                activity.transfers.map { it.status },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedAndWrongChainBalancesFail() = runTest {
        for (body in
            listOf(
                """{"address":"another","crypto":"btc","received":1,"sent":0}""",
                """{"address":"bc1qexample","crypto":"ltc","received":1,"sent":0}""",
                """{"address":"bc1qexample","crypto":"btc","received":1,"sent":2}""",
            )) {
            val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
            val provider = UtxoExplorerProvider(ProviderTransport(client), NetworkProvider.FREEDOM)
            assertFailsWith<ProviderFailure> { provider.balance(asset) }
            client.close()
        }
    }

    @Test
    fun broadcastUsesPostAndChecksAcknowledgement() = runTest {
        val hash = "ab".repeat(32)
        for (id in NetworkProvider.explorers) {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals(HttpMethod.Post, request.method)
                        assertFalse(request.url.toString().contains("010203"))
                        if (id == NetworkProvider.FREEDOM) {
                            assertEquals(
                                """{"txs":["010203"]}""",
                                (request.body as TextContent).text,
                            )
                            respond("""[{"hash":"$hash"}]""", HttpStatusCode.OK)
                        } else {
                            assertEquals("010203", (request.body as TextContent).text)
                            respond(hash, HttpStatusCode.OK)
                        }
                    }
                )
            val provider = UtxoExplorerProvider(ProviderTransport(client), id)
            assertEquals(hash, provider.broadcast("bitcoin", "010203", hash))
            client.close()
        }
    }

    @Test
    fun wrongAcknowledgementNeverSucceedsOrRetries() = runTest {
        for (id in NetworkProvider.explorers) {
            var calls = 0
            val client =
                HttpClient(
                    MockEngine {
                        calls++
                        respond(
                            if (id == NetworkProvider.FREEDOM) "[]" else "cd".repeat(32),
                            HttpStatusCode.OK,
                        )
                    }
                )
            val provider = UtxoExplorerProvider(ProviderTransport(client), id)
            assertFailsWith<ProviderFailure> {
                provider.broadcast("bitcoin", "010203", "ab".repeat(32))
            }
            assertEquals(1, calls)
            client.close()
        }
    }

    @Test
    fun unsupportedCoinsAndTokensDoNotContactServer() = runTest {
        val client = HttpClient(MockEngine { error("No HTTP request expected") })
        val provider = UtxoExplorerProvider(ProviderTransport(client), NetworkProvider.BLOCKSTREAM)
        assertFailsWith<InputException> { provider.balance(asset.copy(contract = "token")) }
        assertFailsWith<ProviderFailure> {
            provider.broadcast("litecoin", "010203", "ab".repeat(32))
        }
        client.close()
    }

    @Test
    fun availableProvidersIncludeAirLockAndMoralis() {
        assertEquals(NetworkProvider.BLOCKSTREAM, NetworkSettings().preferred)
        assertEquals(
            NetworkProvider.explorers + listOf(NetworkProvider.AIRLOCK, NetworkProvider.MORALIS),
            NetworkProvider.available,
        )
        assertEquals(
            listOf(NetworkProvider.MORALIS),
            NetworkProvider.available.filter { it.requiresApiKey },
        )
        assertTrue(ProviderCoverage.canSubmit(NetworkProvider.BLOCKSTREAM, "bitcoin"))
    }
}
