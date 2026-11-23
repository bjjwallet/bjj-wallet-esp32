package pub.airlock.data

import kotlinx.coroutines.delay
import pub.airlock.domain.*

/** Offline fixture adapter. It has the same contract as the production hardware adapter. */
class DemoHardwareWallet : HardwareWallet {
    override val simulated = true

    override suspend fun discover() = listOf(HardwareDevice("fixture", "Test device"))

    override suspend fun connect(deviceId: String) {
        connected = true
    }

    private var connected = true
    private val coins =
        CoinCatalog.coins.filter { coin -> PublicNetworks.entries.any { it.coin == coin.coin } }

    override suspend fun synchronize(mode: WalletMode): WalletData {
        delay(350)
        connected = true
        val data = coins.map { createAccount(mode, it) }
        return WalletData(data.flatMap { it.accounts }, data.flatMap { it.assets })
    }

    override suspend fun availableCoins() = coins

    override suspend fun createAccount(mode: WalletMode, coin: CoinDefinition): WalletData {
        requireInput(connected, InputError.DEVICE_DISCONNECTED)
        require(coin in coins)
        val account =
            Account(
                "${mode.name}:${coin.coin}",
                coin.coin,
                coin.name,
                coin.symbol,
                coin.icon,
                mode,
                "MOCK_PUBLIC_KEY_${coin.symbol}",
                PublicNetworks.get(coin.coin).path,
                enabled = coin.coin in PublicNetworks.initiallyEnabled,
            )
        val asset = addAsset(account, 0)
        val balances =
            mapOf(
                "BTC" to "0.0245",
                "ETH" to "1.25",
                "TRX" to "250",
                "BNB" to "0.8",
                "XRP" to "2.996704",
            )
        val native =
            asset.copy(
                balance = if (mode == WalletMode.HIDDEN) "0" else balances[coin.symbol] ?: "0"
            )
        val tokens =
            Stablecoins.forNetwork(coin.coin).map { token ->
                token
                    .asset(native)
                    .copy(
                        balance =
                            if (mode == WalletMode.HIDDEN) "0"
                            else if (token.symbol == "USDT") "100" else "50"
                    )
            }
        return WalletData(listOf(account), listOf(native) + tokens)
    }

    override suspend fun addAsset(account: Account, index: Int): Asset {
        requireInput(connected, InputError.DEVICE_DISCONNECTED)
        // These addresses remain visibly invalid, including when copied/shared as a QR code.
        return DemoPublicKeyDeriver().derive(account, index.toLong())
    }

    override suspend fun deviceInfo(): DeviceInfo {
        delay(150)
        return DeviceInfo("BJJ-Cold-Wallet-Mock", "1.0.25", 100, connected = connected)
    }

    override suspend fun control(command: DeviceCommand) {
        connected = true
        delay(200)
        connected = command != DeviceCommand.SHUTDOWN
    }

    override suspend fun sign(quote: TransferQuote, paymentPin: String, mode: WalletMode): String {
        connected = true
        PinPolicy.validate(paymentPin)
        delay(200)
        return "MOCK_SIGNED:${quote.asset.coin}:${quote.asset.contract}:${quote.asset.id}:${quote.destination}:${quote.amount}:${quote.fee}"
    }

    override suspend fun disconnect() {
        connected = false
    }
}

/** Offline fixtures keep intentionally invalid addresses and never handle real keys. */
class DemoPublicKeyDeriver : PublicKeyDeriver {
    override suspend fun derive(account: Account, index: Long): Asset {
        requireInput(account.xpub.startsWith("MOCK_PUBLIC_KEY_"), InputError.PUBLIC_DATA_INVALID)
        requireInput(
            PublicNetworks.get(account.coin).extended || index < 10,
            InputError.PUBLIC_DATA_SYNC_REQUIRED,
        )
        val coin = CoinCatalog.get(account.coin)
        return Asset(
            "${account.id}:$index",
            account.id,
            account.coin,
            coin.symbol,
            coin.name,
            "MOCK-ADDRESS-${coin.symbol}-${account.mode.name}-$index",
            coin.icon,
            coin.decimals,
            derivationPath = PublicNetworks.path(account.coin, index),
        )
    }
}

class MockWalletNetwork : WalletNetwork {
    override suspend fun canReceiveIssuedAsset(asset: Asset) = true

    override suspend fun balance(asset: Asset) = asset.balance

    override suspend fun prices(symbols: List<String>): Map<String, String> =
        mapOf(
                "BTC" to "95172.21",
                "ETH" to "3317.44",
                "TRX" to "0.31965",
                "BNB" to "949.13",
                "XRP" to "2.0529",
                "DOGE" to "0.18",
                "SOL" to "145.20",
                "BCH" to "320.15",
                "TON" to "3.45",
                "AVAX" to "21.50",
                "ETC" to "18.30",
                "LTC" to "86.50",
                "POL" to "0.35",
                "SUI" to "2.50",
                "DASH" to "24.50",
                "USDT" to "1",
                "USDC" to "1",
            )
            .filterKeys { it in symbols }

    override suspend fun estimate(
        asset: Asset,
        destination: String,
        amount: String,
    ): TransferQuote {
        delay(150)
        // Deterministic fee fixtures. Network strategies will supply production quotes through this
        // port.
        return TransferQuote(
            asset,
            destination,
            amount,
            if (CoinCatalog.get(asset.coin).decimals >= 8) "0.0000029" else "0.001",
        )
    }

    override suspend fun broadcast(signedTransaction: String): String {
        require(signedTransaction.startsWith("MOCK_SIGNED:"))
        delay(150)
        return "MOCK-TX-${kotlin.random.Random.nextLong().toULong()}"
    }
}
