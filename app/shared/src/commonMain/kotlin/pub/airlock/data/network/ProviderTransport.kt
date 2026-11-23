package pub.airlock.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class ExplorerNotFound : Exception()

internal enum class Failure {
    UNAVAILABLE,
    RATE_LIMITED,
    AUTH,
    UNSUPPORTED,
    INVALID_RESPONSE,
    WRONG_CHAIN,
    REJECTED,
}

/** No URL, response body or nested exception: provider URLs contain credentials. */
internal class ProviderFailure(val kind: Failure) : Exception(kind.name)

internal class ProviderTransport(private val client: HttpClient) {
    suspend fun submitForm(url: String, key: String, value: String): JsonObject {
        try {
            val response =
                client.post(url) {
                    expectSuccess = false
                    contentType(ContentType.Application.FormUrlEncoded)
                    setBody(listOf(key to value).formUrlEncode())
                }
            if (response.status.value !in 200..299) throw ProviderFailure(Failure.REJECTED)
            return Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailure) {
            throw e
        } catch (_: Exception) {
            throw ProviderFailure(Failure.UNAVAILABLE)
        }
    }

    suspend fun json(
        url: String,
        body: JsonObject? = null,
        query: List<Pair<String, String>> = emptyList(),
        apiKey: String? = null,
    ): JsonObject {
        try {
            val response =
                client.request(url) {
                    expectSuccess = false
                    method = if (body == null) HttpMethod.Get else HttpMethod.Post
                    query.forEach { (key, value) -> parameter(key, value) }
                    if (apiKey != null) header("X-API-Key", apiKey)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            val code = response.status.value
            if (code !in 200..299)
                throw ProviderFailure(
                    when (code) {
                        401,
                        403 -> Failure.AUTH
                        404,
                        405 -> Failure.UNSUPPORTED
                        429 -> if (apiKey == null) Failure.UNAVAILABLE else Failure.RATE_LIMITED
                        408 -> Failure.UNAVAILABLE
                        in 500..599 -> Failure.UNAVAILABLE
                        else -> Failure.INVALID_RESPONSE // Redirects are not followed.
                    }
                )
            return Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailure) {
            throw e
        } catch (_: Exception) {
            throw ProviderFailure(Failure.UNAVAILABLE)
        }
    }

    /**
     * Raw explorer responses include arrays and plain-text txids. 404 is never a balance of zero.
     */
    suspend fun explorer(
        url: String,
        body: String? = null,
        jsonBody: Boolean = false,
        notFound: Boolean = false,
    ): String {
        try {
            val response =
                client.request(url) {
                    expectSuccess = false
                    method = if (body == null) HttpMethod.Get else HttpMethod.Post
                    if (body != null) {
                        contentType(
                            if (jsonBody) ContentType.Application.Json else ContentType.Text.Plain
                        )
                        setBody(body)
                    }
                }
            if (notFound && response.status.value == 404) throw ExplorerNotFound()
            if (response.status.value !in 200..299)
                throw ProviderFailure(
                    when {
                        body != null && response.status.value in setOf(400, 422) -> Failure.REJECTED
                        response.status.value == 404 -> Failure.UNSUPPORTED
                        else -> Failure.UNAVAILABLE
                    }
                )
            return response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExplorerNotFound) {
            throw e
        } catch (e: ProviderFailure) {
            throw e
        } catch (_: Exception) {
            throw ProviderFailure(Failure.UNAVAILABLE)
        }
    }

    suspend fun rpc(
        url: String,
        method: String,
        params: JsonElement = JsonArray(emptyList()),
    ): JsonElement {
        val response =
            json(
                url,
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", method)
                    put("params", params)
                },
            )
        if (response["jsonrpc"] != JsonPrimitive("2.0") || response["id"] != JsonPrimitive(1))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val error = response["error"]
        if (error != null && error != JsonNull) {
            val objectError =
                error as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            val code = (objectError["code"] as? JsonPrimitive)?.intOrNull
            val message = (objectError["message"] as? JsonPrimitive)?.content.orEmpty().lowercase()
            throw ProviderFailure(
                when {
                    code == -32601 -> Failure.UNSUPPORTED
                    code in listOf(429, -32005, -32016) ||
                        listOf(
                                "rate limit",
                                "too many",
                                "capacity",
                                "timeout",
                                "temporarily",
                                "compute units",
                            )
                            .any { it in message } -> Failure.UNAVAILABLE
                    listOf("unauthorized", "api key", "authentication").any { it in message } ->
                        Failure.AUTH
                    else -> Failure.REJECTED
                }
            )
        }
        return response["result"] ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    suspend fun submitCbor(url: String, body: ByteArray): String {
        try {
            val response =
                client.post(url) {
                    expectSuccess = false
                    contentType(ContentType("application", "cbor"))
                    setBody(body)
                }
            if (response.status.value !in 200..299) throw ProviderFailure(Failure.REJECTED)
            return response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderFailure) {
            throw e
        } catch (_: Exception) {
            throw ProviderFailure(Failure.UNAVAILABLE)
        }
    }
}

internal fun JsonElement.stringValue(): String =
    (this as? JsonPrimitive)?.contentOrNull ?: throw ProviderFailure(Failure.INVALID_RESPONSE)

internal fun decimalValue(element: JsonElement, decimals: Int): String {
    val value = element.stringValue()
    if (
        !Regex("[0-9]+(\\.[0-9]+)?").matches(value) ||
            value.length > 128 ||
            value.substringAfter('.', "").length > decimals
    )
        throw ProviderFailure(Failure.INVALID_RESPONSE)
    return pub.airlock.domain.Amount.display(
        pub.airlock.domain.Amount.atomic(value, decimals),
        decimals,
    )
}
