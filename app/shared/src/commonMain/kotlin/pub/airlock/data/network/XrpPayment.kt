package pub.airlock.data.network

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Digest
import kotlinx.serialization.json.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*

internal object XrpPayment {
    const val endpoint = "https://xrplcluster.com"
    private val alphabet = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
    private val bitcoin = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun address(value: String): ByteArray =
        try {
            val normalized = value.map { bitcoin[alphabet.indexOf(it)] }.joinToString("")
            val (version, bytes) = Base58Check.decode(normalized)
            requireInput(version == 0.toByte() && bytes.size == 20, InputError.INVALID_DESTINATION)
            bytes
        } catch (_: Exception) {
            throw InputException(InputError.INVALID_DESTINATION)
        }

    private fun JsonObject.number(key: String) =
        get(key)?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw InputException(InputError.INVALID_QUOTE)

    private fun be(value: Long, n: Int = 8) =
        ByteArray(n) { (value ushr ((n - it - 1) * 8)).toByte() }

    fun body(
        quote: TransferQuote,
        key: ByteArray = byteArrayOf(),
        signature: ByteArray = byteArrayOf(),
    ): ByteArray {
        val asset = quote.asset
        requireInput(
            asset.coin == "ripple" &&
                LedgerNetworks.supportsPayment(asset) &&
                asset.address != quote.destination &&
                quote.feeCurrency == CoinCatalog.get("ripple"),
            InputError.INVALID_QUOTE,
        )
        val plan = quote.ledgerPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            plan.keys == setOf("network", "sequence", "last_ledger") &&
                plan["network"] == JsonPrimitive("ripple-mainnet"),
            InputError.INVALID_QUOTE,
        )
        val sequence = plan.number("sequence")
        val last = plan.number("last_ledger")
        val fee =
            Amount.atomic(quote.fee, 6).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        var amount =
            Amount.atomic(quote.amount, asset.decimals).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            sequence in 1..0xffffffffL && last in 1..0xffffffffL && fee in 1..1000000 && amount > 0,
            InputError.INVALID_QUOTE,
        )
        val from = address(asset.address)
        val to = address(quote.destination)
        val token = Stablecoins.definition(asset)
        val encoded =
            if (token == null) {
                requireInput(amount <= 100000000000000000L, InputError.INVALID_QUOTE)
                be(0x4000000000000000L or amount)
            } else {
                var exponent = -asset.decimals
                while (amount < 1000000000000000L) {
                    amount *= 10
                    exponent--
                }
                while (amount > 9999999999999999L) {
                    requireInput(amount % 10 == 0L, InputError.INVALID_QUOTE)
                    amount /= 10
                    exponent++
                }
                requireInput(exponent in -96..80, InputError.INVALID_QUOTE)
                be(0xc000000000000000UL.toLong() or ((exponent + 97).toLong() shl 54) or amount) +
                    EvmEncoding.bytes("0x" + token.contract.substringBefore('.')) +
                    address(token.contract.substringAfter('.'))
            }
        requireInput(
            key.isEmpty() || (key.size == 33 && key[0].toInt() in 2..3),
            InputError.INVALID_QUOTE,
        )
        return byteArrayOf(0x12, 0, 0, 0x22) +
            be(0x80000000, 4) +
            byteArrayOf(0x24) +
            be(sequence, 4) +
            byteArrayOf(0x20, 0x1b) +
            be(last, 4) +
            byteArrayOf(0x61) +
            encoded +
            byteArrayOf(0x68) +
            be(0x4000000000000000L or fee) +
            byteArrayOf(0x73, key.size.toByte()) +
            key +
            (if (signature.isEmpty()) byteArrayOf()
            else byteArrayOf(0x74, signature.size.toByte()) + signature) +
            byteArrayOf(0x81.toByte(), 20) +
            from +
            byteArrayOf(0x83.toByte(), 20) +
            to
    }

    private fun half(bytes: ByteArray) = Digest.sha512().hash(bytes).copyOf(32)

    private fun der(compact: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val stripped =
                bytes.dropWhile { it == 0.toByte() }.ifEmpty { listOf(0.toByte()) }.toByteArray()
            val positive = if (stripped[0] < 0) byteArrayOf(0) + stripped else stripped
            return byteArrayOf(2, positive.size.toByte()) + positive
        }
        val encoded = integer(compact.copyOfRange(0, 32)) + integer(compact.copyOfRange(32, 64))
        return byteArrayOf(0x30, encoded.size.toByte()) + encoded
    }

    fun signed(signature: String, quote: TransferQuote): ByteArray {
        requireInput(Regex("[0-9a-f]{196}").matches(signature), InputError.INVALID_QUOTE)
        val bytes = EvmEncoding.bytes("0x$signature")
        val public = bytes.copyOfRange(0, 33)
        val compact = bytes.copyOfRange(33, 97)
        val hash = half(byteArrayOf(0x53, 0x54, 0x58, 0) + body(quote, public))
        requireInput(bytes[97].toInt() in 0..1, InputError.INVALID_QUOTE)
        val recovered =
            try {
                Crypto.recoverPublicKey(ByteVector64(compact), hash, bytes[97].toInt())
            } catch (_: Exception) {
                throw InputException(InputError.INVALID_QUOTE)
            }
        requireInput(
            recovered.value.toByteArray().contentEquals(public) &&
                recovered.hash160().contentEquals(address(quote.asset.address)),
            InputError.INVALID_QUOTE,
        )
        return body(quote, public, der(compact))
    }

    fun transactionId(signature: String, quote: TransferQuote) =
        half(byteArrayOf(0x54, 0x58, 0x4e, 0) + signed(signature, quote)).hex()

    private suspend fun rpc(
        http: ProviderTransport,
        method: String,
        params: JsonObject = buildJsonObject {},
    ): JsonObject {
        val response =
            http.json(
                endpoint,
                buildJsonObject {
                    put("method", method)
                    put("params", JsonArray(listOf(params)))
                },
            )
        val result =
            response["result"] as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (result["error"] != null || result["status"] == JsonPrimitive("error"))
            throw ProviderFailure(Failure.REJECTED)
        return result
    }

    suspend fun balance(http: ProviderTransport, asset: Asset): String {
        address(asset.address)
        if (asset.contract.isEmpty()) {
            val account =
                rpc(
                    http,
                    "account_info",
                    buildJsonObject {
                        put("account", asset.address)
                        put("ledger_index", "validated")
                    },
                )
            val data = account.getValue("account_data").jsonObject
            requireInput(data["Account"] == JsonPrimitive(asset.address), InputError.INVALID_QUOTE)
            return Amount.display(data.number("Balance").toString(), 6)
        }
        val token =
            Stablecoins.definition(asset) ?: throw InputException(InputError.NETWORK_UNSUPPORTED)
        val lines =
            rpc(
                http,
                "account_lines",
                buildJsonObject {
                    put("account", asset.address)
                    put("peer", token.contract.substringAfter('.'))
                    put("ledger_index", "validated")
                },
            )
        requireInput(
            lines["account"] == JsonPrimitive(asset.address) && lines["marker"] == null,
            InputError.INVALID_QUOTE,
        )
        val rows =
            lines.getValue("lines").jsonArray.filter {
                it.jsonObject["currency"] == JsonPrimitive(token.contract.substringBefore('.')) &&
                    it.jsonObject["account"] == JsonPrimitive(token.contract.substringAfter('.'))
            }
        if (rows.isEmpty()) return "0"
        val balance = rows.single().jsonObject.getValue("balance").jsonPrimitive.content
        return Amount.display(Amount.atomic(balance, asset.decimals), asset.decimals)
    }

    suspend fun estimate(
        http: ProviderTransport,
        asset: Asset,
        recipient: String,
        amount: String,
    ): TransferQuote {
        address(asset.address)
        address(recipient)
        val account =
            rpc(
                http,
                "account_info",
                buildJsonObject {
                    put("account", asset.address)
                    put("ledger_index", "current")
                },
            )
        val data = account.getValue("account_data").jsonObject
        requireInput(data["Account"] == JsonPrimitive(asset.address), InputError.INVALID_QUOTE)
        val sequence = data.number("Sequence")
        val ledger = rpc(http, "ledger_current").number("ledger_current_index")
        val fee =
            rpc(http, "fee")
                .getValue("drops")
                .jsonObject
                .number("open_ledger_fee")
                .coerceAtLeast(10)
        val quote =
            TransferQuote(
                asset,
                recipient,
                amount,
                Amount.display(fee.toString(), 6),
                ledgerPlan =
                    buildJsonObject {
                        put("network", "ripple-mainnet")
                        put("sequence", sequence.toString())
                        put("last_ledger", (ledger + 20).toString())
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
        val hash = transactionId(signature, quote)
        val result =
            rpc(
                http,
                "submit",
                buildJsonObject {
                    put("tx_blob", signed(signature, quote).hex())
                    put("fail_hard", true)
                },
            )
        val code = result["engine_result"]?.jsonPrimitive?.content
        requireInput(
            code in setOf("tesSUCCESS", "terQUEUED") &&
                result["tx_json"]?.jsonObject?.get("hash")?.jsonPrimitive?.content?.lowercase() ==
                    hash,
            InputError.TRANSACTION_REJECTED,
        )
        return TransferStatus.SUBMITTED
    }
}
