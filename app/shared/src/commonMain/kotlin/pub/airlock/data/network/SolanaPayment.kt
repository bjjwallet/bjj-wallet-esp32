package pub.airlock.data.network

import fr.acinq.bitcoin.Base58
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.*
import pub.airlock.domain.*

internal object SolanaPayment {
    private const val feeAtomic = 5000
    private val endpoint = NetworkProvider.SOLANA_RPC.serviceUrl("")
    private val commitment = buildJsonObject { put("commitment", "confirmed") }

    private fun key(encoded: String): ByteArray =
        try {
            require(encoded.length in 32..44)
            Base58.decode(encoded).also {
                require(
                    it.size == 32 &&
                        it.any { byte -> byte != 0.toByte() } &&
                        Base58.encode(it) == encoded
                )
            }
        } catch (_: IllegalArgumentException) {
            throw InputException(InputError.INVALID_DESTINATION)
        }

    private fun message(quote: TransferQuote): ByteArray {
        val token = SolanaNetwork.supportsToken(quote.asset)
        requireInput(
            (SolanaNetwork.isNative(quote.asset) || token) &&
                quote.feeCurrency == CoinCatalog.get("solana"),
            InputError.INVALID_QUOTE,
        )
        val plan = quote.solanaPlan ?: throw InputException(InputError.INVALID_QUOTE)
        val extensions = token && quote.asset.symbol in SolanaNetwork.token2022Symbols
        val rent = if (extensions) 2192400L else 2039280L
        requireInput(
            (if (token) plan.rentAtomic == 0L || plan.rentAtomic == rent
            else plan.rentAtomic == 0L) &&
                Amount.atomic(quote.fee, 9) == (feeAtomic + plan.rentAtomic).toString(),
            InputError.INVALID_QUOTE,
        )
        val sender = key(quote.asset.address)
        val recipient = key(quote.destination)
        requireInput(!sender.contentEquals(recipient), InputError.INVALID_DESTINATION)
        val amount =
            Amount.atomic(quote.amount, quote.asset.decimals).toULongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(amount != 0uL, InputError.AMOUNT_POSITIVE)
        if (token) {
            val program = SolanaNetwork.tokenProgram(quote.asset)
            val source =
                SolanaTokenAddress.associated(quote.asset.address, quote.asset.contract, program)
            val destination =
                SolanaTokenAddress.associated(quote.destination, quote.asset.contract, program)
            val keys =
                sender +
                    source +
                    destination +
                    recipient +
                    key(quote.asset.contract) +
                    ByteArray(32) +
                    key(program) +
                    key(SolanaTokenAddress.associatedProgram) +
                    (if (extensions) key("MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr")
                    else byteArrayOf())
            val create =
                if (plan.rentAtomic == 0L) byteArrayOf()
                else byteArrayOf(7, 6, 0, 2, 3, 4, 5, 6, 1, 1)
            val transfer =
                byteArrayOf(6, 4, 1, 4, 2, 0, if (extensions) 19 else 10) +
                    (if (extensions) byteArrayOf(26, 1) else byteArrayOf(12)) +
                    ByteArray(8) { (amount shr (it * 8)).toByte() } +
                    byteArrayOf(quote.asset.decimals.toByte()) +
                    (if (extensions) ByteArray(8) else byteArrayOf())
            return byteArrayOf(1, 0, if (extensions) 6 else 5, if (extensions) 9 else 8) +
                keys +
                key(plan.blockhash) +
                byteArrayOf(
                    ((if (plan.rentAtomic == 0L) 1 else 2) + (if (extensions) 1 else 0)).toByte()
                ) +
                create +
                (if (extensions) byteArrayOf(8, 0, 0) else byteArrayOf()) +
                transfer
        }
        return byteArrayOf(1, 0, 1, 3) +
            sender +
            recipient +
            ByteArray(32) +
            key(plan.blockhash) +
            byteArrayOf(1, 2, 2, 0, 1, 12, 2, 0, 0, 0) +
            ByteArray(8) { (amount shr (it * 8)).toByte() }
    }

    suspend fun estimate(
        transport: ProviderTransport,
        asset: Asset,
        to: String,
        amount: String,
    ): TransferQuote {
        requireInput(
            SolanaNetwork.isNative(asset) || SolanaNetwork.supportsToken(asset),
            InputError.NETWORK_UNSUPPORTED,
        )
        key(asset.address)
        key(to)
        requireInput(to != asset.address, InputError.INVALID_DESTINATION)
        val rent =
            if (SolanaNetwork.supportsToken(asset)) tokenRent(transport, asset, to, amount) else 0L
        val response =
            transport.rpc(endpoint, "getLatestBlockhash", JsonArray(listOf(commitment))).jsonObject
        val blockhash = response.getValue("value").jsonObject.getValue("blockhash").stringValue()
        val quote =
            TransferQuote(
                asset,
                to,
                amount,
                Amount.display((feeAtomic + rent).toString(), 9),
                solanaPlan = SolanaTransactionPlan(blockhash, rent),
            )
        val encoded = Base64.encode(message(quote))
        val fee =
            transport
                .rpc(
                    endpoint,
                    "getFeeForMessage",
                    JsonArray(listOf(JsonPrimitive(encoded), commitment)),
                )
                .jsonObject["value"]
        if (fee != JsonPrimitive(feeAtomic)) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return quote
    }

    private suspend fun tokenRent(
        transport: ProviderTransport,
        asset: Asset,
        to: String,
        amount: String,
    ): Long {
        val program = SolanaNetwork.tokenProgram(asset)
        val source =
            Base58.encode(SolanaTokenAddress.associated(asset.address, asset.contract, program))
        val destination = Base58.encode(SolanaTokenAddress.associated(to, asset.contract, program))
        val response =
            transport
                .rpc(
                    endpoint,
                    "getMultipleAccounts",
                    JsonArray(
                        listOf(
                            JsonArray(
                                listOf(source, destination, asset.contract).map(::JsonPrimitive)
                            ),
                            buildJsonObject {
                                put("encoding", "jsonParsed")
                                put("commitment", "confirmed")
                            },
                        )
                    ),
                )
                .jsonObject
        val rows =
            response["value"] as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        if (rows.size != 3) throw ProviderFailure(Failure.INVALID_RESPONSE)
        fun info(value: JsonElement, type: String): JsonObject {
            val row = value as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (row["owner"] != JsonPrimitive(program) || row["executable"] != JsonPrimitive(false))
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            val parsed =
                (row["data"] as? JsonObject)?.get("parsed") as? JsonObject
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (parsed["type"] != JsonPrimitive(type))
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            return parsed["info"] as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        }
        val mint = info(rows[2], "mint")
        if (
            mint["decimals"] != JsonPrimitive(asset.decimals) ||
                mint["isInitialized"] != JsonPrimitive(true)
        )
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        val extensions = asset.symbol in SolanaNetwork.token2022Symbols
        if (extensions) validateExtensions(mint)
        fun checkAccount(value: JsonElement, owner: String): String {
            val account = info(value, "account")
            if (
                account["owner"] != JsonPrimitive(owner) ||
                    account["mint"] != JsonPrimitive(asset.contract)
            )
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (account["state"] != JsonPrimitive("initialized"))
                throw ProviderFailure(Failure.REJECTED)
            val tokens =
                account["tokenAmount"] as? JsonObject
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (tokens["decimals"] != JsonPrimitive(asset.decimals))
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            val balance =
                (tokens["amount"] as? JsonPrimitive)?.content
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (balance.toULongOrNull() == null || !Regex("0|[1-9][0-9]*").matches(balance))
                throw ProviderFailure(Failure.INVALID_RESPONSE)
            return balance
        }
        if (rows[0] == JsonNull) throw InputException(InputError.INSUFFICIENT_BALANCE)
        val balance = checkAccount(rows[0], asset.address)
        val atomic = Amount.atomic(amount, asset.decimals)
        requireInput(atomic.toULongOrNull() != null && atomic != "0", InputError.INVALID_AMOUNT)
        requireInput(Amount.compare(balance, atomic) >= 0, InputError.INSUFFICIENT_BALANCE)
        if (rows[1] != JsonNull) {
            checkAccount(rows[1], to)
            return 0L
        }
        val expected = if (extensions) 2192400L else 2039280L
        val rent =
            transport
                .rpc(
                    endpoint,
                    "getMinimumBalanceForRentExemption",
                    JsonArray(listOf(JsonPrimitive(if (extensions) 187 else 165), commitment)),
                )
                .jsonPrimitive
                .longOrNull
        // Match the rent reviewed by this firmware version. Governance changes require an explicit
        // update.
        if (rent != expected) throw ProviderFailure(Failure.INVALID_RESPONSE)
        return expected
    }

    private fun validateExtensions(mint: JsonObject) {
        val allowed =
            setOf(
                "mintCloseAuthority",
                "permanentDelegate",
                "transferFeeConfig",
                "confidentialTransferMint",
                "confidentialTransferFeeConfig",
                "transferHook",
                "metadataPointer",
                "tokenMetadata",
            )
        val extensions =
            mint["extensions"] as? JsonArray ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
        val seen = mutableSetOf<String>()
        for (entry in extensions) {
            val row = entry as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            val kind =
                (row["extension"] as? JsonPrimitive)?.content
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (kind !in allowed || !seen.add(kind)) throw ProviderFailure(Failure.UNSUPPORTED)
            val state =
                row["state"] as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (kind == "transferHook" && state["programId"] != JsonNull)
                throw ProviderFailure(Failure.UNSUPPORTED)
            if (kind == "transferFeeConfig")
                for (period in listOf("olderTransferFee", "newerTransferFee")) {
                    val fee =
                        state[period] as? JsonObject
                            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                    val bps =
                        (fee["transferFeeBasisPoints"] as? JsonPrimitive)?.intOrNull
                            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                    val maximum =
                        (fee["maximumFee"] as? JsonPrimitive)?.content?.toULongOrNull()
                            ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
                    if (bps !in 0..10000 || (bps != 0 && maximum != 0uL))
                        throw ProviderFailure(Failure.UNSUPPORTED)
                }
        }
        if (!seen.containsAll(setOf("transferFeeConfig", "transferHook")))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
    }

    fun transactionId(
        signatureHex: String,
        quote: TransferQuote,
        verifier: Ed25519Verifier,
    ): String {
        requireInput(Regex("[0-9a-f]{128}").matches(signatureHex), InputError.INVALID_QUOTE)
        val signature = EvmEncoding.bytes("0x" + signatureHex)
        requireInput(
            verifier.verify(key(quote.asset.address), message(quote), signature),
            InputError.INVALID_QUOTE,
        )
        return Base58.encode(signature)
    }

    suspend fun submit(
        transport: ProviderTransport,
        signature: String,
        quote: TransferQuote,
        id: String,
    ): TransferStatus {
        suspend fun status(): TransferStatus? {
            val query = buildJsonObject { put("searchTransactionHistory", true) }
            val result =
                transport
                    .rpc(
                        endpoint,
                        "getSignatureStatuses",
                        JsonArray(listOf(JsonArray(listOf(JsonPrimitive(id))), query)),
                    )
                    .jsonObject
            val row =
                (result["value"] as? JsonArray)?.singleOrNull()
                    ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (row == JsonNull) return null
            val receipt = row as? JsonObject ?: throw ProviderFailure(Failure.INVALID_RESPONSE)
            if ("err" !in receipt) throw ProviderFailure(Failure.INVALID_RESPONSE)
            if (receipt["err"] != JsonNull) return TransferStatus.FAILED
            return when (receipt["confirmationStatus"]) {
                JsonPrimitive("finalized"),
                JsonPrimitive("confirmed") -> TransferStatus.CONFIRMED
                JsonPrimitive("processed") -> TransferStatus.SUBMITTED
                else -> throw ProviderFailure(Failure.INVALID_RESPONSE)
            }
        }
        status()?.let {
            return it
        }
        val plan = quote.solanaPlan ?: throw InputException(InputError.INVALID_QUOTE)
        val valid =
            transport
                .rpc(
                    endpoint,
                    "isBlockhashValid",
                    JsonArray(listOf(JsonPrimitive(plan.blockhash), commitment)),
                )
                .jsonObject["value"]
        if (valid != JsonPrimitive(true)) throw ProviderFailure(Failure.REJECTED)
        val options = buildJsonObject {
            put("encoding", "base64")
            put("preflightCommitment", "confirmed")
            put("maxRetries", 0)
        }
        val transaction = byteArrayOf(1) + EvmEncoding.bytes("0x" + signature) + message(quote)
        return try {
            val result =
                transport.rpc(
                    endpoint,
                    "sendTransaction",
                    JsonArray(listOf(JsonPrimitive(Base64.encode(transaction)), options)),
                )
            if (result != JsonPrimitive(id)) throw ProviderFailure(Failure.INVALID_RESPONSE)
            TransferStatus.SUBMITTED
        } catch (error: ProviderFailure) {
            status() ?: throw error
        }
    }
}
