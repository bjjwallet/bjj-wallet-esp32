@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import pub.airlock.data.*
import pub.airlock.domain.*
import pub.airlock.presentation.*

class WalletViewModelTest {
    @Test
    fun themePersistsAcrossRestartAndLockAndKeepsSelectionWhenWriteFails() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vm =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                )
            advanceUntilIdle()
            assertEquals(AppTheme.SYSTEM, vm.state.value.theme)
            for (theme in listOf(AppTheme.DARK, AppTheme.LIGHT, AppTheme.SYSTEM)) {
                vm.theme(theme)
                advanceUntilIdle()
                val restored =
                    WalletViewModel(
                        VaultRepository(storage, TestCrypto(), dispatcher),
                        DemoHardwareWallet(),
                        MockWalletNetwork(),
                    )
                advanceUntilIdle()
                assertEquals(theme, restored.state.value.theme)
            }
            vm.setup("123456", "654321")
            advanceUntilIdle()
            vm.unlock("123456")
            advanceUntilIdle()
            vm.theme(AppTheme.DARK)
            advanceUntilIdle()
            vm.lock()
            advanceUntilIdle()
            assertEquals(AppTheme.DARK, vm.state.value.theme)
            storage.failWrites = true
            vm.theme(AppTheme.LIGHT)
            advanceUntilIdle()
            assertEquals(AppTheme.DARK, vm.state.value.theme)
            assertEquals(InputError.OPERATION_FAILED, vm.state.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun legacyVaultWithoutThemeFollowsSystem() {
        assertEquals(
            AppTheme.SYSTEM,
            kotlinx.serialization.json.Json.decodeFromString<VaultSnapshot>("{}").theme,
        )
    }

    @Test
    fun allLanguagesPersistBeforeSetupAndAfterLocking() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val repository = VaultRepository(storage, TestCrypto(), dispatcher)
            val vm = WalletViewModel(repository, DemoHardwareWallet(), MockWalletNetwork())
            advanceUntilIdle()
            assertNull(vm.state.value.language)
            for (language in AppLanguage.entries) {
                vm.language(language.code)
                advanceUntilIdle()
                // A new repository verifies durable storage, not just the ViewModel/cache.
                val restored =
                    WalletViewModel(
                        VaultRepository(storage, TestCrypto(), dispatcher),
                        DemoHardwareWallet(),
                        MockWalletNetwork(),
                    )
                advanceUntilIdle()
                assertEquals(language.code, restored.state.value.language)
                assertEquals(Screen.SYNC, restored.state.value.screen)
            }
            vm.setup("123456", "654321")
            advanceUntilIdle()
            vm.unlock("123456")
            advanceUntilIdle()
            vm.language("fr")
            advanceUntilIdle()
            vm.lock()
            advanceUntilIdle()
            assertEquals("fr", vm.state.value.language)
            assertEquals(
                "fr",
                VaultRepository(storage, TestCrypto(), dispatcher).snapshot().language,
            )
            vm.language("xx")
            advanceUntilIdle()
            assertEquals(InputError.INVALID_LANGUAGE, vm.state.value.error)
            assertEquals("fr", vm.state.value.language)
            vm.language(null)
            advanceUntilIdle()
            assertNull(vm.state.value.language)
            val restored =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                )
            advanceUntilIdle()
            assertNull(restored.state.value.language)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun preservesPreviouslySavedLanguagesAndFallsBackForUnknownCodes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for ((saved, expected) in listOf("en" to "en", "zh" to "zh", "xx" to null)) {
                val repository = VaultRepository(MemoryStorage(), TestCrypto(), dispatcher)
                repository.update { it.copy(language = saved) }
                val vm = WalletViewModel(repository, DemoHardwareWallet(), MockWalletNetwork())
                advanceUntilIdle()
                assertEquals(expected, vm.state.value.language)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun validationErrorsExposeTranslatableCodes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val vm =
                WalletViewModel(
                    VaultRepository(MemoryStorage(), TestCrypto(), dispatcher),
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                )
            advanceUntilIdle()
            vm.setup("123456", "123456")
            advanceUntilIdle()
            assertEquals(InputError.PIN_SAME, vm.state.value.error)
            vm.setup("123456", "654321")
            advanceUntilIdle()
            vm.unlock("000000")
            advanceUntilIdle()
            assertEquals(InputError.INCORRECT_PIN, vm.state.value.error)
            vm.unlock("123456")
            advanceUntilIdle()
            vm.saveServer("http://example.test")
            advanceUntilIdle()
            assertEquals(InputError.HTTPS_REQUIRED, vm.state.value.error)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedPreferenceWriteKeepsPreviousLanguage() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val storage = MemoryStorage()
            val vm =
                WalletViewModel(
                    VaultRepository(storage, TestCrypto(), dispatcher),
                    DemoHardwareWallet(),
                    MockWalletNetwork(),
                )
            advanceUntilIdle()
            vm.language("es")
            advanceUntilIdle()
            storage.failWrites = true
            vm.language("de")
            advanceUntilIdle()
            assertEquals("es", vm.state.value.language)
            assertEquals(InputError.OPERATION_FAILED, vm.state.value.error)
            storage.failWrites = false
            assertEquals(
                "es",
                VaultRepository(storage, TestCrypto(), dispatcher).snapshot().language,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun completeFirstRunAndLockClearsWalletFromUi() = runTest {
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
            fun idle() {
                advanceUntilIdle()
            }
            idle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            vm.setup("123456", "654321")
            idle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            vm.unlock("123456")
            idle()
            assertEquals(Screen.SYNC, vm.state.value.screen)
            syncTestWallet(vm)
            advanceUntilIdle()
            idle()
            assertEquals(5, vm.state.value.accounts.size)
            vm.open(vm.state.value.accounts.first())
            assertEquals(Screen.ASSET, vm.state.value.screen)
            vm.lock()
            advanceUntilIdle()
            assertEquals(Screen.LOGIN, vm.state.value.screen)
            assertTrue(vm.state.value.accounts.isEmpty())
            assertTrue(vm.state.value.assets.isEmpty())
            assertNull(vm.state.value.mode)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
