package pub.airlock.data.network

import fr.acinq.bitcoin.Base58
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*

internal object SuiPayment {
    const val endpoint = "https://graphql.mainnet.sui.io/graphql"

    private fun address(value: String): ByteArray {
        requireInput(Regex("0x[0-9a-f]{64}").matches(value), InputError.INVALID_DESTINATION)
        return EvmEncoding.bytes(value)
    }

    private fun JsonObject.n(key: String) =
        get(key)?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw InputException(InputError.INVALID_QUOTE)

    private class Bcs {
        val bytes = mutableListOf<Byte>()

        fun b(n: Int) {
            bytes += n.toByte()
        }

        fun raw(value: ByteArray) {
            bytes.addAll(value.toList())
        }

        fun le(n: Long, size: Int = 8) {
            repeat(size) { b((n ushr (it * 8)).toInt()) }
        }

        fun text(s: String) {
            val raw = s.encodeToByteArray()
            requireInput(raw.size < 128, InputError.INVALID_QUOTE)
            b(raw.size)
            raw(raw)
        }

        fun input(index: Int) {
            b(1)
            le(index.toLong(), 2)
        }

        fun ref(r: JsonObject) {
            raw(address(r.getValue("id").jsonPrimitive.content))
            le(r.n("version"))
            b(32)
            raw(EvmEncoding.bytes("0x" + r.getValue("digest").jsonPrimitive.content))
        }

        fun result() = bytes.toByteArray()
    }

    private fun refs(plan: JsonObject, key: String): List<JsonObject> {
        val rows = plan[key] as? JsonArray ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(rows.size <= 16, InputError.INVALID_QUOTE)
        return rows.map { value ->
            val r = value.jsonObject
            requireInput(
                r.keys == setOf("id", "version", "digest", "balance") &&
                    r.n("version") > 0 &&
                    r.n("balance") > 0 &&
                    Regex("[0-9a-f]{64}").matches(r.getValue("digest").jsonPrimitive.content),
                InputError.INVALID_QUOTE,
            )
            address(r.getValue("id").jsonPrimitive.content)
            r
        }
    }

    fun body(quote: TransferQuote): ByteArray {
        val asset = quote.asset
        val plan = quote.ledgerPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            asset.coin == "sui" &&
                LedgerNetworks.supportsPayment(asset) &&
                quote.feeCurrency == CoinCatalog.get("sui") &&
                asset.address != quote.destination &&
                plan.keys == setOf("network", "gas_price", "epoch", "gas", "coins") &&
                plan["network"] == JsonPrimitive("sui-mainnet"),
            InputError.INVALID_QUOTE,
        )
        val sender = address(asset.address)
        val recipient = address(quote.destination)
        val amount =
            Amount.atomic(quote.amount, asset.decimals).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val fee =
            Amount.atomic(quote.fee, 9).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val price = plan.n("gas_price")
        val epoch = plan.n("epoch")
        requireInput(
            amount > 0 && fee in 1..1000000000 && price in 1..10000000 && epoch > 0,
            InputError.INVALID_QUOTE,
        )
        val gas = refs(plan, "gas")
        val coins = refs(plan, "coins")
        val token = Stablecoins.definition(asset)
        requireInput(
            gas.isNotEmpty() &&
                (if (token == null) coins.isEmpty() else coins.isNotEmpty()) &&
                (gas + coins).map { it["id"] }.distinct().size == gas.size + coins.size,
            InputError.INVALID_QUOTE,
        )
        fun total(rows: List<JsonObject>): Long {
            var total = 0L
            for (r in rows) {
                val n = r.n("balance")
                requireInput(n <= Long.MAX_VALUE - total, InputError.INVALID_QUOTE)
                total += n
            }
            return total
        }
        requireInput(
            total(gas) >= fee &&
                (if (token == null) total(gas) - fee >= amount else total(coins) >= amount),
            InputError.INSUFFICIENT_BALANCE,
        )
        return Bcs()
            .apply {
                b(0)
                b(0)
                b(coins.size + 2)
                for (c in coins) {
                    b(1)
                    b(0)
                    ref(c)
                }
                b(0)
                b(8)
                le(amount)
                b(0)
                b(32)
                raw(recipient)
                val merge = coins.size > 1
                b(if (merge) 3 else 2)
                if (merge) {
                    b(3)
                    input(0)
                    b(coins.size - 1)
                    for (i in 1 until coins.size) input(i)
                }
                if (token != null) {
                    b(0)
                    raw(ByteArray(32).apply { this[31] = 2 })
                    text("coin")
                    text("split")
                    b(1)
                    b(7)
                    val type = token.contract.split("::")
                    requireInput(type.size == 3, InputError.INVALID_QUOTE)
                    raw(address(type[0]))
                    text(type[1])
                    text(type[2])
                    b(0)
                    b(2)
                    input(0)
                    input(coins.size)
                } else {
                    b(2)
                    b(0)
                    b(1)
                    input(0)
                }
                b(1)
                b(1)
                b(if (token != null) 2 else 3)
                le(if (merge) 1 else 0, 2)
                if (token == null) le(0, 2)
                input(coins.size + 1)
                raw(sender)
                b(gas.size)
                for (g in gas) ref(g)
                raw(sender)
                le(price)
                le(fee)
                b(1)
                le(epoch)
            }
            .result()
    }

    fun transactionId(signature: String, quote: TransferQuote, verifier: Ed25519Verifier): String {
        requireInput(Regex("[0-9a-f]{192}").matches(signature), InputError.INVALID_QUOTE)
        val bytes = EvmEncoding.bytes("0x$signature")
        val key = bytes.copyOfRange(0, 32)
        val body = body(quote)
        requireInput(
            CardanoBlake2b.hash(byteArrayOf(0) + key, 32)
                .contentEquals(address(quote.asset.address)) &&
                verifier.verify(
                    key,
                    CardanoBlake2b.hash(byteArrayOf(0, 0, 0) + body, 32),
                    bytes.copyOfRange(32, 96),
                ),
            InputError.INVALID_QUOTE,
        )
        return Base58.encode(
            CardanoBlake2b.hash("TransactionData::".encodeToByteArray() + body, 32)
        )
    }

    private suspend fun gql(
        http: ProviderTransport,
        query: String,
        variables: JsonObject = buildJsonObject {},
    ): JsonObject {
        val result =
            http.json(
                endpoint,
                buildJsonObject {
                    put("query", query)
                    put("variables", variables)
                },
            )
        if (result["errors"] != null) throw ProviderFailure(Failure.REJECTED)
        return result["data"] as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    private fun type(asset: Asset) =
        if (asset.contract.isEmpty()) "0x2::sui::SUI"
        else
            Stablecoins.definition(asset)?.contract
                ?: throw InputException(InputError.NETWORK_UNSUPPORTED)

    private fun canonical(type: String) =
        type.replace(Regex("0x[0-9a-fA-F]+")) {
            "0x" + it.value.drop(2).trimStart('0').ifEmpty { "0" }.lowercase()
        }

    suspend fun balance(http: ProviderTransport, asset: Asset): String {
        address(asset.address)
        val data =
            gql(
                http,
                "query(\$owner:SuiAddress!,\$type:String!){address(address:\$owner){balance(coinType:\$type){coinType{repr} totalBalance}}}",
                buildJsonObject {
                    put("owner", asset.address)
                    put("type", type(asset))
                },
            )
        val balance = data.getValue("address").jsonObject["balance"]
        if (balance == JsonNull) return "0"
        requireInput(
            canonical(
                balance!!
                    .jsonObject
                    .getValue("coinType")
                    .jsonObject
                    .getValue("repr")
                    .jsonPrimitive
                    .content
            ) == canonical(type(asset)),
            InputError.INVALID_QUOTE,
        )
        return Amount.display(balance.jsonObject.n("totalBalance").toString(), asset.decimals)
    }

    private suspend fun coins(
        http: ProviderTransport,
        owner: String,
        type: String,
    ): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        var cursor: JsonElement = JsonNull
        repeat(8) {
            val data =
                gql(
                    http,
                    "query(\$owner:SuiAddress!,\$type:String!,\$after:String){address(address:\$owner){address objects(first:50,after:\$after,filter:{type:\$type}){pageInfo{hasNextPage endCursor} nodes{address version digest owner{__typename ... on AddressOwner{address{address}}} contents{type{repr} json}}}}}",
                    buildJsonObject {
                        put("owner", owner)
                        put("type", "0x2::coin::Coin<$type>")
                        put("after", cursor)
                    },
                )
            val address = data.getValue("address").jsonObject
            requireInput(address["address"] == JsonPrimitive(owner), InputError.INVALID_QUOTE)
            val objects = address.getValue("objects").jsonObject
            for (value in objects.getValue("nodes").jsonArray) {
                val r = value.jsonObject
                val contents = r.getValue("contents").jsonObject
                val objectOwner = r.getValue("owner").jsonObject
                requireInput(
                    canonical(
                        contents.getValue("type").jsonObject.getValue("repr").jsonPrimitive.content
                    ) == canonical("0x2::coin::Coin<$type>"),
                    InputError.INVALID_QUOTE,
                )
                requireInput(
                    objectOwner["__typename"] == JsonPrimitive("AddressOwner") &&
                        objectOwner["address"]!!.jsonObject["address"] == JsonPrimitive(owner),
                    InputError.INVALID_QUOTE,
                )
                val digest = Base58.decode(r.getValue("digest").jsonPrimitive.content)
                requireInput(digest.size == 32, InputError.INVALID_QUOTE)
                rows += buildJsonObject {
                    put("id", r.getValue("address"))
                    put("version", r.getValue("version").jsonPrimitive.content)
                    put("digest", digest.hex())
                    put("balance", contents.getValue("json").jsonObject.n("balance").toString())
                }
            }
            val page = objects.getValue("pageInfo").jsonObject
            if (page["hasNextPage"] == JsonPrimitive(false)) return rows
            val next = page.getValue("endCursor")
            requireInput(next != cursor && next != JsonNull, InputError.INVALID_QUOTE)
            cursor = next
        }
        throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    suspend fun estimate(
        http: ProviderTransport,
        asset: Asset,
        recipient: String,
        amount: String,
    ): TransferQuote {
        address(asset.address)
        address(recipient)
        val epoch = gql(http, "{epoch{epochId referenceGasPrice}}").getValue("epoch").jsonObject
        val price = epoch.n("referenceGasPrice")
        val budget = (price * 10000 + 10000000).coerceAtMost(1000000000)
        val gas =
            coins(http, asset.address, "0x2::sui::SUI")
                .sortedByDescending { it.n("balance") }
                .take(16)
        val tokens =
            if (asset.contract.isEmpty()) emptyList()
            else
                coins(http, asset.address, type(asset))
                    .sortedByDescending { it.n("balance") }
                    .take(16)
        val quote =
            TransferQuote(
                asset,
                recipient,
                amount,
                Amount.display(budget.toString(), 9),
                ledgerPlan =
                    buildJsonObject {
                        put("network", "sui-mainnet")
                        put("gas_price", price.toString())
                        put("epoch", (epoch.n("epochId") + 1).toString())
                        put("gas", JsonArray(gas))
                        put("coins", JsonArray(tokens))
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
        val id = transactionId(signature, quote, verifier)
        val bytes = EvmEncoding.bytes("0x$signature")
        val result =
            gql(
                http,
                "mutation(\$tx:String!,\$sigs:[String!]!){executeTransaction(transactionDataBcs:\$tx,signatures:\$sigs){effects{status transaction{digest}}}}",
                buildJsonObject {
                    put("tx", Base64.encode(body(quote)))
                    put(
                        "sigs",
                        JsonArray(
                            listOf(
                                JsonPrimitive(
                                    Base64.encode(
                                        byteArrayOf(0) +
                                            bytes.copyOfRange(32, 96) +
                                            bytes.copyOfRange(0, 32)
                                    )
                                )
                            )
                        ),
                    )
                },
            )
        val effects =
            result.getValue("executeTransaction").jsonObject.getValue("effects").jsonObject
        requireInput(
            effects["status"] == JsonPrimitive("SUCCESS") &&
                effects["transaction"]?.jsonObject?.get("digest") == JsonPrimitive(id),
            InputError.TRANSACTION_REJECTED,
        )
        return TransferStatus.CONFIRMED
    }
}
