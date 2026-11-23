package pub.airlock.data.network

import io.ktor.http.*
import kotlinx.serialization.json.*
import pub.airlock.domain.*

/** Mainnet-only explorer contracts. Amounts never pass through floating point. */
internal class UtxoExplorerProvider(
    private val transport: ProviderTransport,
    override val id: NetworkProvider,
) : WalletProvider {
    init {
        require(id in NetworkProvider.explorers)
    }

    private val base = id.serviceUrl("")
    private val networks =
        mapOf(
            "bitcoin" to "btc",
            "bitcoincash" to "bch",
            "litecoin" to "ltc",
            "dogecoin" to "doge",
            "dash" to "dash",
            "ecash" to "xec",
        )

    override fun supports(coin: String, capability: Capability) =
        (capability == Capability.BALANCE && coin in ProviderCoverage.balances(id)) ||
            (capability == Capability.HISTORY &&
                coin == "bitcoin" &&
                id in setOf(NetworkProvider.BLOCKSTREAM, NetworkProvider.MEMPOOL))

    suspend fun history(asset: Asset, cursor: String?): TransactionPage {
        requireInput(
            supports(asset.coin, Capability.HISTORY) &&
                asset.contract.isEmpty() &&
                asset.decimals == 8,
            InputError.NETWORK_UNSUPPORTED,
        )
        val sender =
            UtxoStrategy.script(asset.coin, asset.address).let { EvmEncoding.run { it.hex() } }
        requireInput(
            cursor == null || cursor.matches(Regex("[0-9a-f]{64}")),
            InputError.INVALID_QUOTE,
        )
        val endpoint =
            if (cursor == null) url(asset.coin, "address", asset.address, "txs")
            else url(asset.coin, "address", asset.address, "txs", "chain", cursor)
        val rows =
            Json.parseToJsonElement(transport.explorer(endpoint)) as? JsonArray
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (rows.size > 75) throw ProviderFailure(Failure.INVALID_RESPONSE)
        fun hash(value: JsonElement?) =
            value?.stringValue()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        fun amount(row: JsonObject) =
            integer(row["value"]).also {
                if (Amount.compare(it, "2100000000000000") > 0)
                    throw ProviderFailure(Failure.INVALID_RESPONSE)
            }
        fun ours(row: JsonObject) = row["scriptpubkey"]?.stringValue() == sender
        val transfers =
            rows.map { value ->
                val row = value.jsonObject
                val txid = hash(row["txid"])
                val outputs =
                    row["vout"]?.jsonArray?.map { it.jsonObject }
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val inputs =
                    row["vin"]?.jsonArray?.mapNotNull { it.jsonObject["prevout"] as? JsonObject }
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val spent =
                    inputs.filter(::ours).fold("0") { sum, output ->
                        Amount.add(sum, amount(output))
                    }
                val received =
                    outputs.filter(::ours).fold("0") { sum, output ->
                        Amount.add(sum, amount(output))
                    }
                val incoming = spent == "0"
                if (incoming && received == "0") throw ProviderFailure(Failure.INVALID_RESPONSE)
                val external = outputs.filterNot(::ours)
                val sent = external.fold("0") { sum, output -> Amount.add(sum, amount(output)) }
                val valueAtomic = if (incoming) received else sent
                val confirmed =
                    row["status"]?.jsonObject?.get("confirmed")?.jsonPrimitive?.booleanOrNull
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                Transfer(
                    txid,
                    asset.id,
                    Amount.display(valueAtomic, 8),
                    if (incoming) asset.address
                    else
                        external
                            .firstOrNull()
                            ?.get("scriptpubkey_address")
                            ?.stringValue()
                            .orEmpty(),
                    incoming,
                    if (confirmed) TransferStatus.CONFIRMED else TransferStatus.SUBMITTED,
                )
            }
        if (transfers.map { it.id }.distinct().size != transfers.size)
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val confirmed = transfers.filter { it.status == TransferStatus.CONFIRMED }
        return TransactionPage(
            transfers,
            confirmed.lastOrNull()?.id?.takeIf { confirmed.size >= 25 },
        )
    }

    private fun url(coin: String, vararg segments: String): String {
        if (coin !in ProviderCoverage.balances(id)) throw ProviderFailure(Failure.UNSUPPORTED)
        return URLBuilder(base)
            .apply {
                if (id == NetworkProvider.FREEDOM) appendPathSegments(networks.getValue(coin))
                appendPathSegments(*segments, encodeSlash = true)
            }
            .buildString()
    }

    override suspend fun balance(asset: Asset): String = addressActivity(asset).balance

    suspend fun addressActivity(asset: Asset): AddressActivity {
        requireInput(asset.contract.isEmpty(), InputError.NETWORK_UNSUPPORTED)
        requireInput(
            asset.decimals == CoinCatalog.get(asset.coin).decimals,
            InputError.PUBLIC_DATA_INVALID,
        )
        val result = transport.json(url(asset.coin, "address", asset.address))
        fun canonical(address: String) =
            when (asset.coin) {
                "bitcoincash",
                "ecash" -> address.removePrefix("${asset.coin}:")
                else -> address
            }
        if (result["address"]?.stringValue()?.let(::canonical) != canonical(asset.address))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val atomic =
            if (id == NetworkProvider.FREEDOM) {
                if (result["crypto"]?.stringValue() != networks[asset.coin])
                    throw ProviderFailure(Failure.WRONG_CHAIN)
                val received =
                    Amount.atomic(
                        decimalValue(result.getValue("received"), asset.decimals),
                        asset.decimals,
                    )
                val sent =
                    Amount.atomic(
                        decimalValue(result.getValue("sent"), asset.decimals),
                        asset.decimals,
                    )
                if (Amount.compare(received, sent) < 0)
                    throw ProviderFailure(Failure.INVALID_RESPONSE)
                Amount.subtract(received, sent)
            } else {
                // Include unconfirmed incoming/outgoing transfers without negative intermediate
                // balances.
                val chain =
                    result["chain_stats"] as? JsonObject
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val mempool =
                    result["mempool_stats"] as? JsonObject
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val received =
                    Amount.add(integer(chain["funded_txo_sum"]), integer(mempool["funded_txo_sum"]))
                val spent =
                    Amount.add(integer(chain["spent_txo_sum"]), integer(mempool["spent_txo_sum"]))
                if (Amount.compare(received, spent) < 0)
                    throw ProviderFailure(Failure.INVALID_RESPONSE)
                Amount.subtract(received, spent)
            }
        val transfers = if (asset.coin == "ecash") ecashTransfers(asset, result) else emptyList()
        return AddressActivity(Amount.display(atomic, asset.decimals), transfers)
    }

    private fun ecashTransfers(asset: Asset, result: JsonObject): List<Transfer> {
        val rows = result["transactions"] ?: return emptyList()
        if (rows !is JsonArray) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return rows
            .map { row ->
                val transaction =
                    row as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val hash =
                    transaction["hash"]?.stringValue()?.takeIf {
                        Regex("[a-fA-F0-9]{64}").matches(it)
                    } ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val amount =
                    transaction["amount"]?.stringValue()
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val absolute = amount.removePrefix("-")
                val value = decimalValue(JsonPrimitive(absolute), asset.decimals)
                val confirmed =
                    (transaction["block"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
                Transfer(
                    "${asset.id}:$hash",
                    asset.id,
                    value,
                    hash,
                    incoming = !amount.startsWith('-'),
                    status = if (confirmed) TransferStatus.CONFIRMED else TransferStatus.SUBMITTED,
                )
            }
            .distinctBy { it.id }
    }

    /** Caller supplies a locally computed txid; a mismatched acknowledgement is never success. */
    suspend fun broadcast(coin: String, raw: String, expectedTxId: String): String {
        requireInput(
            Regex("(?:[0-9a-fA-F]{2})+").matches(raw) && raw.length <= 800000,
            InputError.INVALID_QUOTE,
        )
        requireInput(Regex("[0-9a-f]{64}").matches(expectedTxId), InputError.INVALID_QUOTE)
        val returned =
            if (id == NetworkProvider.FREEDOM) {
                // POST avoids putting a signed transaction into URL logs.
                val body = buildJsonObject { put("txs", JsonArray(listOf(JsonPrimitive(raw)))) }
                val response =
                    Json.parseToJsonElement(
                        transport.explorer(url(coin, "broadcast"), body.toString(), true)
                    )
                (response as? JsonArray)?.singleOrNull()?.jsonObject?.get("hash")?.stringValue()
            } else transport.explorer(url(coin, "tx"), raw).trim()
        if (returned != expectedTxId) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return expectedTxId
    }

    suspend fun estimate(asset: Asset, destination: String, amount: String): TransferQuote {
        requireInput(
            asset.contract.isEmpty() &&
                asset.coin in UtxoNetworks.supported &&
                asset.decimals == CoinCatalog.get(asset.coin).decimals,
            InputError.NETWORK_UNSUPPORTED,
        )
        // Validate destination before exposing the sender address to a server.
        UtxoStrategy.script(asset.coin, destination)
        val sender = UtxoStrategy.script(asset.coin, asset.address)
        val rateObject =
            transport.json(
                if (id == NetworkProvider.FREEDOM) url(asset.coin, "mempool", "info")
                else if (id == NetworkProvider.MEMPOOL) url(asset.coin, "v1", "fees", "recommended")
                else url(asset.coin, "fee-estimates")
            )
        val rateValue =
            rateObject[
                    if (id == NetworkProvider.FREEDOM) "recommended_fee"
                    else if (id == NetworkProvider.MEMPOOL) "halfHourFee" else "6"]
                ?.stringValue() ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (!Regex("[0-9]{1,8}(\\.[0-9]{1,8})?").matches(rateValue))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val rate =
            rateValue.substringBefore('.').toLong() +
                if (rateValue.substringAfter('.', "").any { it != '0' }) 1 else 0
        val list =
            Json.parseToJsonElement(
                transport.explorer(url(asset.coin, "address", asset.address, "utxo"))
            ) as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (list.size > 1000) throw ProviderFailure(Failure.INVALID_RESPONSE)
        val inputs = mutableListOf<UtxoInput>()
        val seen = mutableSetOf<Pair<String, Long>>()
        // Fetch at most 16 funding transactions, largest first. Keep the BLE request bounded.
        val rows =
            list
                .map { it.jsonObject }
                .sortedByDescending {
                    if (id == NetworkProvider.FREEDOM)
                        Amount.atomic(
                                decimalValue(it.getValue("amount"), asset.decimals),
                                asset.decimals,
                            )
                            .toLong()
                    else integer(it["value"]).toLong()
                }
        var parentBytes = 0
        var inspectedParents = 0
        for (row in rows) {
            if (inspectedParents++ == 16) break
            val txid =
                row[if (id == NetworkProvider.FREEDOM) "hash" else "txid"]?.stringValue()?.takeIf {
                    Regex("[0-9a-f]{64}").matches(it)
                } ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            val index =
                row[if (id == NetworkProvider.FREEDOM) "index" else "vout"]
                    ?.jsonPrimitive
                    ?.longOrNull
                    ?.takeIf { it in 0..0xffffffffL }
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (!seen.add(txid to index)) throw ProviderFailure(Failure.INVALID_RESPONSE)
            val value =
                if (id == NetworkProvider.FREEDOM)
                    Amount.atomic(
                            decimalValue(row.getValue("amount"), asset.decimals),
                            asset.decimals,
                        )
                        .toLong()
                else integer(row["value"]).toLong()
            val raw =
                transport
                    .explorer(
                        url(
                            asset.coin,
                            "tx",
                            txid,
                            if (id == NetworkProvider.FREEDOM) "raw" else "hex",
                        )
                    )
                    .trim()
            parentBytes += raw.length
            if (parentBytes > 160000) throw ProviderFailure(Failure.INVALID_RESPONSE)
            val input = UtxoInput(txid, index, value, EvmEncoding.run { sender.hex() }, raw)
            try {
                if (asset.coin == "ecash" && UtxoStrategy.tokenFunding(input)) continue
                UtxoStrategy.input(input, sender, asset.coin)
            } catch (_: Exception) {
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            }
            inputs += input
            try {
                return UtxoStrategy.plan(asset, destination, amount, inputs, rate)
            } catch (e: InputException) {
                if (e.reason != InputError.INSUFFICIENT_BALANCE) throw e
            }
            if (inputs.size == 16) break
        }
        throw InputException(InputError.INSUFFICIENT_BALANCE)
    }

    /** Only a real HTTP 404 means absence. A timeout or invalid response must block sending. */
    suspend fun status(coin: String, hash: String): TransferStatus? {
        val raw =
            try {
                transport.explorer(url(coin, "tx", hash), notFound = true)
            } catch (_: ExplorerNotFound) {
                return null
            }
        if (raw.isEmpty()) throw ProviderFailure(Failure.INVALID_RESPONSE)
        val objectValue = Json.parseToJsonElement(raw).jsonObject
        if (id == NetworkProvider.FREEDOM) {
            if (objectValue["crypto"]?.stringValue() != networks[coin])
                throw ProviderFailure(Failure.WRONG_CHAIN)
            val data =
                objectValue["data"]?.jsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (data["hash"]?.stringValue() != hash) throw ProviderFailure(Failure.INVALID_RESPONSE)
            return if (
                data["mined"] != null &&
                    data["mined"] != JsonNull &&
                    data["mined"]?.jsonPrimitive?.longOrNull?.let { it > 0 } == true
            )
                TransferStatus.CONFIRMED
            else TransferStatus.SUBMITTED
        }
        if (objectValue["txid"]?.stringValue() != hash)
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val confirmed =
            objectValue["status"]?.jsonObject?.get("confirmed")?.jsonPrimitive?.booleanOrNull
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        return if (confirmed) TransferStatus.CONFIRMED else TransferStatus.SUBMITTED
    }

    private fun integer(value: JsonElement?): String =
        value?.stringValue()?.takeIf { Regex("[0-9]{1,30}").matches(it) }
            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
}
