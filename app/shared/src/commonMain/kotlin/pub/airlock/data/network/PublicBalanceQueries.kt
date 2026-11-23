package pub.airlock.data.network

import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Base58Check
import io.ktor.http.*
import kotlinx.serialization.json.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.data.validCardanoBaseAddress
import pub.airlock.data.validStellarAccount
import pub.airlock.domain.*

/** Chain-specific public queries shared by providers; no signing or secret material. */
internal object PublicBalanceQueries {
    suspend fun stellar(transport: ProviderTransport, asset: Asset): String {
        requireInput(
            asset.coin == "stellar" && validStellarAccount(asset.address),
            InputError.INVALID_DESTINATION,
        )
        val token = Stablecoins.definition(asset)
        requireInput(asset.contract.isEmpty() || token != null, InputError.NETWORK_UNSUPPORTED)
        val raw =
            try {
                transport.explorer(
                    NetworkProvider.STELLAR_HORIZON.serviceUrl("") + "/accounts/" + asset.address,
                    notFound = true,
                )
            } catch (_: ExplorerNotFound) {
                return "0"
            }
        val account =
            Json.parseToJsonElement(raw) as? JsonObject
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (account["account_id"] != JsonPrimitive(asset.address))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val rows =
            account["balances"] as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        val matches =
            rows
                .map { it as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE) }
                .filter {
                    if (token == null) it["asset_type"] == JsonPrimitive("native")
                    else
                        it["asset_code"] == JsonPrimitive(token.contract.substringBefore(':')) &&
                            it["asset_issuer"] == JsonPrimitive(token.contract.substringAfter(':'))
                }
        if (matches.isEmpty() && token != null) return "0"
        val value =
            matches.singleOrNull()?.get("balance")?.stringValue()
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        return try {
            Amount.display(Amount.atomic(value, asset.decimals), asset.decimals)
        } catch (_: Exception) {
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        }
    }

    suspend fun cardano(transport: ProviderTransport, asset: Asset): String {
        val token = Stablecoins.definition(asset)
        requireInput(
            CardanoNetwork.isNative(asset) || token?.coin == "cardano",
            InputError.NETWORK_UNSUPPORTED,
        )
        requireInput(validCardanoBaseAddress(asset.address), InputError.INVALID_DESTINATION)
        val body = buildJsonObject {
            put("_addresses", JsonArray(listOf(JsonPrimitive(asset.address))))
        }
        val endpoint = NetworkProvider.KOIOS.serviceUrl("")
        val policy = token?.contract?.take(56)
        val assetName = token?.contract?.drop(56)
        val query =
            if (token == null) "/address_info?select=address,balance"
            else
                "/address_assets?select=address,policy_id,asset_name,quantity&policy_id=eq.$policy&asset_name=eq.$assetName"
        val raw = transport.explorer(endpoint + query, body.toString(), jsonBody = true)
        val rows =
            runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (token != null && rows.isEmpty()) return "0"
        val row =
            rows.singleOrNull() as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (row["address"] != JsonPrimitive(asset.address))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (token == null) return atomicBalance(row["balance"], asset.decimals)
        if (
            row["policy_id"] != JsonPrimitive(policy) ||
                row["asset_name"] != JsonPrimitive(assetName)
        )
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        return atomicBalance(row["quantity"], asset.decimals)
    }

    suspend fun solana(transport: ProviderTransport, endpoint: String, asset: Asset): String {
        requireInput(
            runCatching { Base58.decode(asset.address).size == 32 }.getOrDefault(false),
            InputError.INVALID_DESTINATION,
        )
        val options = buildJsonObject {
            put("commitment", "finalized")
            put("encoding", "jsonParsed")
        }
        if (asset.contract.isEmpty()) {
            val result =
                transport
                    .rpc(
                        endpoint,
                        "getBalance",
                        JsonArray(listOf(JsonPrimitive(asset.address), options)),
                    )
                    .jsonObject
            return atomicBalance(result["value"], asset.decimals)
        }
        val result =
            transport
                .rpc(
                    endpoint,
                    "getTokenAccountsByOwner",
                    JsonArray(
                        listOf(
                            JsonPrimitive(asset.address),
                            buildJsonObject { put("mint", asset.contract) },
                            options,
                        )
                    ),
                )
                .jsonObject
        val accounts =
            result["value"] as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        var total = "0"
        val seen = mutableSetOf<String>()
        for (entry in accounts) {
            val account = entry.jsonObject
            if (!seen.add(account.getValue("pubkey").stringValue()))
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            val info =
                account
                    .getValue("account")
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("parsed")
                    .jsonObject
                    .getValue("info")
                    .jsonObject
            val token = info.getValue("tokenAmount").jsonObject
            if (
                info["owner"]?.stringValue() != asset.address ||
                    info["mint"]?.stringValue() != asset.contract ||
                    token["decimals"]?.jsonPrimitive?.intOrNull != asset.decimals
            )
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            total = Amount.add(total, integer(token["amount"]))
        }
        return Amount.display(total, asset.decimals)
    }

    suspend fun sui(transport: ProviderTransport, endpoint: String, asset: Asset): String {
        requireInput(
            Regex("0x[0-9a-fA-F]{64}").matches(asset.address),
            InputError.INVALID_DESTINATION,
        )
        val type = asset.contract.ifEmpty { "0x2::sui::SUI" }
        val result =
            transport.rpc(endpoint, "suix_getBalance", params(asset.address, type)).jsonObject
        fun canonical(value: String): String =
            value.split("::").let {
                require(it.size == 3)
                "0x" +
                    it[0].removePrefix("0x").trimStart('0').ifEmpty { "0" }.lowercase() +
                    "::" +
                    it.drop(1).joinToString("::")
            }
        if (canonical(result.getValue("coinType").stringValue()) != canonical(type))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        return atomicBalance(result["totalBalance"], asset.decimals)
    }

    suspend fun tron(transport: ProviderTransport, endpoint: String, asset: Asset): String {
        val address = tronAddress(asset.address)
        if (asset.contract.isEmpty()) {
            val result =
                transport.json(
                    "$endpoint/wallet/getaccount",
                    buildJsonObject {
                        put("address", asset.address)
                        put("visible", true)
                    },
                )
            if (result.isEmpty()) return "0" // An address that has not been activated.
            val returned =
                result["address"]?.stringValue() ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            val matches =
                if (returned.startsWith("T")) returned == asset.address
                else returned.lowercase() == "41" + address.hex()
            if (!matches) throw ProviderFailure(Failure.INVALID_RESPONSE)
            return atomicBalance(result["balance"] ?: JsonPrimitive("0"), asset.decimals)
        }
        tronAddress(asset.contract)
        val result =
            transport.json(
                "$endpoint/wallet/triggerconstantcontract",
                buildJsonObject {
                    put("owner_address", asset.address)
                    put("contract_address", asset.contract)
                    put("function_selector", "balanceOf(address)")
                    put("parameter", address.hex().padStart(64, '0'))
                    put("visible", true)
                },
            )
        if ((result["result"] as? JsonObject)?.get("result") != JsonPrimitive(true))
            throw ProviderFailure(Failure.REJECTED)
        val value =
            (result["constant_result"] as? JsonArray)?.singleOrNull()?.stringValue()
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (value.length != 64) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return Amount.display(EvmEncoding.decimal("0x$value"), asset.decimals)
    }

    suspend fun utxo(transport: ProviderTransport, endpoint: String, asset: Asset): String {
        requireInput(asset.contract.isEmpty(), InputError.NETWORK_UNSUPPORTED)
        val url =
            URLBuilder(endpoint)
                .apply {
                    appendPathSegments("api", "v2", "address", asset.address, encodeSlash = true)
                }
                .buildString()
        val result = transport.json(url, query = listOf("details" to "basic"))
        fun address(value: String) =
            if (asset.coin == "bitcoincash") value.removePrefix("bitcoincash:") else value
        if (result["address"]?.stringValue()?.let(::address) != address(asset.address))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        return atomicBalance(result["balance"], asset.decimals)
    }

    private fun tronAddress(address: String): ByteArray {
        try {
            val (prefix, bytes) = Base58Check.decode(address)
            require(prefix == 0x41.toByte() && bytes.size == 20)
            return bytes
        } catch (_: Exception) {
            throw InputException(InputError.INVALID_DESTINATION)
        }
    }

    private fun integer(element: JsonElement?): String {
        val value = element?.stringValue() ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (!Regex("[0-9]{1,78}").matches(value)) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return value
    }

    private fun atomicBalance(element: JsonElement?, decimals: Int) =
        Amount.display(integer(element), decimals)
}
