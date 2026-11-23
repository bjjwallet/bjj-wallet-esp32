package pub.airlock.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.*
import pub.airlock.domain.*

fun configuredClient() = HttpClient {
    expectSuccess = true
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
    }
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
}

fun validateApiUrl(value: String): String {
    val clean = value.trim().trimEnd('/')
    val url =
        try {
            Url(clean)
        } catch (_: IllegalArgumentException) {
            throw InputException(InputError.INVALID_SERVER)
        }
    requireInput(
        url.protocol == URLProtocol.HTTPS && url.host.isNotBlank(),
        InputError.HTTPS_REQUIRED,
    )
    requireInput(
        url.user == null &&
            url.password == null &&
            url.parameters.isEmpty() &&
            url.fragment.isEmpty(),
        InputError.INVALID_SERVER,
    )
    return clean
}

/** Contracts copied from Cordova ChainService. No automatic retries for broadcasts. */
class AirLockApi(private val client: HttpClient, private val server: () -> String) {
    private fun HttpRequestBuilder.endpoint(vararg segments: String) {
        url {
            takeFrom(validateApiUrl(server()))
            appendPathSegments(*segments, encodeSlash = true)
        }
    }

    suspend fun supported(): JsonObject = client.get { endpoint("config", "tokens") }.body()

    suspend fun prices(symbols: List<String>): JsonObject =
        client
            .get {
                endpoint("prices")
                parameter("coins", symbols.joinToString(","))
            }
            .body()

    suspend fun balance(asset: Asset): JsonObject =
        client
            .get {
                endpoint("balance", asset.coin, asset.address)
                if (asset.contract.isNotEmpty()) parameter("contract", asset.contract)
            }
            .body()

    suspend fun history(asset: Asset, limit: Int = 10): JsonElement =
        client
            .get {
                require(limit in 1..100)
                endpoint("transaction", asset.coin, asset.address)
                parameter("limit", limit)
                if (asset.contract.isNotEmpty()) parameter("contract", asset.contract)
            }
            .body()

    suspend fun fee(coin: String): JsonElement = client.get { endpoint("fee", coin) }.body()

    suspend fun nonce(asset: Asset): JsonElement =
        client.get { endpoint("nonce", asset.coin, asset.address) }.body()

    suspend fun seqno(asset: Asset): JsonElement =
        client.get { endpoint("seqno", asset.coin, asset.address) }.body()

    suspend fun block(asset: Asset): JsonElement =
        client.get { endpoint("block", asset.coin, asset.address) }.body()

    suspend fun utxos(asset: Asset, totalAtomic: String): JsonElement =
        client
            .get {
                require(totalAtomic.isNotEmpty() && totalAtomic.all { it in '0'..'9' })
                endpoint("utxo", asset.coin, asset.address)
                parameter("total_value", totalAtomic)
            }
            .body()

    suspend fun accountResource(asset: Asset): JsonElement =
        client
            .get {
                endpoint("accountResource", asset.coin, asset.address)
                if (asset.contract.isNotEmpty()) parameter("contract", asset.contract)
            }
            .body()

    suspend fun estimateGas(asset: Asset): JsonElement =
        client
            .get {
                endpoint("estimateGas", asset.coin, asset.address)
                if (asset.contract.isNotEmpty()) parameter("contract", asset.contract)
            }
            .body()

    suspend fun broadcast(coin: String, signedTransaction: JsonElement): String {
        val result: JsonObject =
            client
                .post {
                    endpoint("broadcast", coin)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("tx_hex", signedTransaction) })
                }
                .body()
        return result["txid"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: error("Server returned no transaction ID")
    }

    suspend fun version(): JsonObject = client.get { endpoint("version") }.body()
}
