package pub.airlock.domain

data class TokenDefinition(
    val coin: String,
    val symbol: String,
    val name: String,
    val contract: String,
    val icon: String,
    val decimals: Int = 6,
) {
    fun matches(asset: Asset): Boolean =
        asset.coin == coin &&
            asset.symbol == symbol &&
            asset.decimals == decimals &&
            contract.equals(
                asset.contract,
                ignoreCase = contract.startsWith("0x") && !contract.contains("::"),
            )

    fun asset(native: Asset): Asset {
        require(native.coin == coin && native.contract.isEmpty())
        return native.copy(
            id = "${native.id}:$symbol",
            symbol = symbol,
            name = name,
            contract = contract,
            icon = icon,
            decimals = decimals,
            balance = "0",
        )
    }
}

/**
 * Mainnet deployments. Bridged and pegged variants are identified by their contract and display
 * name.
 */
object Stablecoins {
    val bscBrz =
        TokenDefinition(
            "bsc",
            "BRZ",
            "Brazilian Digital Token",
            "0x0295afd3d7e86068050d64509e515f2db71b4914",
            "brz.png",
            18,
        )
    val ethereumBrz =
        TokenDefinition(
            "ethereum",
            "BRZ",
            "Brazilian Digital Token",
            "0x01d33FD36ec67c6Ada32cf36b31e88EE190B1839",
            "brz.png",
            18,
        )
    val ethereumBnb =
        TokenDefinition(
            "ethereum",
            "BNB",
            "BNB (original ERC-20)",
            "0xB8c77482e45F1F44dE1745F52C74426C631bDD52",
            "bnb.svg",
            18,
        )
    val arbitrumBrz =
        TokenDefinition(
            "arbitrum",
            "BRZ",
            "Brazilian Digital Token",
            "0xa8940698fda5a07abaef4a5ccdf2f1bb525b47a2",
            "brz.png",
            18,
        )

    fun supportsHardwareBrz(asset: Asset) = bscBrz.matches(asset) || ethereumBrz.matches(asset)

    fun supportsAdditionalEvmToken(asset: Asset) =
        ethereumBnb.matches(asset) || arbitrumBrz.matches(asset)

    fun supportsHardwareEvmToken(asset: Asset) =
        supportsHardwareBrz(asset) || supportsAdditionalEvmToken(asset)

    val tokens =
        listOf(
            bscBrz,
            TokenDefinition(
                "solana",
                "BRLP",
                "Brazilian Real Liquid Pay",
                "7iUmRZ4CexRF1SG1V8Bt8zpCT1BsCqtCawhgX5gCDFAW",
                "brlp.png",
                6,
            ),
            TokenDefinition(
                "solana",
                "XRP",
                "Hex Trust Wrapped XRP",
                "6UpQcMAb5xMzxc7ZfPaVMgx3KqsvKZdT5U718BzD5We2",
                "xrp.svg",
                6,
            ),
            TokenDefinition(
                "solana",
                "MON",
                "Wrapped Monad",
                "CrAr4RRJMBVwRsZtT62pEhfA9H5utymC2mVx8e7FreP2",
                "mon.svg",
                8,
            ),
            ethereumBnb,
            TokenDefinition(
                "stellar",
                "USDC",
                "USD Coin",
                "USDC:GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN",
                "usdc.svg",
                7,
            ),
            TokenDefinition(
                "stellar",
                "BRLP",
                "Brazilian Real Liquid Pay",
                "BRLP:GDD3ZAU3GHMI44VCMXED2JHJ6ZBO33GQGXAJPNMTASIC24ZMNSQYNPRF",
                "brlp.png",
                7,
            ),
            TokenDefinition(
                "stellar",
                "BRZ",
                "Brazilian Digital Token",
                "BRZ:GABMA6FPH3OJXNTGWO7PROF7I5WPQUZOB4BLTBTP4FK6QV7HWISLIEO2",
                "brz.png",
                7,
            ),
            arbitrumBrz,
            TokenDefinition(
                "tron",
                "USDC",
                "USD Coin (legacy Tron)",
                "TEkxiTehnzSmSe2XqrBj4w32RUN966rdz8",
                "usdc.svg",
                6,
            ),
            TokenDefinition(
                "ethereum",
                "USDT",
                "Tether USD",
                "0xdac17f958d2ee523a2206206994597c13d831ec7",
                "usdterc20.svg",
            ),
            TokenDefinition(
                "tron",
                "USDT",
                "Tether USD",
                "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
                "usdttrc20.svg",
            ),
            TokenDefinition(
                "solana",
                "USDT",
                "Tether USD",
                "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
                "usdtsol.svg",
            ),
            TokenDefinition(
                "avalanche",
                "USDT",
                "Tether USD",
                "0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7",
                "usdtavax.svg",
            ),
            TokenDefinition(
                "ton",
                "USDT",
                "Tether USD",
                "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
                "usdtton.svg",
            ),
            TokenDefinition(
                "ethereum",
                "USDC",
                "USD Coin",
                "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                "usdc.svg",
            ),
            TokenDefinition(
                "arbitrum",
                "USDC",
                "USD Coin",
                "0xaf88d065e77c8cC2239327C5EDb3A432268e5831",
                "usdcarb.svg",
            ),
            TokenDefinition(
                "avalanche",
                "USDC",
                "USD Coin",
                "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E",
                "usdcavax.svg",
            ),
            TokenDefinition(
                "polygon",
                "USDC",
                "USD Coin",
                "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359",
                "usdcmatic.svg",
            ),
            TokenDefinition(
                "solana",
                "USDC",
                "USD Coin",
                "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
                "usdcsol.svg",
            ),
            TokenDefinition(
                "sui",
                "USDC",
                "USD Coin",
                "0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC",
                "usdc.svg",
            ),
            TokenDefinition(
                "base",
                "USDC",
                "USD Coin",
                "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913",
                "usdc.svg",
            ),
            TokenDefinition(
                "solana",
                "BRZ",
                "Brazilian Digital Token",
                "FtgGSFADXBtroxq8VCausXRr2of47QBf5AS1NtZCu4GD",
                "brz.png",
                4,
            ),
            TokenDefinition(
                "solana",
                "SKR",
                "Seeker",
                "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3",
                "skr.png",
                6,
            ),
            TokenDefinition(
                "solana",
                "USDS",
                "USDS",
                "USDSwr9ApdHk5bvJKMjzff41FfuX8bSxdKcR81vTwcA",
                "usds.png",
                6,
            ),
            TokenDefinition(
                "solana",
                "PYUSD",
                "PayPal USD",
                "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo",
                "pyusd.svg",
                6,
            ),
            TokenDefinition(
                "solana",
                "EURC",
                "EURC",
                "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr",
                "eurc.png",
                6,
            ),
            TokenDefinition(
                "solana",
                "LINK",
                "Chainlink",
                "LinkhB3afbBKb2EQQu7s7umdZceV3wcvAUJhQAfQ23L",
                "link.svg",
                9,
            ),
            TokenDefinition(
                "solana",
                "JUP",
                "Jupiter",
                "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
                "jup.png",
                6,
            ),
            TokenDefinition(
                "ethereum",
                "USDS",
                "USDS",
                "0xdC035D45d973E3EC169d2276DDab16f1e407384F",
                "usds.png",
                18,
            ),
            TokenDefinition(
                "ethereum",
                "USDe",
                "Ethena USDe",
                "0x4c9EDD5852cd905f086C759E8383e09bff1E68B3",
                "usde.svg",
                18,
            ),
            TokenDefinition(
                "ethereum",
                "RLUSD",
                "Ripple USD",
                "0x8292bb45bf1ee4d140127049757c2e0ff06317ed",
                "rlusd.png",
                18,
            ),
            TokenDefinition(
                "ethereum",
                "EURC",
                "EURC",
                "0x1aBaEA1f7C830bD89Acc67eC4af516284b1bC33c",
                "eurc.png",
                6,
            ),
            ethereumBrz,
            TokenDefinition(
                "base",
                "USDS",
                "USDS",
                "0x820C137fa70C8691f0e44Dc420a5e53c168921Dc",
                "usds.png",
                18,
            ),
            TokenDefinition(
                "base",
                "EURC",
                "EURC",
                "0x60a3E35Cc302bFA44Cb288Bc5a4F316Fdb1adb42",
                "eurc.png",
                6,
            ),
            TokenDefinition(
                "base",
                "LINK",
                "Chainlink",
                "0x88Fb150BDc53A65fe94Dea0c9BA0a6dAf8C6e196",
                "link.svg",
                18,
            ),
            TokenDefinition(
                "base",
                "BRZ",
                "Brazilian Digital Token",
                "0xE9185Ee218cae427aF7B9764A011bb89FeA761B4",
                "brz.png",
                18,
            ),
            TokenDefinition(
                "base",
                "USDe",
                "Ethena USDe",
                "0x5d3a1Ff2b6BAb83b63cd9AD0787074081a52ef34",
                "usde.svg",
                18,
            ),
            TokenDefinition(
                "base",
                "USDT",
                "Bridged Tether USD",
                "0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2",
                "usdterc20.svg",
                6,
            ),
            TokenDefinition(
                "hyperevm",
                "USDC",
                "USD Coin",
                "0xb88339CB7199b77E23DB6E890353E22632Ba630f",
                "usdc.svg",
                6,
            ),
            TokenDefinition(
                "hyperevm",
                "LINK",
                "Chainlink",
                "0x1AC2EE68b8d038C982C1E1f73F596927dd70De59",
                "link.svg",
                18,
            ),
            TokenDefinition(
                "arbitrum",
                "ARB",
                "Arbitrum",
                "0x912CE59144191C1204E64559FE8253a0e49E6548",
                "arbitrum.svg",
                18,
            ),
            TokenDefinition(
                "arbitrum",
                "USDT",
                "USDT0",
                "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9",
                "arbitrumusdt.svg",
                6,
            ),
            TokenDefinition(
                "arbitrum",
                "USDS",
                "USDS",
                "0x6491c05A82219b8D1479057361ff1654749b876b",
                "usds.png",
                18,
            ),
            TokenDefinition(
                "arbitrum",
                "LINK",
                "Chainlink",
                "0xf97f4df75117a78c1A5a0DBb814Af92458539FB4",
                "link.svg",
                18,
            ),
            TokenDefinition(
                "arbitrum",
                "USDe",
                "Ethena USDe",
                "0x5d3a1Ff2b6BAb83b63cd9AD0787074081a52ef34",
                "usde.svg",
                18,
            ),
            TokenDefinition(
                "bsc",
                "USDT",
                "Binance-Peg BSC-USD",
                "0x55d398326f99059fF775485246999027B3197955",
                "usdtbsc.svg",
                18,
            ),
            TokenDefinition(
                "bsc",
                "LINK",
                "Chainlink",
                "0x404460C6A5EdE2D891e8297795264fDe62ADBB75",
                "link.svg",
                18,
            ),
            TokenDefinition(
                "bsc",
                "USDC",
                "Binance-Peg USD Coin",
                "0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d",
                "usdc.svg",
                18,
            ),
            TokenDefinition(
                "bsc",
                "USDe",
                "Ethena USDe",
                "0x5d3a1Ff2b6BAb83b63cd9AD0787074081a52ef34",
                "usde.svg",
                18,
            ),
            TokenDefinition(
                "bsc",
                "XRP",
                "Binance-Peg XRP",
                "0x1d2f0da169ceb9fc7b3144628db156f3f6c60dbe",
                "xrp.svg",
                18,
            ),
            TokenDefinition(
                "polygon",
                "USDT",
                "USDT0",
                "0xc2132D05D31c914a87C6611C10748AEb04B58e8F",
                "usdt0.svg",
                6,
            ),
            TokenDefinition(
                "polygon",
                "LINK",
                "Chainlink",
                "0xb0897686c545045aFc77CF20eC7A532E3120E0F1",
                "link.svg",
                18,
            ),
            TokenDefinition(
                "polygon",
                "BRZ",
                "Brazilian Digital Token",
                "0x4eD141110F6EeeAbA9A1df36d8c26f684d2475Dc",
                "brz.png",
                18,
            ),
            TokenDefinition(
                "ripple",
                "RLUSD",
                "Ripple USD",
                "524C555344000000000000000000000000000000.rMxCKbEDwqr76QuheSUMdEGf4B9xJ8m5De",
                "rlusd.png",
                15,
            ),
            TokenDefinition(
                "ripple",
                "USDC",
                "USD Coin",
                "5553444300000000000000000000000000000000.rGm7WCVp9gb4jZHWTEtGUr4dd74z2XuWhE",
                "usdc.svg",
                15,
            ),
            // Cardano uses the registry subject: 28-byte policy ID followed by the hex asset name.
            TokenDefinition(
                "cardano",
                "SNEK",
                "Snek",
                "279c909f348e533da5808898f87f9a14bb2c3dfbbacccd631d927a3f534e454b",
                "snek.png",
                0,
            ),
            TokenDefinition(
                "cardano",
                "MIN",
                "Minswap",
                "29d222ce763455e3d7a09a665ce554f00ac89d2e99a1a83d267170c64d494e",
                "min.png",
                6,
            ),
            TokenDefinition(
                "cardano",
                "IAG",
                "Iagon",
                "5d16cc1a177b5d9ba9cfa9793b07e60f1fb70fea1f8aef064415d114494147",
                "iag.png",
                6,
            ),
            TokenDefinition(
                "cardano",
                "WMTX",
                "World Mobile Token",
                "e5a42a1a1d3d1da71b0449663c32798725888d2eb0843c4dabeca05a576f726c644d6f62696c65546f6b656e58",
                "wmtx.png",
                6,
            ),
            TokenDefinition(
                "cardano",
                "DJED",
                "Djed",
                "8db269c3ec630e06ae29f74bc39edd1f87c819f1056206e879a1cd61446a65644d6963726f555344",
                "djed.png",
                6,
            ),
            TokenDefinition(
                "robinhood",
                "USDG",
                "Global Dollar",
                "0x5fc5360D0400a0Fd4f2af552ADD042D716F1d168",
                "usdg.png",
                6,
            ),
        )

    fun forNetwork(coin: String) = tokens.filter { it.coin == coin }

    fun definition(asset: Asset) = tokens.firstOrNull { it.matches(asset) }

    /**
     * Registration adds only missing assets, preserving existing balances, IDs and other wallet
     * modes.
     */
    fun missing(assets: List<Asset>): List<Asset> =
        assets
            .filter { it.contract.isEmpty() }
            .flatMap { native ->
                forNetwork(native.coin)
                    .filter { token ->
                        assets.none {
                            it.accountId == native.accountId &&
                                it.address == native.address &&
                                token.matches(it)
                        }
                    }
                    .map { it.asset(native) }
            }

    fun sources(selected: Asset, assets: List<Asset>): List<Asset> {
        val token = definition(selected) ?: return listOf(selected)
        return assets.filter { candidate -> definition(candidate)?.symbol == token.symbol }
    }
}
