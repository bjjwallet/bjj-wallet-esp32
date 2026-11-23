package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import pub.airlock.data.*
import pub.airlock.domain.Asset

class ApiContractTest {
    private val token =
        Asset(
            "1",
            "1",
            "tron",
            "USDT",
            "Tether",
            "TAddress",
            "usdttrc20.svg",
            6,
            contract = "TContract",
        )

    @Test
    fun balanceAndHistoryPreserveContractParameters() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("TContract", request.url.parameters["contract"])
            if ("transaction" in request.url.encodedPath)
                assertEquals("10", request.url.parameters["limit"])
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val api = AirLockApi(client) { "https://example.test/api" }
        api.balance(token)
        api.history(token)
        assertEquals(listOf("/api/balance/tron/TAddress", "/api/transaction/tron/TAddress"), paths)
        client.close()
    }

    @Test
    fun broadcastBodyMatchesCordovaAndIsOnlySentOnce() = runTest {
        var calls = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/broadcast/tron", request.url.encodedPath)
                    assertEquals(
                        "{\"tx_hex\":\"signed-fixture\"}",
                        (request.body as TextContent).text,
                    )
                    respond(
                        "{\"txid\":\"fixture-id\"}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                install(ContentNegotiation) { json() }
            }
        assertEquals(
            "fixture-id",
            AirLockApi(client) { "https://example.test" }
                .broadcast("tron", JsonPrimitive("signed-fixture")),
        )
        assertEquals(1, calls)
        client.close()
    }

    @Test
    fun rejectsUnsafeOrAmbiguousServerAddresses() {
        listOf(
                "http://example.test",
                "https://user:password@example.test",
                "https://example.test?key=x",
                "https://example.test#fragment",
            )
            .forEach { input ->
                assertFailsWith<IllegalArgumentException>(input) { validateApiUrl(input) }
            }
        assertEquals("https://example.test/api", validateApiUrl(" https://example.test/api/ "))
    }
}
