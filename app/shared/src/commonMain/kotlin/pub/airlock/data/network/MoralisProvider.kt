package pub.airlock.data.network

import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.*
import pub.airlock.domain.*

internal class MoralisProvider(
    private val transport: ProviderTransport,
    private val apiKey: String,
) : WalletProvider {
    override val id = NetworkProvider.MORALIS

    override fun supports(coin: String, capability: Capability) =
        when (capability) {
            Capability.BALANCE -> coin in ProviderCoverage.balances(id)
            Capability.HISTORY -> coin in ProviderCoverage.moralisHistory
            else -> false
        }

    override suspend fun balance(asset: Asset): String = balances(listOf(asset)).getValue(asset.id)

    suspend fun balances(assets: List<Asset>): Map<String, String> {
        val first = assets.first()
        if (
            assets.any {
                it.coin != first.coin ||
                    !sameAddress(first, it.address, first.address) ||
                    !ProviderCoverage.canRead(id, it)
            }
        )
            throw ProviderFailure(Failure.UNSUPPORTED)
        if (first.coin == "solana") return solanaBalances(assets)

        val pending = assets.associateBy { it.id }.toMutableMap()
        val balances = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response = wallet(first, "tokens", cursor)
            for (value in response.rows("result")) {
                val row = value.objectValue()
                verifyChain(first.coin, row)
                val native =
                    (row["nativeToken"] as? JsonPrimitive)?.booleanOrNull
                        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                val matches =
                    pending.values.filter { asset ->
                        if (asset.contract.isEmpty()) native
                        else
                            !native && sameAddress(asset, row.field("tokenAddress"), asset.contract)
                    }
                for (asset in matches) {
                    verifyDecimals(row, "decimals", asset)
                    balances[asset.id] = Amount.display(row.atomic("balanceRaw"), asset.decimals)
                    pending.remove(asset.id)
                }
            }
            cursor = response.field("cursor")?.takeIf { it.isNotEmpty() }
            if (pending.isEmpty() || cursor == null) break
            if (!seen.add(cursor) || seen.size >= 20)
                throw ProviderFailure(Failure.INVALID_RESPONSE)
        } while (true)
        return balances + pending.keys.associateWith { "0" }
    }

    private suspend fun solanaBalances(assets: List<Asset>): Map<String, String> {
        val address = publicAddress(assets.first())
        val response =
            transport.json(
                "https://solana-gateway.moralis.io/account/mainnet/$address/portfolio",
                apiKey = apiKey,
            )
        val tokens = response.rows("tokens").map { it.objectValue() }
        return assets.associate { asset ->
            val atomic =
                if (asset.contract.isEmpty())
                    response.requiredObject("nativeBalance").atomic("lamports")
                else
                    tokens
                        .filter { it.field("mint") == asset.contract }
                        .fold("0") { total, row ->
                            verifyDecimals(row, "decimals", asset)
                            Amount.add(total, row.atomic("amountRaw"))
                        }
            asset.id to Amount.display(atomic, asset.decimals)
        }
    }

    suspend fun history(asset: Asset, cursor: String?): TransactionPage {
        if (!ProviderCoverage.canReadHistory(id, asset)) throw ProviderFailure(Failure.UNSUPPORTED)
        val response = wallet(asset, "history", cursor)
        val next = response.field("cursor")?.takeIf { it.isNotEmpty() }
        if (next != null && next == cursor) throw ProviderFailure(Failure.INVALID_RESPONSE)
        val transfers =
            response.rows("result").flatMap { item ->
                val row = item.objectValue()
                verifyChain(asset.coin, row)
                transfers(asset, row.requiredObject("transaction"))
            }
        return TransactionPage(transfers.distinctBy { it.id }, next)
    }

    private fun transfers(asset: Asset, transaction: JsonObject): List<Transfer> {
        val hash =
            transaction.field("hash")?.takeIf { Regex("(?:0x)?[0-9a-fA-F]{64}").matches(it) }
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        val status =
            when (transaction.field("status")?.lowercase()) {
                "failed",
                "failure",
                "reverted",
                "0",
                "0x0" -> TransferStatus.FAILED
                "pending",
                "unconfirmed",
                "submitted" -> TransferStatus.SUBMITTED
                else ->
                    if (
                        (transaction.field("confirmations")?.toLongOrNull() ?: 0) > 0 ||
                            transaction.field("status")?.lowercase() in
                                setOf("success", "confirmed", "1", "0x1")
                    )
                        TransferStatus.CONFIRMED
                    else TransferStatus.SUBMITTED
            }
        val native = asset.contract.isEmpty()
        val collection = transaction[if (native) "nativeTransfers" else "tokenTransfers"]
        if (collection == null || collection == JsonNull) return emptyList()
        return collection.objectValue().rows("items").mapIndexedNotNull { index, item ->
            val row = item.objectValue()
            if (native && row.field("reason")?.contains("fee", ignoreCase = true) == true)
                return@mapIndexedNotNull null
            if (!native) {
                if (!sameAddress(asset, row.field("tokenAddress"), asset.contract))
                    return@mapIndexedNotNull null
                verifyDecimals(row, "tokenDecimals", asset)
            }
            val from = row.field("fromAddress")
            val to = row.field("toAddress")
            val fromWallet = sameAddress(asset, from, asset.address)
            val toWallet = sameAddress(asset, to, asset.address)
            if (fromWallet == toWallet) return@mapIndexedNotNull null
            val amount =
                when {
                    !native -> Amount.display(row.atomic("value"), asset.decimals)
                    asset.coin == "bitcoin" ->
                        decimalValue(
                            row["amount"] ?: throw ProviderFailure(Failure.INVALID_RESPONSE),
                            asset.decimals,
                        )
                    else -> Amount.display(row.atomic("amount"), asset.decimals)
                }
            if (amount == "0") return@mapIndexedNotNull null
            val destination = (if (toWallet) from else to).orEmpty()
            Transfer(
                "${hash.lowercase()}:${asset.id}:$index",
                asset.id,
                amount,
                destination,
                toWallet,
                status,
            )
        }
    }

    private suspend fun wallet(asset: Asset, endpoint: String, cursor: String?): JsonObject {
        val chain = chain(asset.coin)
        val address = publicAddress(asset)
        val response =
            transport.json(
                "${id.serviceUrl("")}/v1/wallets/$address/$endpoint",
                query =
                    buildList {
                        add("chains" to chain)
                        if (cursor != null) add("cursor" to cursor)
                    },
                apiKey = apiKey,
            )
        if (!sameAddress(asset, response.field("address"), asset.address))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val meta = response.requiredObject("meta")
        if (meta.hasItems("unsupportedChains")) throw ProviderFailure(Failure.UNSUPPORTED)
        if (meta.hasItems("failedChains")) throw ProviderFailure(Failure.UNAVAILABLE)
        return response
    }

    private fun chain(coin: String) =
        if (coin == "bitcoin") "bitcoin"
        else
            EvmNetworks.chainIds[coin]?.let { "0x" + it.toString(16) }
                ?: throw ProviderFailure(Failure.UNSUPPORTED)

    private fun publicAddress(asset: Asset): String {
        val pattern =
            when (asset.coin) {
                "bitcoin" -> "(?:[13][1-9A-HJ-NP-Za-km-z]{25,34}|(?i:bc1[0-9a-z]{11,87}))"
                "solana" -> "[1-9A-HJ-NP-Za-km-z]{32,44}"
                else -> "0x[0-9a-fA-F]{40}"
            }
        if (!Regex(pattern).matches(asset.address)) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return asset.address.encodeURLPathPart()
    }

    private fun verifyChain(coin: String, row: JsonObject) {
        val actual = row.field("chainId")
        if (actual != chain(coin) && !(coin == "bitcoin" && actual == "bitcoin-mainnet"))
            throw ProviderFailure(Failure.WRONG_CHAIN)
    }

    private fun verifyDecimals(row: JsonObject, key: String, asset: Asset) {
        if (row.field(key)?.toIntOrNull() != asset.decimals)
            throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    private fun sameAddress(asset: Asset, first: String?, second: String) =
        second.equals(
            first,
            ignoreCase =
                asset.coin in EvmNetworks.chainIds ||
                    (asset.coin == "bitcoin" && second.startsWith("bc1", ignoreCase = true)),
        )
}

private fun JsonElement.objectValue() =
    this as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

private fun JsonObject.requiredObject(key: String) =
    this[key]?.objectValue() ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

private fun JsonObject.rows(key: String) =
    this[key] as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

private fun JsonObject.hasItems(key: String) =
    this[key]
        ?.takeUnless { it == JsonNull }
        ?.let { (it as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)).isNotEmpty() }
        ?: false

private fun JsonObject.field(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.atomic(key: String) =
    field(key)?.takeIf { it.length in 1..96 && it.all { c -> c in '0'..'9' } }
        ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
