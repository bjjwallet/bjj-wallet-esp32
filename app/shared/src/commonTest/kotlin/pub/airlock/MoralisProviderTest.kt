package pub.airlock

import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pub.airlock.data.network.*
import pub.airlock.domain.*

class MoralisProviderTest {
    private val key = "header.payload.signature"
    private val ethereum = evmAsset
    private val usdc =
        Stablecoins.forNetwork("ethereum").single { it.symbol == "USDC" }.asset(ethereum)
    private val other = "0x" + "a".repeat(40)
    private val hash = "0x" + "b".repeat(64)

    private fun response(
        address: String,
        rows: String,
        cursor: String = "null",
        meta: String = "{}",
    ) = Reply("""{"address":"$address","meta":$meta,"cursor":$cursor,"result":[$rows]}""")

    private fun ProviderHarness.withKey() = apply {
        snapshot = snapshot.copy(networkSettings = snapshot.networkSettings.copy(moralisKey = key))
    }

    @Test
    fun batchesByAddressAndFollowsBalanceCursorToFindContract() = runTest {
        val harness =
            ProviderHarness(NetworkProvider.MORALIS) { request ->
                    assertEquals("api.moralis.com", request.url.host)
                    assertEquals("/v1/wallets/${ethereum.address}/tokens", request.url.encodedPath)
                    assertEquals("0x1", request.url.parameters["chains"])
                    assertEquals(key, request.headers["X-API-Key"])
                    assertFalse(request.url.toString().contains(key))
                    if (request.url.parameters["cursor"] == null)
                        response(
                            ethereum.address,
                            """
                {"chainId":"0x1","nativeToken":true,"decimals":18,"balanceRaw":"1000000000000000001"},
                {"chainId":"0x1","nativeToken":false,"tokenAddress":"$other","symbol":"USDC","decimals":6,"balanceRaw":"2000000"}
            """,
                            "\"p2\"",
                        )
                    else
                        response(
                            ethereum.address,
                            """
                {"chainId":"0x1","nativeToken":false,"tokenAddress":"${usdc.contract.lowercase()}","decimals":6,"balanceRaw":"9007199254740993"}
            """,
                        )
                }
                .withKey()
        try {
            assertEquals(
                mapOf(ethereum.id to "1.000000000000000001", usdc.id to "9007199254.740993"),
                MoralisProvider(ProviderTransport(harness.client), key)
                    .balances(listOf(ethereum, usdc)),
            )
            assertEquals(2, harness.calls.size)
            assertTrue(
                ProviderCoverage.canSubmit(NetworkProvider.MORALIS, usdc)
            ) // Signed on the cold wallet; fixed chain RPC submits.
        } finally {
            harness.close()
        }
    }

    @Test
    fun historyFiltersTransfersByContractAndUsesRawUnits() = runTest {
        val harness =
            ProviderHarness(NetworkProvider.MORALIS) { request ->
                    assertEquals("/v1/wallets/${ethereum.address}/history", request.url.encodedPath)
                    if (request.url.parameters["cursor"] == null)
                        response(
                            ethereum.address,
                            """
                {"chainId":"0x1","transaction":{"hash":"$hash","confirmations":2,"tokenTransfers":{"items":[
                    {"tokenAddress":"$other","value":"42","fromAddress":"$other","toAddress":"${ethereum.address}"},
                    {"tokenAddress":"${usdc.contract}","tokenDecimals":6,"value":"1000001","fromAddress":"$other","toAddress":"${ethereum.address}"}
                ]}}}
            """,
                            "\"p2\"",
                        )
                    else response(ethereum.address, "")
                }
                .withKey()
        try {
            val page = harness.network.transactionHistory(usdc)
            val tx = page.transfers.single()
            assertEquals("1.000001", tx.amount)
            assertTrue(tx.incoming)
            assertEquals(TransferStatus.CONFIRMED, tx.status)
            assertEquals("p2", page.nextCursor)
            assertNull(harness.network.transactionHistory(usdc, page.nextCursor).nextCursor)
        } finally {
            harness.close()
        }
    }

    @Test
    fun bitcoinUsesAddressAndFormattedHistoryAmounts() = runTest {
        val asset =
            ethereum.copy(
                coin = "bitcoin",
                symbol = "BTC",
                decimals = 8,
                address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            )
        val harness =
            ProviderHarness(NetworkProvider.MORALIS) { request ->
                    assertEquals("bitcoin", request.url.parameters["chains"])
                    response(
                        asset.address,
                        """
                {"chainId":"bitcoin","transaction":{"hash":"${hash.removePrefix("0x")}","confirmations":6,
                    "nativeTransfers":{"items":[{"fromAddress":"sender","toAddress":"${asset.address}","amount":"0.00000003"}]}}}
            """,
                    )
                }
                .withKey()
        try {
            assertEquals(
                "0.00000003",
                harness.network.transactionHistory(asset).transfers.single().amount,
            )
            assertTrue(CoinCatalog.isStandalone("bitcoin"))
            assertEquals(
                InputError.NETWORK_UNSUPPORTED,
                assertFailsWith<InputException> {
                        harness.network.transactionHistory(asset.copy(coin = "ecash"))
                    }
                    .reason,
            )
            assertEquals(1, harness.calls.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun wrongChainPartialFailureAndAuthenticationDoNotLookLikeZeroBalances() = runTest {
        val replies =
            mutableListOf(
                response(
                    ethereum.address,
                    """{"chainId":"0x89","nativeToken":true,"decimals":18,"balanceRaw":"1"}""",
                ),
                response(ethereum.address, "", meta = """{"failedChains":[{"chainId":"0x1"}]}"""),
                Reply("{}", HttpStatusCode.Unauthorized),
                Reply("{}", HttpStatusCode.TooManyRequests),
            )
        val harness = ProviderHarness(NetworkProvider.MORALIS) { replies.removeAt(0) }.withKey()
        try {
            for (error in
                listOf(
                    Failure.WRONG_CHAIN,
                    Failure.UNAVAILABLE,
                    Failure.AUTH,
                    Failure.RATE_LIMITED,
                )) {
                assertEquals(
                    error,
                    assertFailsWith<ProviderFailure> {
                            MoralisProvider(ProviderTransport(harness.client), key)
                                .balance(ethereum)
                        }
                        .kind,
                )
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun solanaAddsTokenAccountsWithMatchingMintAndReportsHistoryUnavailable() = runTest {
        val sol =
            ethereum.copy(coin = "solana", symbol = "SOL", decimals = 9, address = "1".repeat(32))
        val token = Stablecoins.forNetwork("solana").single { it.symbol == "USDC" }.asset(sol)
        val harness =
            ProviderHarness(NetworkProvider.MORALIS) { request ->
                    assertEquals("solana-gateway.moralis.io", request.url.host)
                    assertEquals(
                        "/account/mainnet/${sol.address}/portfolio",
                        request.url.encodedPath,
                    )
                    Reply(
                        """{"nativeBalance":{"lamports":"123"},"tokens":[
                {"mint":"${token.contract}","decimals":6,"amountRaw":"4"},
                {"mint":"${token.contract}","decimals":6,"amountRaw":"32"},
                {"mint":"unrelated","decimals":6,"amountRaw":"9"}]}"""
                    )
                }
                .withKey()
        try {
            assertEquals(
                mapOf(sol.id to "0.000000123", token.id to "0.000036"),
                MoralisProvider(ProviderTransport(harness.client), key).balances(listOf(sol, token)),
            )
            assertFalse(ProviderCoverage.canReadHistory(NetworkProvider.MORALIS, sol))
            assertEquals(1, harness.calls.size)
        } finally {
            harness.close()
        }
    }
}
