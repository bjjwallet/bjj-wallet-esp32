package pub.airlock.ble

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import pub.airlock.domain.InputError

// CoreBluetooth starts pairing on the encrypted characteristic read.
internal actual suspend fun pairAirLockLink(peripheral: Peripheral, failure: InputError) = Unit

internal actual suspend fun configureAirLockLink(peripheral: Peripheral) {
    // CoreBluetooth negotiates the ATT MTU automatically.
    check(
        peripheral.maximumWriteValueLengthForType(WriteType.WithoutResponse) >=
            AirLockGatt.CHUNK_SIZE
    )
}
