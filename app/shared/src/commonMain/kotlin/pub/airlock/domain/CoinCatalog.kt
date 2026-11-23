package pub.airlock.domain

/** Mainnet network identifiers shared by asset registration and fee accounting. */
object CoinCatalog {
    private val standaloneCoins = UtxoNetworks.supported + setOf("ecash", "monero")

    fun isStandalone(coin: String) = coin in standaloneCoins

    val coins =
        listOf(
            CoinDefinition("bitcoin", "Bitcoin", "BTC", "btc.svg", 8, "Bitcoin"),
            CoinDefinition("ethereum", "Ethereum", "ETH", "eth.svg", 18, "Ethereum"),
            CoinDefinition("tron", "Tron", "TRX", "trx.svg", 6, "Tron"),
            CoinDefinition("bsc", "BNB Smart Chain", "BNB", "bnb.svg", 18, "Ethereum"),
            CoinDefinition("ripple", "XRP Ledger", "XRP", "xrp.svg", 6, "XRP"),
            CoinDefinition("dogecoin", "Dogecoin", "DOGE", "doge.svg", 8, "Bitcoin"),
            CoinDefinition("solana", "Solana", "SOL", "sol.svg", 9, "Solana"),
            CoinDefinition("cardano", "Cardano", "ADA", "ada.svg", 6, "Cardano"),
            CoinDefinition("bitcoincash", "Bitcoin Cash", "BCH", "bch.svg", 8, "BitcoinCash"),
            CoinDefinition("ton", "TON", "TON", "ton.svg", 9, "TheOpenNetwork"),
            CoinDefinition("avalanche", "Avalanche C-Chain", "AVAX", "avax.svg", 18, "Ethereum"),
            CoinDefinition("ethereumclassic", "Ethereum Classic", "ETC", "etc.svg", 18, "Ethereum"),
            CoinDefinition("litecoin", "Litecoin", "LTC", "ltc.svg", 8, "Bitcoin"),
            CoinDefinition("arbitrum", "Arbitrum", "ETH", "arbitrum.svg", 18, "Ethereum"),
            CoinDefinition("polygon", "Polygon", "POL", "pol.svg", 18, "Ethereum"),
            CoinDefinition("sui", "Sui", "SUI", "sui.svg", 9, "Sui"),
            CoinDefinition("dash", "Dash", "DASH", "dash.svg", 8, "Bitcoin"),
            CoinDefinition("ecash", "eCash", "XEC", "xec.svg", 2, "BitcoinCash"),
            CoinDefinition("base", "Base", "ETH", "eth.svg", 18, "Ethereum"),
            CoinDefinition("monero", "Monero", "XMR", "xmr.svg", 12, "Monero"),
            CoinDefinition("stellar", "Stellar", "XLM", "xlm.svg", 7, "Stellar"),
            CoinDefinition("hyperevm", "HyperEVM", "HYPE", "hype.png", 18, "Ethereum"),
            CoinDefinition(
                "robinhood",
                "Robinhood Chain",
                "ETH",
                "eth.svg",
                18,
                "Ethereum",
                networkIcon = "robinhood_chain.jpg",
            ),
        )

    fun find(coin: String): CoinDefinition? = coins.firstOrNull { it.coin == coin }

    fun get(coin: String): CoinDefinition =
        find(coin) ?: throw InputException(InputError.INTEGRATION_UNAVAILABLE)
}
