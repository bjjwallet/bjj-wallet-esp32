package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.ble.*
import pub.airlock.data.*
import pub.airlock.data.network.*
import pub.airlock.domain.*
import pub.airlock.platform.AndroidEd25519Verifier

class LedgerPaymentTest {
    private val rows
        get() =
            javaClass.classLoader!!
                .getResourceAsStream("ledger_signing_vectors.json")!!
                .bufferedReader()
                .use { Json.parseToJsonElement(it.readText()).jsonArray }

    private fun quote(row: JsonObject): TransferQuote {
        val request = row.getValue("request").jsonObject
        fun field(key: String) = request.getValue(key).jsonPrimitive.content
        val coin = field("network").removeSuffix("-mainnet")
        val native = CoinCatalog.get(coin)
        var asset =
            Asset(
                coin,
                coin,
                coin,
                native.symbol,
                native.name,
                field("sender"),
                native.icon,
                native.decimals,
                derivationPath = PublicNetworks.get(coin).path,
            )
        if (field("asset") != native.symbol)
            asset = Stablecoins.forNetwork(coin).single { it.symbol == field("asset") }.asset(asset)
        val plan =
            JsonObject(
                request.filterKeys {
                    it !in
                        setOf(
                            "wallet_id",
                            "mode",
                            "sender",
                            "recipient",
                            "asset",
                            "amount_atomic",
                            "fee_atomic",
                        )
                }
            )
        return TransferQuote(
            asset,
            field("recipient"),
            Amount.display(field("amount_atomic"), asset.decimals),
            Amount.display(field("fee_atomic"), native.decimals),
            ledgerPlan = plan,
        )
    }

    @Test
    fun eachFirmwareSignatureAndSerializationMatchesTheIndependentOfficialSdk() {
        val checked = mutableSetOf<Pair<String, String>>()
        for (value in rows) {
            val row = value.jsonObject
            val quote = quote(row)
            val asset = quote.asset
            val coin = asset.coin
            val plan = quote.ledgerPlan!!
            val signature = row.getValue("signature").jsonPrimitive.content
            assertEquals(
                row.getValue("reference_body_hex").jsonPrimitive.content,
                EvmEncoding.run {
                    (if (coin == "ripple")
                            XrpPayment.body(
                                quote,
                                EvmEncoding.bytes(
                                    "0x" + row.getValue("signature").jsonPrimitive.content.take(66)
                                ),
                            )
                        else LedgerPayment.body(quote))
                        .hex()
                },
            )
            assertEquals(
                row.getValue("reference_hash").jsonPrimitive.content,
                LedgerPayment.transactionId(signature, quote, AndroidEd25519Verifier),
            )
            checked.add(coin to asset.symbol)
            for (changed in
                listOf(
                    quote.copy(amount = "2"),
                    quote.copy(destination = asset.address),
                    quote.copy(asset = asset.copy(decimals = 1)),
                    quote.copy(
                        ledgerPlan = JsonObject(plan + ("expires" to JsonPrimitive("2100000001")))
                    ),
                )) {
                assertFailsWith<InputException> {
                    LedgerPayment.transactionId(signature, changed, AndroidEd25519Verifier)
                }
            }
            assertFailsWith<InputException> {
                LedgerPayment.transactionId(
                    "00".repeat(signature.length / 2),
                    quote,
                    AndroidEd25519Verifier,
                )
            }
        }
        val expected =
            CoinCatalog.coins
                .filter { it.coin in setOf("stellar", "tron", "ripple", "sui", "ton") }
                .flatMap { c ->
                    listOf(c.coin to c.symbol) +
                        Stablecoins.forNetwork(c.coin).map { c.coin to it.symbol }
                }
                .toSet()
        assertEquals(expected, checked)
    }

    @Test
    fun eachProtocolSubmitsOnlyTheVerifiedBytesToItsOwnRoute() = runTest {
        for (value in rows) {
            val row = value.jsonObject
            val quote = quote(row)
            val coin = quote.asset.coin
            val signature = row.getValue("signature").jsonPrimitive.content
            val hash = row.getValue("reference_hash").jsonPrimitive.content
            var sends = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        sends++
                        val reply =
                            when (coin) {
                                "stellar" -> {
                                    assertEquals("horizon.stellar.org", request.url.host)
                                    assertEquals("/transactions", request.url.encodedPath)
                                    val encoded =
                                        (request.body as TextContent)
                                            .text
                                            .substringAfter("tx=")
                                            .decodeURLQueryComponent()
                                    assertTrue(Base64.decode(encoded).size > 100)
                                    "{\"hash\":\"$hash\",\"successful\":true}"
                                }
                                "tron" -> {
                                    assertEquals("api.trongrid.io", request.url.host)
                                    assertEquals("/wallet/broadcasthex", request.url.encodedPath)
                                    assertTrue(
                                        request
                                            .bodyJson()
                                            .getValue("transaction")
                                            .jsonPrimitive
                                            .content
                                            .endsWith(signature)
                                    )
                                    "{\"txid\":\"$hash\",\"result\":true}"
                                }
                                "ripple" -> {
                                    assertEquals("xrplcluster.com", request.url.host)
                                    assertEquals(
                                        JsonPrimitive("submit"),
                                        request.bodyJson()["method"],
                                    )
                                    val blob =
                                        request
                                            .bodyJson()
                                            .getValue("params")
                                            .jsonArray
                                            .single()
                                            .jsonObject
                                            .getValue("tx_blob")
                                            .jsonPrimitive
                                            .content
                                    assertContentEquals(
                                        XrpPayment.signed(signature, quote),
                                        EvmEncoding.bytes("0x$blob"),
                                    )
                                    "{\"result\":{\"engine_result\":\"tesSUCCESS\",\"tx_json\":{\"hash\":\"$hash\"}}}"
                                }
                                "sui" -> {
                                    assertEquals("graphql.mainnet.sui.io", request.url.host)
                                    val variables =
                                        request.bodyJson().getValue("variables").jsonObject
                                    assertContentEquals(
                                        SuiPayment.body(quote),
                                        Base64.decode(
                                            variables.getValue("tx").jsonPrimitive.content
                                        ),
                                    )
                                    val signed =
                                        Base64.decode(
                                            variables
                                                .getValue("sigs")
                                                .jsonArray
                                                .single()
                                                .jsonPrimitive
                                                .content
                                        )
                                    assertEquals(97, signed.size)
                                    assertEquals(0, signed[0].toInt())
                                    "{\"data\":{\"executeTransaction\":{\"effects\":{\"status\":\"SUCCESS\",\"transaction\":{\"digest\":\"$hash\"}}}}}"
                                }
                                "ton" -> {
                                    assertEquals("toncenter.com", request.url.host)
                                    assertEquals(
                                        "/api/v2/sendBocReturnHash",
                                        request.url.encodedPath,
                                    )
                                    assertTrue(
                                        Base64.decode(
                                                request
                                                    .bodyJson()
                                                    .getValue("boc")
                                                    .jsonPrimitive
                                                    .content
                                            )
                                            .size > 100
                                    )
                                    "{\"ok\":true,\"result\":{\"hash\":\"$hash\"}}"
                                }
                                else -> error("Missing route test")
                            }
                        respond(
                            reply,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                )
            try {
                val network = ProductionWalletNetwork(client, AndroidEd25519Verifier)
                assertFailsWith<InputException> {
                    network.broadcast("00".repeat(signature.length / 2), quote)
                }
                assertEquals(0, sends)
                assertEquals(hash, network.broadcast(signature, quote))
                assertEquals(1, sends)
                assertEquals(LedgerNetworks.provider(quote.asset), network.lastProvider)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun everyLedgerAssetAuthenticatesAndHonorsPhysicalRejectionWithoutSendingASecret() = runTest {
        for (value in rows) {
            val row = value.jsonObject
            val quote = quote(row)
            var approve = false
            var authenticated = false
            var requested = 0
            val connection =
                object : HardwareConnection {
                    override suspend fun discover() = emptyList<HardwareDevice>()

                    override suspend fun connect(id: String) {
                        authenticated = false
                    }

                    override suspend fun disconnect() {
                        authenticated = false
                    }

                    override suspend fun request(method: String, params: JsonObject): JsonElement =
                        when (method) {
                            "verify_pin" -> {
                                authenticated = true
                                buildJsonObject { put("accepted", true) }
                            }
                            "get_device_info" ->
                                buildJsonObject {
                                    put("ledger_signing_version", 1)
                                    put("pin_required", true)
                                }
                            "ledger_sign_transfer" -> {
                                if (!authenticated)
                                    throw HardwareRejectedException("PIN required", "PIN_REQUIRED")
                                requested++
                                assertEquals(row.getValue("request"), params)
                                assertFalse(
                                    params.keys.any {
                                        it in
                                            setOf(
                                                "private_key",
                                                "seed",
                                                "mnemonic",
                                                "password",
                                                "pin",
                                            )
                                    }
                                )
                                if (!approve)
                                    throw HardwareRejectedException("Rejected", "USER_REJECTED")
                                row.getValue("signature")
                            }
                            else -> error("Unexpected RPC")
                        }
                }
            val hardware = ProductionHardwareWallet(connection, Bip32PublicKeyDeriver())
            hardware.bindWalletDevice("public-fixture")
            assertEquals(
                InputError.HARDWARE_PIN_REQUIRED,
                assertFailsWith<InputException> {
                        hardware.signLedger(quote, "1".repeat(64), WalletMode.STANDARD)
                    }
                    .reason,
            )
            assertEquals(0, requested)
            assertEquals(
                InputError.TRANSACTION_REJECTED,
                assertFailsWith<InputException> {
                        hardware.withSigningAccessPin("Aa1234") {
                            hardware.signLedger(quote, "1".repeat(64), WalletMode.STANDARD)
                        }
                    }
                    .reason,
            )
            approve = true
            assertEquals(
                row.getValue("signature").jsonPrimitive.content,
                hardware.withSigningAccessPin("Aa1234") {
                    hardware.signLedger(quote, "1".repeat(64), WalletMode.STANDARD)
                },
            )
            assertEquals(2, requested)
        }
    }

    @Test
    fun everyAssetPreparesOnItsOwnRouteRegardlessOfTheSelectedApi() = runTest {
        for (value in rows) {
            val row = value.jsonObject
            val fixture = quote(row)
            val asset = fixture.asset
            val client =
                HttpClient(
                    MockEngine { request ->
                        val response =
                            when (asset.coin) {
                                "stellar" ->
                                    when (request.url.encodedPath) {
                                        "/accounts/${asset.address}" ->
                                            "{\"account_id\":\"${asset.address}\",\"sequence\":\"41\"}"
                                        "/fee_stats" -> "{\"fee_charged\":{\"p90\":\"100\"}}"
                                        else -> error("Unexpected Stellar request")
                                    }
                                "tron" -> {
                                    assertEquals("/wallet/getnowblock", request.url.encodedPath)
                                    "{\"blockID\":\"0000000000001234${"1".repeat(48)}\",\"block_header\":{\"raw_data\":{\"number\":4660,\"timestamp\":1800000000000}}}"
                                }
                                "ripple" ->
                                    when (
                                        request.bodyJson().getValue("method").jsonPrimitive.content
                                    ) {
                                        "account_info" ->
                                            "{\"result\":{\"account_data\":{\"Account\":\"${asset.address}\",\"Sequence\":42}}}"
                                        "ledger_current" ->
                                            "{\"result\":{\"ledger_current_index\":99999980}}"
                                        "fee" ->
                                            "{\"result\":{\"drops\":{\"open_ledger_fee\":\"12\"}}}"
                                        else -> error("Unexpected XRPL method")
                                    }
                                "ton" -> {
                                    assertEquals(
                                        "/api/v2/getWalletInformation",
                                        request.url.encodedPath,
                                    )
                                    "{\"ok\":true,\"result\":{\"seqno\":42,\"account_state\":\"active\",\"wallet_type\":\"wallet v4 r2\"}}"
                                }
                                "sui" -> {
                                    val body = request.bodyJson()
                                    if (
                                        body.getValue("query").jsonPrimitive.content ==
                                            "{epoch{epochId referenceGasPrice}}"
                                    )
                                        "{\"data\":{\"epoch\":{\"epochId\":1299,\"referenceGasPrice\":\"100\"}}}"
                                    else {
                                        val type =
                                            body
                                                .getValue("variables")
                                                .jsonObject
                                                .getValue("type")
                                                .jsonPrimitive
                                                .content
                                        val refs =
                                            fixture.ledgerPlan!!
                                                .getValue(
                                                    if (type.contains("sui::SUI")) "gas"
                                                    else "coins"
                                                )
                                                .jsonArray
                                        buildJsonObject {
                                                putJsonObject("data") {
                                                    putJsonObject("address") {
                                                        put("address", asset.address)
                                                        putJsonObject("objects") {
                                                            putJsonObject("pageInfo") {
                                                                put("hasNextPage", false)
                                                                put("endCursor", JsonNull)
                                                            }
                                                            putJsonArray("nodes") {
                                                                for (value in refs) {
                                                                    val r = value.jsonObject
                                                                    addJsonObject {
                                                                        put(
                                                                            "address",
                                                                            r.getValue("id"),
                                                                        )
                                                                        put(
                                                                            "version",
                                                                            r.getValue("version"),
                                                                        )
                                                                        put(
                                                                            "digest",
                                                                            fr.acinq.bitcoin.Base58
                                                                                .encode(
                                                                                    EvmEncoding
                                                                                        .bytes(
                                                                                            "0x" +
                                                                                                r
                                                                                                    .getValue(
                                                                                                        "digest"
                                                                                                    )
                                                                                                    .jsonPrimitive
                                                                                                    .content
                                                                                        )
                                                                                ),
                                                                        )
                                                                        putJsonObject("owner") {
                                                                            put(
                                                                                "__typename",
                                                                                "AddressOwner",
                                                                            )
                                                                            putJsonObject(
                                                                                "address"
                                                                            ) {
                                                                                put(
                                                                                    "address",
                                                                                    asset.address,
                                                                                )
                                                                            }
                                                                        }
                                                                        putJsonObject("contents") {
                                                                            putJsonObject("type") {
                                                                                put("repr", type)
                                                                            }
                                                                            putJsonObject("json") {
                                                                                put(
                                                                                    "balance",
                                                                                    r.getValue(
                                                                                        "balance"
                                                                                    ),
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            .toString()
                                    }
                                }
                                else -> error("Uncovered protocol")
                            }
                        respond(
                            response,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                )
            try {
                for (provider in NetworkProvider.available) {
                    val network =
                        ProductionWalletNetwork(client, AndroidEd25519Verifier) {
                            VaultSnapshot(networkSettings = NetworkSettings(provider))
                        }
                    val prepared = network.estimate(asset, fixture.destination, fixture.amount)
                    assertEquals(asset, prepared.asset)
                    assertEquals(fixture.destination, prepared.destination)
                    assertEquals(fixture.amount, prepared.amount)
                    assertEquals(CoinCatalog.get(asset.coin), prepared.feeCurrency)
                    assertEquals(LedgerNetworks.provider(asset), network.lastProvider)
                    assertTrue(LedgerPayment.body(prepared).isNotEmpty())
                }
            } finally {
                client.close()
            }
        }
    }
}
