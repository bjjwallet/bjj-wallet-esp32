@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pub.airlock.data.monero.LocalMoneroScanner
import pub.airlock.data.monero.MoneroRuntime
import pub.airlock.domain.InputError
import pub.airlock.domain.InputException
import pub.airlock.domain.MoneroWatchWallet

class LocalMoneroScannerTest {
    @Test
    fun continuesALongScanWithoutRequiringAnotherTap() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var requests = 0
        val engine =
            MockEngine.create {
                this.dispatcher = dispatcher
                addHandler {
                    requests++
                    if (requests == 1) respond("", HttpStatusCode.ServiceUnavailable)
                    else respond("{}")
                }
            }
        val client = HttpClient(engine) { install(HttpTimeout) }
        val runtime = TestRuntime().apply { finishAfter = 8 }
        val scanner = LocalMoneroScanner(client, { runtime }, dispatcher) { emptyMap() }
        try {
            assertEquals(42L, scanner.scan(wallet)?.height)
            assertEquals(8, runtime.replies)
            assertEquals(9, requests)
            assertEquals(1, runtime.starts)
            assertEquals(0, runtime.closes)
        } finally {
            scanner.clear()
            client.close()
            engine.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun keepsThePendingBatchWhenTheNodeIsUnavailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var available = false
        val engine =
            MockEngine.create {
                this.dispatcher = dispatcher
                addHandler {
                    if (available) respond("{}") else respond("", HttpStatusCode.ServiceUnavailable)
                }
            }
        val client = HttpClient(engine) { install(HttpTimeout) }
        val runtime = TestRuntime().apply { finishAfter = 1 }
        val scanner = LocalMoneroScanner(client, { runtime }, dispatcher) { emptyMap() }
        try {
            val error = assertFailsWith<InputException> { scanner.scan(wallet) }
            assertEquals(InputError.MONERO_SCAN_PAUSED, error.reason)
            assertEquals(0, runtime.closes)
            assertEquals(0, runtime.replies)
            available = true
            assertEquals(42L, scanner.scan(wallet)?.height)
            assertEquals(1, runtime.starts)
            assertEquals(1, runtime.replies)
        } finally {
            scanner.clear()
            client.close()
            engine.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retriesAnOversizedBlockResponseWithFewerBlocks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val original =
            Base64.decode(
                "AREBAQEBAgEBDAlibG9ja19pZHMKAAxzdGFydF9oZWlnaHQFADUMAAAAAAAFcHJ1bmULAQ=="
            )
        val limited =
            Base64.decode(
                "AREBAQEBAgEBEAlibG9ja19pZHMKAAxzdGFydF9oZWlnaHQFADUMAAAAAAAFcHJ1bmULAQ9tYXhfYmxvY2tfY291bnQFAAEAAAAAAAA="
            )
        var requests = 0
        val engine =
            MockEngine.create {
                this.dispatcher = dispatcher
                addHandler { request ->
                    val bytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
                    when (++requests) {
                        1 -> {
                            assertContentEquals(original, bytes)
                            respond("", headers = headersOf(HttpHeaders.ContentLength, "1048577"))
                        }
                        else -> {
                            assertContentEquals(limited, bytes)
                            respond("block reply")
                        }
                    }
                }
            }
        val client = HttpClient(engine) { install(HttpTimeout) }
        val rpc =
            """{"kind":"rpc","path":"/getblocks.bin","method":"POST","binary":true,"data":"AREBAQEBAgEBDAlibG9ja19pZHMKAAxzdGFydF9oZWlnaHQFADUMAAAAAAAFcHJ1bmULAQ=="}"""
        val runtime = TestRuntime(rpc = rpc).apply { finishAfter = 1 }
        val scanner = LocalMoneroScanner(client, { runtime }, dispatcher) { emptyMap() }
        try {
            assertEquals(42L, scanner.scan(wallet)?.height)
            assertEquals(2, requests)
            assertEquals(1, runtime.starts)
        } finally {
            scanner.clear()
            client.close()
            engine.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun stopsAndClosesTheRuntimeWhenTheScannerStopsResponding() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = MockEngine { error("No daemon request expected") }
        val client = HttpClient(engine)
        val runtime = TestRuntime(stalled = true)
        val scanner = LocalMoneroScanner(client, { runtime }, dispatcher) { emptyMap() }
        try {
            val error = assertFailsWith<InputException> { scanner.scan(wallet) }
            assertEquals(InputError.MONERO_SCAN_TIMEOUT, error.reason)
            assertEquals(1, runtime.closes)
        } finally {
            scanner.clear()
            client.close()
            engine.close()
            Dispatchers.resetMain()
        }
    }

    private class TestRuntime(
        private val stalled: Boolean = false,
        private val rpc: String =
            """{"kind":"rpc","path":"/get_height","method":"GET","binary":false,"data":""}""",
    ) : MoneroRuntime {
        var starts = 0
        var closes = 0
        var replies = 0
        var finishAfter = Int.MAX_VALUE

        override fun load(files: Map<String, ByteArray>) = Unit

        override suspend fun evaluate(script: String): String =
            when {
                script.startsWith("typeof airlockStart") -> "ready"
                script.startsWith("airlockStart(") -> {
                    starts++
                    "started"
                }
                script == "airlockNext()" -> {
                    if (stalled) awaitCancellation()
                    if (replies == finishAfter)
                        """{"kind":"verified","atomic":"0","unlockedAtomic":"0","height":42}"""
                    else {
                        delay(40_000)
                        rpc
                    }
                }
                script.startsWith("airlockReplyDone") -> {
                    replies++
                    ""
                }
                else -> ""
            }

        override fun close() {
            closes++
        }
    }

    private val wallet =
        MoneroWatchWallet(
            "4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge",
            "01" + "00".repeat(31),
            10,
            emptyList(),
        )
}
