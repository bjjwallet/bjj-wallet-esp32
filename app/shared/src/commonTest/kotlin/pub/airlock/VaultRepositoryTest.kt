package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pub.airlock.data.*
import pub.airlock.domain.*

internal class MemoryStorage(var value: String? = null) : VaultStorage {
    var failWrites = false

    override fun read() = VaultRead(value)

    override fun write(value: String) {
        if (failWrites) error("Disk full")
        this.value = value
    }
}

/** Test double only. Production hosts always inject PBKDF2 implementations. */
internal class TestCrypto : PinCrypto {
    override fun randomHex(bytes: Int) = "a1".repeat(bytes)

    override fun derive(pin: String, saltHex: String) = "$saltHex:$pin"
}

class VaultRepositoryTest {
    @Test
    fun appPinVerifierAndPublicExportCommitTogetherAndSurviveRestart() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val data = DemoHardwareWallet().synchronizeWallets()
        val configuration = vault.prepareAppSecurity("app123")
        assertNull(storage.value)
        vault.commitHardwareSynchronization(data, configuration)
        val restored = VaultRepository(storage, TestCrypto(), dispatcher).snapshot()
        assertEquals(configuration.pin, restored.appPin)
        assertTrue(restored.appSecurityConfigured)
        assertTrue(restored.accounts.isNotEmpty())
        assertTrue(restored.assets.isNotEmpty())
        val raw = storage.value
        storage.failWrites = true
        assertFailsWith<IllegalStateException> {
            vault.commitHardwareSynchronization(data, vault.prepareAppSecurity("other123"))
        }
        assertEquals(raw, storage.value)
        assertEquals(restored, vault.snapshot())
        assertEquals(restored, VaultRepository(storage, TestCrypto(), dispatcher).snapshot())
    }

    @Test
    fun invalidPublicExportCannotSaveAppProtectionAndResyncPreservesIt() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        val data = DemoHardwareWallet().synchronizeWallets()
        val configuration = vault.prepareAppSecurity("app123")
        assertFailsWith<InputException> {
            vault.commitHardwareSynchronization(
                data.filterKeys { it == WalletMode.STANDARD },
                configuration,
            )
        }
        assertNull(storage.value)
        vault.commitHardwareSynchronization(data, configuration)
        val before = vault.snapshot()
        val invalid = data + (WalletMode.HIDDEN to data.getValue(WalletMode.STANDARD))
        assertFailsWith<IllegalArgumentException> {
            vault.commitHardwareSynchronization(invalid, AppSecurityConfiguration(null))
        }
        assertEquals(before, vault.snapshot())
        vault.commitHardwareSynchronization(data)
        assertEquals(before, VaultRepository(storage, TestCrypto(), dispatcher).snapshot())
    }

    @Test
    fun loadingPreviousBuildRemovesSavedColdWalletPinsAndPreservesOtherData() = runTest {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto(), dispatcher)
        vault.configureAppSecurity("app123")
        vault.update { it.copy(language = "pt", theme = AppTheme.DARK) }
        val previous = vault.snapshot()
        storage.value =
            storage.value!!.dropLast(1) +
                ",\"hardwareCredentials\":{\"device\":{\"accessPin\":\"001234\"}}}"
        assertEquals(previous, VaultRepository(storage, TestCrypto(), dispatcher).snapshot())
        assertFalse(storage.value!!.contains("hardwareCredentials"))
        assertFalse(storage.value!!.contains("001234"))
        assertTrue(VaultRepository(storage, TestCrypto(), dispatcher).unlockApp("app123"))
    }

    @Test
    fun moneroKeepsTheLastVerifiedBalanceAndTransactionAcrossRestart() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto())
        val mode = WalletMode.HIDDEN
        val wallet =
            MoneroWatchWallet(
                "4AdUndXHHZ6cfufTMvppY6JwXNouMBzSkbLYfpAV5Usx3skxNgYeYTRj5UzqtReoS44qo9mtmXCqY45DJ852K5Jv2684Rge",
                "01" + "00".repeat(31),
                10,
                listOf(MoneroKeyImage("2".repeat(64), "3".repeat(128))),
            )
        assertFalse(wallet.toString().contains(wallet.privateViewKey))
        assertFailsWith<InputException> { wallet.copy(address = wallet.address.dropLast(1) + "f") }
        assertFailsWith<InputException> { wallet.copy(privateViewKey = "ff".repeat(32)) }
        vault.saveMoneroWatchWallet(mode, wallet)
        vault.refreshMonero(mode) { null }
        assertEquals(wallet.address, vault.assets(mode).single().address)
        assertNull(vault.snapshot().moneroScanHeights[mode])
        val walletId = vault.snapshot().activeWalletId!!
        vault.setMoneroRestoreHeight(mode, walletId, 11)
        vault.saveMoneroWatchWallet(mode, wallet.copy(restoreHeight = 0), walletId)
        assertEquals(
            11L,
            VaultRepository(storage, TestCrypto())
                .snapshot()
                .moneroWatchWallets
                .getValue(mode)
                .scanFromHeight,
        )
        assertTrue(vault.snapshot().moneroWatchWallets[WalletMode.STANDARD] == null)
        val last = MoneroTransfer("4".repeat(64), "1.25", true)
        vault.refreshMonero(mode) { input ->
            assertEquals(wallet.keyImages, input.keyImages)
            MoneroScan("1.25", 12, last, unlockedBalance = "1")
        }
        val reopened = VaultRepository(storage, TestCrypto())
        reopened.refreshMonero(mode) { null }
        reopened.refreshMonero(mode) { MoneroScan("0", 11, null) }
        val snapshot = reopened.snapshot()
        val data = ProviderCoverage.wallet(snapshot, mode, NetworkProvider.COINBASE)
        assertEquals("1.25", data.assets.single().balance)
        assertEquals("1", data.assets.single().unlockedBalance)
        assertEquals(last.txId, snapshot.transfers.single().id)
        assertEquals(12L, snapshot.moneroScanHeights[mode])
        assertTrue(ProviderCoverage.wallet(snapshot, WalletMode.STANDARD).assets.isEmpty())
    }

    @Test
    fun pinModesAndPersistence() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto())
        vault.configurePins("normal123", "hidden456")
        assertEquals(WalletMode.STANDARD, vault.unlock("normal123"))
        assertEquals(WalletMode.HIDDEN, vault.unlock("hidden456"))
        assertNull(vault.unlock("wrong123"))
        assertEquals(WalletMode.HIDDEN, VaultRepository(storage, TestCrypto()).unlock("hidden456"))
    }

    @Test
    fun refusesMatchingPinsAndReinitialization() = runTest {
        val vault = VaultRepository(MemoryStorage(), TestCrypto())
        assertFailsWith<IllegalArgumentException> { vault.configurePins("123456", "123456") }
        vault.configurePins("123456", "654321")
        assertFailsWith<IllegalStateException> { vault.configurePins("abcdef", "fedcba") }
    }

    @Test
    fun modeIsolationSurvivesSynchronizationAndRestart() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto())
        val demo = DemoHardwareWallet()
        vault.merge(WalletMode.STANDARD, demo.synchronize(WalletMode.STANDARD))
        vault.merge(WalletMode.HIDDEN, demo.synchronize(WalletMode.HIDDEN))
        val reopened = VaultRepository(storage, TestCrypto())
        assertEquals(5, reopened.accounts(WalletMode.STANDARD).size)
        assertTrue(reopened.assets(WalletMode.STANDARD).all { "STANDARD" in it.address })
        assertTrue(
            reopened.assets(WalletMode.HIDDEN).all { "HIDDEN" in it.address && it.balance == "0" }
        )
        vault.merge(WalletMode.STANDARD, demo.synchronize(WalletMode.STANDARD))
        assertEquals(38, vault.snapshot().accounts.size)
    }

    @Test
    fun failedWriteDoesNotPublishNewState() = runTest {
        val storage = MemoryStorage()
        val vault = VaultRepository(storage, TestCrypto())
        vault.configurePins("123456", "654321")
        storage.failWrites = true
        assertFailsWith<IllegalStateException> {
            vault.changePin(WalletMode.STANDARD, "123456", "abcdef")
        }
        assertEquals(WalletMode.STANDARD, vault.unlock("123456"))
        assertNull(vault.unlock("abcdef"))
    }

    @Test
    fun changingOnePinPreservesOtherMode() = runTest {
        val vault = VaultRepository(MemoryStorage(), TestCrypto())
        vault.configurePins("123456", "654321")
        assertFailsWith<IllegalArgumentException> {
            vault.changePin(WalletMode.STANDARD, "123456", "654321")
        }
        vault.changePin(WalletMode.STANDARD, "123456", "abcdef")
        assertNull(vault.unlock("123456"))
        assertEquals(WalletMode.HIDDEN, vault.unlock("654321"))
        assertEquals(WalletMode.STANDARD, vault.unlock("abcdef"))
    }

    @Test
    fun corruptedDataIsNeverReplacedByEmptyVault() = runTest {
        val storage = MemoryStorage("corrupt")
        assertFails { VaultRepository(storage, TestCrypto()).snapshot() }
        assertEquals("corrupt", storage.value)
    }

    @Test
    fun rejectsAccountsForAnotherMode() = runTest {
        val vault = VaultRepository(MemoryStorage(), TestCrypto())
        val data = DemoHardwareWallet().synchronize(WalletMode.HIDDEN)
        assertFailsWith<IllegalArgumentException> { vault.merge(WalletMode.STANDARD, data) }
        assertTrue(vault.snapshot().accounts.isEmpty())
    }

    @Test
    fun refusesAssetCollisionAcrossWalletModes() = runTest {
        val vault = VaultRepository(MemoryStorage(), TestCrypto())
        val demo = DemoHardwareWallet()
        val standard = demo.synchronize(WalletMode.STANDARD)
        vault.merge(WalletMode.STANDARD, standard)
        val before = vault.snapshot()
        val hidden = demo.synchronize(WalletMode.HIDDEN)
        val collision =
            hidden.copy(
                assets =
                    hidden.assets.mapIndexed { index, asset ->
                        if (index == 0) asset.copy(id = standard.assets.first().id) else asset
                    }
            )
        assertFailsWith<IllegalArgumentException> { vault.merge(WalletMode.HIDDEN, collision) }
        assertEquals(before, vault.snapshot())
    }
}
