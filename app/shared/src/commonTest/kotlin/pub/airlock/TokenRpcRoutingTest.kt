package pub.airlock

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.kotlincrypto.hash.sha3.Keccak256
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

/** Every registered token is exercised with all selectable services. No live requests or funds. */
class TokenRpcRoutingTest {
    private val recipient = "0x3535353535353535353535353535353535353535"

    private fun enabled(asset: Asset, selected: NetworkProvider) =
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
                networkSettings = NetworkSettings(selected),
            )
            .let { it.canSendSelectedAsset && it.selectedAssetHasSendSupport }

    private fun word(value: String) = rpc("0x" + EvmEncoding.hex(value).drop(2).padStart(64, '0'))

    private fun selector(value: String) =
        "0x" +
            Keccak256().digest(value.encodeToByteArray()).take(4).toByteArray().let {
                EvmEncoding.run { it.hex() }
            }

    @Test
    fun allNativeEvmCoinsAlsoUseTheirOwnRpcRatherThanTheSelectedService() = runTest {
        for (coin in EvmNetworks.rpcUrls.keys) for (selected in NetworkProvider.available) {
            val definition = CoinCatalog.get(coin)
            val asset =
                evmAsset.copy(
                    coin = coin,
                    symbol = definition.symbol,
                    decimals = definition.decimals,
                )
            val h =
                ProviderHarness(selected) { request ->
                    assertEquals(Url(EvmNetworks.rpcUrls.getValue(coin)).host, request.url.host)
                    when (request.methodName()) {
                        "eth_chainId" ->
                            rpc(EvmEncoding.hex(EvmNetworks.chainIds.getValue(coin).toString()))
                        "eth_getBalance" -> rpc("0x1")
                        else -> error("Unexpected native balance method")
                    }
                }
            try {
                assertTrue(enabled(asset, selected))
                assertEquals(Amount.display("1", definition.decimals), h.network.balance(asset))
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun allEvmTokenBalancesAndEstimatesUseTheirOwnRpcAndSendIsEnabled() = runTest {
        for (token in Stablecoins.tokens.filter { it.coin in EvmNetworks.rpcUrls }) for (selected in
            NetworkProvider.available) {
            val asset =
                token.asset(
                    evmAsset.copy(id = token.coin, accountId = token.coin, coin = token.coin)
                )
            val endpoint = Url(EvmNetworks.rpcUrls.getValue(token.coin))
            val h =
                ProviderHarness(selected) { request ->
                    assertEquals(
                        endpoint.host,
                        request.url.host,
                        "$selected/${token.coin}/${token.symbol}",
                    )
                    assertNull(request.headers["X-API-Key"])
                    when (request.methodName()) {
                        "eth_chainId" ->
                            rpc(
                                EvmEncoding.hex(
                                    EvmNetworks.chainIds.getValue(token.coin).toString()
                                )
                            )
                        "eth_call" -> {
                            val call = request.bodyJson()["params"]!!.jsonArray.first().jsonObject
                            val data = call["data"]!!.jsonPrimitive.content
                            if (call["to"] == JsonPrimitive(BaseFees.oracle))
                                when (data.take(10)) {
                                    selector("getL1FeeUpperBound(uint256)") -> word("1000")
                                    selector("getOperatorFee(uint256)") -> word("100")
                                    else -> error("Unknown oracle method")
                                }
                            else {
                                assertEquals(
                                    token.contract.lowercase(),
                                    call["to"]!!.jsonPrimitive.content.lowercase(),
                                )
                                assertEquals(
                                    "0x70a08231" +
                                        asset.address.drop(2).lowercase().padStart(64, '0'),
                                    data,
                                )
                                rpc("0x1")
                            }
                        }
                        "eth_getTransactionCount" -> rpc("0x9")
                        "eth_gasPrice" -> rpc("0x1")
                        "eth_estimateGas" -> {
                            val call = request.bodyJson()["params"]!!.jsonArray.first().jsonObject
                            assertEquals(
                                token.contract.lowercase(),
                                call["to"]!!.jsonPrimitive.content,
                            )
                            assertEquals("0x0", call["value"]!!.jsonPrimitive.content)
                            assertEquals(
                                "0xa9059cbb" +
                                    recipient.drop(2).padStart(64, '0') +
                                    EvmEncoding.hex(Amount.atomic("1", token.decimals))
                                        .drop(2)
                                        .padStart(64, '0'),
                                call["data"]!!.jsonPrimitive.content,
                            )
                            rpc("0xea60")
                        }
                        else -> error("Unexpected method")
                    }
                }
            h.snapshot =
                h.snapshot.copy(
                    networkSettings = NetworkSettings(selected, moralisKey = "test_key")
                )
            try {
                assertTrue(enabled(asset, selected), "SEND ${token.coin}/${token.symbol}")
                val minimum = Amount.display("1", token.decimals)
                assertEquals(minimum, h.network.balance(asset))
                assertEquals(mapOf(asset.id to minimum), h.network.balances(listOf(asset)))
                val quote = h.network.estimate(asset, recipient, "1")
                assertEquals(EvmNetworks.chainIds.getValue(token.coin), quote.evmPlan?.chainId)
                assertEquals(CoinCatalog.get(token.coin), quote.feeCurrency)
                assertEquals("1", quote.amount)
            } finally {
                h.close()
            }
        }
    }

    /** Derive a synthetic sender using a public reference signature, without any private key. */
    private fun signed(quote: TransferQuote): Pair<String, TransferQuote> {
        val reference = LegacyRlp.decode(EvmEncoding.bytes(EIP155_RAW))
        val plan = quote.evmPlan!!
        val fields = LegacyRlp.decode(EvmEncoding.unsigned(plan)).toMutableList()
        val v = EvmEncoding.hex((plan.chainId * 2 + 35).toString()).drop(2)
        fields[6] = EvmEncoding.bytes("0x" + v.padStart(v.length + v.length % 2, '0'))
        fields[7] = reference[7]
        fields[8] = reference[8]
        val key =
            Crypto.recoverPublicKey(
                ByteVector64(fields[7] + fields[8]),
                Keccak256().digest(EvmEncoding.unsigned(plan)),
                0,
            )
        val address =
            "0x" +
                EvmEncoding.run {
                    Keccak256()
                        .digest(key.toUncompressedBin().drop(1).toByteArray())
                        .takeLast(20)
                        .toByteArray()
                        .hex()
                }
        return "0x" + EvmEncoding.run { LegacyRlp.encode(fields).hex() } to
            quote.copy(asset = quote.asset.copy(address = address))
    }

    @Test
    fun allEvmTokensBroadcastIdenticalVerifiedBytesOnlyToTheirOwnChain() = runTest {
        for (token in Stablecoins.tokens.filter { it.coin in EvmNetworks.rpcUrls }) {
            val asset = token.asset(evmAsset.copy(coin = token.coin))
            val payment = EvmEncoding.payment(asset, recipient, "1")
            val plan =
                EvmTransactionPlan(
                    EvmNetworks.chainIds.getValue(token.coin),
                    "0x9",
                    "0x1",
                    "0x11940",
                    payment.first,
                    payment.second,
                    payment.third,
                    l1Fee = if (token.coin == "base") "1200" else null,
                    operatorFee = if (token.coin == "base") "120" else null,
                )
            val quote =
                TransferQuote(
                    asset,
                    recipient,
                    "1",
                    Amount.display(if (token.coin == "base") "73320" else "72000", 18),
                    evmPlan = plan,
                )
            val (raw, verified) = signed(quote)
            val hash = EvmEncoding.transactionId(raw, verified)
            var sends = 0
            val h =
                ProviderHarness(NetworkProvider.MORALIS) { request ->
                    val method = request.methodName()
                    assertEquals(
                        if (token.coin == "base" && method == "eth_sendRawTransaction")
                            "mainnet-sequencer.base.org"
                        else Url(EvmNetworks.rpcUrls.getValue(token.coin)).host,
                        request.url.host,
                    )
                    when (method) {
                        "eth_chainId" -> rpc(EvmEncoding.hex(plan.chainId.toString()))
                        "eth_getTransactionByHash",
                        "eth_getTransactionReceipt" -> rpc(JsonNull)
                        "eth_call" ->
                            if (
                                request
                                    .bodyJson()["params"]!!
                                    .jsonArray
                                    .first()
                                    .jsonObject["data"]!!
                                    .jsonPrimitive
                                    .content
                                    .startsWith(selector("getL1FeeUpperBound(uint256)"))
                            )
                                word("1000")
                            else word("100")
                        "eth_sendRawTransaction" -> {
                            assertEquals(
                                raw,
                                request
                                    .bodyJson()["params"]!!
                                    .jsonArray
                                    .single()
                                    .jsonPrimitive
                                    .content,
                            )
                            sends++
                            rpc(hash)
                        }
                        else -> error("Unexpected method")
                    }
                }
            try {
                assertEquals(hash, h.network.broadcast(raw, verified))
                assertEquals(1, sends)
            } finally {
                h.close()
            }
        }
    }

    @Test
    fun solanaAndAllItsTokensUseSolanaRpcForBalancesAndPreparationWithAnySelectedApi() = runTest {
        val owner = Base58.encode(ByteArray(32) { 1 })
        val recipient = Base58.encode(ByteArray(32) { 2 })
        val blockhash = Base58.encode(ByteArray(32) { 3 })
        val sol = Asset("sol", "sol", "solana", "SOL", "Solana", owner, "", 9)
        for (selected in NetworkProvider.available) for (asset in
            listOf(sol) + Stablecoins.forNetwork("solana").map { it.asset(sol) }) {
            val program = SolanaNetwork.tokenProgram(asset)
            fun row(type: String, info: JsonObject) = buildJsonObject {
                put("owner", program)
                put("executable", false)
                putJsonObject("data") {
                    putJsonObject("parsed") {
                        put("type", type)
                        put("info", info)
                    }
                }
            }
            fun account(address: String) =
                row(
                    "account",
                    buildJsonObject {
                        put("owner", address)
                        put("mint", asset.contract)
                        put("state", "initialized")
                        putJsonObject("tokenAmount") {
                            put("amount", "500")
                            put("decimals", asset.decimals)
                        }
                    },
                )
            val calls = mutableListOf<String>()
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("api.mainnet.solana.com", request.url.host)
                        val method = request.methodName()
                        calls += method
                        val response =
                            when (method) {
                                "getBalance" -> rpc(buildJsonObject { put("value", 500) })
                                "getTokenAccountsByOwner" -> {
                                    assertEquals(
                                        asset.contract,
                                        request
                                            .bodyJson()["params"]!!
                                            .jsonArray[1]
                                            .jsonObject["mint"]!!
                                            .jsonPrimitive
                                            .content,
                                    )
                                    rpc(
                                        buildJsonObject {
                                            putJsonArray("value") {
                                                add(
                                                    buildJsonObject {
                                                        put("pubkey", "account")
                                                        put("account", account(owner))
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                                "getMultipleAccounts" ->
                                    rpc(
                                        buildJsonObject {
                                            put(
                                                "value",
                                                JsonArray(
                                                    listOf(
                                                        account(owner),
                                                        account(recipient),
                                                        row(
                                                            "mint",
                                                            buildJsonObject {
                                                                put("decimals", asset.decimals)
                                                                put("isInitialized", true)
                                                                if (asset.symbol == "PYUSD")
                                                                    putJsonArray("extensions") {
                                                                        add(
                                                                            buildJsonObject {
                                                                                put(
                                                                                    "extension",
                                                                                    "transferHook",
                                                                                )
                                                                                putJsonObject(
                                                                                    "state"
                                                                                ) {
                                                                                    put(
                                                                                        "programId",
                                                                                        JsonNull,
                                                                                    )
                                                                                }
                                                                            }
                                                                        )
                                                                        add(
                                                                            buildJsonObject {
                                                                                put(
                                                                                    "extension",
                                                                                    "transferFeeConfig",
                                                                                )
                                                                                putJsonObject(
                                                                                    "state"
                                                                                ) {
                                                                                    for (key in
                                                                                        listOf(
                                                                                            "olderTransferFee",
                                                                                            "newerTransferFee",
                                                                                        )) putJsonObject(
                                                                                        key
                                                                                    ) {
                                                                                        put(
                                                                                            "transferFeeBasisPoints",
                                                                                            0,
                                                                                        )
                                                                                        put(
                                                                                            "maximumFee",
                                                                                            "0",
                                                                                        )
                                                                                    }
                                                                                }
                                                                            }
                                                                        )
                                                                    }
                                                            },
                                                        ),
                                                    )
                                                ),
                                            )
                                        }
                                    )
                                "getLatestBlockhash" ->
                                    rpc(
                                        buildJsonObject {
                                            putJsonObject("value") { put("blockhash", blockhash) }
                                        }
                                    )
                                "getFeeForMessage" -> rpc(buildJsonObject { put("value", 5000) })
                                "getSignatureStatuses" ->
                                    rpc(
                                        buildJsonObject {
                                            put("value", JsonArray(listOf(JsonNull)))
                                        }
                                    )
                                "isBlockhashValid" -> rpc(buildJsonObject { put("value", true) })
                                "sendTransaction" -> {
                                    val bytes =
                                        Base64.decode(
                                            request
                                                .bodyJson()["params"]!!
                                                .jsonArray
                                                .first()
                                                .jsonPrimitive
                                                .content
                                        )
                                    assertEquals(1, bytes[0].toInt())
                                    assertContentEquals(
                                        ByteArray(64) { 1 },
                                        bytes.copyOfRange(1, 65),
                                    )
                                    assertTrue(bytes.size > 65)
                                    rpc(Base58.encode(ByteArray(64) { 1 }))
                                }
                                else -> error("Unexpected Solana method")
                            }
                        respond(
                            response.body,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                )
            val network =
                ProductionWalletNetwork(client, Ed25519Verifier { _, _, _ -> false }) {
                    VaultSnapshot(
                        networkSettings = NetworkSettings(selected, moralisKey = "test_key")
                    )
                }
            try {
                assertTrue(enabled(asset, selected))
                val balance = Amount.display("500", asset.decimals)
                assertEquals(balance, network.balance(asset))
                assertEquals(mapOf(asset.id to balance), network.balances(listOf(asset)))
                val quote = network.estimate(asset, recipient, Amount.display("1", asset.decimals))
                assertEquals("0.000005", quote.fee)
                assertEquals(blockhash, quote.solanaPlan?.blockhash)
                assertEquals(0L, quote.solanaPlan?.rentAtomic)
                val count = calls.size
                assertEquals(
                    InputError.INVALID_QUOTE,
                    assertFailsWith<InputException> { network.broadcast("01".repeat(64), quote) }
                        .reason,
                )
                assertEquals(count, calls.size, "Invalid signature must not be transmitted")
                // Test the transport separately using public dummy bytes, only against MockEngine.
                assertEquals(
                    TransferStatus.SUBMITTED,
                    SolanaPayment.submit(
                        ProviderTransport(client),
                        "01".repeat(64),
                        quote,
                        Base58.encode(ByteArray(64) { 1 }),
                    ),
                )
                assertEquals(1, calls.count { it == "sendTransaction" })
            } finally {
                client.close()
            }
        }
    }
}
