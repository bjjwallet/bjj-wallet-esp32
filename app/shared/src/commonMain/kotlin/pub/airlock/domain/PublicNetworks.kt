package pub.airlock.domain

/** Explicit mainnet mapping to the identifiers and paths exported by the AirLock firmware. */
data class PublicNetwork(
    val coin: String,
    val hardwareCoin: String,
    val path: String,
    val extended: Boolean = true,
)

object PublicNetworks {
    val entries =
        listOf(
            PublicNetwork("bitcoin", "bitcoin", "m/84'/0'/0'/0/0"),
            PublicNetwork("ethereum", "ethereum", "m/44'/60'/0'/0/0"),
            PublicNetwork("tron", "tron", "m/44'/195'/0'/0/0"),
            PublicNetwork("bsc", "smartchain", "m/44'/60'/0'/0/0"),
            PublicNetwork("ripple", "ripple", "m/44'/144'/0'/0/0"),
            PublicNetwork("dogecoin", "doge", "m/44'/3'/0'/0/0"),
            PublicNetwork("solana", "solana", "m/44'/501'/0'", false),
            PublicNetwork("bitcoincash", "bitcoincash", "m/44'/145'/0'/0/0"),
            PublicNetwork("ton", "ton", "m/44'/607'/0'", false),
            PublicNetwork("avalanche", "avalanchec", "m/44'/60'/0'/0/0"),
            PublicNetwork("ethereumclassic", "classic", "m/44'/61'/0'/0/0"),
            PublicNetwork("litecoin", "litecoin", "m/84'/2'/0'/0/0"),
            PublicNetwork("arbitrum", "arbitrum", "m/44'/60'/0'/0/0"),
            PublicNetwork("polygon", "polygon", "m/44'/60'/0'/0/0"),
            PublicNetwork("sui", "sui", "m/44'/784'/0'/0'/0'", false),
            PublicNetwork("dash", "dash", "m/44'/5'/0'/0/0"),
        )
    val ecash = PublicNetwork("ecash", "ecash", "m/44'/899'/0'/0/0")
    val cardano = PublicNetwork("cardano", "cardano", "m/1852'/1815'/0'/0/0", false)
    val optional =
        listOf(PublicNetwork("stellar", "stellar", "m/44'/148'/0'", false), ecash, cardano)
    val initiallyEnabled = setOf("bitcoin", "ethereum", "tron", "bsc", "ripple")
    // These networks share Ethereum's public derivation and need no additional firmware export.
    val ethereumAliases = setOf("base", "hyperevm", "robinhood")

    fun get(coin: String): PublicNetwork =
        (if (coin in ethereumAliases) get("ethereum").copy(coin = coin)
        else (entries + optional).firstOrNull { it.coin == coin })
            ?: throw InputException(InputError.PUBLIC_DATA_INVALID)

    fun path(coin: String, index: Long): String {
        requireInput(index in 0..0x7fffffff, InputError.ADDRESS_INDEX_EXHAUSTED)
        val network = get(coin)
        val hardened = !network.extended && network != cardano
        return network.path.substringBeforeLast('/') + "/$index" + if (hardened) "'" else ""
    }
}
