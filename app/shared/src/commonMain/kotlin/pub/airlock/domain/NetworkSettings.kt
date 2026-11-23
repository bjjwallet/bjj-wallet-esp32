package pub.airlock.domain

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkProvider(val label: String) {
    BLOCKSTREAM("Blockstream Esplora"),
    MEMPOOL("mempool.space"),
    FREEDOM("Freedom Street"),
    BNB_SMART_CHAIN("BNB Smart Chain RPC"),
    // Keep historical provider identifiers for vault decoding.
    AIRLOCK("AirLock"),
    ALCHEMY("Alchemy"),
    ANKR("Ankr"),
    COINBASE("Coinbase"),
    MORALIS("Moralis"),
    KOIOS("Koios"),
    POLYGON_RPC("Polygon PoS RPC"),
    SOLANA_RPC("Solana RPC"),
    ETHEREUM_RPC("Ethereum RPC"),
    ARBITRUM_RPC("Arbitrum RPC"),
    STELLAR_HORIZON("Stellar Horizon"),
    TRON_RPC("TronGrid"),
    EVM_RPC("EVM mainnet RPC"),
    XRP_RPC("XRP Ledger RPC"),
    SUI_RPC("Sui RPC"),
    TON_API("TON API");

    companion object {
        val explorers = listOf(BLOCKSTREAM, MEMPOOL, FREEDOM)
        val available = explorers + listOf(AIRLOCK, MORALIS)
    }

    // The selected asset supplies the chain-specific route. Credentials are never part of this
    // display.
    fun serviceUrl(airlockUrl: String) =
        when (this) {
            BLOCKSTREAM -> "https://blockstream.info/api"
            MEMPOOL -> "https://mempool.space/api"
            FREEDOM -> "https://api.freedom.st"
            BNB_SMART_CHAIN -> airlockUrl
            AIRLOCK -> airlockUrl
            ALCHEMY -> "https://eth-mainnet.g.alchemy.com/v2"
            ANKR -> "https://rpc.ankr.com"
            COINBASE -> "https://api.developer.coinbase.com/rpc/v1/base"
            MORALIS -> "https://api.moralis.com"
            KOIOS -> "https://api.koios.rest/api/v1"
            POLYGON_RPC -> "https://polygon.drpc.org"
            SOLANA_RPC -> "https://api.mainnet.solana.com"
            ETHEREUM_RPC -> "https://ethereum-rpc.publicnode.com"
            ARBITRUM_RPC -> "https://arb1.arbitrum.io/rpc"
            STELLAR_HORIZON -> "https://horizon.stellar.org"
            TRON_RPC -> "https://api.trongrid.io"
            XRP_RPC -> "https://xrplcluster.com"
            SUI_RPC -> "https://graphql.mainnet.sui.io/graphql"
            TON_API -> "https://toncenter.com/api/v2"
            EVM_RPC -> "" // A fixed chain-specific endpoint is supplied by EvmNetworks.
        }

    val requiresApiKey: Boolean
        get() = this in setOf(ALCHEMY, ANKR, COINBASE, MORALIS)
}

/** Stored only inside the platform's encrypted vault. Never put credentials in logs. */
@Serializable
data class NetworkSettings(
    val preferred: NetworkProvider = NetworkProvider.BLOCKSTREAM,
    val alchemyKey: String = "",
    val ankrKey: String = "",
    val coinbaseKey: String = "",
    val moralisKey: String = "",
) {
    override fun toString() = "NetworkSettings(preferred=$preferred)"

    fun validated(): NetworkSettings {
        requireInput(preferred in NetworkProvider.available, InputError.NETWORK_UNSUPPORTED)
        val clean =
            copy(
                alchemyKey = alchemyKey.trim(),
                ankrKey = ankrKey.trim(),
                coinbaseKey = coinbaseKey.trim(),
                moralisKey = moralisKey.trim(),
            )
        requireInput(
            listOf(clean.alchemyKey, clean.ankrKey, clean.coinbaseKey).all {
                it.isEmpty() || Regex("[A-Za-z0-9_-]{1,256}").matches(it)
            },
            InputError.INVALID_API_KEY,
        )
        requireInput(
            clean.moralisKey.isEmpty() || Regex("[A-Za-z0-9_.-]{1,2048}").matches(clean.moralisKey),
            InputError.INVALID_API_KEY,
        )
        requireInput(
            clean.preferred != NetworkProvider.ALCHEMY || clean.alchemyKey.isNotEmpty(),
            InputError.API_KEY_REQUIRED,
        )
        requireInput(
            clean.preferred != NetworkProvider.COINBASE || clean.coinbaseKey.isNotEmpty(),
            InputError.API_KEY_REQUIRED,
        )
        // The integrated Ankr features include authenticated prices, Tron and Base.
        requireInput(
            clean.preferred != NetworkProvider.ANKR || clean.ankrKey.isNotEmpty(),
            InputError.API_KEY_REQUIRED,
        )
        requireInput(
            clean.preferred != NetworkProvider.MORALIS || clean.moralisKey.isNotEmpty(),
            InputError.API_KEY_REQUIRED,
        )
        return clean
    }

    fun keyFor(provider: NetworkProvider) =
        when (provider) {
            NetworkProvider.BLOCKSTREAM,
            NetworkProvider.MEMPOOL,
            NetworkProvider.FREEDOM,
            NetworkProvider.AIRLOCK,
            NetworkProvider.BNB_SMART_CHAIN,
            NetworkProvider.KOIOS,
            NetworkProvider.POLYGON_RPC,
            NetworkProvider.SOLANA_RPC,
            NetworkProvider.ETHEREUM_RPC,
            NetworkProvider.ARBITRUM_RPC,
            NetworkProvider.STELLAR_HORIZON,
            NetworkProvider.TRON_RPC,
            NetworkProvider.XRP_RPC,
            NetworkProvider.SUI_RPC,
            NetworkProvider.TON_API,
            NetworkProvider.EVM_RPC -> ""
            NetworkProvider.ALCHEMY -> alchemyKey
            NetworkProvider.ANKR -> ankrKey
            NetworkProvider.COINBASE -> coinbaseKey
            NetworkProvider.MORALIS -> moralisKey
        }

    fun withKey(provider: NetworkProvider, value: String) =
        when (provider) {
            NetworkProvider.BLOCKSTREAM,
            NetworkProvider.MEMPOOL,
            NetworkProvider.FREEDOM,
            NetworkProvider.AIRLOCK,
            NetworkProvider.BNB_SMART_CHAIN,
            NetworkProvider.KOIOS,
            NetworkProvider.POLYGON_RPC,
            NetworkProvider.SOLANA_RPC,
            NetworkProvider.ETHEREUM_RPC,
            NetworkProvider.ARBITRUM_RPC,
            NetworkProvider.STELLAR_HORIZON,
            NetworkProvider.TRON_RPC,
            NetworkProvider.XRP_RPC,
            NetworkProvider.SUI_RPC,
            NetworkProvider.TON_API,
            NetworkProvider.EVM_RPC -> this
            NetworkProvider.ALCHEMY -> copy(alchemyKey = value)
            NetworkProvider.ANKR -> copy(ankrKey = value)
            NetworkProvider.COINBASE -> copy(coinbaseKey = value)
            NetworkProvider.MORALIS -> copy(moralisKey = value)
        }
}

/**
 * Explicit mainnet mappings; adding an endpoint does not enable another chain's transaction format.
 */
object EvmNetworks {
    val chainIds =
        mapOf(
            "ethereum" to 1L,
            "bsc" to 56L,
            "polygon" to 137L,
            "arbitrum" to 42161L,
            "avalanche" to 43114L,
            "ethereumclassic" to 61L,
            "base" to 8453L,
            "hyperevm" to 999L,
            "robinhood" to 4663L,
        )
    val rpcUrls =
        mapOf(
            "ethereum" to "https://ethereum-rpc.publicnode.com",
            "bsc" to "https://bsc-dataseed.bnbchain.org",
            "polygon" to "https://polygon.drpc.org",
            "arbitrum" to "https://arb1.arbitrum.io/rpc",
            "avalanche" to "https://api.avax.network/ext/bc/C/rpc",
            "ethereumclassic" to "https://rpc.etcplanets.com",
            "base" to "https://mainnet.base.org",
            "hyperevm" to "https://rpc.hyperliquid.xyz/evm",
            "robinhood" to "https://rpc.mainnet.chain.robinhood.com",
        )

    fun supportsPayment(asset: Asset): Boolean {
        if (asset.coin !in chainIds) return false
        if (asset.contract.isNotEmpty()) return Stablecoins.definition(asset) != null
        val native = CoinCatalog.find(asset.coin) ?: return false
        return asset.symbol == native.symbol && asset.decimals == native.decimals
    }

    fun paymentProvider(asset: Asset): NetworkProvider? =
        if (!supportsPayment(asset)) null
        else
            when (asset.coin) {
                "ethereum" -> NetworkProvider.ETHEREUM_RPC
                "polygon" -> NetworkProvider.POLYGON_RPC
                "arbitrum" -> NetworkProvider.ARBITRUM_RPC
                else -> NetworkProvider.EVM_RPC
            }

    fun isNativePolygon(asset: Asset) =
        asset.coin == "polygon" &&
            asset.contract.isEmpty() &&
            asset.symbol == "POL" &&
            asset.decimals == 18

    fun isNativeEthereum(asset: Asset) =
        asset.coin == "ethereum" &&
            asset.contract.isEmpty() &&
            asset.symbol == "ETH" &&
            asset.decimals == 18

    fun nativePaymentProvider(asset: Asset): NetworkProvider? =
        if (asset.contract.isEmpty()) paymentProvider(asset) else null
}

object SolanaNetwork {
    const val classicProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val extensionsProgram = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
    val token2022Symbols = setOf("PYUSD")

    fun isNative(asset: Asset) =
        asset.coin == "solana" &&
            asset.contract.isEmpty() &&
            asset.symbol == "SOL" &&
            asset.decimals == 9

    fun supportsToken(asset: Asset) =
        asset.coin == "solana" && Stablecoins.definition(asset) != null

    fun tokenProgram(asset: Asset) =
        if (asset.symbol in token2022Symbols) extensionsProgram else classicProgram

    fun supportsPayment(asset: Asset) = isNative(asset) || supportsToken(asset)
}

object LedgerNetworks {
    fun supportsPayment(asset: Asset) =
        asset.coin in setOf("stellar", "tron", "ripple", "sui", "ton") &&
            ((asset.contract.isEmpty() &&
                asset.symbol == CoinCatalog.get(asset.coin).symbol &&
                asset.decimals == CoinCatalog.get(asset.coin).decimals) ||
                Stablecoins.definition(asset) != null)

    fun provider(asset: Asset) =
        when (asset.coin) {
            "ripple" -> NetworkProvider.XRP_RPC
            "tron" -> NetworkProvider.TRON_RPC
            "sui" -> NetworkProvider.SUI_RPC
            "ton" -> NetworkProvider.TON_API
            else -> NetworkProvider.STELLAR_HORIZON
        }
}

object CardanoNetwork {
    fun supportsPayment(asset: Asset) =
        isNative(asset) || (asset.coin == "cardano" && Stablecoins.definition(asset) != null)

    fun isNative(asset: Asset) =
        asset.coin == "cardano" &&
            asset.contract.isEmpty() &&
            asset.symbol == "ADA" &&
            asset.decimals == 6
}

/** App integration coverage, not a statement about every API sold by a provider. */
object ProviderCoverage {
    internal val moralisHistory =
        setOf("bitcoin", "ethereum", "bsc", "polygon", "arbitrum", "avalanche", "base", "robinhood")
    private val moralisBalances = moralisHistory + "solana"
    val alchemyBalances =
        EvmNetworks.chainIds.keys +
            setOf("bitcoin", "litecoin", "dogecoin", "bitcoincash", "solana", "tron", "sui")
    val ankrBalances =
        (EvmNetworks.chainIds.keys - setOf("hyperevm", "robinhood")) +
            setOf("solana", "tron", "sui")

    fun balances(provider: NetworkProvider): Set<String> =
        when (provider) {
            NetworkProvider.BLOCKSTREAM,
            NetworkProvider.MEMPOOL -> setOf("bitcoin")
            NetworkProvider.FREEDOM ->
                setOf("bitcoin", "bitcoincash", "litecoin", "dogecoin", "dash", "ecash")
            NetworkProvider.AIRLOCK -> PublicNetworks.entries.map { it.coin }.toSet()
            NetworkProvider.ALCHEMY -> alchemyBalances
            NetworkProvider.ANKR -> ankrBalances
            NetworkProvider.COINBASE -> setOf("base")
            NetworkProvider.BNB_SMART_CHAIN -> setOf("bsc")
            NetworkProvider.MORALIS -> moralisBalances
            NetworkProvider.KOIOS -> setOf("cardano")
            NetworkProvider.POLYGON_RPC -> setOf("polygon")
            NetworkProvider.SOLANA_RPC -> setOf("solana")
            NetworkProvider.ETHEREUM_RPC -> setOf("ethereum")
            NetworkProvider.ARBITRUM_RPC -> setOf("arbitrum")
            NetworkProvider.STELLAR_HORIZON -> setOf("stellar")
            NetworkProvider.TRON_RPC -> setOf("tron")
            NetworkProvider.XRP_RPC -> setOf("ripple")
            NetworkProvider.SUI_RPC -> setOf("sui")
            NetworkProvider.TON_API -> setOf("ton")
            NetworkProvider.EVM_RPC -> EvmNetworks.chainIds.keys
        }

    // Token balances use the decimals registered in the AirLock service.
    private val airLockTokens =
        listOf(Stablecoins.bscBrz, Stablecoins.ethereumBrz) +
            Stablecoins.tokens.filter {
                it.coin to it.symbol in
                    setOf(
                        "ethereum" to "USDT",
                        "bsc" to "USDC",
                        "tron" to "USDT",
                        "arbitrum" to "ARB",
                    )
            }

    fun canReadNative(provider: NetworkProvider, coin: String) =
        coin in EvmNetworks.chainIds ||
            coin in setOf("cardano", "solana", "stellar", "tron", "ripple", "ton", "sui") ||
            (provider in NetworkProvider.available && coin in balances(provider))

    fun canRead(provider: NetworkProvider, token: TokenDefinition) =
        ((token.coin in EvmNetworks.chainIds ||
            token.coin in setOf("cardano", "stellar", "solana", "tron", "ripple", "ton", "sui") ||
            token == Stablecoins.ethereumBnb ||
            token == Stablecoins.arbitrumBrz ||
            (token.coin == "tron" && token.symbol == "USDC")) && token in Stablecoins.tokens) ||
            when (provider) {
                NetworkProvider.AIRLOCK -> token in airLockTokens
                NetworkProvider.MORALIS ->
                    token.coin in moralisBalances && token in Stablecoins.tokens
                else -> false
            }

    fun canRead(provider: NetworkProvider, asset: Asset) =
        if (LedgerNetworks.supportsPayment(asset) || CardanoNetwork.supportsPayment(asset)) true
        else if (asset.contract.isEmpty())
            SolanaNetwork.isNative(asset) || canReadNative(provider, asset.coin)
        else Stablecoins.definition(asset)?.let { canRead(provider, it) } ?: false

    fun canReadHistory(provider: NetworkProvider, asset: Asset) =
        (provider == NetworkProvider.MORALIS &&
            asset.coin in moralisHistory &&
            canRead(provider, asset)) ||
            (provider in setOf(NetworkProvider.BLOCKSTREAM, NetworkProvider.MEMPOOL) &&
                asset.coin == "bitcoin" &&
                asset.contract.isEmpty())

    /** An actual independent route wins; otherwise the selected API must implement submission. */
    fun hasDirectSendRoute(asset: Asset) =
        LedgerNetworks.supportsPayment(asset) ||
            CardanoNetwork.supportsPayment(asset) ||
            SolanaNetwork.supportsPayment(asset) ||
            (asset.coin in EvmNetworks.rpcUrls && EvmNetworks.supportsPayment(asset))

    fun selectedApiSupportsSend(provider: NetworkProvider, asset: Asset) =
        if (provider == NetworkProvider.AIRLOCK) Stablecoins.supportsHardwareBrz(asset)
        else asset.contract.isEmpty() && explorerSupportsSend(provider, asset.coin)

    private fun explorerSupportsSend(provider: NetworkProvider, coin: String) =
        provider in NetworkProvider.explorers &&
            coin in balances(provider) &&
            coin in UtxoNetworks.supported

    fun canSubmit(provider: NetworkProvider, asset: Asset) =
        hasDirectSendRoute(asset) || selectedApiSupportsSend(provider, asset)

    fun canSubmit(provider: NetworkProvider, coin: String) =
        (coin in EvmNetworks.chainIds && coin in EvmNetworks.rpcUrls) ||
            coin in setOf("solana", "cardano", "stellar", "tron", "ripple", "sui", "ton") ||
            explorerSupportsSend(provider, coin)

    fun canSubmit(provider: NetworkProvider, token: TokenDefinition) =
        (token.coin in setOf("solana", "cardano", "stellar", "tron", "ripple", "sui", "ton") &&
            token in Stablecoins.tokens) ||
            (token.coin in EvmNetworks.chainIds &&
                token.coin in EvmNetworks.rpcUrls &&
                token in Stablecoins.tokens) ||
            (provider == NetworkProvider.AIRLOCK &&
                token in listOf(Stablecoins.bscBrz, Stablecoins.ethereumBrz))

    /** Retains enabled accounts and cached assets for wallet operations and exports. */
    @Suppress("UNUSED_PARAMETER")
    fun wallet(
        snapshot: VaultSnapshot,
        mode: WalletMode,
        provider: NetworkProvider = snapshot.networkSettings.preferred,
    ): WalletData {
        val accounts = snapshot.accounts.filter { it.mode == mode && it.enabled }
        val ids = accounts.map { it.id }.toSet()
        return WalletData(accounts, snapshot.assets.filter { it.accountId in ids })
    }
}

@Serializable
data class EvmTransactionPlan(
    val chainId: Long,
    val nonce: String,
    val gasPrice: String,
    val gasLimit: String,
    val to: String,
    val value: String,
    val data: String,
    // Base fees outside the signed L2 gas limit; decimal wei estimates including a margin.
    val l1Fee: String? = null,
    val operatorFee: String? = null,
)

/** Persisted before the first submission, so a restart cannot turn a timeout into a new payment. */
@Serializable
data class PendingSubmission(
    val txId: String,
    val signedTransaction: String,
    val quote: TransferQuote,
    val mode: WalletMode,
) {
    override fun toString() = "PendingSubmission(txId=$txId, mode=$mode)"
}
