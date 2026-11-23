package pub.airlock.data

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import pub.airlock.data.network.*
import pub.airlock.domain.*

/** Chain-specific routes are fixed. Other operations capture the selected provider once. */
class ProductionWalletNetwork(
    private val client: HttpClient? = null,
    private val ed25519: Ed25519Verifier? = null,
    private val snapshot: (suspend () -> VaultSnapshot)? = null,
) : WalletNetwork {
    private val transport = client?.let(::ProviderTransport)

    private fun nativeProvider(asset: Asset): WalletProvider? {
        if (asset.coin !in EvmNetworks.rpcUrls) return null
        val provider = EvmNetworks.paymentProvider(asset) ?: return null
        val http = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
        return object : EvmRpcProvider(http) {
            override val id = provider

            override fun endpoint(coin: String): String {
                if (coin != asset.coin) throw ProviderFailure(Failure.UNSUPPORTED)
                return EvmNetworks.rpcUrls.getValue(coin)
            }

            override suspend fun rpc(coin: String, method: String, params: JsonArray) =
                http.rpc(
                    if (coin == "base" && method == "eth_sendRawTransaction")
                        "https://mainnet-sequencer.base.org"
                    else endpoint(coin),
                    method,
                    params,
                )
        }
    }

    override var lastProvider: NetworkProvider? = null
        private set

    override var lastSubmissionStatus = TransferStatus.SUBMITTED
        private set

    private suspend fun selected(coin: String, capability: Capability): WalletProvider {
        val transport = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
        val settings =
            snapshot?.invoke() ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
        val config = settings.networkSettings.validated()
        val provider =
            when (config.preferred) {
                NetworkProvider.BLOCKSTREAM,
                NetworkProvider.MEMPOOL,
                NetworkProvider.FREEDOM -> UtxoExplorerProvider(transport, config.preferred)
                NetworkProvider.MORALIS -> MoralisProvider(transport, config.moralisKey)
                else -> throw InputException(InputError.NETWORK_UNSUPPORTED)
            }
        requireInput(
            provider.supports(coin, capability),
            if (
                capability == Capability.PRICES &&
                    config.preferred == NetworkProvider.ANKR &&
                    config.ankrKey.isEmpty()
            )
                InputError.API_KEY_REQUIRED
            else InputError.NETWORK_UNSUPPORTED,
        )
        return provider
    }

    private suspend fun <T> read(
        coin: String,
        capability: Capability,
        action: suspend (WalletProvider) -> T,
    ): T {
        val provider = selected(coin, capability)
        try {
            return action(provider).also { lastProvider = provider.id }
        } catch (e: ProviderFailure) {
            throw InputException(e.kind.inputError())
        } catch (e: CancellationException) {
            throw e
        } catch (e: InputException) {
            throw e
        } catch (_: Exception) {
            throw InputException(InputError.PROVIDER_UNAVAILABLE)
        }
    }

    override suspend fun canReceiveIssuedAsset(asset: Asset): Boolean =
        read(asset.coin, Capability.BALANCE) { provider ->
            requireInput(provider.id == NetworkProvider.AIRLOCK, InputError.NETWORK_UNSUPPORTED)
            XrplTrustlines(transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE))
                .canReceive(asset)
        }

    private suspend fun airLock(): AirLockRestWallet? {
        val settings =
            snapshot?.invoke()?.takeIf { it.networkSettings.preferred == NetworkProvider.AIRLOCK }
                ?: return null
        val http = client ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
        return AirLockRestWallet(AirLockApi(http) { settings.apiUrl })
    }

    override suspend fun balance(asset: Asset): String {
        if (asset.coin == "tron")
            return try {
                PublicBalanceQueries.tron(
                        transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                        NetworkProvider.TRON_RPC.serviceUrl(""),
                        asset,
                    )
                    .also { lastProvider = NetworkProvider.TRON_RPC }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        if (asset.coin == "ton")
            return try {
                TonPayment.balance(
                        transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                        asset,
                    )
                    .also { lastProvider = NetworkProvider.TON_API }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        if (asset.coin == "sui")
            return try {
                SuiPayment.balance(
                        transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                        asset,
                    )
                    .also { lastProvider = NetworkProvider.SUI_RPC }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        if (asset.coin == "ripple") {
            return try {
                XrpPayment.balance(
                        transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                        asset,
                    )
                    .also { lastProvider = NetworkProvider.XRP_RPC }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        }
        if (
            asset.coin == "stellar" ||
                (asset.coin == "tron" &&
                    ((asset.symbol == "USDC" && Stablecoins.definition(asset) != null) ||
                        (asset.contract.isEmpty() &&
                            "tron" !in
                                ProviderCoverage.balances(
                                    snapshot?.invoke()?.networkSettings?.preferred
                                        ?: NetworkProvider.BLOCKSTREAM
                                ))))
        ) {
            val http = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
            return try {
                if (asset.coin == "stellar")
                    PublicBalanceQueries.stellar(http, asset).also {
                        lastProvider = NetworkProvider.STELLAR_HORIZON
                    }
                else
                    PublicBalanceQueries.tron(http, NetworkProvider.TRON_RPC.serviceUrl(""), asset)
                        .also { lastProvider = NetworkProvider.TRON_RPC }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        }
        if (asset.coin == "solana" && useSolanaRpcForBalance(asset))
            return readSolana {
                PublicBalanceQueries.solana(it, NetworkProvider.SOLANA_RPC.serviceUrl(""), asset)
            }
        if (asset.coin == "cardano")
            return try {
                val http = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
                PublicBalanceQueries.cardano(http, asset).also {
                    lastProvider = NetworkProvider.KOIOS
                }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        if (useNativeEvmBalance(asset)) return readEvm(asset) { it.balance(asset) }
        airLock()?.let {
            requireInput(
                ProviderCoverage.canRead(NetworkProvider.AIRLOCK, asset),
                InputError.NETWORK_UNSUPPORTED,
            )
            return it.balance(asset).also { lastProvider = NetworkProvider.AIRLOCK }
        }
        return read(asset.coin, Capability.BALANCE) { it.balance(asset) }
    }

    override suspend fun balances(assets: List<Asset>): Map<String, String> {
        val coin = assets.firstOrNull()?.coin ?: return emptyMap()
        if (coin in setOf("cardano", "stellar", "tron"))
            return assets.associate { it.id to balance(it) }
        val nativeSolana = assets.filter { it.coin == "solana" && useSolanaRpcForBalance(it) }
        if (nativeSolana.isNotEmpty())
            return balances(assets - nativeSolana.toSet()) +
                nativeSolana.associate { it.id to balance(it) }
        val nativeEvm = assets.filter { useNativeEvmBalance(it) }
        if (nativeEvm.isNotEmpty())
            return balances(assets - nativeEvm.toSet()) +
                nativeEvm.associate { it.id to balance(it) }
        airLock()?.let {
            return super<WalletNetwork>.balances(assets)
        }
        return read(coin, Capability.BALANCE) {
            if (it is MoralisProvider) it.balances(assets)
            else assets.associate { asset -> asset.id to it.balance(asset) }
        }
    }

    override suspend fun transactionHistory(asset: Asset, cursor: String?) =
        read(asset.coin, Capability.HISTORY) { provider ->
            when (provider) {
                is UtxoExplorerProvider -> provider.history(asset, cursor)
                is MoralisProvider -> provider.history(asset, cursor)
                else -> throw ProviderFailure(Failure.UNSUPPORTED)
            }
        }

    override suspend fun addressActivity(asset: Asset): AddressActivity =
        if (asset.coin == "ecash")
            read(asset.coin, Capability.BALANCE) { provider ->
                (provider as UtxoExplorerProvider).addressActivity(asset)
            }
        else AddressActivity(balance(asset))

    override suspend fun prices(symbols: List<String>): Map<String, String> {
        if (symbols.isEmpty()) return emptyMap()
        airLock()?.let {
            return it.prices(symbols).also { lastProvider = NetworkProvider.AIRLOCK }
        }
        return read("", Capability.PRICES) { it.prices(symbols) }
    }

    override suspend fun estimate(
        asset: Asset,
        destination: String,
        amount: String,
    ): TransferQuote {
        if (LedgerNetworks.supportsPayment(asset)) {
            requireInput(ed25519 != null, InputError.INTEGRATION_UNAVAILABLE)
            return try {
                LedgerPayment.estimate(
                        transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                        asset,
                        destination,
                        amount,
                    )
                    .also { lastProvider = LedgerNetworks.provider(asset) }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        }
        if (CardanoNetwork.supportsPayment(asset)) {
            requireInput(ed25519 != null, InputError.INTEGRATION_UNAVAILABLE)
            val http = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
            return try {
                CardanoPayment.estimate(http, asset, destination, amount).also {
                    lastProvider = NetworkProvider.KOIOS
                }
            } catch (error: ProviderFailure) {
                throw InputException(error.kind.inputError())
            }
        }
        if (SolanaNetwork.supportsPayment(asset)) {
            requireInput(ed25519 != null, InputError.INTEGRATION_UNAVAILABLE)
            return readSolana { SolanaPayment.estimate(it, asset, destination, amount) }
        }
        if (!EvmNetworks.supportsPayment(asset))
            airLock()?.let {
                return it.estimate(asset, destination, amount).also {
                    lastProvider = NetworkProvider.AIRLOCK
                }
            }
        if (asset.coin in UtxoNetworks.supported)
            return read(asset.coin, Capability.BALANCE) {
                (it as? UtxoExplorerProvider
                        ?: throw InputException(InputError.NETWORK_UNSUPPORTED))
                    .estimate(asset, destination, amount)
            }
        return readEvm(asset) { provider ->
            val quote = EvmStrategy.estimate(provider, asset, destination, amount)
            snapshot?.invoke()?.let { saved ->
                val senderIds =
                    saved.assets
                        .filter { it.coin == asset.coin && it.address.equals(asset.address, true) }
                        .map { it.id }
                        .toSet() + asset.id
                val nonce = EvmEncoding.decimal(quote.evmPlan!!.nonce)
                // Even a pending nonce may be stale. Never replace a payment already submitted
                // locally.
                for (tx in
                    saved.transfers.filter {
                        it.assetId in senderIds && it.status == TransferStatus.SUBMITTED
                    }) {
                    requireInput(
                        tx.nonce != null &&
                            Amount.compare(EvmEncoding.decimal(tx.nonce), nonce) < 0,
                        InputError.SUBMISSION_PENDING,
                    )
                }
            }
            quote
        }
    }

    private suspend fun <T> readEvm(asset: Asset, action: suspend (WalletProvider) -> T): T {
        val provider = nativeProvider(asset) ?: return read(asset.coin, Capability.EVM_RPC, action)
        return try {
            action(provider).also { lastProvider = provider.id }
        } catch (error: ProviderFailure) {
            throw InputException(error.kind.inputError())
        }
    }

    private suspend fun <T> readSolana(action: suspend (ProviderTransport) -> T): T {
        val rpc = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
        return try {
            action(rpc).also { lastProvider = NetworkProvider.SOLANA_RPC }
        } catch (error: ProviderFailure) {
            throw InputException(error.kind.inputError())
        }
    }

    private fun useSolanaRpcForBalance(asset: Asset) = SolanaNetwork.supportsPayment(asset)

    private fun useNativeEvmBalance(asset: Asset) =
        asset.coin in EvmNetworks.rpcUrls && EvmNetworks.supportsPayment(asset)

    /** Low-level transport for an externally signed UTXO transaction; does not create a payment. */
    suspend fun broadcastSignedUtxo(coin: String, signedTransaction: String): String {
        requireInput(
            coin in setOf("bitcoin", "bitcoincash", "litecoin", "dogecoin"),
            InputError.NETWORK_UNSUPPORTED,
        )
        requireInput(
            signedTransaction.length <= 800000 &&
                Regex("(?:[0-9a-fA-F]{2})+").matches(signedTransaction),
            InputError.INVALID_QUOTE,
        )
        val hash =
            try {
                fr.acinq.bitcoin.Transaction.read(signedTransaction).txid.toString()
            } catch (_: Exception) {
                throw InputException(InputError.INVALID_QUOTE)
            }
        val provider =
            selected(coin, Capability.BALANCE) as? UtxoExplorerProvider
                ?: throw InputException(InputError.NETWORK_UNSUPPORTED)
        try {
            return provider.broadcast(coin, signedTransaction, hash).also {
                lastProvider = provider.id
            }
        } catch (_: ProviderFailure) {
            // Never automatically resend after a timeout or invalid acknowledgement.
            throw InputException(InputError.SUBMISSION_PENDING)
        }
    }

    override fun transactionId(signedTransaction: String, quote: TransferQuote) =
        when {
            LedgerNetworks.supportsPayment(quote.asset) ->
                LedgerPayment.transactionId(
                    signedTransaction,
                    quote,
                    ed25519 ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                )
            CardanoNetwork.supportsPayment(quote.asset) ->
                CardanoPayment.transactionId(
                    signedTransaction,
                    quote,
                    ed25519 ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                )
            SolanaNetwork.supportsPayment(quote.asset) ->
                SolanaPayment.transactionId(
                    signedTransaction,
                    quote,
                    ed25519 ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                )
            quote.asset.coin in UtxoNetworks.supported ->
                UtxoStrategy.transactionId(signedTransaction, quote)
            else -> EvmEncoding.transactionId(signedTransaction, quote)
        }

    override suspend fun broadcast(signedTransaction: String): String =
        throw InputException(InputError.INVALID_QUOTE)

    override suspend fun broadcast(signedTransaction: String, quote: TransferQuote): String {
        val hash = transactionId(signedTransaction, quote)
        if (LedgerNetworks.supportsPayment(quote.asset)) {
            try {
                lastSubmissionStatus =
                    LedgerPayment.submit(
                        transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                        signedTransaction,
                        quote,
                        ed25519 ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                    )
                lastProvider = LedgerNetworks.provider(quote.asset)
                return hash
            } catch (_: ProviderFailure) {
                throw InputException(InputError.SUBMISSION_PENDING)
            }
        }
        if (quote.bitcoinSignatureTest) {
            BitcoinSignatureTest.validate(quote, TransferTestConfig.TEST_MODE)
            val provider =
                selected("bitcoin", Capability.BALANCE) as? UtxoExplorerProvider
                    ?: throw InputException(InputError.NETWORK_UNSUPPORTED)
            lastProvider = provider.id
            // A diagnostic attempt, never a pending payment. Surface rejection separately
            // from an unavailable API; neither proves acceptance into a node's mempool.
            try {
                return provider.broadcast("bitcoin", signedTransaction, hash)
            } catch (failure: ProviderFailure) {
                throw InputException(failure.kind.inputError())
            }
        }
        if (CardanoNetwork.supportsPayment(quote.asset)) {
            val http = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
            try {
                lastSubmissionStatus =
                    CardanoPayment.submit(
                        http,
                        signedTransaction,
                        quote,
                        ed25519 ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE),
                    )
                lastProvider = NetworkProvider.KOIOS
                return hash
            } catch (_: ProviderFailure) {
                throw InputException(InputError.SUBMISSION_PENDING)
            }
        }
        if (SolanaNetwork.supportsPayment(quote.asset)) {
            val rpc = transport ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
            try {
                lastSubmissionStatus = SolanaPayment.submit(rpc, signedTransaction, quote, hash)
                lastProvider = NetworkProvider.SOLANA_RPC
                return hash
            } catch (_: ProviderFailure) {
                throw InputException(InputError.SUBMISSION_PENDING)
            }
        }
        if (!EvmNetworks.supportsPayment(quote.asset))
            airLock()?.let {
                lastSubmissionStatus = it.submit(quote.asset, signedTransaction, hash)
                lastProvider = NetworkProvider.AIRLOCK
                return hash
            }
        val coin = quote.asset.coin
        if (coin in UtxoNetworks.supported) {
            val provider =
                selected(coin, Capability.BALANCE) as? UtxoExplorerProvider
                    ?: throw InputException(InputError.NETWORK_UNSUPPORTED)
            lastSubmissionStatus = TransferStatus.SUBMITTED
            try {
                provider.status(coin, hash)?.let { status ->
                    lastSubmissionStatus = status
                    lastProvider = provider.id
                    return hash
                }
                try {
                    return provider.broadcast(coin, signedTransaction, hash).also {
                        lastProvider = provider.id
                    }
                } catch (_: ProviderFailure) {
                    provider.status(coin, hash)?.let { status ->
                        lastSubmissionStatus = status
                        lastProvider = provider.id
                        return hash
                    }
                    throw InputException(InputError.SUBMISSION_PENDING)
                }
            } catch (_: ProviderFailure) {
                throw InputException(InputError.SUBMISSION_PENDING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InputException) {
                throw e
            } catch (_: Exception) {
                throw InputException(InputError.SUBMISSION_PENDING)
            }
        }
        val provider = nativeProvider(quote.asset) ?: selected(coin, Capability.EVM_RPC)
        lastSubmissionStatus = TransferStatus.SUBMITTED
        try {
            provider.verifyChain(coin)
            // A lookup error is uncertainty, NOT "not found". In that case no send is attempted.
            if (known(provider, coin, hash)) return hash.also { lastProvider = provider.id }
            try {
                if (coin == "base") {
                    val plan = quote.evmPlan!!
                    val (l1, operator) = BaseFees.estimate(provider, plan)
                    if (
                        Amount.compare(l1, plan.l1Fee!!) > 0 ||
                            Amount.compare(operator, plan.operatorFee!!) > 0
                    )
                        throw ProviderFailure(Failure.REJECTED)
                }
                val returned =
                    provider
                        .rpc(coin, "eth_sendRawTransaction", params(signedTransaction))
                        .stringValue()
                if (!returned.equals(hash, ignoreCase = true))
                    throw ProviderFailure(Failure.INVALID_RESPONSE)
                return hash.also { lastProvider = provider.id }
            } catch (_: ProviderFailure) {
                // Includes timeout, "already known" and "nonce too low": none is proof of success.
                if (known(provider, coin, hash)) return hash.also { lastProvider = provider.id }
                throw InputException(InputError.SUBMISSION_PENDING)
            }
        } catch (e: ProviderFailure) {
            throw InputException(
                if (e.kind == Failure.WRONG_CHAIN) InputError.NETWORK_MISMATCH
                else InputError.SUBMISSION_PENDING
            )
        }
    }

    private suspend fun known(provider: WalletProvider, coin: String, hash: String): Boolean {
        val transaction = provider.rpc(coin, "eth_getTransactionByHash", params(hash))
        val located = transaction != JsonNull
        if (located) {
            val returned = (transaction as? JsonObject)?.get("hash")?.stringValue()
            if (!hash.equals(returned, ignoreCase = true))
                throw ProviderFailure(Failure.INVALID_RESPONSE)
        }
        val receipt =
            try {
                provider.rpc(coin, "eth_getTransactionReceipt", params(hash))
            } catch (e: ProviderFailure) {
                if (located) return true else throw e
            }
        if (receipt == JsonNull) return located
        val returned = (receipt as? JsonObject)?.get("transactionHash")?.stringValue()
        if (!hash.equals(returned, ignoreCase = true))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        lastSubmissionStatus =
            when ((receipt as JsonObject)["status"]?.stringValue()) {
                "0x1" -> TransferStatus.CONFIRMED
                "0x0" -> TransferStatus.FAILED
                else -> throw ProviderFailure(Failure.INVALID_RESPONSE)
            }
        return true // Even a reverted transaction was processed: never recreate the payment.
    }
}

private fun Failure.inputError() =
    when (this) {
        Failure.AUTH -> InputError.API_KEY_REQUIRED
        Failure.UNSUPPORTED -> InputError.NETWORK_UNSUPPORTED
        Failure.WRONG_CHAIN -> InputError.NETWORK_MISMATCH
        Failure.REJECTED -> InputError.TRANSACTION_REJECTED
        Failure.RATE_LIMITED -> InputError.PROVIDER_RATE_LIMITED
        Failure.UNAVAILABLE,
        Failure.INVALID_RESPONSE -> InputError.PROVIDER_UNAVAILABLE
    }
