@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class ResetSynchronizationTest {
    @Test
    fun confirmationIsRequiredAndConfirmedResetClearsEveryWalletAndRestartsFirstConnection() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val storage = MemoryStorage()
                val vault = VaultRepository(storage, TestCrypto(), dispatcher)
                val data = DemoHardwareWallet().synchronizeWallets()
                vault.commitHardwareSynchronization(
                    data,
                    vault.prepareAppSecurity("app123"),
                    "Principal",
                    "device",
                )
                val first = vault.snapshot().savedWallets.single()
                vault.update {
                    it.copy(
                        savedWallets =
                            it.savedWallets + first.copy(id = "wallet-2", name = "Outra"),
                        networkSettings = NetworkSettings(NetworkProvider.FREEDOM),
                        language = "pt",
                        theme = AppTheme.DARK,
                        transfers =
                            listOf(
                                Transfer(
                                    "public-hash",
                                    it.assets.first().id,
                                    "1",
                                    "public-destination",
                                )
                            ),
                        pendingSubmissions =
                            listOf(
                                PendingSubmission(
                                    "pending",
                                    "public-signed",
                                    TransferQuote(
                                        it.assets.first(),
                                        "public-destination",
                                        "1",
                                        "0.1",
                                    ),
                                    WalletMode.STANDARD,
                                )
                            ),
                    )
                }
                var controls = 0
                var exports = 0
                var disconnects = 0
                val hardware =
                    object : HardwareWallet by DemoHardwareWallet() {
                        override suspend fun discover() = listOf(HardwareDevice("device", "Test"))

                        override suspend fun connect(deviceId: String) = Unit

                        override suspend fun requiresAccessPin() = false

                        override suspend fun control(command: DeviceCommand) {
                            controls++
                        }

                        override suspend fun synchronizePublicWallets() =
                            WalletSynchronization.Legacy(synchronizeWallets())

                        override suspend fun synchronizeWallets(): Map<WalletMode, WalletData> {
                            exports++
                            return data
                        }

                        override suspend fun disconnect() {
                            disconnects++
                        }
                    }
                val vm = WalletViewModel(vault, hardware, MockWalletNetwork())
                advanceUntilIdle()
                vm.unlock("app123")
                advanceUntilIdle()
                vm.navigate(Screen.SETTINGS)
                val original = vault.snapshot()
                vm.confirmResetAndSynchronize()
                advanceUntilIdle()
                assertEquals(original, vault.snapshot())
                vm.requestResetSynchronization()
                assertTrue(vm.state.value.resetSyncConfirmation)
                assertEquals(original, vault.snapshot())
                vm.cancelResetSynchronization()
                assertFalse(vm.state.value.resetSyncConfirmation)
                assertEquals(original, vault.snapshot())
                vm.requestResetSynchronization()
                vm.confirmResetAndSynchronize()
                advanceUntilIdle()
                assertEquals(VaultSnapshot(), vault.snapshot())
                assertEquals(
                    VaultSnapshot(),
                    VaultRepository(storage, TestCrypto(), dispatcher).snapshot(),
                )
                assertEquals(Screen.SYNC, vm.state.value.screen)
                assertNull(vm.state.value.mode)
                assertTrue(vm.state.value.accounts.isEmpty())
                assertTrue(vm.state.value.savedWallets.isEmpty())
                assertTrue(vm.state.value.pendingTransfers.isEmpty())
                assertNull(vm.state.value.quote)
                assertFalse(vm.state.value.resetSyncConfirmation)
                assertFalse(vm.state.value.busy)
                assertEquals(0, controls)
                assertEquals(0, exports)
                assertEquals(1, disconnects)
                vm.synchronize()
                advanceUntilIdle()
                vm.synchronizeWith("device")
                advanceUntilIdle()
                assertEquals(Screen.APP_SECURITY, vm.state.value.screen)
                assertEquals(0, exports)
                assertFalse(vault.snapshot().appSecurityConfigured)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun failedResetKeepsDurableWalletAndDoesNotStartSynchronization() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            vault.commitHardwareSynchronization(
                DemoHardwareWallet().synchronizeWallets(),
                vault.prepareAppSecurity(null),
                "Carteira",
            )
            val vm = WalletViewModel(vault, DemoHardwareWallet(), MockWalletNetwork())
            advanceUntilIdle()
            vm.navigate(Screen.SETTINGS)
            val original = vault.snapshot()
            val visible = vm.state.value.accounts
            vm.requestResetSynchronization()
            storage.failWrites = true
            vm.confirmResetAndSynchronize()
            advanceUntilIdle()
            storage.failWrites = false
            assertEquals(original, vault.snapshot())
            assertEquals(original, VaultRepository(storage, TestCrypto(), dispatcher).snapshot())
            assertEquals(Screen.SETTINGS, vm.state.value.screen)
            assertEquals(visible, vm.state.value.accounts)
            assertTrue(vm.state.value.resetSyncConfirmation)
            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.busy)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
