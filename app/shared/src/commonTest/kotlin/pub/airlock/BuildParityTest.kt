@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.di.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class BuildParityTest {
    private val actions =
        object : AddressActions {
            override fun qrCode(text: String) = byteArrayOf()

            override fun share(text: String) = Unit

            override fun scanQrCode(
                prompt: String,
                cancelLabel: String,
                completion: (QrScanResult) -> Unit,
            ) = completion(QrScanResult())

            override fun openAppSettings() = Unit
        }

    @Test
    fun releaseNeverFallsBackToFixtures() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph =
            AppGraph(
                MemoryStorage(),
                TestCrypto(),
                AppEnvironment.RELEASE,
                actions,
                StandardTestDispatcher(testScheduler),
            )
        try {
            val vm = graph.viewModel()
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            assertFalse(vm.state.value.simulated)
            vm.synchronize()
            advanceUntilIdle()
            assertEquals(InputError.INTEGRATION_UNAVAILABLE, vm.state.value.error)
            assertTrue(vm.state.value.accounts.isEmpty())
            assertTrue(vm.state.value.assets.isEmpty())
        } finally {
            graph.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sameFlowUsesInjectedServicesWithoutCheckingSimulationFlag() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (simulated in listOf(true, false)) {
                val hardware =
                    object : HardwareWallet by DemoHardwareWallet() {
                        override val simulated = simulated
                    }
                val storage = MemoryStorage()
                val vault =
                    VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
                val vm =
                    WalletViewModel(vault, hardware, MockWalletNetwork(), DemoPublicKeyDeriver())
                advanceUntilIdle()
                vm.setup("normal123", "hidden456")
                advanceUntilIdle()
                vm.unlock("normal123")
                advanceUntilIdle()
                syncTestWallet(vm)
                advanceUntilIdle()
                assertEquals(5, vm.state.value.accounts.size)
                assertEquals("95172.21", vm.state.value.prices["BTC"])
                vm.showAddAccount()
                advanceUntilIdle()
                assertEquals(Screen.ADD_ACCOUNT, vm.state.value.screen)
                assertFalse(vm.state.value.availableCoins.any { it.coin == "bitcoin" })
                vm.addAccount(vm.state.value.availableCoins.first())
                advanceUntilIdle()
                assertEquals(6, vm.state.value.accounts.size)
                vm.open(vm.state.value.accounts.first())
                vm.addAsset()
                advanceUntilIdle()
                assertEquals(2, vm.state.value.assets.count { it.coin == "bitcoin" })
                vm.open(vm.state.value.assets.first())
                vm.receive()
                assertEquals(Screen.RECEIVE, vm.state.value.screen)
                vm.back()
                assertEquals(Screen.ASSET, vm.state.value.screen)
                vm.send()
                vm.prepareTransfer("MOCK-RECIPIENT", "0.001")
                advanceUntilIdle()
                assertEquals(Screen.CONFIRM, vm.state.value.screen)
                assertEquals("0.0000029", vm.state.value.quote?.fee)
                vm.confirmTransfer("abc123")
                advanceUntilIdle()
                assertEquals(Screen.TRANSACTIONS, vm.state.value.screen)
                assertEquals("0.0234971", vm.state.value.selectedAsset?.balance)
                assertEquals(1, vm.state.value.transfers.size)
                val restored =
                    VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
                        .snapshot()
                assertEquals("0.0234971", restored.assets.first().balance)
                assertTrue(restored.transfers.first().id.startsWith("MOCK-TX-"))
                vm.navigate(Screen.DEVICE)
                advanceUntilIdle()
                vm.deviceCommand(DeviceCommand.SHUTDOWN)
                advanceUntilIdle()
                assertEquals(false, vm.state.value.device?.connected)
                vm.open(vm.state.value.accounts.first())
                vm.addAsset()
                advanceUntilIdle()
                assertNull(vm.state.value.error)
                assertEquals(
                    3,
                    vm.state.value.assets.count { it.coin == "bitcoin" && it.contract.isEmpty() },
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun feeIsValidatedBeforeSigningOrChangingTheBalance() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm =
                WalletViewModel(
                    VaultRepository(
                        MemoryStorage(),
                        TestCrypto(),
                        StandardTestDispatcher(testScheduler),
                    ),
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                )
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.open(vm.state.value.accounts.first())
            vm.open(vm.state.value.assets.first())
            vm.send()
            vm.prepareTransfer("MOCK-RECIPIENT", "0.0245")
            advanceUntilIdle()
            assertEquals(Screen.SEND, vm.state.value.screen)
            assertEquals(InputError.INSUFFICIENT_BALANCE, vm.state.value.error)
            assertEquals("0.0245", vm.state.value.selectedAsset?.balance)
            assertTrue(vm.state.value.transfers.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun fiatValuationUsesExactDecimalArithmetic() {
        assertEquals("233172", Amount.usdCents("0.0245", "95172.21"))
        assertEquals("2,331.72", Amount.usd("233172"))
        assertEquals("1", Amount.usdCents("0.005", "1"))
        assertEquals("0", Amount.usdCents("0.0049", "1"))
        assertEquals(
            "999999999999999998000000000000000001",
            Amount.multiply("999999999999999999", "999999999999999999"),
        )
    }
}
