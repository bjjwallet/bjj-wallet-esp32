package pub.airlock.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import pub.airlock.domain.InputError
import pub.airlock.domain.InputException
import pub.airlock.domain.requireInput

@Suppress("DEPRECATION")
@SuppressLint(
    "MissingPermission"
) // KableHardwareConnection checks Bluetooth permission before connecting.
internal actual suspend fun pairAirLockLink(peripheral: Peripheral, failure: InputError) {
    val address = (peripheral as AndroidPeripheral).address
    val device =
        BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)
            ?: throw InputException(InputError.BLUETOOTH_UNAVAILABLE)
    val initialState = device.bondState
    if (initialState == BluetoothDevice.BOND_BONDED) return
    requireInput(initialState == BluetoothDevice.BOND_BONDING || device.createBond(), failure)

    val bonded =
        withTimeoutOrNull(45_000L) {
            var bonding = initialState == BluetoothDevice.BOND_BONDING
            var state = device.bondState
            while (
                state != BluetoothDevice.BOND_BONDED &&
                    !(bonding && state == BluetoothDevice.BOND_NONE)
            ) {
                bonding = bonding || state == BluetoothDevice.BOND_BONDING
                delay(200L)
                state = device.bondState
            }
            state == BluetoothDevice.BOND_BONDED
        }
    requireInput(bonded == true, failure)
}

internal actual suspend fun configureAirLockLink(peripheral: Peripheral) {
    // The firmware fragments replies using the negotiated MTU, including the default 23.
    // Request a larger MTU to reduce fragmentation when the link supports it.
    try {
        (peripheral as AndroidPeripheral).requestMtu(247)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Android may reject an optional MTU request while GATT remains connected.
    }
}
