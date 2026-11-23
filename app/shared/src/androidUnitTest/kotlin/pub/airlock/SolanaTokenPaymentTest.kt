package pub.airlock

import fr.acinq.bitcoin.Base58
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.data.network.Failure
import pub.airlock.data.network.ProviderFailure
import pub.airlock.data.network.ProviderTransport
import pub.airlock.data.network.SolanaPayment
import pub.airlock.data.network.SolanaTokenAddress
import pub.airlock.domain.*
import pub.airlock.platform.AndroidEd25519Verifier

class SolanaTokenPaymentTest {
    private fun fixtures(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonArray
        }

    @Test
    fun associatedAccountsMatchTheIndependentWalletCoreReferenceForEveryTokenAndBothPrograms() {
        val rows = fixtures("solana_ata_vectors.json")
        assertEquals(Stablecoins.forNetwork("solana").size * 2, rows.size)
        for (value in rows) {
            val row = value.jsonObject
            assertEquals(
                row.getValue("ata").jsonPrimitive.content,
                Base58.encode(
                    SolanaTokenAddress.associated(
                        row.getValue("owner").jsonPrimitive.content,
                        row.getValue("mint").jsonPrimitive.content,
                        row.getValue("program").jsonPrimitive.content,
                    )
                ),
            )
        }
    }

    @Test
    fun appVerifiesEveryFirmwareSignedTokenTransferAndRejectsChangesToTheApprovedPayment() {
        val native =
            Asset(
                "sol",
                "account",
                "solana",
                "SOL",
                "Solana",
                "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL",
                "sol.svg",
                9,
                "2",
            )
        val recipient = "CktRuQ2mttgRGkXJtyksdKHjUdc2C4TgDzyB98oEzy8"
        val blockhash = "US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx"
        val checked = mutableSetOf<Pair<String, Boolean>>()
        for (value in fixtures("solana_signing_vectors.json")) {
            val row = value.jsonObject
            val token =
                Stablecoins.forNetwork("solana").single {
                    it.symbol == row.getValue("symbol").jsonPrimitive.content
                }
            val rent = row.getValue("rent").jsonPrimitive.long
            val asset = token.asset(native)
            val quote =
                TransferQuote(
                    asset,
                    recipient,
                    Amount.display("1250000000", asset.decimals),
                    Amount.display((5000 + rent).toString(), 9),
                    solanaPlan = SolanaTransactionPlan(blockhash, rent),
                )
            val signature = row.getValue("signature").jsonPrimitive.content
            assertEquals(
                Base58.encode(signature.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
                SolanaPayment.transactionId(signature, quote, AndroidEd25519Verifier),
            )
            assertTrue(checked.add(token.symbol to (rent > 0)))
            assertFailsWith<InputException> {
                SolanaPayment.transactionId(
                    signature,
                    quote.copy(amount = "1"),
                    AndroidEd25519Verifier,
                )
            }
            assertFailsWith<InputException> {
                SolanaPayment.transactionId(
                    signature,
                    quote.copy(destination = native.address),
                    AndroidEd25519Verifier,
                )
            }
            val changedRent =
                if (rent > 0) 0
                else if (token.symbol in SolanaNetwork.token2022Symbols) 2192400 else 2039280
            assertFailsWith<InputException> {
                SolanaPayment.transactionId(
                    signature,
                    quote.copy(
                        fee = Amount.display((5000L + changedRent).toString(), 9),
                        solanaPlan = SolanaTransactionPlan(blockhash, changedRent.toLong()),
                    ),
                    AndroidEd25519Verifier,
                )
            }
        }
        assertEquals(
            Stablecoins.forNetwork("solana")
                .flatMap { listOf(it.symbol to false, it.symbol to true) }
                .toSet(),
            checked,
        )
    }

    @Test
    fun estimatesOnlyVerifiedTokenAccountsAndIncludesRecipientRentWithoutHidingTokenFeesOrHooks() =
        runTest {
            val sender = "GjJyeC1r2RgkuoCWMyPYkCWSGSGLcz266EaAkLA27AhL"
            val recipient = "CktRuQ2mttgRGkXJtyksdKHjUdc2C4TgDzyB98oEzy8"
            val native = Asset("sol", "account", "solana", "SOL", "Solana", sender, "sol.svg", 9)
            for (symbol in listOf("USDC", "PYUSD")) {
                val asset =
                    Stablecoins.forNetwork("solana").single { it.symbol == symbol }.asset(native)
                val program = SolanaNetwork.tokenProgram(asset)
                var exists = false
                var wrongProgram = false
                var hasTokenFee = false
                var hasHook = false
                var rentQueries = 0
                fun account(owner: String) = buildJsonObject {
                    put("owner", program)
                    put("executable", false)
                    putJsonObject("data") {
                        putJsonObject("parsed") {
                            put("type", "account")
                            putJsonObject("info") {
                                put("owner", owner)
                                put("mint", asset.contract)
                                put("state", "initialized")
                                putJsonObject("tokenAmount") {
                                    put("decimals", asset.decimals)
                                    put("amount", "9007199254740993")
                                }
                            }
                        }
                    }
                }
                fun mint() = buildJsonObject {
                    put("owner", if (wrongProgram) "11111111111111111111111111111111" else program)
                    put("executable", false)
                    putJsonObject("data") {
                        putJsonObject("parsed") {
                            put("type", "mint")
                            putJsonObject("info") {
                                put("decimals", asset.decimals)
                                put("isInitialized", true)
                                if (symbol == "PYUSD")
                                    putJsonArray("extensions") {
                                        addJsonObject {
                                            put("extension", "transferHook")
                                            putJsonObject("state") {
                                                put(
                                                    "programId",
                                                    if (hasHook)
                                                        JsonPrimitive(
                                                            SolanaTokenAddress.associatedProgram
                                                        )
                                                    else JsonNull,
                                                )
                                            }
                                        }
                                        addJsonObject {
                                            put("extension", "transferFeeConfig")
                                            putJsonObject("state") {
                                                for (period in
                                                    listOf(
                                                        "olderTransferFee",
                                                        "newerTransferFee",
                                                    )) putJsonObject(period) {
                                                    put(
                                                        "transferFeeBasisPoints",
                                                        if (hasTokenFee) 1 else 0,
                                                    )
                                                    put("maximumFee", if (hasTokenFee) 1000 else 0)
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
                fun ata(owner: String) =
                    fixtures("solana_ata_vectors.json")
                        .single {
                            it.jsonObject["owner"] == JsonPrimitive(owner) &&
                                it.jsonObject["mint"] == JsonPrimitive(asset.contract)
                        }
                        .jsonObject
                        .getValue("ata")
                val client =
                    HttpClient(
                        MockEngine { request ->
                            assertEquals("api.mainnet.solana.com", request.url.host)
                            val params = request.bodyJson().getValue("params").jsonArray
                            val result =
                                when (request.methodName()) {
                                    "getMultipleAccounts" -> {
                                        assertEquals(
                                            JsonArray(
                                                listOf(
                                                    ata(sender),
                                                    ata(recipient),
                                                    JsonPrimitive(asset.contract),
                                                )
                                            ),
                                            params[0],
                                        )
                                        buildJsonObject {
                                            putJsonArray("value") {
                                                add(account(sender))
                                                add(if (exists) account(recipient) else JsonNull)
                                                add(mint())
                                            }
                                        }
                                    }
                                    "getMinimumBalanceForRentExemption" -> {
                                        rentQueries++
                                        assertEquals(
                                            JsonPrimitive(if (symbol == "PYUSD") 187 else 165),
                                            params[0],
                                        )
                                        JsonPrimitive(if (symbol == "PYUSD") 2192400 else 2039280)
                                    }
                                    "getLatestBlockhash" ->
                                        buildJsonObject {
                                            putJsonObject("value") {
                                                put(
                                                    "blockhash",
                                                    "US517G5965aydkZ46HS38QLi7UQiSojurfbQfKCELFx",
                                                )
                                            }
                                        }
                                    "getFeeForMessage" -> buildJsonObject { put("value", 5000) }
                                    else -> error("Unexpected RPC; estimates must never broadcast")
                                }
                            respond(
                                rpc(result).body,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    )
                try {
                    val transport = ProviderTransport(client)
                    val first = SolanaPayment.estimate(transport, asset, recipient, "1")
                    val rent = if (symbol == "PYUSD") 2192400L else 2039280L
                    assertEquals(rent, first.solanaPlan?.rentAtomic)
                    assertEquals(Amount.display((5000 + rent).toString(), 9), first.fee)
                    assertEquals(CoinCatalog.get("solana"), first.feeCurrency)
                    exists = true
                    assertEquals(
                        "0.000005",
                        SolanaPayment.estimate(transport, asset, recipient, "1").fee,
                    )
                    assertEquals(1, rentQueries)
                    wrongProgram = true
                    assertEquals(
                        Failure.INVALID_RESPONSE,
                        assertFailsWith<ProviderFailure> {
                                SolanaPayment.estimate(transport, asset, recipient, "1")
                            }
                            .kind,
                    )
                    wrongProgram = false
                    if (symbol == "PYUSD") {
                        hasTokenFee = true
                        assertEquals(
                            Failure.UNSUPPORTED,
                            assertFailsWith<ProviderFailure> {
                                    SolanaPayment.estimate(transport, asset, recipient, "1")
                                }
                                .kind,
                        )
                        hasTokenFee = false
                        hasHook = true
                        assertEquals(
                            Failure.UNSUPPORTED,
                            assertFailsWith<ProviderFailure> {
                                    SolanaPayment.estimate(transport, asset, recipient, "1")
                                }
                                .kind,
                        )
                    }
                } finally {
                    client.close()
                }
            }
        }
}
