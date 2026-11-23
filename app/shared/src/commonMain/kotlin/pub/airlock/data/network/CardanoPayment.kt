package pub.airlock.data.network

import kotlinx.serialization.json.*
import pub.airlock.data.cardanoPaymentAddress
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.data.validCardanoBaseAddress
import pub.airlock.domain.*

internal object CardanoPayment {
    private val endpoint = NetworkProvider.KOIOS.serviceUrl("")
    private const val maxLovelace = 45_000_000_000_000_000L
    private const val maxFee = 2_000_000L
    private const val maxInputs = 16
    private val txHash = Regex("[0-9a-f]{64}")
    private val atomicPattern = Regex("0|[1-9][0-9]{0,17}")
    private val order = compareBy<CardanoInput>({ it.txHash }, { it.index })

    fun lovelace(value: String): Long =
        Amount.atomic(value, 6).toLongOrNull()?.takeIf { it in 0..maxLovelace }
            ?: throw InputException(InputError.INVALID_QUOTE)

    private class Cbor {
        private val output = mutableListOf<Byte>()

        private fun value(major: Int, n: Long) {
            require(n >= 0)
            val prefix = major shl 5
            if (n < 24) {
                output += (prefix or n.toInt()).toByte()
                return
            }
            val size =
                when {
                    n <= 0xff -> 1
                    n <= 0xffff -> 2
                    n <= 0xffffffffL -> 4
                    else -> 8
                }
            val marker =
                when (size) {
                    1 -> 24
                    2 -> 25
                    4 -> 26
                    else -> 27
                }
            output += (prefix or marker).toByte()
            for (i in size - 1 downTo 0) output += (n ushr (8 * i)).toByte()
        }

        fun uint(n: Long) = value(0, n)

        fun array(n: Int) = value(4, n.toLong())

        fun map(n: Int) = value(5, n.toLong())

        fun set(n: Int) {
            value(6, 258)
            array(n)
        }

        fun bytes(value: ByteArray) {
            value(2, value.size.toLong())
            raw(value)
        }

        fun raw(value: ByteArray) {
            output.addAll(value.toList())
        }

        fun result() = output.toByteArray()
    }

    private fun output(
        address: ByteArray,
        amount: Long,
        contract: String = "",
        quantity: Long = 0,
    ) =
        Cbor()
            .apply {
                map(2)
                uint(0)
                bytes(address)
                uint(1)
                if (contract.isEmpty() || quantity == 0L) uint(amount)
                else {
                    array(2)
                    uint(amount)
                    map(1)
                    bytes(EvmEncoding.bytes("0x" + contract.take(56)))
                    map(1)
                    bytes(EvmEncoding.bytes("0x" + contract.drop(56)))
                    uint(quantity)
                }
            }
            .result()

    internal fun body(quote: TransferQuote): ByteArray {
        val plan = quote.cardanoPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            CardanoNetwork.supportsPayment(quote.asset) &&
                quote.feeCurrency == CoinCatalog.get("cardano") &&
                validCardanoBaseAddress(quote.asset.address) &&
                quote.asset.address != quote.destination &&
                plan.inputs.size in 1..maxInputs &&
                plan.change in 0..maxLovelace &&
                plan.ttl > 0,
            InputError.INVALID_QUOTE,
        )
        val sender = cardanoPaymentAddress(quote.asset.address)!!
        val recipient =
            cardanoPaymentAddress(quote.destination)
                ?: throw InputException(InputError.INVALID_DESTINATION)
        val token = quote.asset.contract.isNotEmpty()
        val quantity =
            Amount.atomic(quote.amount, quote.asset.decimals).toLongOrNull()?.takeIf { it > 0 }
                ?: throw InputException(InputError.INVALID_QUOTE)
        val amount = if (token) plan.recipientLovelace else lovelace(quote.amount)
        val fee = lovelace(quote.fee)
        requireInput(amount > 0 && fee in 1..maxFee, InputError.INVALID_QUOTE)
        val inputs = plan.inputs.sortedWith(order)
        var total = 0L
        var tokenTotal = 0L
        for ((i, input) in inputs.withIndex()) {
            requireInput(
                txHash.matches(input.txHash) &&
                    input.index in 0..65535 &&
                    input.lovelace in 1..(maxLovelace - total) &&
                    (i == 0 || order.compare(inputs[i - 1], input) != 0),
                InputError.INVALID_QUOTE,
            )
            requireInput(
                input.tokenQuantity >= 0 && input.tokenQuantity <= Long.MAX_VALUE - tokenTotal,
                InputError.INVALID_QUOTE,
            )
            tokenTotal += input.tokenQuantity
            total += input.lovelace
        }
        requireInput(total == amount + fee + plan.change, InputError.INVALID_QUOTE)
        requireInput(
            if (token) tokenTotal >= quantity && (tokenTotal == quantity || plan.change > 0)
            else tokenTotal == 0L && plan.recipientLovelace == 0L,
            InputError.INVALID_QUOTE,
        )
        return Cbor()
            .apply {
                map(4)
                uint(0)
                set(inputs.size)
                for (input in inputs) {
                    array(2)
                    bytes(EvmEncoding.bytes("0x" + input.txHash))
                    uint(input.index.toLong())
                }
                uint(1)
                array(if (plan.change == 0L) 1 else 2)
                raw(output(recipient, amount, quote.asset.contract, if (token) quantity else 0))
                if (plan.change != 0L)
                    raw(
                        output(
                            sender,
                            plan.change,
                            quote.asset.contract,
                            if (token) tokenTotal - quantity else 0,
                        )
                    )
                uint(2)
                uint(fee)
                uint(3)
                uint(plan.ttl)
            }
            .result()
    }

    private fun transaction(body: ByteArray, witness: ByteArray) =
        Cbor()
            .apply {
                array(4)
                raw(body)
                map(1)
                uint(0)
                set(1)
                array(2)
                bytes(witness.copyOfRange(0, 32))
                bytes(witness.copyOfRange(32, 96))
                raw(byteArrayOf(0xf5.toByte(), 0xf6.toByte()))
            }
            .result()

    private suspend fun read(
        transport: ProviderTransport,
        path: String,
        request: JsonObject? = null,
    ): JsonArray {
        val raw = transport.explorer(endpoint + path, request?.toString(), jsonBody = true)
        return runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    private fun JsonObject.number(name: String): Long =
        (this[name] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf(atomicPattern::matches)
            ?.toLongOrNull() ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

    private suspend fun tip(transport: ProviderTransport): JsonObject =
        read(transport, "/tip").singleOrNull() as? JsonObject
            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

    suspend fun estimate(
        transport: ProviderTransport,
        asset: Asset,
        destination: String,
        amount: String,
    ): TransferQuote {
        requireInput(
            CardanoNetwork.supportsPayment(asset) && validCardanoBaseAddress(asset.address),
            InputError.INVALID_QUOTE,
        )
        val sender = cardanoPaymentAddress(asset.address)!!
        val recipient =
            cardanoPaymentAddress(destination)
                ?: throw InputException(InputError.INVALID_DESTINATION)
        requireInput(destination != asset.address, InputError.INVALID_DESTINATION)
        val token = asset.contract.isNotEmpty()
        val quantity =
            Amount.atomic(amount, asset.decimals).toLongOrNull()?.takeIf { it > 0 }
                ?: throw InputException(InputError.AMOUNT_POSITIVE)
        val tip = tip(transport)
        val slot = tip.number("abs_slot")
        val epoch = tip.number("epoch_no")
        if (slot !in 1..0xffffffffL) throw ProviderFailure(Failure.INVALID_RESPONSE)
        val params =
            read(transport, "/epoch_params?_epoch_no=$epoch").singleOrNull() as? JsonObject
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        val perByte = params.number("min_fee_a")
        val fixedFee = params.number("min_fee_b")
        val coinsPerByte = params.number("coins_per_utxo_size")
        val maxSize = params.number("max_tx_size")
        if (
            params.number("epoch_no") != epoch ||
                perByte !in 1..1000 ||
                fixedFee !in 1..maxFee ||
                coinsPerByte !in 1..50000 ||
                maxSize !in 256..65536
        )
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        fun minimum(to: ByteArray, value: Long, quantity: Long = 0) =
            (160 + output(to, value, asset.contract, quantity).size) * coinsPerByte
        var sent = if (token) 1_000_000L else lovelace(amount)
        if (token) repeat(4) { sent = minimum(recipient, sent, quantity) }
        requireInput(
            sent >= minimum(recipient, sent, if (token) quantity else 0),
            InputError.INVALID_QUOTE,
        )
        val addresses = buildJsonObject {
            put("_addresses", JsonArray(listOf(JsonPrimitive(asset.address))))
            put("_extended", true)
        }
        val rows = read(transport, "/address_utxos?order=value.desc&limit=128", addresses)
        if (rows.size > 128) throw ProviderFailure(Failure.INVALID_RESPONSE)
        val inputs =
            rows
                .mapNotNull { value ->
                    val row =
                        value as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                    if (row["address"] != JsonPrimitive(asset.address))
                        throw ProviderFailure(Failure.INVALID_RESPONSE)
                    val assets =
                        row["asset_list"] as? JsonArray
                            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                    if (
                        (!token && assets.isNotEmpty()) ||
                            row["is_spent"] == JsonPrimitive(true) ||
                            listOf("datum_hash", "inline_datum", "reference_script").any {
                                row[it] != JsonNull
                            }
                    )
                        return@mapNotNull null
                    var tokenQuantity = 0L
                    for (entry in assets) {
                        val item =
                            entry as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                        val subject =
                            item["policy_id"]?.stringValue().orEmpty() +
                                item["asset_name"]?.stringValue().orEmpty()
                        // Do not consume other assets or omit them from change.
                        if (subject != asset.contract) return@mapNotNull null
                        if (tokenQuantity != 0L) throw ProviderFailure(Failure.INVALID_RESPONSE)
                        tokenQuantity = item.number("quantity")
                        if (tokenQuantity <= 0) throw ProviderFailure(Failure.INVALID_RESPONSE)
                    }
                    val hash =
                        row["tx_hash"]?.stringValue()
                            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                    val index = row.number("tx_index")
                    val lovelace = row.number("value")
                    if (!txHash.matches(hash) || index !in 0..65535 || lovelace !in 1..maxLovelace)
                        throw ProviderFailure(Failure.INVALID_RESPONSE)
                    CardanoInput(hash, index.toInt(), lovelace, tokenQuantity)
                }
                .sortedWith(
                    compareByDescending<CardanoInput> { it.tokenQuantity > 0 }
                        .thenByDescending { it.lovelace }
                )
        if (inputs.map { it.txHash to it.index }.distinct().size != inputs.size)
            throw ProviderFailure(Failure.INVALID_RESPONSE)

        val selected = mutableListOf<CardanoInput>()
        var total = 0L
        var tokenTotal = 0L
        for (input in inputs.take(maxInputs)) {
            selected += input
            total += input.lovelace
            if (input.tokenQuantity > Long.MAX_VALUE - tokenTotal)
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            tokenTotal += input.tokenQuantity
            if (total > maxLovelace) throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (token && tokenTotal < quantity) continue
            var fee = fixedFee
            repeat(8) {
                val change = total - sent - fee
                if (change < 0 || fee > maxFee) return@repeat
                val quote =
                    TransferQuote(
                        asset,
                        destination,
                        amount,
                        Amount.display(fee.toString(), 6),
                        cardanoPlan =
                            CardanoTransactionPlan(
                                selected.toList(),
                                change,
                                slot + 900,
                                if (token) sent else 0,
                            ),
                    )
                val size = transaction(body(quote), ByteArray(96)).size
                if (size > maxSize) throw ProviderFailure(Failure.INVALID_RESPONSE)
                val needed = fixedFee + size * perByte
                if (fee >= needed) {
                    if (
                        (change == 0L && (!token || tokenTotal == quantity)) ||
                            change >=
                                minimum(sender, change, if (token) tokenTotal - quantity else 0)
                    )
                        return quote
                } else fee = needed
            }
        }
        throw InputException(InputError.INSUFFICIENT_BALANCE)
    }

    private fun verify(
        witnessHex: String,
        quote: TransferQuote,
        verifier: Ed25519Verifier,
    ): Pair<ByteArray, ByteArray> {
        requireInput(Regex("[0-9a-f]{192}").matches(witnessHex), InputError.INVALID_QUOTE)
        val signed = EvmEncoding.bytes("0x" + witnessHex)
        val body = body(quote)
        val key = signed.copyOfRange(0, 32)
        val sender = cardanoPaymentAddress(quote.asset.address)!!
        val hash = CardanoBlake2b.hash(body, 32)
        requireInput(
            CardanoBlake2b.hash(key, 28).contentEquals(sender.copyOfRange(1, 29)) &&
                verifier.verify(key, hash, signed.copyOfRange(32, 96)),
            InputError.INVALID_QUOTE,
        )
        return hash to transaction(body, signed)
    }

    fun transactionId(witness: String, quote: TransferQuote, verifier: Ed25519Verifier): String =
        verify(witness, quote, verifier).first.hex()

    suspend fun submit(
        transport: ProviderTransport,
        witness: String,
        quote: TransferQuote,
        verifier: Ed25519Verifier,
    ): TransferStatus {
        val (hash, transaction) = verify(witness, quote, verifier)
        val id = hash.hex()
        val query = buildJsonObject { put("_tx_hashes", JsonArray(listOf(JsonPrimitive(id)))) }
        suspend fun status(): TransferStatus? {
            val rows = read(transport, "/tx_status", query)
            if (rows.isEmpty()) return null
            val row =
                rows.singleOrNull() as? JsonObject
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (row["tx_hash"] != JsonPrimitive(id)) throw ProviderFailure(Failure.INVALID_RESPONSE)
            // Koios can echo an unknown hash with null confirmations.
            if (row["num_confirmations"] == JsonNull) return null
            return if (row.number("num_confirmations") > 0) TransferStatus.CONFIRMED
            else TransferStatus.SUBMITTED
        }
        status()?.let {
            return it
        }
        if (tip(transport).number("abs_slot") >= quote.cardanoPlan!!.ttl)
            throw ProviderFailure(Failure.REJECTED)
        return try {
            val result =
                transport
                    .submitCbor("$endpoint/submittx", transaction)
                    .trim()
                    .removeSurrounding("\"")
            if (result != id) throw ProviderFailure(Failure.INVALID_RESPONSE)
            TransferStatus.SUBMITTED
        } catch (failure: ProviderFailure) {
            status() ?: throw failure
        }
    }
}
