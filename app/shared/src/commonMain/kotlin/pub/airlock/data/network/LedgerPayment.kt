package pub.airlock.data.network

import fr.acinq.bitcoin.Crypto
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.serialization.json.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.data.validStellarAccount
import pub.airlock.domain.*

/** Public transaction construction and verification. Signing exists only in firmware. */
internal object LedgerPayment {
    const val stellar = "https://horizon.stellar.org"

    private fun key(address: String): ByteArray {
        requireInput(validStellarAccount(address), InputError.INVALID_DESTINATION)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes = mutableListOf<Byte>()
        var acc = 0
        var bits = 0
        for (c in address) {
            acc = (acc shl 5) or alphabet.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                bytes += (acc ushr bits).toByte()
            }
        }
        return bytes.toByteArray().copyOfRange(1, 33)
    }

    private class Xdr {
        val bytes = mutableListOf<Byte>()

        fun n(value: Long, size: Int = 4) {
            for (i in size - 1 downTo 0) bytes += (value ushr (i * 8)).toByte()
        }

        fun raw(value: ByteArray) {
            bytes.addAll(value.toList())
        }

        fun result() = bytes.toByteArray()
    }

    private fun JsonObject.number(name: String) =
        get(name)?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw InputException(InputError.INVALID_QUOTE)

    fun body(quote: TransferQuote): ByteArray {
        if (quote.asset.coin == "ton") return TonPayment.body(quote)
        if (quote.asset.coin == "sui") return SuiPayment.body(quote)
        if (quote.asset.coin == "ripple") return XrpPayment.body(quote)
        if (quote.asset.coin == "tron") return TronPayment.body(quote)
        requireInput(
            quote.asset.coin == "stellar" &&
                LedgerNetworks.supportsPayment(quote.asset) &&
                quote.feeCurrency == CoinCatalog.get("stellar") &&
                quote.asset.address != quote.destination,
            InputError.INVALID_QUOTE,
        )
        val plan = quote.ledgerPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            plan.keys == setOf("network", "sequence", "expires") &&
                plan["network"] == JsonPrimitive("stellar-mainnet"),
            InputError.INVALID_QUOTE,
        )
        val sequence = plan.number("sequence")
        val expires = plan.number("expires")
        val fee =
            Amount.atomic(quote.fee, 7).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val amount =
            Amount.atomic(quote.amount, 7).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            sequence > 0 && expires > 0 && fee in 100..1000000 && amount > 0,
            InputError.INVALID_QUOTE,
        )
        val source = key(quote.asset.address)
        val recipient = key(quote.destination)
        return Xdr()
            .apply {
                n(0)
                raw(source)
                n(fee)
                n(sequence, 8)
                n(1)
                n(0, 8)
                n(expires, 8)
                n(0)
                n(1)
                n(0)
                n(1)
                n(0)
                raw(recipient)
                val token = Stablecoins.definition(quote.asset)
                if (token == null) n(0)
                else {
                    val code = token.contract.substringBefore(':').encodeToByteArray()
                    val size = if (code.size <= 4) 4 else 12
                    requireInput(code.size in 1..12, InputError.INVALID_QUOTE)
                    n(if (size == 4) 1 else 2)
                    raw(code.copyOf(size))
                    n(0)
                    raw(key(token.contract.substringAfter(':')))
                }
                n(amount, 8)
                n(0)
            }
            .result()
    }

    private fun sha256(bytes: ByteArray) = Crypto.sha256(bytes)

    private fun hash(body: ByteArray) =
        sha256(
            sha256("Public Global Stellar Network ; September 2015".encodeToByteArray()) +
                byteArrayOf(0, 0, 0, 2) +
                body
        )

    fun transactionId(signature: String, quote: TransferQuote, verifier: Ed25519Verifier): String {
        if (quote.asset.coin == "ton") return TonPayment.transactionId(signature, quote, verifier)
        if (quote.asset.coin == "sui") return SuiPayment.transactionId(signature, quote, verifier)
        if (quote.asset.coin == "ripple") return XrpPayment.transactionId(signature, quote)
        if (quote.asset.coin == "tron") return TronPayment.transactionId(signature, quote)
        requireInput(Regex("[0-9a-f]{128}").matches(signature), InputError.INVALID_QUOTE)
        val hash = hash(body(quote))
        requireInput(
            verifier.verify(key(quote.asset.address), hash, EvmEncoding.bytes("0x$signature")),
            InputError.INVALID_QUOTE,
        )
        return hash.hex()
    }

    suspend fun estimate(
        http: ProviderTransport,
        asset: Asset,
        recipient: String,
        amount: String,
    ): TransferQuote {
        if (asset.coin == "ton") return TonPayment.estimate(http, asset, recipient, amount)
        if (asset.coin == "sui") return SuiPayment.estimate(http, asset, recipient, amount)
        if (asset.coin == "ripple") return XrpPayment.estimate(http, asset, recipient, amount)
        if (asset.coin == "tron") return TronPayment.estimate(http, asset, recipient, amount)
        requireInput(LedgerNetworks.supportsPayment(asset), InputError.NETWORK_UNSUPPORTED)
        key(asset.address)
        key(recipient)
        val account = http.json("$stellar/accounts/${asset.address}")
        requireInput(
            account["account_id"] == JsonPrimitive(asset.address),
            InputError.INVALID_QUOTE,
        )
        val sequence = account.number("sequence")
        requireInput(sequence < Long.MAX_VALUE, InputError.INVALID_QUOTE)
        val fees = http.json("$stellar/fee_stats")
        val fee =
            (fees["fee_charged"] as? JsonObject)?.number("p90")?.coerceAtLeast(100)
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(fee <= 1000000, InputError.INVALID_QUOTE)
        val quote =
            TransferQuote(
                asset,
                recipient,
                amount,
                Amount.display(fee.toString(), 7),
                ledgerPlan =
                    buildJsonObject {
                        put("network", "stellar-mainnet")
                        put("sequence", (sequence + 1).toString())
                        put("expires", (Clock.System.now().epochSeconds + 300).toString())
                    },
            )
        body(quote)
        return quote
    }

    suspend fun submit(
        http: ProviderTransport,
        signature: String,
        quote: TransferQuote,
        verifier: Ed25519Verifier,
    ): TransferStatus {
        if (quote.asset.coin == "ton") return TonPayment.submit(http, signature, quote, verifier)
        if (quote.asset.coin == "sui") return SuiPayment.submit(http, signature, quote, verifier)
        if (quote.asset.coin == "ripple") return XrpPayment.submit(http, signature, quote)
        if (quote.asset.coin == "tron") return TronPayment.submit(http, signature, quote)
        val id = transactionId(signature, quote, verifier)
        requireInput(
            quote.ledgerPlan!!.number("expires") > Clock.System.now().epochSeconds,
            InputError.INVALID_QUOTE,
        )
        val envelope =
            Xdr()
                .apply {
                    n(2)
                    raw(body(quote))
                    n(1)
                    raw(key(quote.asset.address).takeLast(4).toByteArray())
                    n(64)
                    raw(EvmEncoding.bytes("0x$signature"))
                }
                .result()
        val result = http.submitForm("$stellar/transactions", "tx", Base64.encode(envelope))
        requireInput(
            result["hash"] == JsonPrimitive(id) && result["successful"] == JsonPrimitive(true),
            InputError.TRANSACTION_REJECTED,
        )
        return TransferStatus.CONFIRMED
    }
}
