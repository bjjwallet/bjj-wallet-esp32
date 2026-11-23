package pub.airlock.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pub.airlock.data.Bip32PublicKeyDeriver
import pub.airlock.data.VaultRepository
import pub.airlock.data.network.BitcoinSignatureTest
import pub.airlock.data.validateApiUrl
import pub.airlock.domain.*

enum class HardwareOperation {
    VERIFY_PIN,
    IMPORT_PUBLIC_DATA,
    CONNECT_MONERO,
    PAIR_MONERO,
    APPROVE_MONERO,
}

enum class Screen {
    LOADING,
    SETUP,
    LOGIN,
    SYNC,
    HARDWARE_SELECT,
    APP_SECURITY,
    HARDWARE_PIN,
    PIN_BLOCKED,
    ACCOUNTS,
    ADD_ACCOUNT,
    WALLETS,
    MONERO_SYNC,
    MONERO_DEVICE,
    MONERO_PIN,
    ASSET,
    TRANSACTIONS,
    RECEIVE,
    SEND,
    CONFIRM,
    DEVICE,
    SETTINGS,
    FATAL,
}

data class PendingTransferUi(
    val txId: String,
    val assetId: String,
    val coin: String,
    val symbol: String,
    val amount: String,
    val destination: String,
    val canRetry: Boolean,
)

data class TransferDraft(
    val assetId: String,
    val coin: String,
    val destination: String,
    val amount: String,
)

internal val WalletUiState.canSendMonero: Boolean
    get() =
        moneroNetwork == MoneroNetwork.STAGENET &&
            moneroScannerAvailable &&
            moneroWatchImported &&
            moneroHeight != null &&
            selectedAsset?.unlockedBalance?.let { it != "0" } == true

data class WalletUiState(
    val transferStage: TransferStage? = null,
    val transferConnectionError: InputError? = null,
    val signingQuote: TransferQuote? = null,
    val refreshingTransactions: Boolean = false,
    val chainHistory: Map<String, TransactionPage> = emptyMap(),
    val historyErrors: Map<String, InputError> = emptyMap(),
    val bitcoinTestSignatureVerified: Boolean = false,
    val screen: Screen = Screen.LOADING,
    val mode: WalletMode? = null,
    val savedWallets: List<WalletChoice> = emptyList(),
    val activeWalletId: String? = null,
    val activeWalletName: String = "",
    val resetSyncConfirmation: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val ecashHistory: Map<String, List<Transfer>> = emptyMap(),
    val moralisHistory: Map<String, TransactionPage> = emptyMap(),
    val moralisHistoryErrors: Map<String, InputError> = emptyMap(),
    val selectedAccount: Account? = null,
    val selectedAsset: Asset? = null,
    val selectedCatalogSymbol: String? = null,
    val assetBackScreen: Screen = Screen.ACCOUNTS,
    val showingAddresses: Boolean = false,
    val busy: Boolean = false,
    val error: InputError? = null,
    val message: WalletMessage? = null,
    val device: DeviceInfo? = null,
    val apiUrl: String = "https://api.airlock.pub",
    val language: String? = null,
    val theme: AppTheme = AppTheme.SYSTEM,
    val simulated: Boolean = false,
    val prices: Map<String, String> = emptyMap(),
    val publicAccountCoins: Set<String> = emptySet(),
    val quote: TransferQuote? = null,
    val hardwareDevices: List<HardwareDevice> = emptyList(),
    val networkSettings: NetworkSettings = NetworkSettings(),
    val networkFailure: InputError? = null,
    val failedBalances: Map<String, InputError> = emptyMap(),
    val pendingTransfers: List<PendingTransferUi> = emptyList(),
    val transferDraft: TransferDraft? = null,
    val blockedSeconds: Long = 0,
    val biometricAvailable: Boolean = false,
    val hardwareConnectionAccepted: Boolean = false,
    val hardwareOperation: HardwareOperation? = null,
    val moneroSync: Boolean = false,
    val moneroScannerAvailable: Boolean = false,
    val moneroWatchImported: Boolean = false,
    val moneroNetwork: MoneroNetwork? = null,
    val moneroHeight: Long? = null,
    val moneroRestoreHeight: Long? = null,
    val moneroSending: Boolean = false,
    val moneroNeedsImages: Boolean = false,
) {
    fun supportsAccount(coin: String) =
        simulated ||
            coin == "monero" ||
            if (coin == "cardano") coin in publicAccountCoins
            else ProviderCoverage.canReadNative(networkSettings.preferred, coin)

    fun supportsToken(token: TokenDefinition) =
        supportsAccount(token.coin) &&
            (simulated || ProviderCoverage.canRead(networkSettings.preferred, token))

    val catalogAccounts: List<Account>
        get() {
            val walletMode = mode ?: return emptyList()
            val enabled = accounts.associateBy { it.coin }
            return CoinCatalog.coins.map { coin ->
                enabled[coin.coin]
                    ?: Account(
                        "${walletMode.name}:${coin.coin}",
                        coin.coin,
                        coin.name,
                        coin.symbol,
                        coin.icon,
                        walletMode,
                        enabled = false,
                        pendingPublicData = true,
                    )
            } + accounts.filter { CoinCatalog.find(it.coin) == null }
        }

    val selectedCurrencyAssets: List<Asset>
        get() {
            val selected = selectedAsset ?: return emptyList()
            return assets.filter {
                it.accountId == selected.accountId && it.contract == selected.contract
            }
        }

    val availableCoins: List<CoinDefinition>
        get() {
            val enabled = accounts.map { it.coin }.toSet()
            return CoinCatalog.coins.filter { it.coin !in enabled }
        }

    val canSendSelectedAsset: Boolean
        get() =
            selectedAsset?.let {
                if (it.coin == "monero") canSendMonero
                else simulated || ProviderCoverage.canSubmit(networkSettings.preferred, it)
            } ?: false

    val selectedAssetHasSendSupport: Boolean
        get() {
            val account = selectedAccount ?: return false
            if (account.coin == "monero" || simulated) return true
            val provider = networkSettings.preferred
            selectedAsset?.let {
                return ProviderCoverage.canSubmit(provider, it)
            }
            val symbol = selectedCatalogSymbol ?: account.symbol
            return if (symbol == account.symbol) ProviderCoverage.canSubmit(provider, account.coin)
            else
                Stablecoins.forNetwork(account.coin).any {
                    it.symbol == symbol && ProviderCoverage.canSubmit(provider, it)
                }
        }

    val canRetryNetworkFailure: Boolean
        get() =
            transferDraft?.let { draft ->
                assets.any {
                    it.id == draft.assetId &&
                        (simulated || ProviderCoverage.canSubmit(networkSettings.preferred, it))
                }
            } ?: true
}

class WalletViewModel(
    private val vault: VaultRepository,
    private val hardware: HardwareWallet,
    private val network: WalletNetwork,
    private val deriver: PublicKeyDeriver = Bip32PublicKeyDeriver(),
    private val moneroScanner: MoneroScanner? = null,
    private val biometrics: AppBiometrics = NoAppBiometrics,
    private val transferTestMode: Boolean = TransferTestConfig.TEST_MODE,
    private val transactionDiagnostics: (String) -> Unit = {},
    private val currentTimeMillis: () -> Long = {
        kotlin.time.Clock.System.now().toEpochMilliseconds()
    },
) : ViewModel() {
    val requiresSigningAccessPin: Boolean
        get() = hardware.requiresSigningAccessPin

    private val mutableState = MutableStateFlow(WalletUiState(simulated = hardware.simulated))

    /**
     * Stage labels and error enums only: never log credentials, addresses, amounts or signed bytes.
     */
    private fun traceBitcoin(
        event: String,
        coin: String? = mutableState.value.selectedAsset?.coin,
    ) {
        if (coin == "bitcoin") runCatching { transactionDiagnostics("bitcoin.$event") }
    }

    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var failedUnlocks = 0
    private var generation = 0L
    private var blockTicker: Job? = null
    private var hardwareRequiresPin: Boolean? = null
    private var connected = false
    private var pendingAppSecurity: AppSecurityConfiguration? = null
    private var appAuthorized = false
    private var pendingWalletName: String? = null
    private var syncDeviceId: String? = null

    private class MoneroSync(
        val localId: String,
        val coldId: String,
        val mode: WalletMode,
        val outputs: MoneroOutputs?,
        val addressOnly: Boolean,
        val payment: MoneroPayment? = null,
    )

    private class MoneroPayment(val quote: TransferQuote, val unsigned: MoneroUnsignedTransfer)

    private var moneroPayment: MoneroPayment? = null
    private var pendingMonero: MoneroSync? = null
    private var resumeMoneroWalletId: String? = null

    private fun now() = currentTimeMillis()

    private fun isFixture(value: String) =
        listOf("MOCK_", "MOCK-", "DEMO_", "DEMO-").any(value::startsWith)

    init {
        initialize()
    }

    fun initialize() = runOperation {
        try {
            // Discard public fixture records left by earlier debug builds.
            val saved = vault.snapshot()
            val mockIds = saved.accounts.filter { isFixture(it.xpub) }.map { it.id }.toSet()
            if (
                !saved.appSecurityConfigured &&
                    (mockIds.isNotEmpty() || saved.assets.any { isFixture(it.address) })
            )
                vault.update {
                    val assets =
                        it.assets.filterNot { a -> a.accountId in mockIds || isFixture(a.address) }
                    it.copy(
                        accounts = it.accounts.filterNot { a -> a.id in mockIds },
                        assets = assets,
                        transfers =
                            it.transfers.filter { t ->
                                t.status != TransferStatus.SIMULATED &&
                                    assets.any { a -> a.id == t.assetId }
                            },
                        pendingSubmissions =
                            it.pendingSubmissions.filterNot { tx ->
                                isFixture(tx.signedTransaction)
                            },
                    )
                }
            val legacy = vault.snapshot()
            if (
                !legacy.appSecurityConfigured && legacy.pins != null && legacy.accounts.isNotEmpty()
            ) {
                vault.update {
                    it.copy(
                        appSecurityConfigured = true,
                        appPin = legacy.pins.copy(hiddenHash = ""),
                        pins = null,
                    )
                }
            }
            val snapshot = vault.snapshot()
            val initialScreen =
                when {
                    !snapshot.appSecurityConfigured -> Screen.SYNC
                    snapshot.appPin != null -> Screen.LOGIN
                    else -> Screen.LOADING
                }
            mutableState.update {
                it.copy(
                    screen = initialScreen,
                    apiUrl = snapshot.apiUrl,
                    language = AppLanguage.explicitSelection(snapshot.language),
                    theme = snapshot.theme,
                    biometricAvailable = biometrics.available(),
                )
            }
            if (snapshot.appSecurityConfigured && snapshot.appPin == null) {
                appAuthorized = true
                openSavedWallet()
            }
            if (snapshot.hardwareBlockedUntil > now()) startBlockCountdown()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(screen = Screen.FATAL, error = InputError.VAULT_OPEN) }
        }
    }

    /** Legacy entry point retained for stored-vault migration tools. */
    fun setup(standard: String, hidden: String) = runOperation {
        vault.configurePins(standard, hidden)
        vault.configureAppSecurity(standard)
        mutableState.update { it.copy(screen = Screen.LOGIN) }
    }

    fun unlock(pin: String) = runOperation {
        if (state.value.screen != Screen.LOGIN) return@runOperation
        val snapshot = vault.snapshot()
        val accepted =
            if (snapshot.appSecurityConfigured) vault.unlockApp(pin) else vault.unlock(pin) != null
        if (!accepted) {
            failedUnlocks++
            if (failedUnlocks >= 5) delay(5_000)
            throw InputException(InputError.INCORRECT_PIN)
        }
        failedUnlocks = 0
        appAuthorized = true
        openSavedWallet()
    }

    fun unlockBiometric(reason: String, pinLabel: String) = runOperation {
        if (state.value.screen != Screen.LOGIN || !biometrics.available()) return@runOperation
        val accepted =
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                biometrics.authenticate(reason, pinLabel) { result ->
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                }
            }
        if (accepted) {
            appAuthorized = true
            openSavedWallet()
        }
    }

    private suspend fun openSavedWallet(device: DeviceInfo? = null) {
        val mode = WalletMode.STANDARD
        vault.registerBaseAccounts()
        vault.registerStablecoins(mode)
        val snapshot = vault.snapshot()
        val wallet = ProviderCoverage.wallet(snapshot, mode)
        val moneroAccount =
            resumeMoneroWalletId
                ?.takeIf { it == snapshot.activeWalletId }
                ?.let { wallet.accounts.firstOrNull { account -> account.coin == "monero" } }
        resumeMoneroWalletId = null
        val screen =
            when {
                moneroAccount != null -> Screen.MONERO_SYNC
                wallet.accounts.isEmpty() -> Screen.SYNC
                else -> Screen.ACCOUNTS
            }
        hardware.bindWalletDevice(snapshot.sourceDeviceId)
        mutableState.update {
            it.copy(
                mode = mode,
                accounts = wallet.accounts,
                assets = wallet.assets,
                savedWallets = snapshot.savedWallets.map { WalletChoice(it.id, it.name) },
                activeWalletId = snapshot.activeWalletId,
                activeWalletName =
                    snapshot.savedWallets
                        .firstOrNull { wallet -> wallet.id == snapshot.activeWalletId }
                        ?.name
                        .orEmpty(),
                networkSettings = snapshot.networkSettings,
                selectedAccount = moneroAccount,
                selectedAsset = null,
                device = device,
                assetBackScreen = Screen.ACCOUNTS,
                selectedCatalogSymbol = null,
                showingAddresses = false,
                moneroScannerAvailable = moneroScanner != null,
                moneroWatchImported = mode in snapshot.moneroWatchWallets,
                moneroNetwork = snapshot.moneroNetwork(mode),
                moneroHeight = snapshot.moneroScanHeights[mode],
                moneroNeedsImages = false,
                moneroRestoreHeight = snapshot.moneroWatchWallets[mode]?.scanFromHeight,
                quote = null,
                transferDraft = null,
                transfers = emptyList(),
                ecashHistory = emptyMap(),
                chainHistory = emptyMap(),
                historyErrors = emptyMap(),
                moralisHistory = emptyMap(),
                moralisHistoryErrors = emptyMap(),
                pendingTransfers = emptyList(),
                prices = emptyMap(),
                failedBalances = emptyMap(),
                networkFailure = null,
                publicAccountCoins = emptySet(),
                screen = screen,
            )
        }
        refreshTransfers()
        if (screen == Screen.ACCOUNTS && wallet.assets.isNotEmpty()) refreshPrices()
    }

    fun requestResetSynchronization() {
        val current = state.value
        if (current.busy || current.screen != Screen.SETTINGS || current.mode == null) return
        mutableState.update { it.copy(resetSyncConfirmation = true, error = null, message = null) }
    }

    fun cancelResetSynchronization() {
        if (!state.value.busy) mutableState.update { it.copy(resetSyncConfirmation = false) }
    }

    fun confirmResetAndSynchronize() = runOperation {
        if (
            !state.value.resetSyncConfirmation ||
                state.value.screen != Screen.SETTINGS ||
                !appAuthorized
        )
            return@runOperation
        // Keep the existing screen and data if disconnecting or the durable write fails.
        val biometricAvailable = biometrics.available()
        hardware.disconnect()
        vault.resetForSynchronization()
        moneroScanner?.clear()
        hardware.bindWalletDevice(null)
        blockTicker?.cancel()
        blockTicker = null
        failedUnlocks = 0
        appAuthorized = false
        connected = false
        hardwareRequiresPin = null
        pendingAppSecurity = null
        pendingWalletName = null
        syncDeviceId = null
        mutableState.update {
            WalletUiState(
                screen = Screen.SYNC,
                busy = true,
                simulated = hardware.simulated,
                biometricAvailable = biometricAvailable,
            )
        }
    }

    fun selectWallet(id: String) = runOperation {
        if (state.value.mode == null || state.value.savedWallets.none { it.id == id })
            return@runOperation
        if (id == state.value.activeWalletId) return@runOperation
        vault.selectWallet(id)
        moneroScanner?.clear()
        openSavedWallet()
    }

    fun renameWallet(id: String, name: String) = runOperation {
        if (state.value.mode == null || state.value.savedWallets.none { it.id == id })
            return@runOperation
        val saved = vault.renameWallet(id, name)
        mutableState.update {
            it.copy(
                savedWallets = saved.savedWallets.map { WalletChoice(it.id, it.name) },
                activeWalletName =
                    saved.savedWallets
                        .firstOrNull { wallet -> wallet.id == saved.activeWalletId }
                        ?.name
                        .orEmpty(),
            )
        }
    }

    fun beginWalletSynchronization(name: String? = null) = runOperation {
        if (state.value.mode == null) return@runOperation
        if (name != null) requireInput(validWalletName(name), InputError.INVALID_WALLET_NAME)
        pendingWalletName = name?.trim()
        mutableState.update {
            it.copy(
                screen = Screen.SYNC,
                selectedAccount = null,
                selectedAsset = null,
                quote = null,
                transferDraft = null,
            )
        }
    }

    fun synchronize() = runOperation {
        if (state.value.screen != Screen.SYNC) return@runOperation
        val devices = hardware.discover()
        mutableState.update {
            it.copy(
                hardwareDevices = devices,
                screen = if (devices.isEmpty()) Screen.SYNC else Screen.HARDWARE_SELECT,
            )
        }
        if (devices.isEmpty()) throw InputException(InputError.DEVICE_DISCONNECTED)
    }

    fun showMoneroSync() =
        mutableState.update {
            if (it.screen == Screen.ASSET && it.selectedAccount?.coin == "monero" && !it.busy)
                it.copy(screen = Screen.MONERO_SYNC, error = null, message = null)
            else it
        }

    fun setMoneroRestoreHeight(height: Long?) = runOperation {
        val current = state.value
        val mode = current.mode ?: return@runOperation
        val walletId = current.activeWalletId ?: return@runOperation
        if (
            current.screen != Screen.MONERO_SYNC ||
                current.selectedAccount?.coin != "monero" ||
                !appAuthorized
        )
            return@runOperation
        vault.setMoneroRestoreHeight(mode, walletId, height)
        moneroScanner?.clear()
        publishMonero(mode)
        mutableState.update { it.copy(moneroNeedsImages = false) }
    }

    fun cancelMoneroScan() {
        if (state.value.screen == Screen.MONERO_SYNC && state.value.busy) operation?.cancel()
    }

    fun synchronizeMonero() = startMoneroSync(addressOnly = false)

    fun synchronizeMoneroAddress() = startMoneroSync(addressOnly = true)

    private fun startMoneroSync(addressOnly: Boolean) = runOperation {
        val current = state.value
        val mode = current.mode ?: return@runOperation
        if (
            current.screen != Screen.MONERO_SYNC ||
                current.selectedAccount?.coin != "monero" ||
                !appAuthorized
        )
            return@runOperation
        val snapshot = vault.snapshot()
        val watch = snapshot.moneroWatchWallets[mode]
        val outputs =
            if (watch != null && !addressOnly) {
                val scanner =
                    moneroScanner ?: throw InputException(InputError.MONERO_SCANNER_UNAVAILABLE)
                scanner.exportOutputs(watch)
                    ?: run {
                        if (vault.refreshMonero(mode, scanner = scanner)) {
                            publishMonero(mode)
                            mutableState.update { it.copy(moneroNeedsImages = false) }
                            return@runOperation
                        }
                        scanner.exportOutputs(watch)
                            ?: throw InputException(InputError.MONERO_SCAN_PENDING)
                    }
            } else null
        if (outputs != null) mutableState.update { it.copy(moneroNeedsImages = true) }
        discoverMonero(snapshot, mode, outputs, addressOnly)
    }

    private suspend fun discoverMonero(
        snapshot: VaultSnapshot,
        mode: WalletMode,
        outputs: MoneroOutputs?,
        addressOnly: Boolean,
        payment: MoneroPayment? = null,
    ) {
        val wallet =
            snapshot.savedWallets.singleOrNull { it.id == snapshot.activeWalletId }
                ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        val coldId =
            wallet.coldWalletId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        val deviceId =
            snapshot.sourceDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        val devices =
            withTimeoutOrNull(30_000L) { hardware.discover() }?.filter { it.id == deviceId }
                ?: throw InputException(InputError.DEVICE_DISCONNECTED)
        requireInput(devices.isNotEmpty(), InputError.DEVICE_DISCONNECTED)
        pendingMonero = MoneroSync(wallet.id, coldId, mode, outputs, addressOnly, payment)
        mutableState.update {
            it.copy(
                screen = Screen.MONERO_DEVICE,
                hardwareDevices = devices,
                moneroSync = true,
                moneroSending = payment != null,
            )
        }
    }

    fun connectMonero(deviceId: String) = runOperation {
        if (
            state.value.screen != Screen.MONERO_DEVICE ||
                state.value.hardwareDevices.none { it.id == deviceId }
        )
            return@runOperation
        val sync = pendingMonero ?: throw InputException(InputError.OPERATION_FAILED)
        try {
            mutableState.update { it.copy(hardwareOperation = HardwareOperation.CONNECT_MONERO) }
            withTimeoutOrNull(65_000L) {
                hardware.connectMonero(deviceId) {
                    mutableState.update {
                        it.copy(hardwareOperation = HardwareOperation.PAIR_MONERO)
                    }
                }
            } ?: throw InputException(InputError.DEVICE_DISCONNECTED)
            connected = true
            mutableState.update { it.copy(hardwareOperation = HardwareOperation.APPROVE_MONERO) }
            hardwareRequiresPin = hardware.requiresAccessPin()
            if (sync.payment != null)
                requireInput(hardware.supportsMoneroSigning(), InputError.INTEGRATION_UNAVAILABLE)
            else if (!sync.addressOnly)
                requireInput(
                    hardware.supportsMoneroKeyImages(),
                    if (sync.outputs == null) InputError.MONERO_VIEW_SYNC_REQUIRED
                    else InputError.MONERO_KEY_IMAGES_UNAVAILABLE,
                )
            if (vault.snapshot().hardwareBlockedUntil > now()) startBlockCountdown()
            else if (hardwareRequiresPin == true)
                mutableState.update { it.copy(screen = Screen.MONERO_PIN) }
            else synchronizeMoneroConnected(sync)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failedStage = state.value.hardwareOperation
            val linkFailed =
                !connected && (e as? InputException)?.reason == InputError.DEVICE_DISCONNECTED
            finishMoneroConnection()
            if (linkFailed)
                throw InputException(
                    if (failedStage == HardwareOperation.PAIR_MONERO)
                        InputError.MONERO_PAIRING_FAILED
                    else InputError.MONERO_CONNECTION_FAILED
                )
            throw e
        }
    }

    fun synchronizeWith(deviceId: String) = runOperation {
        if (
            state.value.screen != Screen.HARDWARE_SELECT ||
                state.value.hardwareDevices.none { it.id == deviceId }
        )
            return@runOperation
        try {
            hardwareRequiresPin = null
            pendingAppSecurity = null
            mutableState.update { it.copy(hardwareConnectionAccepted = false) }
            syncDeviceId = deviceId
            hardware.connect(deviceId)
            connected = true
            val firstConnection = !vault.snapshot().appSecurityConfigured
            if (firstConnection) mutableState.update { it.copy(screen = Screen.APP_SECURITY) }
            // GATT connection alone is insufficient: wait for the wallet's RPC response.
            hardwareRequiresPin = hardware.requiresAccessPin()
            mutableState.update { it.copy(hardwareConnectionAccepted = true) }
            if (!firstConnection) continueHardwareConnection()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connected = false
            hardwareRequiresPin = null
            pendingAppSecurity = null
            withContext(NonCancellable) { hardware.disconnect() }
            mutableState.update {
                it.copy(
                    screen = Screen.SYNC,
                    hardwareDevices = emptyList(),
                    hardwareConnectionAccepted = false,
                )
            }
            throw e
        }
    }

    fun configureAppSecurity(pin: String?) = runOperation {
        if (
            state.value.screen != Screen.APP_SECURITY ||
                !connected ||
                !state.value.hardwareConnectionAccepted
        )
            return@runOperation
        pendingAppSecurity = vault.prepareAppSecurity(pin)
        appAuthorized = true
        continueHardwareConnection()
    }

    private suspend fun continueHardwareConnection() {
        requireInput(
            connected && state.value.hardwareConnectionAccepted && hardwareRequiresPin != null,
            InputError.DEVICE_DISCONNECTED,
        )
        if (vault.snapshot().hardwareBlockedUntil > now()) {
            startBlockCountdown()
            return
        }
        if (hardwareRequiresPin == true)
            mutableState.update { it.copy(screen = Screen.HARDWARE_PIN) }
        else synchronizeConnected()
    }

    fun unlockHardware(pin: String) = runOperation {
        val screen = state.value.screen
        if (screen !in setOf(Screen.HARDWARE_PIN, Screen.MONERO_PIN) || !connected)
            return@runOperation
        val snapshot = vault.snapshot()
        if (snapshot.hardwareBlockedUntil > now()) {
            startBlockCountdown()
            return@runOperation
        }
        HardwarePinPolicy.validate(pin)
        mutableState.update { it.copy(hardwareOperation = HardwareOperation.VERIFY_PIN) }
        try {
            // Only an explicit negative device response counts as an incorrect PIN.
            if (hardware.unlockAccess(pin)) {
                vault.update { it.copy(hardwarePinFailures = 0, hardwareBlockedUntil = 0) }
                when (screen) {
                    Screen.MONERO_PIN ->
                        synchronizeMoneroConnected(
                            pendingMonero ?: throw InputException(InputError.OPERATION_FAILED)
                        )
                    Screen.HARDWARE_PIN -> synchronizeConnected()
                    else -> error("Unexpected PIN screen")
                }
            } else {
                val failures = snapshot.hardwarePinFailures + 1
                val seconds = HardwarePinBackoff.seconds(failures)
                vault.update {
                    it.copy(
                        hardwarePinFailures = failures,
                        hardwareBlockedUntil = if (seconds == 0L) 0 else now() + seconds * 1000,
                    )
                }
                if (seconds > 0) startBlockCountdown()
                else mutableState.update { it.copy(error = InputError.INCORRECT_PIN) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (screen == Screen.MONERO_PIN) {
                finishMoneroConnection()
            } else if ((e as? InputException)?.reason == InputError.DEVICE_DISCONNECTED) {
                connected = false
                hardwareRequiresPin = null
                pendingAppSecurity = null
                mutableState.update {
                    it.copy(
                        screen = Screen.SYNC,
                        hardwareConnectionAccepted = false,
                        hardwareDevices = emptyList(),
                    )
                }
                withContext(NonCancellable) { hardware.disconnect() }
            }
            throw e
        }
    }

    private fun startBlockCountdown() {
        blockTicker?.cancel()
        mutableState.update { it.copy(screen = Screen.PIN_BLOCKED, error = null) }
        blockTicker =
            viewModelScope.launch {
                while (true) {
                    val remaining =
                        ((vault.snapshot().hardwareBlockedUntil - now() + 999) / 1000)
                            .coerceAtLeast(0)
                    mutableState.update { it.copy(blockedSeconds = remaining) }
                    if (remaining == 0L) break
                    delay(250)
                }
                if (connected)
                    mutableState.update {
                        it.copy(
                            screen = if (it.moneroSync) Screen.MONERO_PIN else Screen.HARDWARE_PIN
                        )
                    }
                else initialize()
            }
    }

    private suspend fun synchronizeConnected() {
        mutableState.update { it.copy(hardwareOperation = HardwareOperation.IMPORT_PUBLIC_DATA) }
        val mode = WalletMode.STANDARD
        try {
            val deviceId = syncDeviceId
            val skipMoneroWatchWalletIds =
                vault
                    .snapshot()
                    .savedWallets
                    .asSequence()
                    .filter {
                        deviceId != null &&
                            it.sourceDeviceId == deviceId &&
                            (WalletMode.STANDARD in it.moneroWatchWallets ||
                                it.assets.any { asset ->
                                    asset.id == "STANDARD:monero:0" && asset.coin == "monero"
                                })
                    }
                    .mapNotNull { it.coldWalletId }
                    .toSet()
            val wallets = hardware.synchronizePublicWallets(skipMoneroWatchWalletIds)
            val device = hardware.deviceInfo()
            vault.commitPublicWalletSynchronization(
                wallets,
                pendingAppSecurity,
                pendingWalletName,
                syncDeviceId,
            )
            pendingWalletName = null
            mutableState.update { it.copy(hardwareDevices = emptyList()) }
            openSavedWallet(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(screen = Screen.SYNC) }
            throw e
        } finally {
            connected = false
            hardwareRequiresPin = null
            pendingAppSecurity = null
            mutableState.update { it.copy(hardwareConnectionAccepted = false) }
            withContext(NonCancellable) { hardware.disconnect() }
        }
    }

    private suspend fun synchronizeMoneroConnected(sync: MoneroSync) {
        mutableState.update { it.copy(hardwareOperation = HardwareOperation.APPROVE_MONERO) }
        if (sync.payment != null) {
            val pending =
                try {
                    val quote = sync.payment.quote
                    requireInput(
                        vault.snapshot().activeWalletId == sync.localId,
                        InputError.PUBLIC_DATA_CHANGED,
                    )
                    requireNoPending(quote.asset)
                    val signed =
                        hardware.signMoneroTransfer(
                            sync.coldId,
                            sync.mode,
                            quote,
                            sync.payment.unsigned,
                        )
                    PendingSubmission(signed.txid, signed.hex, quote, sync.mode).also { submission
                        ->
                        vault.update { snapshot ->
                            requireInput(
                                snapshot.activeWalletId == sync.localId &&
                                    snapshot.pendingSubmissions.none {
                                        sameSender(it.quote.asset, quote.asset)
                                    },
                                InputError.SUBMISSION_PENDING,
                            )
                            snapshot.copy(
                                pendingSubmissions = snapshot.pendingSubmissions + submission
                            )
                        }
                    }
                } finally {
                    finishMoneroConnection()
                }
            refreshTransfers()
            submitPending(pending)
            return
        }
        val images =
            try {
                requireInput(
                    vault.snapshot().activeWalletId == sync.localId,
                    InputError.PUBLIC_DATA_CHANGED,
                )
                if (sync.addressOnly) {
                    val address = hardware.moneroReceiveAddress(sync.coldId, sync.mode)
                    vault.saveMoneroReceiveAddress(sync.mode, address, sync.localId)
                    publishMonero(sync.mode)
                    return
                }
                if (sync.outputs == null) {
                    val watch = hardware.moneroWatchWallet(sync.coldId, sync.mode)
                    vault.saveMoneroWatchWallet(sync.mode, watch, sync.localId)
                    publishMonero(sync.mode)
                    null
                } else hardware.moneroSignKeyImages(sync.coldId, sync.mode, sync.outputs)
            } finally {
                finishMoneroConnection()
            }
        val scanner = moneroScanner ?: return
        val verified = vault.refreshMonero(sync.mode, images, scanner)
        if (verified) {
            publishMonero(sync.mode)
            mutableState.update { it.copy(moneroNeedsImages = false) }
        } else {
            val watch = vault.snapshot().moneroWatchWallets[sync.mode]
            val needsImages = watch != null && scanner.exportOutputs(watch) != null
            mutableState.update { it.copy(moneroNeedsImages = needsImages) }
            if (images != null) throw InputException(InputError.MONERO_SCAN_PENDING)
        }
    }

    private suspend fun finishMoneroConnection() {
        if (!state.value.moneroSync) return
        connected = false
        hardwareRequiresPin = null
        val sending = pendingMonero?.payment != null
        pendingMonero = null
        mutableState.update {
            it.copy(
                screen = if (sending) Screen.ASSET else Screen.MONERO_SYNC,
                moneroSync = false,
                moneroSending = false,
                quote = if (sending) null else it.quote,
                hardwareDevices = emptyList(),
                hardwareConnectionAccepted = false,
            )
        }
        withContext(NonCancellable) { hardware.disconnect() }
    }

    private fun VaultSnapshot.moneroNetwork(mode: WalletMode): MoneroNetwork? {
        moneroWatchWallets[mode]?.let {
            return it.network
        }
        val asset = assets.singleOrNull { it.id == "${mode.name}:monero:0" } ?: return null
        return MoneroNetwork.entries.firstOrNull { isMoneroAddress(asset.address, it) }
    }

    private suspend fun publishMonero(mode: WalletMode) {
        val snapshot = vault.snapshot()
        val wallet = ProviderCoverage.wallet(snapshot, mode)
        mutableState.update { state ->
            state.copy(
                accounts = wallet.accounts,
                assets = wallet.assets,
                selectedAccount =
                    wallet.accounts.firstOrNull { it.id == state.selectedAccount?.id },
                moneroWatchImported = mode in snapshot.moneroWatchWallets,
                moneroHeight = snapshot.moneroScanHeights[mode],
                moneroNetwork = snapshot.moneroNetwork(mode),
                moneroRestoreHeight = snapshot.moneroWatchWallets[mode]?.scanFromHeight,
            )
        }
        refreshTransfers(snapshot)
    }

    fun navigate(screen: Screen) {
        if (
            state.value.mode == null || state.value.busy || state.value.screen == Screen.PIN_BLOCKED
        )
            return
        require(screen in listOf(Screen.ACCOUNTS, Screen.DEVICE, Screen.SETTINGS, Screen.SYNC))
        mutableState.update {
            it.copy(
                screen = screen,
                error = null,
                message = null,
                selectedAccount = null,
                selectedAsset = null,
                selectedCatalogSymbol = null,
                showingAddresses = false,
                quote = null,
            )
        }
        if (screen == Screen.SYNC) pendingWalletName = null
        if (screen == Screen.DEVICE) loadDevice()
    }

    fun open(account: Account) {
        val current = state.value
        if (current.busy || account !in current.catalogAccounts) return
        val asset =
            if (CoinCatalog.isStandalone(account.coin)) {
                current.assets.firstOrNull { it.accountId == account.id && it.contract.isEmpty() }
            } else null
        val screen =
            when {
                account.coin == "monero" && account.enabled && asset == null -> Screen.MONERO_SYNC
                CoinCatalog.isStandalone(account.coin) -> Screen.ASSET
                else -> Screen.WALLETS
            }
        mutableState.update {
            it.copy(
                screen = screen,
                selectedAccount = account,
                selectedAsset = asset,
                selectedCatalogSymbol = null,
                assetBackScreen = Screen.ACCOUNTS,
                showingAddresses = false,
                quote = null,
            )
        }
        if (asset?.coin == "ecash" && !current.simulated && current.supportsAccount(asset.coin))
            refreshBalances(clearFeedback = false, ecashOnly = true)
        else refreshSelectedMoralisAsset()
    }

    fun openCatalogAsset(symbol: String) =
        mutableState.update {
            if (it.busy || it.screen != Screen.WALLETS || it.showingAddresses) it
            else
                it.copy(
                    screen = Screen.ASSET,
                    selectedAsset = null,
                    selectedCatalogSymbol = symbol,
                    assetBackScreen = Screen.WALLETS,
                    quote = null,
                )
        }

    fun openAccountAddresses() =
        mutableState.update {
            val asset = it.selectedAsset ?: return@update it
            if (it.busy || it.screen != Screen.ASSET || asset.coin == "monero") it
            else it.copy(screen = Screen.WALLETS, showingAddresses = true, quote = null)
        }

    fun open(asset: Asset) {
        if (state.value.busy || asset !in state.value.assets) return
        mutableState.update {
            it.copy(
                screen = Screen.ASSET,
                selectedAsset = asset,
                selectedAccount = it.accounts.first { a -> a.id == asset.accountId },
                assetBackScreen =
                    when {
                        it.showingAddresses -> it.assetBackScreen
                        it.screen == Screen.WALLETS -> Screen.WALLETS
                        else -> Screen.ACCOUNTS
                    },
                selectedCatalogSymbol = null,
                showingAddresses = false,
                quote = null,
            )
        }
        refreshSelectedMoralisAsset()
    }

    fun back() {
        val current = state.value
        if (
            current.moneroSync && current.screen in setOf(Screen.MONERO_DEVICE, Screen.MONERO_PIN)
        ) {
            cancelMoneroConnection()
            return
        }
        if (current.screen == Screen.MONERO_SYNC && current.busy) {
            cancelMoneroScan()
            return
        }
        goBack()
    }

    private fun cancelMoneroConnection() {
        val previous = operation
        val expectedGeneration = ++generation
        previous?.cancel()
        val sending = pendingMonero?.payment != null
        pendingMonero = null
        mutableState.update { it.copy(busy = true, moneroSync = false, moneroSending = false) }
        operation =
            viewModelScope.launch {
                try {
                    previous?.join()
                    hardware.disconnect()
                } finally {
                    if (generation == expectedGeneration) {
                        connected = false
                        hardwareRequiresPin = null
                        mutableState.update {
                            it.copy(
                                screen = if (sending) Screen.ASSET else Screen.MONERO_SYNC,
                                quote = if (sending) null else it.quote,
                                busy = false,
                                error = null,
                                hardwareDevices = emptyList(),
                                hardwareConnectionAccepted = false,
                                hardwareOperation = null,
                            )
                        }
                    }
                }
            }
    }

    private fun goBack() =
        mutableState.update {
            if (
                it.busy ||
                    it.screen in setOf(Screen.PIN_BLOCKED, Screen.HARDWARE_PIN, Screen.APP_SECURITY)
            )
                return@update it
            val selectedAsset =
                if (it.screen == Screen.MONERO_SYNC) {
                    it.assets.firstOrNull { asset -> asset.accountId == it.selectedAccount?.id }
                } else it.selectedAsset
            val screen =
                when (it.screen) {
                    Screen.ASSET -> it.assetBackScreen
                    Screen.MONERO_SYNC ->
                        if (selectedAsset != null) Screen.ASSET else Screen.ACCOUNTS
                    Screen.WALLETS -> if (it.showingAddresses) Screen.ASSET else Screen.ACCOUNTS
                    Screen.RECEIVE,
                    Screen.SEND,
                    Screen.TRANSACTIONS -> Screen.ASSET
                    Screen.CONFIRM -> Screen.SEND
                    Screen.HARDWARE_SELECT ->
                        if (it.accounts.isEmpty()) Screen.SYNC else Screen.ACCOUNTS
                    else -> Screen.ACCOUNTS
                }
            it.copy(
                screen = screen,
                selectedAsset = selectedAsset,
                showingAddresses = false,
                error = null,
                quote = null,
                moneroSync = false,
            )
        }

    fun showAddAccount() = runOperation {
        val mode = state.value.mode ?: return@runOperation
        val publicCoins =
            vault.publicAccounts(mode).filterNot { it.pendingPublicData }.map { it.coin }.toSet()
        mutableState.update {
            it.copy(screen = Screen.ADD_ACCOUNT, publicAccountCoins = publicCoins)
        }
    }

    fun addAccount(coin: CoinDefinition) = runOperation {
        val mode = state.value.mode ?: return@runOperation
        val current = state.value
        if (current.screen != Screen.ADD_ACCOUNT || coin !in CoinCatalog.coins) return@runOperation
        if (coin !in current.availableCoins) return@runOperation
        vault.enableAccount(mode, coin.coin)
        val snapshot = vault.snapshot()
        val wallet = ProviderCoverage.wallet(snapshot, mode)
        mutableState.update { it.copy(accounts = wallet.accounts, assets = wallet.assets) }
        refreshTransfers(snapshot)
    }

    fun removeAccount(coin: CoinDefinition) = runOperation {
        val mode = state.value.mode ?: return@runOperation
        if (
            state.value.screen != Screen.ADD_ACCOUNT ||
                state.value.accounts.none { it.coin == coin.coin }
        )
            return@runOperation
        vault.disableAccount(mode, coin.coin)
        val snapshot = vault.snapshot()
        val wallet = ProviderCoverage.wallet(snapshot, mode)
        mutableState.update { it.copy(accounts = wallet.accounts, assets = wallet.assets) }
        refreshTransfers(snapshot)
    }

    fun addAsset() = runOperation {
        val account = state.value.selectedAccount ?: return@runOperation
        vault.addPublicAddress(account.mode, account.id, deriver)
        val wallet = ProviderCoverage.wallet(vault.snapshot(), account.mode)
        mutableState.update {
            it.copy(
                accounts = wallet.accounts,
                selectedAccount = wallet.accounts.first { a -> a.id == account.id },
                assets = wallet.assets,
            )
        }
    }

    fun receive() {
        val current = state.value
        val asset = current.selectedAsset ?: return
        if (current.busy) return
        if (asset.coin == "ripple" && asset.contract.isNotEmpty()) {
            runOperation {
                requireInput(
                    network.canReceiveIssuedAsset(asset),
                    InputError.XRPL_TRUSTLINE_REQUIRED,
                )
                mutableState.update { it.copy(screen = Screen.RECEIVE, quote = null) }
            }
        } else {
            mutableState.update { it.copy(screen = Screen.RECEIVE, quote = null) }
        }
    }

    fun send() =
        mutableState.update {
            if (it.busy || !it.canSendSelectedAsset) it
            else it.copy(screen = Screen.SEND, quote = null, bitcoinTestSignatureVerified = false)
        }

    fun cancelTransfer() {
        mutableState.update {
            if (it.busy) it
            else it.copy(screen = Screen.ASSET, quote = null, error = null, transferDraft = null)
        }
    }

    fun selectTransferSource(assetId: String) {
        val current = state.value
        val selected = current.selectedAsset ?: return
        if (current.busy || current.screen != Screen.SEND) return
        val candidate =
            Stablecoins.sources(selected, current.assets).firstOrNull {
                it.id == assetId &&
                    (current.simulated ||
                        ProviderCoverage.canSubmit(current.networkSettings.preferred, it))
            } ?: return
        val account =
            current.accounts.firstOrNull { it.id == candidate.accountId && it.mode == current.mode }
                ?: return
        mutableState.update {
            it.copy(
                selectedAsset = candidate,
                selectedAccount = account,
                quote = null,
                error = null,
                bitcoinTestSignatureVerified = false,
            )
        }
    }

    fun deviceCommand(command: DeviceCommand) = runOperation {
        hardware.control(command)
        val info = hardware.deviceInfo()
        mutableState.update { it.copy(device = info, message = WalletMessage.DEVICE_COMMAND) }
    }

    private suspend fun refreshPrices() {
        try {
            val current = state.value
            if (!current.simulated && current.networkSettings.preferred == NetworkProvider.MORALIS)
                return
            val symbols =
                current.assets
                    .filter {
                        it.coin != "monero" &&
                            (current.simulated ||
                                ProviderCoverage.canRead(current.networkSettings.preferred, it))
                    }
                    .map { it.symbol }
                    .distinct()
            if (symbols.isEmpty()) return
            val prices = network.prices(symbols)
            mutableState.update { it.copy(prices = prices) }
        } catch (e: InputException) {
            if (e.reason != InputError.INTEGRATION_UNAVAILABLE)
                mutableState.update { it.copy(networkFailure = e.reason) }
        }
    }

    fun refreshVisibleBalances() {
        val current = state.value
        when (current.screen) {
            Screen.ACCOUNTS -> refreshBalances(clearFeedback = false)
            Screen.WALLETS,
            Screen.ASSET ->
                if (current.selectedAccount?.coin != "monero")
                    refreshBalances(clearFeedback = false)
            else -> Unit
        }
    }

    /** Failed values remain cached, and no provider is substituted. */
    fun refreshBalances() = refreshBalances(clearFeedback = true)

    fun refreshSelectedMoralisAsset() {
        val current = state.value
        if (
            !current.simulated &&
                current.networkSettings.preferred == NetworkProvider.MORALIS &&
                current.selectedAsset?.let {
                    ProviderCoverage.canRead(NetworkProvider.MORALIS, it)
                } == true
        )
            refreshBalances(clearFeedback = false, selectedOnly = true)
    }

    fun loadMoreMoralisHistory() = runOperation { updateMoralisHistory(loadMore = true) }

    private suspend fun updateMoralisHistory(loadMore: Boolean = false) {
        val current = state.value
        if (
            current.simulated ||
                current.networkSettings.preferred != NetworkProvider.MORALIS ||
                current.screen !in setOf(Screen.ASSET, Screen.TRANSACTIONS)
        )
            return
        val assets =
            current.selectedCurrencyAssets.filter {
                ProviderCoverage.canReadHistory(NetworkProvider.MORALIS, it)
            }
        val limit = Semaphore(3)
        val results = coroutineScope {
            assets
                .mapNotNull { asset ->
                    val previous = current.moralisHistory[asset.id]
                    val cursor =
                        if (loadMore && previous != null)
                            previous.nextCursor ?: return@mapNotNull null
                        else null
                    async {
                        limit.withPermit {
                            try {
                                val page = network.transactionHistory(asset, cursor)
                                val transfers =
                                    if (loadMore) previous?.transfers.orEmpty() + page.transfers
                                    else page.transfers
                                Triple(
                                    asset.id,
                                    page.copy(transfers = transfers.distinctBy { it.id }),
                                    null,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Triple(
                                    asset.id,
                                    null,
                                    (e as? InputException)?.reason
                                        ?: InputError.PROVIDER_UNAVAILABLE,
                                )
                            }
                        }
                    }
                }
                .awaitAll()
        }
        mutableState.update { state ->
            state.copy(
                moralisHistory =
                    state.moralisHistory +
                        results.mapNotNull { (id, page, _) -> page?.let { id to it } },
                moralisHistoryErrors =
                    (state.moralisHistoryErrors - results.map { it.first }.toSet()) +
                        results.mapNotNull { (id, _, failure) -> failure?.let { id to it } },
            )
        }
    }

    private fun refreshBalances(
        clearFeedback: Boolean,
        ecashOnly: Boolean = false,
        selectedOnly: Boolean = false,
    ) =
        runOperation(clearFeedback = clearFeedback) {
            updateBalances(ecashOnly, selectedOnly)
            if (clearFeedback || selectedOnly) updateMoralisHistory()
        }

    fun showTransactions() {
        if (state.value.busy || state.value.selectedAsset == null) return
        mutableState.update { it.copy(screen = Screen.TRANSACTIONS) }
        refreshTransactions()
    }

    fun refreshTransactions() = runOperation {
        if (state.value.selectedAsset == null) return@runOperation
        mutableState.update { it.copy(refreshingTransactions = true) }
        try {
            updateBalances(selectedOnly = true)
            refreshTransfers()
            updateMoralisHistory()
            val current = state.value
            if (
                !current.simulated &&
                    current.networkSettings.preferred in
                        setOf(NetworkProvider.BLOCKSTREAM, NetworkProvider.MEMPOOL)
            ) {
                for (asset in
                    current.selectedCurrencyAssets.filter {
                        it.coin == "bitcoin" && it.contract.isEmpty()
                    }) {
                    try {
                        val page = network.transactionHistory(asset)
                        mutableState.update {
                            it.copy(
                                chainHistory = it.chainHistory + (asset.id to page),
                                historyErrors = it.historyErrors - asset.id,
                            )
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        val reason =
                            (failure as? InputException)?.reason ?: InputError.PROVIDER_UNAVAILABLE
                        mutableState.update {
                            it.copy(historyErrors = it.historyErrors + (asset.id to reason))
                        }
                    }
                }
            }
        } finally {
            mutableState.update { it.copy(refreshingTransactions = false) }
        }
    }

    private suspend fun updateBalances(ecashOnly: Boolean = false, selectedOnly: Boolean = false) {
        val current = state.value
        val mode = current.mode ?: return
        val provider = current.networkSettings.preferred
        val moralis = provider == NetworkProvider.MORALIS && !current.simulated
        val assets =
            (if (selectedOnly) current.selectedCurrencyAssets else current.assets).filter {
                it.coin != "monero" &&
                    (!ecashOnly || it.coin == "ecash") &&
                    (current.simulated || ProviderCoverage.canRead(provider, it))
            }
        if (assets.isEmpty()) return
        val groups =
            if (moralis) assets.groupBy { it.coin to it.address }.values else assets.map(::listOf)
        val limit = Semaphore(3)
        val results = coroutineScope {
            groups
                .map { group ->
                    async {
                        limit.withPermit {
                            try {
                                if (moralis) {
                                    val balances = network.balances(group)
                                    group.map {
                                        Triple(
                                            it.id,
                                            AddressActivity(balances.getValue(it.id)),
                                            null,
                                        )
                                    }
                                } else {
                                    val asset = group.single()
                                    val activity =
                                        if (asset.coin == "ecash" && !current.simulated)
                                            network.addressActivity(asset)
                                        else AddressActivity(network.balance(asset))
                                    listOf(Triple(asset.id, activity, null))
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                group.map {
                                    Triple(
                                        it.id,
                                        null,
                                        (e as? InputException)?.reason
                                            ?: InputError.PROVIDER_UNAVAILABLE,
                                    )
                                }
                            }
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }
        val balances =
            results.mapNotNull { (id, value, _) -> value?.let { id to it.balance } }.toMap()
        val ecashIds = assets.filter { it.coin == "ecash" }.map { it.id }.toSet()
        val history =
            results
                .mapNotNull { (id, value, _) ->
                    value?.takeIf { id in ecashIds }?.let { id to it.transfers }
                }
                .toMap()
        val failures = results.mapNotNull { (id, _, failure) -> failure?.let { id to it } }.toMap()
        if (balances.isNotEmpty())
            vault.update { snapshot ->
                snapshot.copy(
                    assets =
                        snapshot.assets.map { asset ->
                            balances[asset.id]?.let { balance ->
                                Amount.atomic(balance, asset.decimals)
                                asset.copy(balance = balance)
                            } ?: asset
                        }
                )
            }
        val snapshot = vault.snapshot()
        val wallet = ProviderCoverage.wallet(snapshot, mode)
        val refreshed = wallet.assets
        mutableState.update {
            val failedBalances =
                if (ecashOnly || selectedOnly)
                    (it.failedBalances - assets.map { a -> a.id }.toSet()) + failures
                else failures
            it.copy(
                accounts = wallet.accounts,
                assets = refreshed,
                failedBalances = failedBalances,
                networkFailure = failedBalances.values.firstOrNull(),
                ecashHistory = it.ecashHistory + history,
                moneroHeight = snapshot.moneroScanHeights[mode],
                selectedAsset =
                    it.selectedAsset?.let { a -> refreshed.firstOrNull { n -> n.id == a.id } },
            )
        }
        if (!ecashOnly) refreshPrices()
    }

    fun lock() {
        val current = state.value
        if (current.screen == Screen.MONERO_SYNC || current.moneroSync) {
            resumeMoneroWalletId = current.activeWalletId
        }
        pendingMonero = null
        moneroScanner?.clear()
        if (state.value.screen == Screen.PIN_BLOCKED) {
            appAuthorized = false
            connected = false
            hardwareRequiresPin = null
            pendingAppSecurity = null
            viewModelScope.launch { hardware.disconnect() }
            return
        }
        generation++
        pendingWalletName = null
        syncDeviceId = null
        val interrupted = operation
        interrupted?.cancel()
        appAuthorized = false
        connected = false
        hardwareRequiresPin = null
        pendingAppSecurity = null
        mutableState.update {
            WalletUiState(
                screen = Screen.LOGIN,
                apiUrl = it.apiUrl,
                language = it.language,
                theme = it.theme,
                simulated = hardware.simulated,
                biometricAvailable = biometrics.available(),
            )
        }
        viewModelScope.launch {
            interrupted?.join()
            hardware.disconnect()
            initialize()
        }
    }

    private fun loadDevice() = runOperation {
        val info = hardware.deviceInfo()
        mutableState.update { it.copy(device = info) }
    }

    fun saveServer(url: String) = runOperation {
        val clean = validateApiUrl(url)
        vault.update { it.copy(apiUrl = clean) }
        mutableState.update { it.copy(apiUrl = clean, message = WalletMessage.SERVER_SAVED) }
    }

    fun saveNetworkSettings(
        settings: NetworkSettings,
        url: String,
        keepAssetOpen: Boolean = false,
    ) = runOperation {
        if (state.value.mode == null) return@runOperation
        val clean = settings.validated()
        val server = validateApiUrl(url)
        val next = vault.update { it.copy(networkSettings = clean, apiUrl = server) }
        val wallet = ProviderCoverage.wallet(next, state.value.mode ?: return@runOperation)
        mutableState.update {
            it.copy(
                networkSettings = clean,
                apiUrl = server,
                quote = null,
                accounts = wallet.accounts,
                assets = wallet.assets,
                selectedAccount = if (keepAssetOpen) it.selectedAccount else null,
                selectedAsset = if (keepAssetOpen) it.selectedAsset else null,
                selectedCatalogSymbol = if (keepAssetOpen) it.selectedCatalogSymbol else null,
                showingAddresses = false,
                prices = emptyMap(),
                ecashHistory = emptyMap(),
                moralisHistory = emptyMap(),
                moralisHistoryErrors = emptyMap(),
                chainHistory = emptyMap(),
                historyErrors = emptyMap(),
                failedBalances =
                    it.failedBalances.filterKeys { id ->
                        wallet.assets.any { asset -> asset.id == id }
                    },
                screen =
                    if (
                        !keepAssetOpen &&
                            it.screen in
                                setOf(
                                    Screen.ASSET,
                                    Screen.TRANSACTIONS,
                                    Screen.WALLETS,
                                    Screen.RECEIVE,
                                    Screen.SEND,
                                    Screen.CONFIRM,
                                    Screen.ADD_ACCOUNT,
                                )
                    )
                        Screen.ACCOUNTS
                    else it.screen,
                message = WalletMessage.SERVER_SAVED,
            )
        }
        refreshTransfers(next)
        if (keepAssetOpen && clean.preferred == NetworkProvider.MORALIS && !state.value.simulated) {
            updateBalances(selectedOnly = true)
            updateMoralisHistory()
        } else if (
            keepAssetOpen && state.value.selectedAsset?.coin == "ecash" && !state.value.simulated
        ) {
            updateBalances(ecashOnly = true)
        }
    }

    fun retryNetworkFailure() {
        if (state.value.busy || state.value.mode == null || !state.value.canRetryNetworkFailure)
            return
        val draft = state.value.transferDraft
        val asset = state.value.assets.firstOrNull { it.id == draft?.assetId }
        if (draft != null && asset != null) {
            if (
                !ProviderCoverage.canSubmit(state.value.networkSettings.preferred, asset) &&
                    !state.value.simulated
            )
                return
            open(asset)
            send()
            prepareTransfer(draft.destination, draft.amount)
        } else refreshBalances()
    }

    fun theme(value: AppTheme) = runOperation {
        vault.update { it.copy(theme = value) }
        mutableState.update { it.copy(theme = value) }
    }

    fun language(value: String?) = runOperation {
        requireInput(
            value == null || AppLanguage.entries.any { it.code == value },
            InputError.INVALID_LANGUAGE,
        )
        vault.update { it.copy(language = value) }
        mutableState.update { it.copy(language = value) }
    }

    fun changePin(old: String, new: String) = runOperation {
        vault.changePin(state.value.mode ?: error("Wallet locked"), old, new)
        mutableState.update { it.copy(message = WalletMessage.PIN_UPDATED) }
    }

    fun prepareTransfer(destination: String, amount: String) = runOperation {
        if (state.value.screen != Screen.SEND) return@runOperation
        mutableState.update { it.copy(quote = null, bitcoinTestSignatureVerified = false) }
        val asset = state.value.selectedAsset ?: error("Select an asset")
        traceBitcoin("prepare.start")
        requireInput(state.value.canSendSelectedAsset, InputError.NETWORK_UNSUPPORTED)
        requireNoPending(asset)
        requireInput(destination.isNotBlank(), InputError.DESTINATION_REQUIRED)
        val canonicalAmount = DecimalInput.canonical(amount, asset.decimals)
        val atomic = Amount.atomic(canonicalAmount, asset.decimals)
        requireInput(atomic != "0", InputError.AMOUNT_POSITIVE)
        TransferAmountPolicy.requireWithinBalance(
            canonicalAmount,
            asset,
            testMode = transferTestMode,
        )
        mutableState.update {
            it.copy(
                transferDraft =
                    TransferDraft(asset.id, asset.coin, destination.trim(), canonicalAmount)
            )
        }
        moneroPayment = null
        traceBitcoin("estimate.start")
        val quote =
            if (asset.coin == "monero") {
                val current = state.value
                requireInput(current.canSendMonero, InputError.NETWORK_UNSUPPORTED)
                val watch =
                    vault.snapshot().moneroWatchWallets[current.mode]
                        ?: throw InputException(InputError.MONERO_VIEW_SYNC_REQUIRED)
                val scanner =
                    moneroScanner ?: throw InputException(InputError.MONERO_SCANNER_UNAVAILABLE)
                val unsigned = scanner.prepareTransfer(watch, destination.trim(), atomic)
                TransferQuote(
                        asset,
                        destination.trim(),
                        canonicalAmount,
                        Amount.display(unsigned.feeAtomic, 12),
                    )
                    .also { moneroPayment = MoneroPayment(it, unsigned) }
            } else
                try {
                    network.estimate(asset, destination.trim(), canonicalAmount)
                } catch (failure: InputException) {
                    if (
                        !transferTestMode ||
                            asset.coin != "bitcoin" ||
                            failure.reason != InputError.INSUFFICIENT_BALANCE
                    )
                        throw failure
                    traceBitcoin("test.synthetic_input")
                    BitcoinSignatureTest.quote(
                        asset,
                        destination.trim(),
                        canonicalAmount,
                        enabled = transferTestMode,
                    )
                }
        requireInput(
            quote.asset == asset &&
                quote.destination == destination.trim() &&
                quote.amount == canonicalAmount,
            InputError.INVALID_QUOTE,
        )
        traceBitcoin("estimate.ok")
        if (quote.bitcoinSignatureTest) BitcoinSignatureTest.validate(quote, transferTestMode)
        else TransferAccounting.remaining(quote, state.value.assets)
        traceBitcoin("funding.ok")
        mutableState.update {
            it.copy(quote = quote, screen = Screen.CONFIRM, networkFailure = null)
        }
        traceBitcoin("confirmation.ready")
    }

    fun requestTransferConnection() {
        val current = state.value
        if (current.busy || current.screen != Screen.CONFIRM || current.quote == null) return
        mutableState.update {
            it.copy(
                transferStage = TransferStage.CONNECTION_REQUIRED,
                signingQuote = current.quote,
                transferConnectionError = null,
                error = null,
            )
        }
    }

    fun cancelTransferConnection() {
        if (state.value.busy || state.value.transferStage != TransferStage.CONNECTION_REQUIRED)
            return
        mutableState.update {
            it.copy(transferStage = null, signingQuote = null, transferConnectionError = null)
        }
    }

    fun confirmTransfer(paymentPin: String, hardwarePin: String = "") = runOperation {
        if (state.value.screen != Screen.CONFIRM) return@runOperation
        traceBitcoin("confirm.start")
        val quote = state.value.quote ?: return@runOperation
        val asset = state.value.selectedAsset ?: return@runOperation
        requireNoPending(asset)
        if (asset.coin == "monero") {
            val mode = state.value.mode ?: return@runOperation
            val payment =
                moneroPayment?.takeIf { it.quote == quote }
                    ?: throw InputException(InputError.INVALID_QUOTE)
            discoverMonero(
                vault.snapshot(),
                mode,
                outputs = null,
                addressOnly = false,
                payment = payment,
            )
            moneroPayment = null
            return@runOperation
        }
        if (hardware.requiresSigningAccessPin) HardwarePinPolicy.validate(hardwarePin)
        val polygon = EvmNetworks.isNativePolygon(asset)
        val ethereum = EvmNetworks.isNativeEthereum(asset)
        val solana = SolanaNetwork.supportsPayment(asset)
        val cardano = CardanoNetwork.supportsPayment(asset)
        val localApproval =
            !hardware.simulated &&
                (LedgerNetworks.supportsPayment(asset) ||
                    cardano ||
                    solana ||
                    EvmNetworks.supportsPayment(asset))
        val coldWalletId =
            if (localApproval) {
                val snapshot = vault.snapshot()
                snapshot.savedWallets
                    .singleOrNull { it.id == snapshot.activeWalletId }
                    ?.coldWalletId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
            } else null
        if (!localApproval) PinPolicy.validate(paymentPin)
        requireInput(quote.asset == asset, InputError.INVALID_QUOTE)
        val remaining =
            if (quote.bitcoinSignatureTest) {
                BitcoinSignatureTest.validate(quote, transferTestMode)
                emptyMap()
            } else TransferAccounting.remaining(quote, state.value.assets)
        traceBitcoin("sign.start")
        mutableState.update {
            it.copy(
                transferStage = TransferStage.CONNECTING,
                signingQuote = quote,
                transferConnectionError = null,
            )
        }
        // A failed broadcast must not leave the same confirmation available for another tap.
        mutableState.update { it.copy(quote = null, screen = Screen.SEND) }
        val mode = state.value.mode ?: return@runOperation
        val progress =
            hardware.signingProgress?.let { flow ->
                viewModelScope.launch {
                    flow.collect { stage -> mutableState.update { it.copy(transferStage = stage) } }
                }
            }
        val signed =
            try {
                hardware.withSigningAccessPin(hardwarePin) {
                    when {
                        coldWalletId == null -> hardware.sign(quote, paymentPin, mode)
                        LedgerNetworks.supportsPayment(asset) ->
                            hardware.signLedger(quote, coldWalletId, mode)
                        cardano -> hardware.signCardano(quote, coldWalletId, mode)
                        solana -> hardware.signSolana(quote, coldWalletId, mode)
                        polygon -> hardware.signPolygon(quote, coldWalletId, mode)
                        ethereum -> hardware.signEthereum(quote, coldWalletId, mode)
                        else -> hardware.signBrz(quote, coldWalletId, mode)
                    }
                }
            } catch (failure: InputException) {
                if (
                    failure.reason !in
                        setOf(
                            InputError.DEVICE_DISCONNECTED,
                            InputError.BLUETOOTH_UNAVAILABLE,
                            InputError.BLUETOOTH_PERMISSION,
                            InputError.HARDWARE_PIN_REQUIRED,
                            InputError.HARDWARE_PIN_LOCKED,
                            InputError.INCORRECT_PIN,
                            InputError.HARDWARE_PIN_FORMAT,
                            InputError.HARDWARE_APPROVAL_REQUIRED,
                        )
                )
                    throw failure
                traceBitcoin("connection.required")
                mutableState.update {
                    it.copy(
                        screen = Screen.CONFIRM,
                        quote = quote,
                        signingQuote = quote,
                        transferStage = TransferStage.CONNECTION_REQUIRED,
                        transferConnectionError = failure.reason,
                        error = null,
                        networkFailure = null,
                    )
                }
                return@runOperation
            } finally {
                progress?.cancel()
            }
        mutableState.update { it.copy(transferStage = TransferStage.VERIFYING) }
        val expectedId = network.transactionId(signed, quote)
        traceBitcoin("signature.verified")
        if (quote.bitcoinSignatureTest) {
            // Never journal synthetic funding as a real payment or debit wallet balances.
            mutableState.update { it.copy(bitcoinTestSignatureVerified = true) }
            traceBitcoin("test.broadcast.start")
            mutableState.update { it.copy(transferStage = TransferStage.SUBMITTING) }
            try {
                network.broadcast(signed, quote)
                traceBitcoin("test.broadcast.acknowledged")
            } catch (failure: InputException) {
                traceBitcoin("test.broadcast.${failure.reason.name}")
                if (failure.reason == InputError.TRANSACTION_REJECTED) {
                    // The signature is already verified. A synthetic input cannot fund a real
                    // payment.
                    mutableState.update {
                        it.copy(
                            error = null,
                            networkFailure = null,
                            message = WalletMessage.BITCOIN_SIGNATURE_TEST_COMPLETED,
                        )
                    }
                    return@runOperation
                }
                throw failure
            }
            return@runOperation
        }
        if (expectedId != null) {
            val pending = PendingSubmission(expectedId, signed, quote, mode)
            vault.update { snapshot ->
                requireInput(
                    snapshot.pendingSubmissions.none { sameSender(it.quote.asset, asset) },
                    InputError.SUBMISSION_PENDING,
                )
                snapshot.copy(pendingSubmissions = snapshot.pendingSubmissions + pending)
            }
            refreshTransfers()
            traceBitcoin("journal.saved")
            submitPending(pending)
            return@runOperation
        }
        val txid = network.broadcast(signed)
        val next =
            vault.update { snapshot ->
                val transfer = Transfer(txid, asset.id, quote.amount, quote.destination)
                snapshot.copy(
                    assets =
                        snapshot.assets.map { a ->
                            remaining[a.id]?.let { a.copy(balance = it) } ?: a
                        },
                    transfers = snapshot.transfers + transfer,
                )
            }
        val assets = ProviderCoverage.wallet(vault.snapshot(), mode).assets
        mutableState.update {
            it.copy(
                assets = assets,
                selectedAsset = assets.first { a -> a.id == asset.id },
                screen = Screen.TRANSACTIONS,
                quote = null,
                transferDraft = null,
                networkFailure = null,
                message = WalletMessage.TRANSFER_COMPLETED,
            )
        }
        refreshTransfers(next)
    }

    private fun sameSender(a: Asset, b: Asset) =
        a.coin == b.coin &&
            if (a.coin in EvmNetworks.chainIds) a.address.equals(b.address, true)
            else a.address == b.address

    private suspend fun requireNoPending(asset: Asset) {
        requireInput(
            vault.snapshot().pendingSubmissions.none { sameSender(it.quote.asset, asset) },
            InputError.SUBMISSION_PENDING,
        )
    }

    /**
     * User explicitly chose to resume. Reuses the stored bytes and never calls the hardware again.
     */
    fun retryPending(txId: String) = runOperation {
        val pending =
            vault.snapshot().pendingSubmissions.singleOrNull {
                it.txId == txId && it.mode == state.value.mode
            } ?: return@runOperation
        requireInput(
            pending.quote.asset.coin == "monero" ||
                ProviderCoverage.canSubmit(
                    state.value.networkSettings.preferred,
                    pending.quote.asset,
                ),
            InputError.NETWORK_UNSUPPORTED,
        )
        submitPending(pending)
    }

    private suspend fun submitPending(pending: PendingSubmission) {
        val quote = pending.quote
        mutableState.update {
            it.copy(transferStage = TransferStage.SUBMITTING, signingQuote = quote)
        }
        traceBitcoin("broadcast.start", quote.asset.coin)
        val txId = pending.txId
        val status =
            if (quote.asset.coin == "monero") {
                val scanner =
                    moneroScanner ?: throw InputException(InputError.MONERO_SCANNER_UNAVAILABLE)
                scanner.submitTransfer(txId, pending.signedTransaction)
            } else {
                requireInput(
                    network.broadcast(pending.signedTransaction, quote) == txId,
                    InputError.SUBMISSION_PENDING,
                )
                network.lastSubmissionStatus
            }
        traceBitcoin("broadcast.acknowledged.${status.name}", quote.asset.coin)
        val next =
            vault.update { snapshot ->
                val transfer =
                    Transfer(
                        txId,
                        quote.asset.id,
                        quote.amount,
                        quote.destination,
                        status = status,
                        nonce = quote.evmPlan?.nonce,
                    )
                // Balances are refreshed from the chain, never debited twice after a
                // timeout/restart.
                snapshot.copy(
                    pendingSubmissions = snapshot.pendingSubmissions.filterNot { it.txId == txId },
                    transfers =
                        if (snapshot.transfers.any { it.id == txId }) snapshot.transfers
                        else snapshot.transfers + transfer,
                )
            }
        mutableState.update {
            it.copy(
                screen = Screen.TRANSACTIONS,
                selectedAsset =
                    it.assets.firstOrNull { asset -> asset.id == quote.asset.id } ?: quote.asset,
                selectedAccount =
                    it.accounts.firstOrNull { account -> account.id == quote.asset.accountId },
                networkFailure = null,
                transferDraft = null,
                quote = null,
                message =
                    when (status) {
                        TransferStatus.CONFIRMED -> WalletMessage.TRANSFER_CONFIRMED
                        TransferStatus.FAILED -> WalletMessage.TRANSFER_REVERTED
                        else -> WalletMessage.TRANSFER_SUBMITTED
                    },
            )
        }
        refreshTransfers(next)
    }

    private suspend fun refreshTransfers(snapshot: VaultSnapshot? = null) {
        val ids = state.value.assets.map { it.id }.toSet()
        val data = snapshot ?: vault.snapshot()
        mutableState.update {
            it.copy(
                transfers = data.transfers.filter { tx -> tx.assetId in ids },
                pendingTransfers =
                    data.pendingSubmissions
                        .filter { tx -> tx.mode == it.mode }
                        .map { tx ->
                            val asset = tx.quote.asset
                            val canRetry =
                                if (asset.coin == "monero") it.moneroScannerAvailable
                                else ProviderCoverage.canSubmit(it.networkSettings.preferred, asset)
                            PendingTransferUi(
                                tx.txId,
                                asset.id,
                                asset.coin,
                                asset.symbol,
                                tx.quote.amount,
                                tx.quote.destination,
                                canRetry,
                            )
                        },
            )
        }
    }

    fun clearMessage() = mutableState.update { it.copy(error = null, message = null) }

    private fun runOperation(clearFeedback: Boolean = true, block: suspend () -> Unit) {
        if (state.value.busy) return
        val startedGeneration = ++generation
        mutableState.update {
            if (clearFeedback) it.copy(busy = true, error = null, message = null)
            else it.copy(busy = true)
        }
        operation =
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val reason = (e as? InputException)?.reason ?: InputError.OPERATION_FAILED
                    traceBitcoin("error.${reason.name}")
                    mutableState.update {
                        it.copy(
                            error = reason,
                            networkFailure =
                                if (reason in networkErrors) reason else it.networkFailure,
                        )
                    }
                } finally {
                    if (generation == startedGeneration)
                        mutableState.update {
                            val reconnect = it.transferStage == TransferStage.CONNECTION_REQUIRED
                            it.copy(
                                busy = false,
                                hardwareOperation = null,
                                transferStage = if (reconnect) it.transferStage else null,
                                signingQuote = if (reconnect) it.signingQuote else null,
                            )
                        }
                }
            }
    }

    private val networkErrors =
        setOf(
            InputError.API_KEY_REQUIRED,
            InputError.PROVIDER_UNAVAILABLE,
            InputError.PROVIDER_RATE_LIMITED,
            InputError.NETWORK_UNSUPPORTED,
            InputError.NETWORK_MISMATCH,
            InputError.TRANSACTION_REJECTED,
            InputError.SUBMISSION_PENDING,
        )
}
