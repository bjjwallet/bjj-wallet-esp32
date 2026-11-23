@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class NetworkRecoveryTest {
    @Test
    fun retiredProviderMigrationClearsKeysAndKeepsWalletAndPendingSignedBytes() = runTest {
        for (retired in
            listOf(NetworkProvider.ALCHEMY, NetworkProvider.ANKR, NetworkProvider.COINBASE)) {
            val pending =
                PendingSubmission("public-hash", EIP155_RAW, publicQuote(), WalletMode.STANDARD)
            val original =
                VaultSnapshot(
                    networkSettings =
                        NetworkSettings(
                            retired,
                            alchemyKey = "old_alchemy",
                            ankrKey = "old_ankr",
                            coinbaseKey = "old_coinbase",
                        ),
                    accounts =
                        listOf(
                            Account(
                                evmAsset.accountId,
                                "ethereum",
                                "Ethereum",
                                "ETH",
                                "",
                                WalletMode.STANDARD,
                            )
                        ),
                    assets = listOf(evmAsset),
                    pendingSubmissions = listOf(pending),
                )
            val storage = MemoryStorage()
            storage.write(Json.encodeToString(original))
            val migrated =
                VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
                    .snapshot()
            assertEquals(NetworkSettings(), migrated.networkSettings)
            assertEquals(original.accounts, migrated.accounts)
            assertEquals(original.assets, migrated.assets)
            assertEquals(listOf(pending), migrated.pendingSubmissions)
            val restored =
                VaultRepository(storage, TestCrypto(), StandardTestDispatcher(testScheduler))
                    .snapshot()
            assertEquals(migrated, restored)
            assertEquals(
                InputError.NETWORK_UNSUPPORTED,
                assertFailsWith<InputException> { original.networkSettings.validated() }.reason,
            )
        }
    }

    @Test
    fun oldVaultsDefaultToBlockstreamAndPublicSubmissionRoundTrips() = runTest {
        val storage = MemoryStorage()
        storage.write("""{"schemaVersion":1,"apiUrl":"https://legacy.example"}""")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        assertEquals(NetworkSettings(), vault.snapshot().networkSettings)
        assertTrue(vault.snapshot().pendingSubmissions.isEmpty())
        val pending =
            PendingSubmission("public-hash", EIP155_RAW, publicQuote(), WalletMode.STANDARD)
        vault.update { it.copy(pendingSubmissions = listOf(pending)) }
        val restored =
            VaultRepository(storage, TestCrypto(), dispatcher)
                .snapshot()
                .pendingSubmissions
                .single()
        assertEquals(pending, restored)
        assertFalse(restored.toString().contains(EIP155_RAW))
    }

    @Test
    fun settingsKeepSavedWalletsVisibleAndClearCredentialsOnLock() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            val vm =
                WalletViewModel(
                    vault,
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                    DemoPublicKeyDeriver(),
                )
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            val accounts = vault.snapshot().accounts
            val assets = vault.snapshot().assets
            for (provider in
                listOf(
                    NetworkProvider.BLOCKSTREAM,
                    NetworkProvider.FREEDOM,
                    NetworkProvider.MORALIS,
                )) {
                vm.saveNetworkSettings(
                    NetworkSettings(provider, moralisKey = "secretMoralis"),
                    "https://custom.example/api",
                )
                advanceUntilIdle()
                val saved = VaultRepository(storage, TestCrypto(), dispatcher).snapshot()
                assertEquals(provider, saved.networkSettings.preferred)
                assertEquals(accounts, saved.accounts)
                assertEquals(assets, saved.assets)
                assertFalse(saved.networkSettings.toString().contains("secret"))
                assertTrue(vm.state.value.assets.any { it.coin == "ethereum" })
                assertEquals(
                    assets.filter { asset ->
                        accounts.any {
                            it.id == asset.accountId && it.mode == WalletMode.STANDARD && it.enabled
                        }
                    },
                    vm.state.value.assets,
                )
                assertEquals(
                    accounts.filter { it.mode == WalletMode.STANDARD && it.enabled },
                    vm.state.value.accounts,
                )
            }
            vm.lock()
            advanceUntilIdle()
            assertTrue(vm.state.value.networkSettings.moralisKey.isEmpty())
            vm.unlock("normal123")
            advanceUntilIdle()
            assertEquals("secretMoralis", vm.state.value.networkSettings.moralisKey)
            storage.failWrites = true
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.AIRLOCK),
                "https://other.example",
            )
            advanceUntilIdle()
            assertEquals(NetworkProvider.MORALIS, vm.state.value.networkSettings.preferred)
            storage.failWrites = false
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.AIRLOCK),
                "https://custom.example/api",
            )
            advanceUntilIdle()
            assertTrue(vm.state.value.assets.any { it.coin == "bitcoin" })
            assertTrue(vm.state.value.accounts.any { it.coin == "ripple" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingSubmissionSurvivesRestartAndRetryNeverSignsOrDebitsAgain() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vault = VaultRepository(storage, TestCrypto(), dispatcher)
            var signatures = 0
            var submissions = 0
            var available = false
            val payloads = mutableListOf<String>()
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override suspend fun sign(
                        quote: TransferQuote,
                        paymentPin: String,
                        mode: WalletMode,
                    ): String {
                        signatures++
                        return "PUBLIC-SIGNED-FIXTURE"
                    }
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override fun transactionId(signedTransaction: String, quote: TransferQuote) =
                        "fixture-hash"

                    override suspend fun broadcast(
                        signedTransaction: String,
                        quote: TransferQuote,
                    ): String {
                        submissions++
                        payloads += signedTransaction
                        if (!available) throw InputException(InputError.SUBMISSION_PENDING)
                        return "fixture-hash"
                    }
                }
            val vm = WalletViewModel(vault, hardware, network, DemoPublicKeyDeriver())
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            val asset =
                vm.state.value.assets.first { it.coin == "ethereum" && it.contract.isEmpty() }
            vm.open(asset)
            vm.send()
            vm.prepareTransfer("MOCK-RECIPIENT", "0.001")
            advanceUntilIdle()
            vm.confirmTransfer("abc123")
            advanceUntilIdle()
            assertEquals(1, signatures)
            assertEquals(1, submissions)
            assertEquals(1, vm.state.value.pendingTransfers.size)
            assertEquals(InputError.SUBMISSION_PENDING, vm.state.value.networkFailure)
            assertTrue(vm.state.value.transfers.isEmpty())
            vm.prepareTransfer("OTHER", "0.001")
            advanceUntilIdle()
            assertEquals(InputError.SUBMISSION_PENDING, vm.state.value.error)
            vm.lock()
            advanceUntilIdle()
            val restored =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    hardware,
                    network,
                    DemoPublicKeyDeriver(),
                )
            advanceUntilIdle()
            restored.unlock("hidden456")
            advanceUntilIdle()
            assertTrue(restored.state.value.pendingTransfers.isEmpty())
            restored.retryPending("fixture-hash")
            advanceUntilIdle()
            assertEquals(1, submissions)
            restored.lock()
            restored.unlock("normal123")
            advanceUntilIdle()
            assertEquals(1, restored.state.value.pendingTransfers.size)
            assertEquals(1, submissions) // No automatic resume on unlock or restart.
            restored.saveNetworkSettings(
                NetworkSettings(NetworkProvider.MORALIS, moralisKey = "moralis_test_key"),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            available = true
            restored.retryPending("fixture-hash")
            advanceUntilIdle()
            assertEquals(1, signatures)
            assertEquals(listOf("PUBLIC-SIGNED-FIXTURE", "PUBLIC-SIGNED-FIXTURE"), payloads)
            assertEquals(1, restored.state.value.transfers.size)
            assertTrue(restored.state.value.pendingTransfers.isEmpty())
            assertEquals(
                asset.balance,
                restored.state.value.assets.first { it.id == asset.id }.balance,
            )
            restored.retryPending("fixture-hash")
            advanceUntilIdle()
            assertEquals(2, submissions)
            assertEquals(
                1,
                VaultRepository(storage, TestCrypto(), dispatcher).snapshot().transfers.size,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unsupportedPendingStaysVisibleAndCannotBroadcastAfterProviderSwitch() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            var submissions = 0
            var signatures = 0
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override suspend fun sign(
                        quote: TransferQuote,
                        paymentPin: String,
                        mode: WalletMode,
                    ): String {
                        signatures++
                        return "signed"
                    }
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun broadcast(
                        signedTransaction: String,
                        quote: TransferQuote,
                    ): String {
                        submissions++
                        return "btc-hash"
                    }
                }
            val vm = WalletViewModel(vault, hardware, network, DemoPublicKeyDeriver())
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            val btc = vm.state.value.assets.first { it.coin == "bitcoin" }
            val pending =
                PendingSubmission(
                    "btc-hash",
                    "public-signed-bytes",
                    network.estimate(btc, "MOCK-RECIPIENT", "0.001"),
                    WalletMode.STANDARD,
                )
            vault.update { it.copy(pendingSubmissions = listOf(pending)) }
            vm.open(btc)
            vm.send()
            vm.prepareTransfer("MOCK-RECIPIENT", "0.001")
            advanceUntilIdle()
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.MORALIS, moralisKey = "moralis_test_key"),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            assertEquals(Screen.ACCOUNTS, vm.state.value.screen)
            assertTrue(vm.state.value.assets.any { it.coin == "bitcoin" })
            assertEquals("btc-hash", vm.state.value.pendingTransfers.single().txId)
            assertFalse(ProviderCoverage.canSubmit(NetworkProvider.MORALIS, "bitcoin"))
            assertTrue(ProviderCoverage.canSubmit(NetworkProvider.AIRLOCK, "ethereum"))
            assertTrue(ProviderCoverage.canSubmit(NetworkProvider.BLOCKSTREAM, "ethereum"))
            vm.retryPending("btc-hash")
            advanceUntilIdle()
            assertEquals(InputError.NETWORK_UNSUPPORTED, vm.state.value.error)
            assertEquals(0, submissions)
            assertEquals(0, signatures)
            assertEquals(pending, vault.snapshot().pendingSubmissions.single())
            assertTrue(vault.snapshot().assets.any { it.id == btc.id })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unsupportedDraftRetryIsDisabledAndDoesNotStartAnotherOperation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var estimates = 0
            var balanceQueries = 0
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun estimate(
                        asset: Asset,
                        destination: String,
                        amount: String,
                    ): TransferQuote {
                        estimates++
                        throw InputException(InputError.PROVIDER_UNAVAILABLE)
                    }

                    override suspend fun balance(asset: Asset): String {
                        balanceQueries++
                        return asset.balance
                    }
                }
            val vm =
                WalletViewModel(
                    VaultRepository(MemoryStorage(), TestCrypto(), dispatcher),
                    object : HardwareWallet by DemoHardwareWallet() {
                        override val simulated = false
                    },
                    network,
                    DemoPublicKeyDeriver(),
                )
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.open(vm.state.value.assets.first { it.coin == "bitcoin" })
            vm.send()
            vm.prepareTransfer("MOCK-RECIPIENT", "0.001")
            advanceUntilIdle()
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.MORALIS, moralisKey = "moralis_test_key"),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            assertFalse(vm.state.value.canRetryNetworkFailure)
            vm.retryNetworkFailure()
            advanceUntilIdle()
            assertEquals(1, estimates)
            assertEquals(0, balanceQueries)
            vm.saveNetworkSettings(
                NetworkSettings(NetworkProvider.BLOCKSTREAM),
                "https://api.airlock.pub",
            )
            advanceUntilIdle()
            assertTrue(
                vm.state.value.canRetryNetworkFailure
            ) // Mock network stays offline; only the compatible explorer enables retry.
            vm.retryNetworkFailure()
            advanceUntilIdle()
            assertEquals(2, estimates)
            vm.cancelTransfer()
            assertNull(vm.state.value.transferDraft)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedJournalWritePreventsFirstBroadcast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            var sends = 0
            val hardware =
                object : HardwareWallet by DemoHardwareWallet() {
                    override suspend fun sign(
                        quote: TransferQuote,
                        paymentPin: String,
                        mode: WalletMode,
                    ): String {
                        storage.failWrites = true
                        return "signed"
                    }
                }
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override fun transactionId(signedTransaction: String, quote: TransferQuote) =
                        "hash"

                    override suspend fun broadcast(
                        signedTransaction: String,
                        quote: TransferQuote,
                    ): String {
                        sends++
                        return "hash"
                    }
                }
            val vm =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    hardware,
                    network,
                    DemoPublicKeyDeriver(),
                )
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            vm.open(vm.state.value.assets.first())
            vm.send()
            vm.prepareTransfer("MOCK-RECIPIENT", "0.001")
            advanceUntilIdle()
            vm.confirmTransfer("abc123")
            advanceUntilIdle()
            assertEquals(0, sends)
            assertEquals(InputError.OPERATION_FAILED, vm.state.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun partialBalanceFailurePreservesAssetsAndTheirLastKnownBalances() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val network =
                object : WalletNetwork by MockWalletNetwork() {
                    override suspend fun balance(asset: Asset): String =
                        if (asset.coin == "bitcoin")
                            throw InputException(InputError.PROVIDER_UNAVAILABLE)
                        else "12"
                }
            val vault = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
            val vm = WalletViewModel(vault, DemoHardwareWallet(), network, DemoPublicKeyDeriver())
            advanceUntilIdle()
            vm.setup("normal123", "hidden456")
            advanceUntilIdle()
            vm.unlock("normal123")
            advanceUntilIdle()
            syncTestWallet(vm)
            advanceUntilIdle()
            val before = vm.state.value.assets
            vm.refreshBalances()
            advanceUntilIdle()
            assertEquals(before.map { it.id }, vm.state.value.assets.map { it.id })
            assertEquals(
                before.first { it.coin == "bitcoin" }.balance,
                vm.state.value.assets.first { it.coin == "bitcoin" }.balance,
            )
            assertTrue(
                vm.state.value.assets.filter { it.coin != "bitcoin" }.all { it.balance == "12" }
            )
            assertEquals(InputError.PROVIDER_UNAVAILABLE, vm.state.value.networkFailure)
            assertEquals(1, vm.state.value.failedBalances.size)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
