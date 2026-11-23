package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.*
import pub.airlock.domain.*

/** Public EIP-155 example. No live requests, keys or funds. */
internal const val EIP155_RAW =
    "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83"
internal val evmAsset =
    Asset(
        "a",
        "account",
        "ethereum",
        "ETH",
        "Ethereum",
        "0x9d8a62f656a8d1615c1294fd71e9cfb3e4855a4f",
        "eth.svg",
        18,
        "2",
    )

internal fun publicQuote() =
    TransferQuote(
        evmAsset,
        "0x3535353535353535353535353535353535353535",
        "1",
        "0.00042",
        evmPlan =
            EvmTransactionPlan(
                1,
                "0x9",
                "0x4a817c800",
                "0x5208",
                "0x3535353535353535353535353535353535353535",
                "0xde0b6b3a7640000",
                "0x",
            ),
    )

internal data class Reply(val body: String, val status: HttpStatusCode = HttpStatusCode.OK)

internal fun rpc(value: JsonElement) =
    Reply(
        buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("result", value)
            }
            .toString()
    )

internal fun rpc(value: String) = rpc(JsonPrimitive(value))

internal fun HttpRequestData.bodyJson() =
    Json.parseToJsonElement((body as TextContent).text).jsonObject

internal fun HttpRequestData.methodName() = bodyJson().getValue("method").jsonPrimitive.content

internal class ProviderHarness(
    preferred: NetworkProvider = NetworkProvider.BLOCKSTREAM,
    private val answer: suspend (HttpRequestData) -> Reply,
) {
    var snapshot =
        VaultSnapshot(
            networkSettings =
                NetworkSettings(preferred, "alchemy_test_key", "ankr_test_key", "coinbase_test_key")
        )
    val calls = mutableListOf<HttpRequestData>()
    val client =
        HttpClient(
            MockEngine { request ->
                calls += request
                val reply = answer(request)
                respond(
                    reply.body,
                    reply.status,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) {
            followRedirects = false
            install(ContentNegotiation) { json() }
        }
    val network = ProductionWalletNetwork(client) { snapshot }

    fun close() = client.close()
}

class NetworkProviderTest {
    @Test
    fun xrplDecoderChecksValidatedIssuerLineBeforeReceiving() = runTest {
        val native =
            evmAsset.copy(
                coin = "ripple",
                symbol = "XRP",
                decimals = 6,
                address = "r9cZA1mLK5R5Am25ArfXFmqgNwjZgnfk59",
            )
        val asset = Stablecoins.forNetwork("ripple").single { it.symbol == "USDC" }.asset(native)
        val (currency, issuer) = asset.contract.split('.')
        val ledger = "A".repeat(64)
        var limit = "10"
        var lineIssuer = issuer
        val harness =
            ProviderHarness(NetworkProvider.AIRLOCK) { request ->
                assertEquals("xrplcluster.com", request.url.host)
                val params = request.bodyJson().getValue("params").jsonArray.single().jsonObject
                rpc(
                    buildJsonObject {
                        put("ledger_hash", ledger)
                        put("validated", true)
                        when (request.methodName()) {
                            "account_lines" -> {
                                assertEquals(JsonPrimitive(issuer), params["peer"])
                                put("account", native.address)
                                putJsonArray("lines") {
                                    addJsonObject {
                                        put("account", lineIssuer)
                                        put("currency", currency)
                                        put("limit", limit)
                                        put("balance", "3.25")
                                    }
                                }
                            }
                            "account_info" -> {
                                assertEquals(JsonPrimitive(ledger), params["ledger_hash"])
                                putJsonObject("account_data") {
                                    put("Account", issuer)
                                    put("Flags", 0)
                                }
                            }
                            else -> error("Unexpected RPC")
                        }
                    }
                )
            }
        try {
            // The decoder remains independently tested; issued XRP is not exposed by current
            // adapters.
            val trustlines = XrplTrustlines(ProviderTransport(harness.client))
            assertEquals(
                InputError.NETWORK_UNSUPPORTED,
                assertFailsWith<InputException> { harness.network.canReceiveIssuedAsset(asset) }
                    .reason,
            )
            assertTrue(harness.calls.isEmpty())
            assertEquals("3.25", trustlines.balance(asset))
            assertTrue(trustlines.canReceive(asset))
            limit = "3.25"
            assertFalse(trustlines.canReceive(asset))
            lineIssuer = native.address
            assertEquals("0", trustlines.balance(asset))
            assertFalse(trustlines.canReceive(asset))
        } finally {
            harness.close()
        }
    }

    @Test
    fun exactQuantitiesAndPublishedSignedVector() {
        val max = "115792089237316195423570985008687907853269984665640564039457584007913129639935"
        assertEquals("0x" + "f".repeat(64), EvmEncoding.hex(max))
        assertEquals(max, EvmEncoding.decimal("0x" + "f".repeat(64)))
        assertEquals("0x20000000000001", EvmEncoding.hex("9007199254740993"))
        assertEquals("0x0", EvmEncoding.hex("000"))
        val hash = EvmEncoding.transactionId(EIP155_RAW, publicQuote())
        assertEquals("0x33469b22e9f636356c4160a87eb19df52b7412e8eac32a4a55ffe88ea8350788", hash)
        for (quote in
            listOf(
                publicQuote().copy(destination = evmAsset.address),
                publicQuote().copy(amount = "2"),
                publicQuote().copy(fee = "0"),
                publicQuote().copy(asset = evmAsset.copy(address = publicQuote().destination)),
                publicQuote().copy(evmPlan = publicQuote().evmPlan!!.copy(chainId = 56)),
            )) {
            assertEquals(
                InputError.INVALID_QUOTE,
                assertFailsWith<InputException> { EvmEncoding.transactionId(EIP155_RAW, quote) }
                    .reason,
            )
        }
        assertFails { EvmEncoding.transactionId("0x02" + EIP155_RAW.drop(2), publicQuote()) }
        assertFails { EvmEncoding.hex(Amount.add(max, "1")) }
    }

    @Test
    fun evmUsesItsFixedChainRouteWhenExplorerSelectionChanges() = runTest {
        val h = ProviderHarness { request ->
            when (request.methodName()) {
                "eth_chainId" -> rpc("0x1")
                else -> rpc("0xde0b6b3a7640000")
            }
        }
        try {
            assertEquals("1", h.network.balance(evmAsset))
            assertTrue(
                h.calls.all {
                    it.url.host == "ethereum-rpc.publicnode.com" && it.url.encodedPath.isEmpty()
                }
            )
            h.calls.clear()
            h.snapshot =
                h.snapshot.copy(
                    networkSettings =
                        h.snapshot.networkSettings.copy(preferred = NetworkProvider.MEMPOOL)
                )
            assertEquals("1", h.network.balance(evmAsset))
            assertTrue(
                h.calls.all {
                    it.url.host == "ethereum-rpc.publicnode.com" && it.url.encodedPath.isEmpty()
                }
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun unauthorizedRateLimitedAndServerErrorsNeverSwitchProvider() = runTest {
        for (status in
            listOf(
                HttpStatusCode.Unauthorized,
                HttpStatusCode.TooManyRequests,
                HttpStatusCode.ServiceUnavailable,
                HttpStatusCode.Found,
            )) {
            val h = ProviderHarness { Reply("error with secret URL", status) }
            try {
                val error = assertFailsWith<InputException> { h.network.balance(evmAsset) }
                assertFalse(error.toString().contains("alchemy_test_key"))
                assertEquals(1, h.calls.size)
                assertTrue(h.calls.all { it.url.host == "ethereum-rpc.publicnode.com" })
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun wrongChainAndMalformedBalanceNeverReplaceCachedFunds() = runTest {
        val wrong = ProviderHarness { rpc("0x38") }
        try {
            assertEquals(
                InputError.NETWORK_MISMATCH,
                assertFailsWith<InputException> { wrong.network.balance(evmAsset) }.reason,
            )
            assertEquals(1, wrong.calls.size)
        } finally {
            wrong.close()
        }
        val malformed = ProviderHarness {
            if (it.methodName() == "eth_chainId") rpc("0x1") else rpc("0x" + "f".repeat(65))
        }
        try {
            assertEquals(
                InputError.PROVIDER_UNAVAILABLE,
                assertFailsWith<InputException> { malformed.network.balance(evmAsset) }.reason,
            )
        } finally {
            malformed.close()
        }
    }

    @Test
    fun cancellationIsNeverConvertedToRetryOrFailure() = runTest {
        val h = ProviderHarness { throw CancellationException("cancelled") }
        try {
            assertFailsWith<CancellationException> { h.network.balance(evmAsset) }
            assertEquals(1, h.calls.size)
        } finally {
            h.close()
        }
    }

    @Test
    fun tokenBalanceAndEstimateUseTheActualRecipientAndContract() = runTest {
        val token = Stablecoins.forNetwork("ethereum").first { it.symbol == "USDC" }.asset(evmAsset)
        val h = ProviderHarness { request ->
            when (request.methodName()) {
                "eth_chainId" -> rpc("0x1")
                "eth_call" -> {
                    val call = request.bodyJson().getValue("params").jsonArray[0].jsonObject
                    assertEquals(token.contract, call["to"]?.jsonPrimitive?.content)
                    assertEquals(
                        "0x70a08231" + evmAsset.address.drop(2).padStart(64, '0'),
                        call["data"]?.jsonPrimitive?.content,
                    )
                    rpc("0x000000000000000000000000000000000000000000000000000000000012d687")
                }
                "eth_getTransactionCount" -> {
                    assertEquals(
                        "pending",
                        request.bodyJson().getValue("params").jsonArray[1].jsonPrimitive.content,
                    )
                    rpc("0x9")
                }
                "eth_gasPrice" -> rpc("0x4a817c800")
                "eth_estimateGas" -> {
                    val tx = request.bodyJson().getValue("params").jsonArray[0].jsonObject
                    assertEquals(token.contract.lowercase(), tx["to"]?.jsonPrimitive?.content)
                    assertEquals("0x0", tx["value"]?.jsonPrimitive?.content)
                    assertEquals(
                        "0xa9059cbb" +
                            publicQuote().destination.drop(2).padStart(64, '0') +
                            "12d687".padStart(64, '0'),
                        tx["data"]?.jsonPrimitive?.content,
                    )
                    rpc("0xea60")
                }
                else -> error("Unexpected request")
            }
        }
        try {
            assertEquals("1.234567", h.network.balance(token))
            val quote = h.network.estimate(token, publicQuote().destination, "1.234567")
            assertEquals("0.00144", quote.fee)
            assertEquals("ETH", quote.feeCurrency.symbol)
            assertEquals("0x11940", quote.evmPlan?.gasLimit)
        } finally {
            h.close()
        }
    }

    @Test
    fun alreadySeenTransactionIsNeverSubmittedAgain() = runTest {
        val hash = EvmEncoding.transactionId(EIP155_RAW, publicQuote())
        val h = ProviderHarness {
            when (it.methodName()) {
                "eth_chainId" -> rpc("0x1")
                "eth_getTransactionByHash" -> rpc(buildJsonObject { put("hash", hash) })
                "eth_getTransactionReceipt" -> rpc(JsonNull)
                else -> error("Must not send")
            }
        }
        try {
            assertEquals(hash, h.network.broadcast(EIP155_RAW, publicQuote()))
            assertFalse(h.calls.any { it.methodName() == "eth_sendRawTransaction" })
        } finally {
            h.close()
        }
    }

    @Test
    fun receiptIncludingRevertedExecutionPreventsAnotherSubmission() = runTest {
        val hash = EvmEncoding.transactionId(EIP155_RAW, publicQuote())
        val h = ProviderHarness {
            when (it.methodName()) {
                "eth_chainId" -> rpc("0x1")
                "eth_getTransactionByHash" -> rpc(JsonNull)
                "eth_getTransactionReceipt" ->
                    rpc(
                        buildJsonObject {
                            put("transactionHash", hash)
                            put("status", "0x0")
                        }
                    )
                else -> error("Must not send")
            }
        }
        try {
            assertEquals(hash, h.network.broadcast(EIP155_RAW, publicQuote()))
            assertEquals(TransferStatus.FAILED, h.network.lastSubmissionStatus)
        } finally {
            h.close()
        }
    }

    @Test
    fun uncertainSubmissionChecksAgainAndManualRetryKeepsBytesAndChainRoute() = runTest {
        var sent = false
        val hash = EvmEncoding.transactionId(EIP155_RAW, publicQuote())
        val h = ProviderHarness { request ->
            when (request.methodName()) {
                "eth_chainId" -> rpc("0x1")
                "eth_getTransactionByHash",
                "eth_getTransactionReceipt" -> rpc(JsonNull)
                "eth_sendRawTransaction" -> {
                    assertEquals(
                        EIP155_RAW,
                        request.bodyJson().getValue("params").jsonArray[0].jsonPrimitive.content,
                    )
                    if (!sent) {
                        sent = true
                        throw IOException("lost response")
                    }
                    rpc(hash)
                }
                else -> error("Unexpected request")
            }
        }
        try {
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { h.network.broadcast(EIP155_RAW, publicQuote()) }
                    .reason,
            )
            assertEquals(1, h.calls.count { it.methodName() == "eth_sendRawTransaction" })
            assertTrue(h.calls.all { it.url.host == "ethereum-rpc.publicnode.com" })
            assertEquals(2, h.calls.count { it.methodName() == "eth_getTransactionByHash" })
            h.snapshot =
                h.snapshot.copy(
                    networkSettings =
                        h.snapshot.networkSettings.copy(preferred = NetworkProvider.MEMPOOL)
                )
            h.calls.clear()
            assertEquals(hash, h.network.broadcast(EIP155_RAW, publicQuote()))
            assertTrue(h.calls.all { it.url.host == "ethereum-rpc.publicnode.com" })
            assertEquals(
                listOf(
                    "eth_chainId",
                    "eth_getTransactionByHash",
                    "eth_getTransactionReceipt",
                    "eth_sendRawTransaction",
                ),
                h.calls.map { it.methodName() },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun lookupFailureCannotBeInterpretedAsNotFound() = runTest {
        val h = ProviderHarness { request ->
            if (request.methodName() == "eth_chainId") rpc("0x1") else throw IOException("offline")
        }
        try {
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { h.network.broadcast(EIP155_RAW, publicQuote()) }
                    .reason,
            )
            assertFalse(h.calls.any { it.methodName() == "eth_sendRawTransaction" })
        } finally {
            h.close()
        }
    }

    @Test
    fun nonceTooLowIsNotProofThatOurTransactionSucceeded() = runTest {
        val h = ProviderHarness { request ->
            when (request.methodName()) {
                "eth_chainId" -> rpc("0x1")
                "eth_getTransactionByHash",
                "eth_getTransactionReceipt" -> rpc(JsonNull)
                "eth_sendRawTransaction" ->
                    Reply(
                        """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"nonce too low"}}"""
                    )
                else -> error("Unexpected request")
            }
        }
        try {
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { h.network.broadcast(EIP155_RAW, publicQuote()) }
                    .reason,
            )
            assertEquals(1, h.calls.count { it.methodName() == "eth_sendRawTransaction" })
            assertEquals(2, h.calls.count { it.methodName() == "eth_getTransactionByHash" })
        } finally {
            h.close()
        }
    }

    @Test
    fun invalidSignedPayloadNeverReachesAProvider() = runTest {
        val h = ProviderHarness { error("No requests allowed") }
        try {
            assertFailsWith<InputException> {
                h.network.broadcast(EIP155_RAW, publicQuote().copy(amount = "2"))
            }
            assertTrue(h.calls.isEmpty())
        } finally {
            h.close()
        }
    }

    @Test
    fun airlockRestDecoderBindsDecimalBalancesToAddressAndChain() = runTest {
        val h =
            ProviderHarness(NetworkProvider.AIRLOCK) { request ->
                assertEquals("/balance/ethereum/${evmAsset.address}", request.url.encodedPath)
                Reply(
                    """{"chain":"ethereum","address":"${evmAsset.address}","balance":"1.000000000000000001"}"""
                )
            }
        try {
            assertEquals(
                "1.000000000000000001",
                AirLockRestWallet(pub.airlock.data.AirLockApi(h.client) { h.snapshot.apiUrl })
                    .balance(evmAsset),
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun pricesUseAirLockContractAndOtherCurrentAdaptersFailWithoutRequests() = runTest {
        val h =
            ProviderHarness(NetworkProvider.AIRLOCK) { request ->
                assertEquals("/prices", request.url.encodedPath)
                assertEquals("ETH", request.url.parameters["coins"])
                Reply("""{"code":200,"data":{"ETH":"3210.12","OTHER":"5"}}""")
            }
        try {
            assertEquals(mapOf("ETH" to "3210.12"), h.network.prices(listOf("ETH")))
        } finally {
            h.close()
        }
        for (provider in NetworkProvider.available - NetworkProvider.AIRLOCK) {
            val unsupported = ProviderHarness(provider) { error("No price request is supported") }
            unsupported.snapshot =
                unsupported.snapshot.copy(
                    networkSettings = NetworkSettings(provider, moralisKey = "moralis_test_key")
                )
            try {
                assertEquals(
                    InputError.NETWORK_UNSUPPORTED,
                    assertFailsWith<InputException> { unsupported.network.prices(listOf("ETH")) }
                        .reason,
                )
                assertTrue(unsupported.calls.isEmpty())
            } finally {
                unsupported.close()
            }
        }
    }

    @Test
    fun freedomBalancesUseExactCoinUnitsAcrossItsSupportedNetworks() = runTest {
        val networks =
            mapOf(
                "bitcoin" to "btc",
                "litecoin" to "ltc",
                "dogecoin" to "doge",
                "bitcoincash" to "bch",
                "dash" to "dash",
                "ecash" to "xec",
            )
        for ((coin, symbol) in networks) {
            val definition = CoinCatalog.get(coin)
            val asset =
                Asset(
                    "u",
                    "u",
                    coin,
                    definition.symbol,
                    coin,
                    "public-address",
                    "",
                    definition.decimals,
                )
            val exact = if (definition.decimals == 2) "90071992.54" else "90071992.54740993"
            val h =
                ProviderHarness(NetworkProvider.FREEDOM) { request ->
                    assertEquals("api.freedom.st", request.url.host)
                    assertEquals("/$symbol/address/public-address", request.url.encodedPath)
                    Reply(
                        """{"address":"public-address","crypto":"$symbol","received":$exact,"sent":0}"""
                    )
                }
            try {
                assertEquals(exact, h.network.balance(asset))
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun solanaSumsAllTokenAccountsUsingIntegers() = runTest {
        val owner = "11111111111111111111111111111111"
        val native = Asset("s", "s", "solana", "SOL", "Solana", owner, "", 9)
        val token = Stablecoins.forNetwork("solana").first { it.symbol == "USDC" }.asset(native)
        for (provider in NetworkProvider.explorers) {
            val h =
                ProviderHarness(provider) { request ->
                    assertEquals("getTokenAccountsByOwner", request.methodName())
                    assertEquals(
                        token.contract,
                        request
                            .bodyJson()
                            .getValue("params")
                            .jsonArray[1]
                            .jsonObject["mint"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                    rpc(
                        buildJsonObject {
                            put(
                                "value",
                                JsonArray(
                                    listOf("1000001", "2000002").mapIndexed { i, value ->
                                        buildJsonObject {
                                            put("pubkey", "account-$i")
                                            put(
                                                "account",
                                                buildJsonObject {
                                                    put(
                                                        "data",
                                                        buildJsonObject {
                                                            put(
                                                                "parsed",
                                                                buildJsonObject {
                                                                    put(
                                                                        "info",
                                                                        buildJsonObject {
                                                                            put("owner", owner)
                                                                            put(
                                                                                "mint",
                                                                                token.contract,
                                                                            )
                                                                            put(
                                                                                "tokenAmount",
                                                                                buildJsonObject {
                                                                                    put(
                                                                                        "amount",
                                                                                        value,
                                                                                    )
                                                                                    put(
                                                                                        "decimals",
                                                                                        6,
                                                                                    )
                                                                                },
                                                                            )
                                                                        },
                                                                    )
                                                                },
                                                            )
                                                        },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                ),
                            )
                        }
                    )
                }
            try {
                assertEquals("3.000003", h.network.balance(token))
                assertTrue(h.calls.all { it.url.host == "api.mainnet.solana.com" })
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun tronTokenQueriesUseBase58AndContractAbiWithoutPrivateData() = runTest {
        val native =
            Asset("t", "t", "tron", "TRX", "Tron", "TQn9Y2khEsLJW1ChVWFMSMeRDow5KcbLSE", "", 6)
        val token = Stablecoins.forNetwork("tron").single { it.symbol == "USDC" }.asset(native)
        for (provider in NetworkProvider.explorers) {
            val h =
                ProviderHarness(provider) { request ->
                    assertTrue(request.url.encodedPath.endsWith("/wallet/triggerconstantcontract"))
                    val body = request.bodyJson()
                    assertEquals(token.contract, body["contract_address"]?.jsonPrimitive?.content)
                    assertEquals(
                        "balanceOf(address)",
                        body["function_selector"]?.jsonPrimitive?.content,
                    )
                    assertEquals(64, body["parameter"]?.jsonPrimitive?.content?.length)
                    Reply(
                        """{"result":{"result":true},"constant_result":["${"f4240".padStart(64,'0')}"]}"""
                    )
                }
            try {
                assertEquals("1", h.network.balance(token))
                assertTrue(h.calls.all { it.url.host == "api.trongrid.io" })
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun suiGraphqlBalanceBindsTheReturnedTokenType() = runTest {
        val native = Asset("s", "s", "sui", "SUI", "Sui", "0x" + "1".repeat(64), "", 9)
        val token = Stablecoins.forNetwork("sui").single().asset(native)
        var returnedType = token.contract
        val h = ProviderHarness { request ->
            assertEquals("graphql.mainnet.sui.io", request.url.host)
            assertEquals(
                token.contract,
                request.bodyJson().getValue("variables").jsonObject["type"]!!.jsonPrimitive.content,
            )
            Reply(
                buildJsonObject {
                        putJsonObject("data") {
                            putJsonObject("address") {
                                putJsonObject("balance") {
                                    put("totalBalance", "12345678")
                                    putJsonObject("coinType") { put("repr", returnedType) }
                                }
                            }
                        }
                    }
                    .toString()
            )
        }
        try {
            assertEquals("12.345678", h.network.balance(token))
            returnedType = "0x2::sui::SUI"
            assertEquals(
                InputError.INVALID_QUOTE,
                assertFailsWith<InputException> { h.network.balance(token) }.reason,
            )
        } finally {
            h.close()
        }
    }
}
