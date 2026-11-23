package pub.airlock.data.network

import fr.acinq.bitcoin.Crypto
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.serialization.json.*
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*

internal object TonPayment {
    const val endpoint = "https://toncenter.com/api/v2"
    private const val v3 = "https://toncenter.com/api/v3"
    private const val walletId = 698983191L

    internal class Cell(
        data: ByteArray = byteArrayOf(),
        var bits: Int = 0,
        refs: List<Cell> = emptyList(),
        private val pinned: ByteArray? = null,
        private val pinnedDepth: Int = 0,
    ) {
        val data = data.toMutableList()
        val refs = refs.toMutableList()

        fun uint(n: Long, count: Int): Cell {
            requireInput(bits + count <= 1023, InputError.INVALID_QUOTE)
            for (i in count - 1 downTo 0) {
                if (bits % 8 == 0) data += 0
                data[data.lastIndex] =
                    (data.last().toInt() or (((n ushr i) and 1).toInt() shl (7 - bits % 8)))
                        .toByte()
                bits++
            }
            return this
        }

        fun raw(bytes: ByteArray): Cell {
            for (b in bytes) uint((b.toInt() and 255).toLong(), 8)
            return this
        }

        fun address(hash: ByteArray) = uint(4, 3).uint(0, 8).raw(hash)

        fun coins(n: Long): Cell {
            requireInput(n >= 0, InputError.INVALID_QUOTE)
            var size = 0
            var v = n
            while (v > 0) {
                size++
                v = v ushr 8
            }
            return uint(size.toLong(), 4).uint(n, size * 8)
        }

        fun depth(): Int =
            if (pinned != null) pinnedDepth else refs.maxOfOrNull { it.depth() + 1 } ?: 0

        fun padded(): ByteArray {
            val out = data.toByteArray()
            if (bits % 8 != 0)
                out[out.lastIndex] = (out.last().toInt() or (1 shl (7 - bits % 8))).toByte()
            return out
        }

        fun hash(): ByteArray {
            if (pinned != null) return pinned
            return Crypto.sha256(
                byteArrayOf(refs.size.toByte(), (bits / 8 + (bits + 7) / 8).toByte()) +
                    padded() +
                    refs
                        .flatMap { listOf((it.depth() ushr 8).toByte(), it.depth().toByte()) }
                        .toByteArray() +
                    refs.flatMap { it.hash().toList() }.toByteArray()
            )
        }

        fun boc(): ByteArray {
            val all = mutableListOf<Cell>()
            fun add(c: Cell) {
                requireInput(c.pinned == null, InputError.INVALID_QUOTE)
                all += c
                for (r in c.refs) add(r)
            }
            add(this)
            requireInput(all.size < 256, InputError.INVALID_QUOTE)
            val cells =
                all.flatMap { c ->
                        (byteArrayOf(
                                c.refs.size.toByte(),
                                (c.bits / 8 + (c.bits + 7) / 8).toByte(),
                            ) +
                                c.padded() +
                                c.refs
                                    .map { r -> all.indexOfFirst { it === r }.toByte() }
                                    .toByteArray())
                            .toList()
                    }
                    .toByteArray()
            requireInput(cells.size < 65536, InputError.INVALID_QUOTE)
            val size = if (cells.size < 256) 1 else 2
            return EvmEncoding.bytes("0xb5ee9c72") +
                byteArrayOf(1, size.toByte(), all.size.toByte(), 1, 0) +
                (if (size == 1) byteArrayOf(cells.size.toByte())
                else byteArrayOf((cells.size ushr 8).toByte(), cells.size.toByte())) +
                byteArrayOf(0) +
                cells
        }
    }

    private fun address(value: String): ByteArray {
        if (Regex("0:[0-9a-f]{64}").matches(value)) return EvmEncoding.bytes("0x" + value.drop(2))
        val bytes =
            try {
                Base64.UrlSafe.decode(value)
            } catch (_: Exception) {
                throw InputException(InputError.INVALID_DESTINATION)
            }
        requireInput(
            bytes.size == 36 && bytes[0].toInt() in setOf(17, 81) && bytes[1] == 0.toByte(),
            InputError.INVALID_DESTINATION,
        )
        var crc = 0
        for (b in bytes.take(34)) {
            crc = crc xor ((b.toInt() and 255) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
        }
        requireInput(
            (bytes[34].toInt() and 255) == (crc ushr 8) &&
                (bytes[35].toInt() and 255) == (crc and 255),
            InputError.INVALID_DESTINATION,
        )
        return bytes.copyOfRange(2, 34)
    }

    private fun rawAddress(hash: ByteArray) = "0:" + hash.hex()

    private fun JsonObject.n(key: String) =
        get(key)?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw InputException(InputError.INVALID_QUOTE)

    private fun walletData(public: ByteArray) =
        Cell().uint(0, 32).uint(walletId, 32).raw(public).uint(0, 1)

    private fun state(code: Cell, data: Cell) =
        Cell().uint(6, 5).apply {
            refs += code
            refs += data
        }

    fun jettonWallet(owner: String, master: String): String {
        val code =
            Cell(
                pinned =
                    EvmEncoding.bytes(
                        "0x89468f02c78e570802e39979c8516fc38df07ea76a48357e0536f2ba7b3ee37b"
                    )
            )
        val data = Cell().uint(0, 4).coins(0).address(address(owner)).address(address(master))
        return rawAddress(state(code, data).hash())
    }

    fun signing(quote: TransferQuote): Cell {
        val asset = quote.asset
        val plan = quote.ledgerPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            asset.coin == "ton" &&
                LedgerNetworks.supportsPayment(asset) &&
                quote.feeCurrency == CoinCatalog.get("ton") &&
                plan.keys == setOf("network", "sequence", "expires", "jetton_wallet") &&
                plan["network"] == JsonPrimitive("ton-mainnet"),
            InputError.INVALID_QUOTE,
        )
        val from = address(asset.address)
        val to = address(quote.destination)
        requireInput(!from.contentEquals(to), InputError.INVALID_DESTINATION)
        val amount =
            Amount.atomic(quote.amount, asset.decimals).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val fee =
            Amount.atomic(quote.fee, 9).toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val seq = plan.n("sequence")
        val expires = plan.n("expires")
        requireInput(
            amount > 0 && fee in 1..1000000000 && seq <= 0xffffffffL && expires in 1..0xffffffffL,
            InputError.INVALID_QUOTE,
        )
        val token = Stablecoins.definition(asset)
        val internal = Cell()
        if (token == null) {
            requireInput(plan["jetton_wallet"] == JsonPrimitive(""), InputError.INVALID_QUOTE)
            internal
                .uint(4, 4)
                .uint(0, 2)
                .address(to)
                .coins(amount)
                .uint(0, 1)
                .coins(0)
                .coins(0)
                .uint(0, 64)
                .uint(0, 32)
                .uint(0, 1)
                .uint(0, 1)
        } else {
            requireInput(fee >= 50000000, InputError.INVALID_QUOTE)
            val wallet = address(plan.getValue("jetton_wallet").jsonPrimitive.content)
            requireInput(
                wallet.contentEquals(address(jettonWallet(asset.address, token.contract))),
                InputError.INVALID_QUOTE,
            )
            val transfer =
                Cell()
                    .uint(0x0f8a7ea5, 32)
                    .uint(seq, 64)
                    .coins(amount)
                    .address(to)
                    .address(from)
                    .uint(0, 1)
                    .coins(1)
                    .uint(0, 1)
            internal
                .uint(6, 4)
                .uint(0, 2)
                .address(wallet)
                .coins(50000000)
                .uint(0, 1)
                .coins(0)
                .coins(0)
                .uint(0, 64)
                .uint(0, 32)
                .uint(0, 1)
                .uint(1, 1)
            internal.refs += transfer
        }
        return Cell()
            .uint(walletId, 32)
            .uint(expires, 32)
            .uint(seq, 32)
            .uint(0, 8)
            .uint(3, 8)
            .apply { refs += internal }
    }

    fun body(quote: TransferQuote) = signing(quote).boc()

    private fun message(signature: String, quote: TransferQuote, verifier: Ed25519Verifier): Cell {
        requireInput(Regex("[0-9a-f]{192}").matches(signature), InputError.INVALID_QUOTE)
        val bytes = EvmEncoding.bytes("0x$signature")
        val public = bytes.copyOfRange(0, 32)
        val signatureBytes = bytes.copyOfRange(32, 96)
        val signing = signing(quote)
        val init = state(tonWalletCode, walletData(public))
        requireInput(
            tonWalletCode.hash().hex() ==
                "feb5ff6820e2ff0d9483e7e0d62c817d846789fb4ae580c878866d959dabd5c0" &&
                init.hash().contentEquals(address(quote.asset.address)) &&
                verifier.verify(public, signing.hash(), signatureBytes),
            InputError.INVALID_QUOTE,
        )
        val signed =
            Cell().raw(signatureBytes).raw(signing.data.toByteArray()).apply {
                refs += signing.refs
            }
        val external = Cell().uint(2, 2).uint(0, 2).address(address(quote.asset.address)).coins(0)
        if (quote.ledgerPlan!!.n("sequence") == 0L) {
            external.uint(1, 1).uint(1, 1)
            external.refs += init
        } else external.uint(0, 1)
        external.uint(1, 1)
        external.refs += signed
        return external
    }

    fun transactionId(signature: String, quote: TransferQuote, verifier: Ed25519Verifier) =
        message(signature, quote, verifier).hash().hex()

    private suspend fun call(
        http: ProviderTransport,
        method: String,
        body: JsonObject,
    ): JsonElement {
        val response = http.json("$endpoint/$method", body)
        if (response["ok"] != JsonPrimitive(true)) throw ProviderFailure(Failure.REJECTED)
        return response["result"] ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    suspend fun balance(http: ProviderTransport, asset: Asset): String {
        address(asset.address)
        if (asset.contract.isEmpty())
            return Amount.display(
                call(http, "getAddressBalance", buildJsonObject { put("address", asset.address) })
                    .jsonPrimitive
                    .content,
                9,
            )
        val token =
            Stablecoins.definition(asset) ?: throw InputException(InputError.NETWORK_UNSUPPORTED)
        val response =
            http.json(
                "$v3/jetton/wallets",
                query =
                    listOf(
                        "owner_address" to asset.address,
                        "jetton_address" to token.contract,
                        "limit" to "2",
                    ),
            )
        val rows = response.getValue("jetton_wallets").jsonArray
        if (rows.isEmpty()) return "0"
        val row = rows.single().jsonObject
        requireInput(
            address(row.getValue("address").jsonPrimitive.content)
                .contentEquals(address(jettonWallet(asset.address, token.contract))) &&
                address(row.getValue("owner").jsonPrimitive.content)
                    .contentEquals(address(asset.address)) &&
                address(row.getValue("jetton").jsonPrimitive.content)
                    .contentEquals(address(token.contract)),
            InputError.INVALID_QUOTE,
        )
        return Amount.display(row.n("balance").toString(), asset.decimals)
    }

    suspend fun estimate(
        http: ProviderTransport,
        asset: Asset,
        recipient: String,
        amount: String,
    ): TransferQuote {
        address(asset.address)
        address(recipient)
        val info =
            call(http, "getWalletInformation", buildJsonObject { put("address", asset.address) })
                .jsonObject
        val seq =
            if (info["account_state"] == JsonPrimitive("uninitialized")) 0 else info.n("seqno")
        requireInput(
            seq == 0L ||
                info["wallet_type"]?.jsonPrimitive?.content in setOf("wallet v4 r2", "wallet_v4r2"),
            InputError.INVALID_QUOTE,
        )
        val token = Stablecoins.definition(asset)
        val quote =
            TransferQuote(
                asset,
                recipient,
                amount,
                if (token == null) "0.01" else "0.06",
                ledgerPlan =
                    buildJsonObject {
                        put("network", "ton-mainnet")
                        put("sequence", seq.toString())
                        put("expires", (Clock.System.now().epochSeconds + 300).toString())
                        put(
                            "jetton_wallet",
                            if (token == null) "" else jettonWallet(asset.address, token.contract),
                        )
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
        val message = message(signature, quote, verifier)
        requireInput(
            quote.ledgerPlan!!.n("expires") > Clock.System.now().epochSeconds,
            InputError.INVALID_QUOTE,
        )
        val result =
            call(
                    http,
                    "sendBocReturnHash",
                    buildJsonObject { put("boc", Base64.encode(message.boc())) },
                )
                .jsonObject
        val hash = result.getValue("hash").jsonPrimitive.content
        requireInput(
            hash == message.hash().hex() || Base64.decode(hash).contentEquals(message.hash()),
            InputError.TRANSACTION_REJECTED,
        )
        return TransferStatus.SUBMITTED
    }
}
