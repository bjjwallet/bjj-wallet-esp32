package pub.airlock.data.network

import kotlinx.serialization.json.*
import pub.airlock.domain.Amount
import pub.airlock.domain.Asset
import pub.airlock.domain.Stablecoins

/**
 * Reads issued currencies from a validated XRP Ledger snapshot, matching both issuer and currency.
 */
internal class XrplTrustlines(private val transport: ProviderTransport) {
    suspend fun balance(asset: Asset): String =
        line(asset)?.first?.let {
            val balance = it.getValue("balance").stringValue()
            val value = decimalValue(JsonPrimitive(balance.removePrefix("-")), asset.decimals)
            if (balance.startsWith("-")) "0" else value
        } ?: "0"

    suspend fun canReceive(asset: Asset): Boolean {
        val (line, ledger) = line(asset) ?: return false
        if (
            listOf("freeze", "freeze_peer", "deep_freeze", "deep_freeze_peer").any {
                line[it] == JsonPrimitive(true)
            }
        )
            return false
        val issuer = asset.contract.substringAfter('.')
        val info =
            call(
                "account_info",
                buildJsonObject {
                    put("account", issuer)
                    put("ledger_hash", ledger)
                },
            )
        val account =
            info["account_data"]?.jsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (
            info["validated"] != JsonPrimitive(true) ||
                info["ledger_hash"] != JsonPrimitive(ledger) ||
                account["Account"]?.stringValue() != issuer
        )
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val flags =
            account.getValue("Flags").stringValue().toLongOrNull()
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (
            flags and 0x00400000 != 0L ||
                flags and 0x00040000 != 0L && line["peer_authorized"] != JsonPrimitive(true)
        )
            return false
        val limit = atomic(line.getValue("limit"), asset.decimals)
        val balance = line.getValue("balance").stringValue()
        return limit != "0" &&
            (balance.startsWith("-") ||
                Amount.compare(limit, atomic(JsonPrimitive(balance), asset.decimals)) > 0)
    }

    private suspend fun line(asset: Asset): Pair<JsonObject, String>? {
        if (asset.coin != "ripple" || Stablecoins.definition(asset) == null)
            throw ProviderFailure(Failure.UNSUPPORTED)
        val (currency, issuer) = asset.contract.split('.', limit = 2)
        var marker: JsonElement? = null
        var ledger: String? = null
        val seen = mutableSetOf<JsonElement>()
        repeat(20) {
            val response =
                call(
                    "account_lines",
                    buildJsonObject {
                        put("account", asset.address)
                        put("peer", issuer)
                        val hash = ledger
                        if (hash == null) put("ledger_index", "validated")
                        else put("ledger_hash", hash)
                        marker?.let { put("marker", it) }
                    },
                )
            if (response["error"] == JsonPrimitive("actNotFound") && ledger == null) return null
            if (
                response["validated"] != JsonPrimitive(true) ||
                    response["account"]?.stringValue() != asset.address
            )
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            val hash =
                response["ledger_hash"]?.stringValue()
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (!Regex("[0-9a-fA-F]{64}").matches(hash) || ledger?.let { it != hash } == true)
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            ledger = hash
            val lines =
                response["lines"] as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            val matches =
                lines
                    .map { it.jsonObject }
                    .filter {
                        it["account"]?.stringValue() == issuer &&
                            it["currency"]?.stringValue() == currency
                    }
            if (matches.size > 1) throw ProviderFailure(Failure.INVALID_RESPONSE)
            matches.singleOrNull()?.let {
                return it to hash
            }
            marker = response["marker"]
            if (marker == null || marker == JsonNull) return null
            if (!seen.add(marker)) throw ProviderFailure(Failure.INVALID_RESPONSE)
        }
        throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject =
        transport
            .json(
                "https://xrplcluster.com/",
                buildJsonObject {
                    put("method", method)
                    put("params", JsonArray(listOf(params)))
                },
            )["result"]
            as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

    private fun atomic(value: JsonElement, decimals: Int) =
        Amount.atomic(decimalValue(value, decimals), decimals)
}
