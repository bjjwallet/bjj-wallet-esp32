package pub.airlock.data.network

import fr.acinq.bitcoin.*
import kotlinx.serialization.json.*
import org.kotlincrypto.hash.sha3.Keccak256
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*

internal object TronPayment {
    val endpoint
        get() = NetworkProvider.TRON_RPC.serviceUrl("")

    private class Proto {
        val out = mutableListOf<Byte>()

        fun v(value: Long) {
            var n = value
            while (n >= 128) {
                out += ((n and 127) or 128).toByte()
                n = n ushr 7
            }
            out += n.toByte()
        }

        fun n(field: Int, value: Long) {
            v((field shl 3).toLong())
            v(value)
        }

        fun b(field: Int, value: ByteArray) {
            v(((field shl 3) or 2).toLong())
            v(value.size.toLong())
            out.addAll(value.toList())
        }

        fun result() = out.toByteArray()
    }

    private fun address(text: String): ByteArray =
        try {
            val (version, bytes) = Base58Check.decode(text)
            requireInput(
                version == 0x41.toByte() && bytes.size == 20,
                InputError.INVALID_DESTINATION,
            )
            byteArrayOf(version) + bytes
        } catch (_: Exception) {
            throw InputException(InputError.INVALID_DESTINATION)
        }

    private fun JsonObject.text(key: String) =
        get(key)?.jsonPrimitive?.content ?: throw InputException(InputError.INVALID_QUOTE)

    private fun JsonObject.number(key: String) =
        text(key).toLongOrNull()?.takeIf { it >= 0 }
            ?: throw InputException(InputError.INVALID_QUOTE)

    fun body(quote: TransferQuote): ByteArray {
        val asset = quote.asset
        requireInput(
            asset.coin == "tron" &&
                LedgerNetworks.supportsPayment(asset) &&
                quote.feeCurrency == CoinCatalog.get("tron") &&
                asset.address != quote.destination,
            InputError.INVALID_QUOTE,
        )
        val plan = quote.ledgerPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            plan.keys ==
                setOf("network", "ref_block_bytes", "ref_block_hash", "timestamp", "expires") &&
                plan["network"] == JsonPrimitive("tron-mainnet"),
            InputError.INVALID_QUOTE,
        )
        val time = plan.number("timestamp")
        val expires = plan.number("expires")
        val fee =
            Amount.atomic(quote.fee, 6).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val amount =
            Amount.atomic(quote.amount, asset.decimals).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            time > 0 &&
                expires > time &&
                expires - time <= 3600000 &&
                fee in 1..1000000000 &&
                amount > 0,
            InputError.INVALID_QUOTE,
        )
        val ref = plan.text("ref_block_bytes")
        val hash = plan.text("ref_block_hash")
        requireInput(
            Regex("[0-9a-f]{4}").matches(ref) && Regex("[0-9a-f]{16}").matches(hash),
            InputError.INVALID_QUOTE,
        )
        val owner = address(asset.address)
        val recipient = address(quote.destination)
        val token = Stablecoins.definition(asset)
        val value =
            Proto()
                .apply {
                    b(1, owner)
                    if (token == null) {
                        b(2, recipient)
                        n(3, amount)
                    } else {
                        b(2, address(token.contract))
                        val abi =
                            EvmEncoding.bytes(
                                "0xa9059cbb" +
                                    recipient.drop(1).toByteArray().hex().padStart(64, '0') +
                                    amount.toString(16).padStart(64, '0')
                            )
                        b(4, abi)
                    }
                }
                .result()
        val type = if (token == null) "TransferContract" else "TriggerSmartContract"
        val any =
            Proto()
                .apply {
                    b(1, "type.googleapis.com/protocol.$type".encodeToByteArray())
                    b(2, value)
                }
                .result()
        val contract =
            Proto()
                .apply {
                    n(1, if (token == null) 1 else 31)
                    b(2, any)
                }
                .result()
        return Proto()
            .apply {
                b(1, EvmEncoding.bytes("0x$ref"))
                b(4, EvmEncoding.bytes("0x$hash"))
                n(8, expires)
                b(11, contract)
                n(14, time)
                if (token != null) n(18, fee)
            }
            .result()
    }

    fun transactionId(signature: String, quote: TransferQuote): String {
        requireInput(Regex("[0-9a-f]{130}").matches(signature), InputError.INVALID_QUOTE)
        val digest = Crypto.sha256(body(quote))
        val bytes = EvmEncoding.bytes("0x$signature")
        requireInput(bytes[64].toInt() in 0..1, InputError.INVALID_QUOTE)
        val public =
            try {
                Crypto.recoverPublicKey(
                    ByteVector64(bytes.copyOfRange(0, 64)),
                    digest,
                    bytes[64].toInt(),
                )
            } catch (_: Exception) {
                throw InputException(InputError.INVALID_QUOTE)
            }
        val owner =
            Keccak256()
                .digest(public.toUncompressedBin().drop(1).toByteArray())
                .takeLast(20)
                .toByteArray()
        requireInput(
            address(quote.asset.address).drop(1).toByteArray().contentEquals(owner),
            InputError.INVALID_QUOTE,
        )
        return digest.hex()
    }

    suspend fun estimate(
        http: ProviderTransport,
        asset: Asset,
        recipient: String,
        amount: String,
    ): TransferQuote {
        address(asset.address)
        address(recipient)
        val block = http.json("$endpoint/wallet/getnowblock", buildJsonObject {})
        val header = block.getValue("block_header").jsonObject.getValue("raw_data").jsonObject
        val number = header.number("number")
        val time = header.number("timestamp")
        val id = block.text("blockID")
        requireInput(Regex("[0-9a-f]{64}").matches(id) && number > 0, InputError.INVALID_QUOTE)
        // Upper bound, explicitly approved in TRX; unused resources are not charged.
        val fee = if (asset.contract.isEmpty()) "2" else "100"
        val quote =
            TransferQuote(
                asset,
                recipient,
                amount,
                fee,
                ledgerPlan =
                    buildJsonObject {
                        put("network", "tron-mainnet")
                        put("ref_block_bytes", number.toString(16).padStart(4, '0').takeLast(4))
                        put("ref_block_hash", id.substring(16, 32))
                        put("timestamp", time.toString())
                        put("expires", (time + 600000).toString())
                    },
            )
        body(quote)
        return quote
    }

    suspend fun submit(
        http: ProviderTransport,
        signature: String,
        quote: TransferQuote,
    ): TransferStatus {
        val id = transactionId(signature, quote)
        val raw =
            Proto()
                .apply {
                    b(1, body(quote))
                    b(2, EvmEncoding.bytes("0x$signature"))
                }
                .result()
        val result =
            http.json(
                "$endpoint/wallet/broadcasthex",
                buildJsonObject { put("transaction", raw.hex()) },
            )
        requireInput(
            result["result"] == JsonPrimitive(true) && result["txid"] == JsonPrimitive(id),
            InputError.TRANSACTION_REJECTED,
        )
        return TransferStatus.SUBMITTED
    }
}
