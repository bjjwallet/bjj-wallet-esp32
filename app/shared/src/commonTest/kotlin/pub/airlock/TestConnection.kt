@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import pub.airlock.presentation.WalletViewModel

/** Explicit device selection is now required after discovery. */
fun TestScope.syncTestWallet(vm: WalletViewModel) {
    vm.synchronize()
    advanceUntilIdle()
    vm.state.value.hardwareDevices.firstOrNull()?.let { vm.synchronizeWith(it.id) }
}
