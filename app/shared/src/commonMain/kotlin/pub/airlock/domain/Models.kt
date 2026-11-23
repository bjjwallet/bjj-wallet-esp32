package pub.airlock.domain

import kotlinx.serialization.Serializable

@Serializable
enum class WalletMode {
    STANDARD,
    HIDDEN,
}

@Serializable
data class Account(
    val id: String,
    val coin: String,
    val name: String,
    val symbol: String,
    val icon: String,
    val mode: WalletMode,
    val xpub: String = "",
    val path: String = "",
    val enabled: Boolean = true,
    val nextReceiveIndex: Long = 1,
    val cachedAddresses: List<PublicAddress> = emptyList(),
    val pendingPublicData: Boolean = false,
)

@Serializable data class PublicAddress(val address: String, val path: String, val index: Long)

data class HardwareDevice(val id: String, val name: String)

@Serializable
data class Asset(
    val id: String,
    val accountId: String,
    val coin: String,
    val symbol: String,
    val name: String,
    val address: String,
    val icon: String,
    val decimals: Int,
    val balance: String = "0",
    val contract: String = "",
    val derivationPath: String = "",
    val unlockedBalance: String? = null,
)

@Serializable
enum class TransferStatus {
    SIMULATED,
    SUBMITTED,
    CONFIRMED,
    FAILED,
}

@Serializable
data class Transfer(
    val id: String,
    val assetId: String,
    val amount: String,
    val destination: String,
    val incoming: Boolean = false,
    val status: TransferStatus = TransferStatus.SUBMITTED,
    val nonce: String? = null,
)

data class AddressActivity(val balance: String, val transfers: List<Transfer> = emptyList())

data class TransactionPage(val transfers: List<Transfer>, val nextCursor: String?)

@Serializable
data class PinRecord(val salt: String, val standardHash: String, val hiddenHash: String)

/** A first-connection choice staged in memory until the public export commits. */
data class AppSecurityConfiguration(val pin: PinRecord?)

@Serializable
data class VaultSnapshot(
    val schemaVersion: Int = 1,
    val activeWalletId: String? = null,
    val savedWallets: List<SavedWallet> = emptyList(),
    val sourceDeviceId: String? = null,
    val pins: PinRecord? = null,
    val appSecurityConfigured: Boolean = false,
    val appPin: PinRecord? = null,
    val hardwarePinFailures: Int = 0,
    val hardwareBlockedUntil: Long = 0,
    val accounts: List<Account> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val apiUrl: String = "https://api.airlock.pub",
    // null follows the device; a code is an explicit, persisted user selection.
    val language: String? = null,
    val theme: AppTheme = AppTheme.SYSTEM,
    val networkSettings: NetworkSettings = NetworkSettings(),
    val pendingSubmissions: List<PendingSubmission> = emptyList(),
    val moneroWatchWallets: Map<WalletMode, MoneroWatchWallet> = emptyMap(),
    val moneroScanHeights: Map<WalletMode, Long> = emptyMap(),
)

data class WalletData(val accounts: List<Account>, val assets: List<Asset>)

data class DeviceInfo(
    val name: String,
    val firmware: String,
    val battery: Int,
    val platform: String = "—",
    val hardware: String = "—",
    val display: String = "—",
    val cpuTemperature: String = "—",
    val connected: Boolean = true,
)

@Serializable
data class CoinDefinition(
    val coin: String,
    val name: String,
    val symbol: String,
    val icon: String,
    val decimals: Int,
    val protocol: String,
    val networkIcon: String = icon,
)

@Serializable
data class TransferQuote(
    val asset: Asset,
    val destination: String,
    val amount: String,
    val fee: String,
    val feeCurrency: CoinDefinition = CoinCatalog.get(asset.coin),
    val evmPlan: EvmTransactionPlan? = null,
    val utxoPlan: UtxoTransactionPlan? = null,
    val solanaPlan: SolanaTransactionPlan? = null,
    val cardanoPlan: CardanoTransactionPlan? = null,
    val bitcoinSignatureTest: Boolean = false,
    val ledgerPlan: kotlinx.serialization.json.JsonObject? = null,
)

@Serializable data class SolanaTransactionPlan(val blockhash: String, val rentAtomic: Long = 0)

@Serializable
data class CardanoInput(
    val txHash: String,
    val index: Int,
    val lovelace: Long,
    val tokenQuantity: Long = 0,
)

@Serializable
data class CardanoTransactionPlan(
    val inputs: List<CardanoInput>,
    val change: Long,
    val ttl: Long,
    val recipientLovelace: Long = 0,
)

fun interface Ed25519Verifier {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

enum class DeviceCommand {
    REBOOT,
    SHUTDOWN,
}

enum class TransferStage {
    CONNECTION_REQUIRED,
    CONNECTING,
    AUTHORIZING,
    APPROVAL,
    VERIFYING,
    SUBMITTING,
}

/** API boundary. UI and transaction orchestration are identical for both build configurations. */
interface WalletNetwork {
    val lastProvider: NetworkProvider?
        get() = null

    val lastSubmissionStatus: TransferStatus
        get() = TransferStatus.SUBMITTED

    suspend fun prices(symbols: List<String>): Map<String, String>

    suspend fun canReceiveIssuedAsset(asset: Asset): Boolean =
        throw InputException(InputError.NETWORK_UNSUPPORTED)

    suspend fun balance(asset: Asset): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun addressActivity(asset: Asset): AddressActivity = AddressActivity(balance(asset))

    suspend fun balances(assets: List<Asset>): Map<String, String> =
        assets.associate { it.id to balance(it) }

    suspend fun transactionHistory(asset: Asset, cursor: String? = null): TransactionPage =
        throw InputException(InputError.NETWORK_UNSUPPORTED)

    suspend fun estimate(asset: Asset, destination: String, amount: String): TransferQuote

    suspend fun broadcast(signedTransaction: String): String

    suspend fun broadcast(signedTransaction: String, quote: TransferQuote): String =
        broadcast(signedTransaction)

    /** null is only for the offline simulator/legacy integrations without a submission journal. */
    fun transactionId(signedTransaction: String, quote: TransferQuote): String? = null
}

/**
 * Platform implementations must authenticate ciphertext and write atomically. Never fall back to
 * plaintext.
 */
data class VaultRead(val contents: String?)

interface VaultStorage {
    @Throws(Exception::class) fun read(): VaultRead

    @Throws(Exception::class) fun write(value: String)
}

interface PinCrypto {
    @Throws(Exception::class) fun randomHex(bytes: Int): String

    @Throws(Exception::class) fun derive(pin: String, saltHex: String): String
}

interface HardwareWallet {
    val requiresSigningAccessPin: Boolean
        get() = false

    suspend fun withSigningAccessPin(pin: String, sign: suspend () -> String): String = sign()

    val signingProgress: kotlinx.coroutines.flow.StateFlow<TransferStage>?
        get() = null

    val simulated: Boolean

    /** Local routing hint only; switching never changes the wallet selected on the hardware. */
    fun bindWalletDevice(deviceId: String?) = Unit

    suspend fun discover(): List<HardwareDevice> = emptyList()

    suspend fun connect(deviceId: String): Unit =
        throw InputException(InputError.DEVICE_DISCONNECTED)

    suspend fun connectMonero(deviceId: String, onPairing: () -> Unit) = connect(deviceId)

    /** Device decides whether public export needs its own PIN. */
    suspend fun requiresAccessPin(): Boolean = false

    suspend fun unlockAccess(pin: String): Boolean =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun synchronize(mode: WalletMode): WalletData

    /** One user operation, with a distinct public set for each wallet. */
    suspend fun synchronizeWallets(): Map<WalletMode, WalletData> =
        WalletMode.entries.associateWith { synchronize(it) }

    /**
     * Returns every stable wallet and both modes when the device supports wallet_sync_version=1.
     */
    suspend fun synchronizePublicWallets(): WalletSynchronization =
        WalletSynchronization.Legacy(synchronizeWallets())

    suspend fun synchronizePublicWallets(
        skipMoneroWatchWalletIds: Set<String>
    ): WalletSynchronization = synchronizePublicWallets()

    suspend fun synchronizeWallet(walletId: String, mode: WalletMode): WalletData =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun supportsMoneroKeyImages(): Boolean = false

    suspend fun supportsMoneroSigning(): Boolean = false

    suspend fun signMoneroTransfer(
        walletId: String,
        mode: WalletMode,
        quote: TransferQuote,
        unsigned: MoneroUnsignedTransfer,
    ): MoneroSignedTransfer = throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun moneroReceiveAddress(walletId: String, mode: WalletMode): MoneroReceiveAddress =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun moneroWatchWallet(walletId: String, mode: WalletMode): MoneroWatchWallet =
        throw InputException(InputError.MONERO_VIEW_SYNC_REQUIRED)

    suspend fun moneroSignKeyImages(
        walletId: String,
        mode: WalletMode,
        outputs: MoneroOutputs,
    ): List<MoneroKeyImage> = throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun deviceInfo(): DeviceInfo

    suspend fun disconnect()

    suspend fun availableCoins(): List<CoinDefinition> =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun createAccount(mode: WalletMode, coin: CoinDefinition): WalletData =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun addAsset(account: Account, index: Int): Asset =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun control(command: DeviceCommand): Unit =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun signLedger(quote: TransferQuote, walletId: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun signBrz(quote: TransferQuote, walletId: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun signPolygon(quote: TransferQuote, walletId: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun signEthereum(quote: TransferQuote, walletId: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun signSolana(quote: TransferQuote, walletId: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun signCardano(quote: TransferQuote, walletId: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun sign(quote: TransferQuote, paymentPin: String, mode: WalletMode): String =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)
}

/** Public derivation only. Secret keys and signing remain on the AirLock hardware. */
interface PublicKeyDeriver {
    suspend fun derive(account: Account, index: Long): Asset
}
