@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class ConnectionSecurityTest {
    private class Device : HardwareWallet by DemoHardwareWallet() {
        var found = true
        var needsPin = false
        var accepted = false
        var unavailable = false
        var pinReply: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        var connectionReply: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        var exportGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var failExport = false
        var exports = 0
        var pinRequests = 0
        override val simulated = false

        override suspend fun discover() =
            if (found) listOf(HardwareDevice("device", "Cold wallet")) else emptyList()

        override suspend fun requiresAccessPin() = connectionReply?.await() ?: needsPin

        override suspend fun unlockAccess(pin: String): Boolean {
            pinRequests++
            if (unavailable) throw InputException(InputError.DEVICE_DISCONNECTED)
            return pinReply?.await() ?: accepted
        }

        override suspend fun synchronizePublicWallets() =
            WalletSynchronization.Legacy(synchronizeWallets())

        override suspend fun synchronizePublicWallets(skipMoneroWatchWalletIds: Set<String>) =
            synchronizePublicWallets()

        override suspend fun synchronizeWallets(): Map<WalletMode, WalletData> {
            exports++
            exportGate?.await()
            if (failExport) throw InputException(InputError.DEVICE_DISCONNECTED)
            return DemoHardwareWallet().synchronizeWallets()
        }
    }

    @Test
    fun firstConnectionScansBeforeOfferingAppProtectionAndRetriesEmptyScan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val device = Device()
            device.found = false
            val vm =
                WalletViewModel(
                    VaultRepository(MemoryStorage(), TestCrypto(), dispatcher),
                    device,
                    MockWalletNetwork(),
                )
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            vm.synchronize()
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertFalse(vm.state.value.busy)
            assertTrue(vm.state.value.hardwareDevices.isEmpty())
            device.found = true
            syncTestWallet(vm)
            advanceUntilIdle()
            assertEquals(Screen.APP_SECURITY, vm.state.value.screen)
            assertEquals(0, device.exports)
            vm.configureAppSecurity(null)
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertEquals(1, device.exports)
            assertEquals(0, device.pinRequests)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun appProtectionChoicesWaitForTheWalletToConfirmTheConnection() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val device = Device()
            device.connectionReply = kotlinx.coroutines.CompletableDeferred()
            val vm = WalletViewModel(vault, device, MockWalletNetwork())
            advanceUntilIdle()
            syncTestWallet(vm)
            runCurrent()
            assertEquals(Screen.APP_SECURITY, vm.state.value.screen)
            assertFalse(vm.state.value.hardwareConnectionAccepted)
            assertTrue(vm.state.value.busy)
            vm.configureAppSecurity(null)
            runCurrent()
            assertEquals(0, device.exports)
            assertFalse(vault.snapshot().appSecurityConfigured)
            device.connectionReply!!.complete(false)
            advanceUntilIdle()
            assertTrue(vm.state.value.hardwareConnectionAccepted)
            assertFalse(vm.state.value.busy)
            vm.configureAppSecurity(null)
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertTrue(vault.snapshot().appSecurityConfigured)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun rejectedConnectionDoesNotEnableAppProtectionChoices() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val device = Device()
            device.connectionReply = kotlinx.coroutines.CompletableDeferred()
            val vm = WalletViewModel(vault, device, MockWalletNetwork())
            advanceUntilIdle()
            syncTestWallet(vm)
            runCurrent()
            device.connectionReply!!.completeExceptionally(
                InputException(InputError.DEVICE_DISCONNECTED)
            )
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertFalse(vm.state.value.hardwareConnectionAccepted)
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            assertEquals(0, device.exports)
            assertFalse(vault.snapshot().appSecurityConfigured)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun hardwareRejectionsBlockAtThirdErrorDoubleAndResetOnlyOnSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val device = Device()
            device.needsPin = true
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val vm =
                WalletViewModel(
                    vault,
                    device,
                    MockWalletNetwork(),
                    currentTimeMillis = { testScheduler.currentTime },
                )
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            assertEquals(Screen.HARDWARE_PIN, vm.state.value.screen)
            for (invalid in listOf("123", "1234567890123", "abcd", "１２３４")) {
                vm.unlockHardware(invalid)
                advanceUntilIdle()
                assertEquals(InputError.HARDWARE_PIN_FORMAT, vm.state.value.error)
                assertEquals(0, device.pinRequests)
                assertEquals(0, vault.snapshot().hardwarePinFailures)
            }
            repeat(2) {
                vm.unlockHardware("Bb0000")
                advanceUntilIdle()
                assertEquals(Screen.HARDWARE_PIN, vm.state.value.screen)
            }
            vm.unlockHardware("Bb0000")
            runCurrent()
            assertEquals(Screen.PIN_BLOCKED, vm.state.value.screen)
            assertEquals(10L, vm.state.value.blockedSeconds)
            vm.back()
            vm.navigate(Screen.ACCOUNTS)
            vm.unlock("app123")
            runCurrent()
            assertEquals(Screen.PIN_BLOCKED, vm.state.value.screen)
            vm.unlockHardware("Bb0000")
            runCurrent()
            assertEquals(3, device.pinRequests)
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(Screen.PIN_BLOCKED, vm.state.value.screen)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(Screen.HARDWARE_PIN, vm.state.value.screen)
            vm.unlockHardware("Bb0000")
            runCurrent()
            assertEquals(20L, vm.state.value.blockedSeconds)
            advanceTimeBy(20_000)
            runCurrent()
            device.accepted = true
            vm.unlockHardware("Aa123456")
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertEquals(0, vault.snapshot().hardwarePinFailures)
            assertEquals(0L, vault.snapshot().hardwareBlockedUntil)
            assertEquals(1, device.exports)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun countdownSurvivesRestartAndDisconnectIsNotAnIncorrectPin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            val device = Device()
            device.needsPin = true
            device.unavailable = true
            val vm =
                WalletViewModel(
                    vault,
                    device,
                    MockWalletNetwork(),
                    currentTimeMillis = { testScheduler.currentTime },
                )
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.configureAppSecurity(null)
            advanceUntilIdle()
            vm.unlockHardware("Aa1234")
            advanceUntilIdle()
            assertEquals(0, vault.snapshot().hardwarePinFailures)
            assertEquals(0, device.exports)
            vault.update { it.copy(hardwarePinFailures = 4, hardwareBlockedUntil = 20_000) }
            val restarted =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    Device(),
                    MockWalletNetwork(),
                    currentTimeMillis = { testScheduler.currentTime },
                )
            runCurrent()
            assertEquals(Screen.PIN_BLOCKED, restarted.state.value.screen)
            advanceTimeBy(19_000)
            runCurrent()
            assertEquals(Screen.PIN_BLOCKED, restarted.state.value.screen)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(Screen.SYNC, restarted.state.value.screen)
            assertEquals(4, vault.snapshot().hardwarePinFailures)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun appPinAndBiometricsUnlockWithoutValidatingTheDevicePin() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            vault.configureAppSecurity("app123")
            val device = Device()
            var success = false
            val biometrics =
                object : AppBiometrics {
                    override fun available() = true

                    override fun authenticate(
                        reason: String,
                        pinLabel: String,
                        completion: (Boolean) -> Unit,
                    ) = completion(success)
                }
            val vm = WalletViewModel(vault, device, MockWalletNetwork(), biometrics = biometrics)
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            vm.unlockBiometric("Unlock with biometrics", "App access PIN")
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            vm.unlock("device-pin")
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            vm.unlock("app123")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            vm.lock()
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            success = true
            vm.unlockBiometric("Unlock with biometrics", "App access PIN")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertEquals(0, device.pinRequests)
            assertEquals(0, device.exports)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun coldWalletPinIsNeverPersistedAndAppVerifierIsSavedOnlyAfterSync() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            val device = Device()
            device.needsPin = true
            val vm = WalletViewModel(vault, device, MockWalletNetwork())
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            assertNull(vault.snapshot().appPin)
            assertFalse(vault.snapshot().appSecurityConfigured)
            vm.unlockHardware("Bb0000")
            advanceUntilIdle()
            assertFalse(storage.value.orEmpty().contains("0000"))
            device.accepted = true
            device.failExport = true
            vm.unlockHardware("0Ab123")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertNull(VaultRepository(storage, TestCrypto(), dispatcher).snapshot().appPin)
            device.failExport = false
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            vm.unlockHardware("0Ab123")
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            val restored = VaultRepository(storage, TestCrypto(), dispatcher)
            assertTrue(restored.unlockApp("app123"))
            assertFalse(restored.unlockApp("0Ab123"))
            assertFalse(storage.value.orEmpty().contains("0Ab123"))
            assertFalse(storage.value.orEmpty().contains("hardwareCredentials"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lockingDuringExportDoesNotPersistThePinOrLeaveTheAppUnlocked() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            vault.configureAppSecurity("app123")
            val device = Device()
            device.needsPin = true
            device.accepted = true
            device.exportGate = kotlinx.coroutines.CompletableDeferred()
            val vm = WalletViewModel(vault, device, MockWalletNetwork())
            advanceUntilIdle()
            vm.unlock("app123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.unlockHardware("0Ab123")
            runCurrent()
            assertEquals(1, device.exports)
            vm.lock()
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            assertTrue(vault.unlockApp("app123"))
            device.exportGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            assertTrue(vault.unlockApp("app123"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun delayedPinAndExportHaveVisibleProgressAndDisconnectAllowsRescan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val device = Device()
            device.needsPin = true
            device.pinReply = kotlinx.coroutines.CompletableDeferred()
            device.exportGate = kotlinx.coroutines.CompletableDeferred()
            val vm = WalletViewModel(vault, device, MockWalletNetwork())
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.configureAppSecurity("app123")
            advanceUntilIdle()
            vm.unlockHardware("Aa1234")
            runCurrent()
            assertTrue(vm.state.value.busy)
            assertEquals(HardwareOperation.VERIFY_PIN, vm.state.value.hardwareOperation)
            assertEquals(0, device.exports)
            device.pinReply!!.complete(true)
            runCurrent()
            assertEquals(HardwareOperation.IMPORT_PUBLIC_DATA, vm.state.value.hardwareOperation)
            device.exportGate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertNull(vm.state.value.hardwareOperation)
            vm.navigate(Screen.SYNC)
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            device.unavailable = true
            vm.unlockHardware("Aa1234")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertEquals(InputError.DEVICE_DISCONNECTED, vm.state.value.error)
            assertFalse(vm.state.value.busy)
            assertFalse(vm.state.value.hardwareConnectionAccepted)
            assertNull(vm.state.value.hardwareOperation)
            assertEquals(0, vault.snapshot().hardwarePinFailures)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backoffContinuesDoublingBeyondTheFirstFewFailures() {
        assertEquals(
            listOf(0L, 0L, 10L, 20L, 40L, 80L, 160L),
            (1..7).map(HardwarePinBackoff::seconds),
        )
        assertTrue(HardwarePinBackoff.seconds(Int.MAX_VALUE) > 0)
    }
}
