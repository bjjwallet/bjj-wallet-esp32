package pub.airlock

import androidx.compose.ui.window.ComposeUIViewController
import pub.airlock.di.AppEnvironment
import pub.airlock.di.AppGraph
import pub.airlock.domain.PinCrypto
import pub.airlock.domain.VaultStorage
import pub.airlock.ui.AirLockApp

fun MainViewController(
    storage: VaultStorage,
    crypto: PinCrypto,
    debug: Boolean,
    actions: pub.airlock.domain.AddressActions,
    biometrics: pub.airlock.domain.AppBiometrics,
    ed25519Verifier: pub.airlock.domain.Ed25519Verifier,
) = ComposeUIViewController {
    val graph =
        androidx.compose.runtime.remember {
            AppGraph(
                storage,
                crypto,
                AppEnvironment.RELEASE,
                actions,
                biometrics = biometrics,
                bluetoothAccess =
                    pub.airlock.ble
                        .BluetoothAccess { /* CoreBluetooth requests native authorization when scanning. */
                        },
                moneroRuntime = { pub.airlock.data.monero.IosMoneroRuntime() },
                ed25519Verifier = ed25519Verifier,
            )
        }
    AirLockApp(graph)
}
