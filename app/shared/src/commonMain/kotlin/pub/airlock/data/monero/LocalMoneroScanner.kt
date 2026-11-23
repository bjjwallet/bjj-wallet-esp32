package pub.airlock.data.monero

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import pub.airlock.domain.*
import pub.airlock.resources.Res

/** A private runtime that serves only the bundled documents and evaluates JS on the UI thread. */
interface MoneroRuntime {
    fun load(files: Map<String, ByteArray>)

    suspend fun evaluate(script: String): String?

    fun close()
}

internal class LocalMoneroScanner(
    private val client: HttpClient,
    private val runtimeFactory: () -> MoneroRuntime,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val reportDiagnostic: ((String) -> Unit)? = null,
    private val loadFiles: suspend () -> Map<String, ByteArray> = { engineFiles(worker) },
) : MoneroScanner {
    private val mutex = Mutex()
    private var runtime: MoneroRuntime? = null
    private var pendingOutputs: MoneroOutputs? = null
    private var outputsFor: MoneroWatchWallet? = null
    private var activeScan: MoneroWatchWallet? = null
    private var activePayment: Pair<String, String>? = null
    private var blockBatchLimit: Int? = null
    private var smallBlockReplies = 0

    override suspend fun scan(wallet: MoneroWatchWallet): MoneroScan? =
        scanOrPrepare(wallet)?.let { message ->
            val atomic = message.getValue("atomic").jsonPrimitive.content
            val unlocked = message.getValue("unlockedAtomic").jsonPrimitive.content
            val totalValue = atomic.toULongOrNull()
            val unlockedValue = unlocked.toULongOrNull()
            requireInput(
                totalValue != null &&
                    unlockedValue != null &&
                    unlockedValue <= totalValue &&
                    atomic.all { it in '0'..'9' } &&
                    unlocked.all { it in '0'..'9' },
                InputError.PUBLIC_DATA_INVALID,
            )
            MoneroScan(
                Amount.display(atomic, 12),
                message.getValue("height").jsonPrimitive.long,
                null,
                Amount.display(unlocked, 12),
            )
        }

    override suspend fun prepareTransfer(
        wallet: MoneroWatchWallet,
        recipient: String,
        amount: String,
    ): MoneroUnsignedTransfer {
        requireInput(
            wallet.network == MoneroNetwork.STAGENET &&
                isMoneroAddress(recipient, wallet.network) &&
                recipient != wallet.address,
            InputError.NETWORK_MISMATCH,
        )
        requireInput(amount.toULongOrNull()?.let { it > 0uL } == true, InputError.INVALID_AMOUNT)
        val result =
            scanOrPrepare(wallet, recipient to amount)
                ?: throw InputException(InputError.MONERO_SCAN_PENDING)
        return MoneroUnsignedTransfer(
            result.getValue("hex").jsonPrimitive.content,
            result.getValue("feeAtomic").jsonPrimitive.content,
        )
    }

    private suspend fun scanOrPrepare(
        wallet: MoneroWatchWallet,
        payment: Pair<String, String>? = null,
    ): JsonObject? =
        withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                try {
                    if (activeScan != null && (activeScan != wallet || activePayment != payment))
                        clear()
                    val host = runtime ?: start()
                    if (activeScan == null) {
                        pendingOutputs = null
                        outputsFor = null
                        blockBatchLimit = null
                        smallBlockReplies = 0
                        val input = buildJsonObject {
                            put("daemon", wallet.network.daemon)
                            put(
                                "networkType",
                                if (wallet.network == MoneroNetwork.STAGENET) 2 else 0,
                            )
                            put("address", wallet.address)
                            put("viewKey", wallet.privateViewKey)
                            put("restoreHeight", wallet.scanFromHeight)
                            payment?.let { (recipient, amount) ->
                                putJsonObject("payment") {
                                    put("recipient", recipient)
                                    put("amount", amount)
                                }
                            }
                            putJsonArray("keyImages") {
                                wallet.keyImages.forEach { image ->
                                    addJsonObject {
                                        put("hex", image.image)
                                        put("signature", image.signature)
                                    }
                                }
                            }
                        }
                        requireInput(
                            host.evaluate("airlockStart($input) ? 'started' : ''") == "started",
                            InputError.OPERATION_FAILED,
                        )
                        activeScan = wallet
                        activePayment = payment
                    }
                    poll(host, wallet).also {
                        activeScan = null
                        activePayment = null
                    }
                } catch (_: DaemonTemporarilyUnavailable) {
                    // The runtime still has this RPC pending and can continue after the user
                    // retries.
                    throw InputException(InputError.MONERO_SCAN_PAUSED)
                } catch (_: TimeoutCancellationException) {
                    clear()
                    currentCoroutineContext().ensureActive()
                    throw InputException(InputError.MONERO_SCAN_TIMEOUT)
                } catch (e: CancellationException) {
                    clear()
                    throw e
                } catch (e: InputException) {
                    clear()
                    throw e
                } catch (_: Exception) {
                    clear()
                    throw InputException(InputError.PROVIDER_UNAVAILABLE)
                }
            }
        }

    override suspend fun exportOutputs(wallet: MoneroWatchWallet): MoneroOutputs? =
        withContext(Dispatchers.Main.immediate) {
            mutex.withLock { pendingOutputs.takeIf { outputsFor == wallet } }
        }

    override fun clear() {
        runtime?.close()
        runtime = null
        activeScan = null
        activePayment = null
        pendingOutputs = null
        outputsFor = null
    }

    private suspend fun start(): MoneroRuntime {
        val files = loadFiles()
        val host = runtimeFactory()
        runtime = host
        host.load(files)
        withTimeoutOrNull(20_000) {
            while (
                host.evaluate("typeof airlockStart === 'function' ? 'ready' : ''") != "ready"
            ) delay(50)
            true
        } ?: throw InputException(InputError.MONERO_SCANNER_UNAVAILABLE)
        return host
    }

    private suspend fun poll(host: MoneroRuntime, wallet: MoneroWatchWallet): JsonObject? {
        while (currentCoroutineContext().isActive) {
            val text =
                withTimeout(90_000L) {
                    var next = host.evaluate("airlockNext()").orEmpty()
                    while (next.isEmpty()) {
                        delay(75)
                        next = host.evaluate("airlockNext()").orEmpty()
                    }
                    next
                }
            val message = Json.parseToJsonElement(text).jsonObject
            when (message.getValue("kind").jsonPrimitive.content) {
                "rpc" -> {
                    val reply = daemonReplyWithRetry(wallet.network.daemon, message)
                    withTimeout(90_000L) {
                        for (chunk in reply.chunked(128 * 1024)) {
                            host.evaluate("airlockReplyChunk(${JsonPrimitive(chunk)}); ''")
                        }
                        host.evaluate("airlockReplyDone(); ''")
                    }
                }
                "outputs" -> {
                    pendingOutputs =
                        MoneroOutputs(
                            message.getValue("hex").jsonPrimitive.content,
                            message.getValue("count").jsonPrimitive.int,
                        )
                    outputsFor = wallet
                    return null
                }
                "pending" -> return null
                "verified" ->
                    if (activePayment == null) return message
                    else throw InputException(InputError.INVALID_QUOTE)
                "prepared" ->
                    if (activePayment != null) return message
                    else throw InputException(InputError.PUBLIC_DATA_INVALID)
                "insufficient" -> throw InputException(InputError.INSUFFICIENT_BALANCE)
                else -> throw InputException(InputError.MONERO_SCAN_PENDING)
            }
        }
        currentCoroutineContext().ensureActive()
        return null
    }

    override suspend fun submitTransfer(txid: String, raw: String): TransferStatus {
        val transfer = MoneroSignedTransfer(txid, raw)
        suspend fun request(path: String, payload: JsonObject): JsonObject {
            val response =
                client.post(MoneroNetwork.STAGENET.daemon + path) {
                    expectSuccess = false
                    contentType(ContentType.Application.Json)
                    timeout { requestTimeoutMillis = 30_000L }
                    setBody(payload.toString())
                }
            requireInput(response.status.isSuccess(), InputError.SUBMISSION_PENDING)
            return Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        suspend fun known(): TransferStatus? {
            val response =
                request(
                    "/get_transactions",
                    buildJsonObject {
                        putJsonArray("txs_hashes") { add(JsonPrimitive(transfer.txid)) }
                    },
                )
            val tx = response["txs"]?.jsonArray?.singleOrNull()?.jsonObject ?: return null
            if (tx["tx_hash"]?.jsonPrimitive?.content != transfer.txid) return null
            return if (tx["in_pool"]?.jsonPrimitive?.booleanOrNull == false)
                TransferStatus.CONFIRMED
            else TransferStatus.SUBMITTED
        }
        try {
            known()?.let {
                return it
            }
            val response =
                request(
                    "/send_raw_transaction",
                    buildJsonObject {
                        put("tx_as_hex", transfer.hex)
                        put("do_not_relay", false)
                    },
                )
            if (
                response["status"]?.jsonPrimitive?.content == "OK" &&
                    response["not_relayed"]?.jsonPrimitive?.booleanOrNull != true
            )
                return TransferStatus.SUBMITTED
            return known() ?: throw InputException(InputError.SUBMISSION_PENDING)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw InputException(InputError.SUBMISSION_PENDING)
        }
    }

    private suspend fun daemonReplyWithRetry(daemon: String, message: JsonObject): String {
        var attempt = 0
        while (true) {
            try {
                return withTimeout(90_000L) { daemonReply(daemon, message) }
            } catch (e: DaemonReplyTooLarge) {
                if (e.path !in blockPaths || blockBatchLimit == 1) {
                    throw InputException(InputError.PUBLIC_DATA_INVALID)
                }
                blockBatchLimit = blockBatchLimit?.let { maxOf(it / 4, 1) } ?: 256
                smallBlockReplies = 0
                reportDiagnostic?.invoke(
                    "block RPC reply exceeded target; retrying with up to ${blockBatchLimit} blocks"
                )
                continue
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val temporary =
                    e is TimeoutCancellationException ||
                        e is IOException ||
                        (e is InputException && e.reason == InputError.PROVIDER_UNAVAILABLE)
                if (!temporary) throw e
                attempt++
                if (attempt == 3) throw DaemonTemporarilyUnavailable()
            }
            delay(attempt * 1_000L)
        }
    }

    private class DaemonTemporarilyUnavailable : Exception()

    private class DaemonReplyTooLarge(val path: String) : Exception()

    private suspend fun daemonReply(daemon: String, message: JsonObject): String =
        withContext(worker) {
            val path = message.getValue("path").jsonPrimitive.content
            val method = message.getValue("method").jsonPrimitive.content
            val binary = message.getValue("binary").jsonPrimitive.boolean
            val data = message.getValue("data").jsonPrimitive.content
            if (
                path !in daemonPaths ||
                    method !in setOf("GET", "POST") ||
                    data.length > 2 * 1024 * 1024 ||
                    binary != path.endsWith(".bin")
            ) {
                reportDiagnostic?.invoke(
                    "RPC request rejected: route=${path.takeIf { it in daemonPaths } ?: "unknown"}, binary=$binary, chars=${data.length}"
                )
                throw InputException(InputError.PUBLIC_DATA_INVALID)
            }
            if (path == "/json_rpc") {
                val rpc =
                    Json.parseToJsonElement(data)
                        .jsonObject
                        .getValue("method")
                        .jsonPrimitive
                        .content
                if (rpc !in jsonMethods) {
                    reportDiagnostic?.invoke("JSON-RPC method rejected")
                    throw InputException(InputError.PUBLIC_DATA_INVALID)
                }
            }
            val requestBody =
                if (method == "POST" && binary)
                    Base64.decode(data).let { bytes ->
                        val limit = blockBatchLimit
                        if (path in blockPaths && limit != null)
                            capMoneroBlockBatch(bytes, limit)
                                ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
                        else bytes
                    }
                else data.encodeToByteArray()
            client
                .prepareRequest(daemon + path) {
                    expectSuccess = false
                    timeout {
                        requestTimeoutMillis = 90_000
                        socketTimeoutMillis = 30_000
                    }
                    this.method = if (method == "POST") HttpMethod.Post else HttpMethod.Get
                    if (method == "POST") {
                        contentType(
                            if (binary) ContentType.Application.OctetStream
                            else ContentType.Application.Json
                        )
                        setBody(requestBody)
                    }
                }
                .execute { response ->
                    requireInput(response.status.isSuccess(), InputError.PROVIDER_UNAVAILABLE)
                    // Bound response size before converting it into a string for the JS runtime.
                    // Dense batches also put pressure on the native wallet2 processing pool.
                    val replyLimit =
                        if (path in blockPaths && blockBatchLimit != 1) blockReplyTarget
                        else maxReplyBytes
                    val declaredSize = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declaredSize != null && declaredSize > replyLimit)
                        throw DaemonReplyTooLarge(path)
                    val bytes =
                        response.bodyAsChannel().readRemaining(replyLimit + 1).readByteArray()
                    if (bytes.size > replyLimit) throw DaemonReplyTooLarge(path)
                    if (path in blockPaths) adjustBlockLimit(bytes.size)
                    Base64.encode(bytes)
                }
        }

    private fun adjustBlockLimit(replyBytes: Int) {
        val limit = blockBatchLimit ?: return
        smallBlockReplies = if (replyBytes < blockReplyTarget / 4) smallBlockReplies + 1 else 0
        if (smallBlockReplies == 4) {
            blockBatchLimit = if (limit >= 512) null else limit * 2
            smallBlockReplies = 0
        }
    }

    private companion object {
        suspend fun engineFiles(worker: CoroutineDispatcher) =
            withContext(worker) {
                mapOf(
                    "/index.html" to page.encodeToByteArray(),
                    "/wallet2.js" to Res.readBytes("files/monero/wallet2.js"),
                    "/engine.js" to Res.readBytes("files/monero/engine.js"),
                )
            }

        val MoneroNetwork.daemon
            get() =
                when (this) {
                    MoneroNetwork.MAINNET -> "https://xmr-node.cakewallet.com:18081"
                    MoneroNetwork.STAGENET -> "https://xmr.nodely.dev:38089"
                }

        const val blockReplyTarget = 1024 * 1024L
        const val maxReplyBytes = 32 * 1024 * 1024L
        val blockPaths = setOf("/getblocks.bin", "/get_blocks.bin")
        val daemonPaths =
            setOf(
                "/json_rpc",
                "/get_height",
                "/getheight",
                "/get_info",
                "/gethashes.bin",
                "/get_hashes.bin",
                "/getblocks.bin",
                "/get_blocks.bin",
                "/get_o_indexes.bin",
                "/get_outs.bin",
                "/get_output_distribution.bin",
                "/get_transactions",
                "/gettransactions",
                "/is_key_image_spent",
                "/get_transaction_pool_hashes.bin",
                "/get_transaction_pool",
                "/get_pool_info.bin",
            )
        val jsonMethods =
            setOf(
                "get_version",
                "get_info",
                "get_block_count",
                "get_block_header_by_height",
                "getblockheaderbyheight",
                "get_last_block_header",
                "getlastblockheader",
                "hard_fork_info",
                "get_fee_estimate",
                "get_txpool_backlog",
                "getblockheadersrange",
                "get_block_headers_range",
            )
        val page =
            """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' airlock-monero: 'unsafe-eval'">
            <script defer src="wallet2.js"></script><script defer src="engine.js"></script>
            </head><body></body></html>
        """
                .trimIndent()
    }
}
