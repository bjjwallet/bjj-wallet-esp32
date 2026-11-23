package pub.airlock.di

import org.koin.dsl.koinApplication
import org.koin.dsl.module
import pub.airlock.ble.*
import pub.airlock.data.*
import pub.airlock.data.monero.LocalMoneroScanner
import pub.airlock.data.monero.MoneroRuntime
import pub.airlock.domain.*
import pub.airlock.presentation.WalletViewModel

/** Each host owns an isolated graph; tests never depend on a global Koin instance. */
enum class AppEnvironment {
    RELEASE
}

class AppGraph(
    storage: VaultStorage,
    crypto: PinCrypto,
    environment: AppEnvironment,
    val addressActions: AddressActions,
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    bluetoothAccess: BluetoothAccess = BluetoothAccess {
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)
    },
    moneroScanner: MoneroScanner? = null,
    biometrics: AppBiometrics = NoAppBiometrics,
    moneroRuntime: (() -> MoneroRuntime)? = null,
    moneroDiagnostics: ((String) -> Unit)? = null,
    ed25519Verifier: Ed25519Verifier? = null,
    transactionDiagnostics: (String) -> Unit = {},
) {
    private val deriver: PublicKeyDeriver = Bip32PublicKeyDeriver()
    private val hardware: HardwareWallet =
        ProductionHardwareWallet(
            KableHardwareConnection(bluetoothAccess, transactionDiagnostics),
            deriver,
        )
    private val httpClient = configuredClient()
    private val scanner =
        moneroScanner
            ?: moneroRuntime?.let {
                LocalMoneroScanner(httpClient, it, reportDiagnostic = moneroDiagnostics)
            }
    private val application = koinApplication {
        modules(
            module {
                single<VaultStorage> { storage }
                single<PinCrypto> { crypto }
                single<HardwareWallet> { hardware }
                single<WalletNetwork> {
                    val vault = get<VaultRepository>()
                    ProductionWalletNetwork(httpClient, ed25519Verifier) { vault.snapshot() }
                }
                single<PublicKeyDeriver> { deriver }
                single { VaultRepository(get(), get(), dispatcher) }
                factory {
                    WalletViewModel(
                        get(),
                        get(),
                        get(),
                        get(),
                        scanner,
                        biometrics,
                        transactionDiagnostics = transactionDiagnostics,
                    )
                }
            }
        )
    }

    fun viewModel(): WalletViewModel = application.koin.get()

    fun close() {
        scanner?.clear()
        application.close()
        httpClient.close()
    }
}
