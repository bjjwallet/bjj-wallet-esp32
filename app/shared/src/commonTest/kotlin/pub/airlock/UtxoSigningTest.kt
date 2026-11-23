package pub.airlock

import fr.acinq.bitcoin.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pub.airlock.data.ProductionWalletNetwork
import pub.airlock.data.network.*
import pub.airlock.domain.*

internal fun utxoVectors() = Json.parseToJsonElement(UTXO_SIGNING_VECTORS).jsonArray

internal fun utxoQuote(vector: JsonElement): TransferQuote {
    val request = vector.jsonObject.getValue("request").jsonObject
    val asset = request.getValue("asset").jsonObject
    val tx = request.getValue("txData").jsonObject
    fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    val coin = asset.s("coin").let { if (it == "doge") "dogecoin" else it }
    val definition = CoinCatalog.get(coin)
    val a =
        Asset(
            "$coin:0",
            coin,
            coin,
            definition.symbol,
            definition.name,
            asset.s("address"),
            definition.icon,
            definition.decimals,
            Amount.display("100000000", definition.decimals),
            derivationPath = asset.s("derivation_path"),
        )
    return TransferQuote(
        a,
        tx.s("toAddress"),
        Amount.display(tx.s("amount"), definition.decimals),
        Amount.display(tx.s("fee"), definition.decimals),
        utxoPlan =
            UtxoTransactionPlan(
                tx.getValue("utxos").jsonArray.map {
                    it.jsonObject.let { input ->
                        UtxoInput(
                            input.s("txHash"),
                            input.s("index").toLong(),
                            input.s("amount").toLong(),
                            input.s("script"),
                            input.s("previousTransaction"),
                        )
                    }
                },
                tx.s("recipientScript"),
                tx.s("changeScript"),
                tx.s("changeAddress"),
                tx.s("changeAmount").toLong(),
                tx.s("fee").toLong(),
                tx.s("byteFee").toLong(),
            ),
    )
}

class UtxoSigningTest {
    @Test
    fun bitcoinCryptographyRejectsAnAlteredSignatureEvenWhenTheTxidIsUnchanged() {
        val vector = utxoVectors().first { utxoQuote(it).asset.coin == "bitcoin" }
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        val tx = Transaction.read(raw)
        val witness = tx.txIn.single().witness
        val original = witness.stack[0].toByteArray()
        val publicKey = PublicKey(witness.stack[1])
        val digest =
            tx.hashForSigning(
                0,
                Script.write(Script.pay2pkh(publicKey.hash160())),
                1,
                Satoshi(quote.utxoPlan!!.inputs.single().amount),
                SigVersion.SIGVERSION_WITNESS_V0,
            )
        assertTrue(Crypto.verifySignature(digest, Crypto.decodeSignatureLax(original), publicKey))

        val altered = original.copyOf()
        altered[altered.lastIndex - 1] = (altered[altered.lastIndex - 1].toInt() xor 1).toByte()
        assertTrue(
            Crypto.isDERSignature(altered),
            "Keep a well-formed signature; only its mathematical value changes",
        )
        assertFalse(Crypto.verifySignature(digest, Crypto.decodeSignatureLax(altered), publicKey))
        val corrupted =
            tx.copy(
                txIn =
                    listOf(
                        tx.txIn
                            .single()
                            .copy(
                                witness =
                                    ScriptWitness(listOf(ByteVector(altered), witness.stack[1]))
                            )
                    )
            )
        assertEquals(tx.txid, corrupted.txid, "A txid alone does not validate a witness signature")
        assertEquals(
            InputError.INVALID_QUOTE,
            assertFailsWith<InputException> {
                    UtxoStrategy.transactionId(
                        EvmEncoding.run { Transaction.write(corrupted).hex() },
                        quote,
                    )
                }
                .reason,
        )

        val unrelated =
            PublicKey(
                ByteVector("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
            )
        assertFalse(Crypto.verifySignature(digest, Crypto.decodeSignatureLax(original), unrelated))
        val wrongKey =
            tx.copy(
                txIn =
                    listOf(
                        tx.txIn
                            .single()
                            .copy(
                                witness = ScriptWitness(listOf(witness.stack[0], unrelated.value))
                            )
                    )
            )
        assertFailsWith<InputException> {
            UtxoStrategy.transactionId(EvmEncoding.run { Transaction.write(wrongKey).hex() }, quote)
        }
    }

    @Test
    fun nativeEcashRefusesSlpAndAlpFundingWithoutLosingItsTwoDecimalScale() {
        val quote = utxoVectors().map(::utxoQuote).first { it.asset.coin == "ecash" }
        assertEquals(2, quote.asset.decimals)
        assertEquals("500000", quote.amount)
        val input = quote.utxoPlan!!.inputs.single()
        val previous = Transaction.read(input.previousTransaction)
        for (marker in listOf("6a04534c5000", "6a5004534c5032")) {
            val colored =
                Transaction(
                    previous.version,
                    previous.txIn,
                    previous.txOut + TxOut(Satoshi(0), ByteVector(marker)),
                    previous.lockTime,
                )
            val funding =
                input.copy(
                    txId = colored.txid.toString(),
                    previousTransaction = EvmEncoding.run { Transaction.write(colored).hex() },
                )
            assertTrue(UtxoStrategy.tokenFunding(funding))
            assertFailsWith<IllegalArgumentException> {
                UtxoStrategy.input(funding, EvmEncoding.bytes("0x" + funding.script), "ecash")
            }
        }
        UtxoStrategy.input(input, EvmEncoding.bytes("0x" + input.script), "ecash")
        assertFailsWith<InputException> {
            UtxoStrategy.script("ecash", quote.destination.replace("ecash:", "bitcoincash:"))
        }
    }

    @Test
    fun esp32SignaturesVerifyForEveryCoinWithAndWithoutChange() {
        assertEquals(UtxoNetworks.supported.size * 2, utxoVectors().size)
        for (vector in utxoVectors()) {
            val quote = utxoQuote(vector)
            val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
            assertEquals(
                Transaction.read(raw).txid.toString(),
                UtxoStrategy.transactionId(raw, quote),
                quote.asset.coin,
            )
            assertFailsWith<InputException> {
                UtxoStrategy.transactionId(raw, quote.copy(destination = quote.asset.address))
            }
            assertFailsWith<InputException> {
                UtxoStrategy.transactionId(raw, quote.copy(fee = "0.00000001"))
            }
            val plan = quote.utxoPlan!!
            assertFailsWith<InputException> {
                UtxoStrategy.transactionId(
                    raw,
                    quote.copy(utxoPlan = plan.copy(change = plan.change + 1)),
                )
            }
            assertFailsWith<InputException> {
                UtxoStrategy.transactionId(
                    raw,
                    quote.copy(
                        utxoPlan =
                            plan.copy(inputs = plan.inputs.map { it.copy(amount = it.amount + 1) })
                    ),
                )
            }
            val altered = raw.dropLast(8) + "01000000"
            assertFailsWith<InputException> { UtxoStrategy.transactionId(altered, quote) }
        }
    }

    @Test
    fun originalWalletCoreSignaturesVerifyAgainstTheSamePlan() {
        for (vector in utxoVectors()) {
            val quote = utxoQuote(vector)
            val raw = vector.jsonObject.getValue("walletCoreSigned").jsonPrimitive.content
            assertEquals(
                Transaction.read(raw).txid.toString(),
                UtxoStrategy.transactionId(raw, quote),
                quote.asset.coin,
            )
        }
    }

    @Test
    fun selectionAccountsForChangeAndDustAndRejectsWrongNetwork() {
        for (vector in utxoVectors().filterIndexed { index, _ -> index % 2 == 0 }) {
            val fixture = utxoQuote(vector)
            val plan = fixture.utxoPlan!!
            val quote =
                UtxoStrategy.plan(
                    fixture.asset,
                    fixture.destination,
                    Amount.display("50000000", fixture.asset.decimals),
                    plan.inputs,
                    1,
                )
            assertTrue(quote.utxoPlan!!.fee > 0)
            assertEquals(100000000L, quote.utxoPlan!!.change + quote.utxoPlan!!.fee + 50000000L)
            assertFailsWith<InputException> {
                UtxoStrategy.script(fixture.asset.coin, "tb1qinvalid")
            }
            assertFailsWith<InputException> {
                UtxoStrategy.plan(
                    fixture.asset,
                    fixture.destination,
                    Amount.display("200000000", fixture.asset.decimals),
                    plan.inputs,
                    1,
                )
            }
        }
    }

    @Test
    fun lookupFailuresNeverStartABroadcast() = runTest {
        val vector = utxoVectors().first()
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        for (reply in
            listOf(
                "unavailable" to HttpStatusCode.ServiceUnavailable,
                "invalid-json" to HttpStatusCode.OK,
                "" to HttpStatusCode.OK,
            )) {
            var sends = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.method == HttpMethod.Post) sends++
                        respond(reply.first, reply.second)
                    }
                )
            val network = ProductionWalletNetwork(client) { VaultSnapshot() }
            assertEquals(
                InputError.SUBMISSION_PENDING,
                assertFailsWith<InputException> { network.broadcast(raw, quote) }.reason,
            )
            assertEquals(0, sends)
            client.close()
        }
    }

    @Test
    fun timeoutChecksHashAndManualRetryReusesIdenticalBytes() = runTest {
        val vector = utxoVectors().first()
        val quote = utxoQuote(vector)
        val raw = vector.jsonObject.getValue("signed").jsonPrimitive.content
        val hash = UtxoStrategy.transactionId(raw, quote)
        var sends = 0
        var known = false
        val client =
            HttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Post) {
                        sends++
                        respond("Unavailable", HttpStatusCode.ServiceUnavailable)
                    } else if (known)
                        respond(
                            """{"txid":"$hash","status":{"confirmed":true}}""",
                            HttpStatusCode.OK,
                        )
                    else respond("Not found", HttpStatusCode.NotFound)
                }
            )
        val network = ProductionWalletNetwork(client) { VaultSnapshot() }
        assertFailsWith<InputException> { network.broadcast(raw, quote) }
        assertEquals(1, sends)
        known = true
        assertEquals(hash, network.broadcast(raw, quote))
        assertEquals(1, sends)
        assertEquals(TransferStatus.CONFIRMED, network.lastSubmissionStatus)
        client.close()
    }
}
