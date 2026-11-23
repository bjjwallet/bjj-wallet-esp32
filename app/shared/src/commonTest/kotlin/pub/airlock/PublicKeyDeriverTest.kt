package pub.airlock

import fr.acinq.bitcoin.Base58Check
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pub.airlock.data.Bip32PublicKeyDeriver
import pub.airlock.domain.*

class PublicKeyDeriverTest {
    private val deriver = Bip32PublicKeyDeriver()

    @Test
    fun matchesIndependentWalletCoreVectorsAcrossAllNetworksAndBothWallets() = runTest {
        for ((mode, vectors) in
            listOf(
                WalletMode.STANDARD to PublicWalletFixtures.standard,
                WalletMode.HIDDEN to PublicWalletFixtures.hidden,
            )) {
            assertEquals(16, vectors.size)
            for (v in vectors) for (expected in v.addresses) {
                val asset = deriver.derive(v.account(mode), expected.index)
                assertEquals(
                    expected.address,
                    asset.address,
                    "${mode.name}/${v.coin}/${expected.index}",
                )
                assertEquals(expected.path, asset.derivationPath)
                assertEquals("0", asset.balance)
            }
        }
    }

    @Test
    fun passphraseWalletHasDifferentPublicKeysAndAddresses() = runTest {
        for (standard in PublicWalletFixtures.standard) {
            val hidden = PublicWalletFixtures.hidden.single { it.coin == standard.coin }
            if (standard.xpub.isNotEmpty()) assertNotEquals(standard.xpub, hidden.xpub)
            assertNotEquals(
                deriver.derive(standard.account(), 1).address,
                deriver.derive(hidden.account(WalletMode.HIDDEN), 1).address,
            )
        }
    }

    @Test
    fun rejectsPrivateVersionsMalformedKeysWrongDepthAndWrongPaths() = runTest {
        val account = PublicWalletFixtures.standard.first().account()
        val (version, payload) = Base58Check.decodeWithIntPrefix(account.xpub)
        val invalid =
            listOf(
                account.xpub.dropLast(1) + if (account.xpub.last() == '1') "2" else "1",
                Base58Check.encode(
                    0x0488ade4,
                    payload,
                ), // Private version; payload is still public fixture data.
                Base58Check.encode(0x0488b21e, payload), // Wrong script version for BIP84.
                Base58Check.encode(version, payload.copyOf().also { it[0] = 4 }),
                Base58Check.encode(
                    version,
                    payload.copyOf().also { it[8] = 1 },
                ), // Different hardened account.
                Base58Check.encode(
                    version,
                    payload.copyOf().also { it[41] = 0 },
                ), // Private-key marker.
                Base58Check.encode(version, payload + byteArrayOf(0)),
                Base58Check.encode(
                    version,
                    payload.copyOf().also { for (i in 42..73) it[i] = 0xff.toByte() },
                ),
            )
        for (key in invalid) {
            assertEquals(
                InputError.PUBLIC_DATA_INVALID,
                assertFailsWith<InputException> { deriver.derive(account.copy(xpub = key), 0) }
                    .reason,
            )
        }
        assertFailsWith<InputException> {
            deriver.derive(account.copy(path = "m/44'/0'/0'/0/0"), 0)
        }
    }

    @Test
    fun cannotDeriveHardenedOrNegativeAddressIndex() = runTest {
        val account = PublicWalletFixtures.standard.first().account()
        for (index in listOf(-1L, 0x80000000L, Long.MAX_VALUE)) {
            assertEquals(
                InputError.ADDRESS_INDEX_EXHAUSTED,
                assertFailsWith<InputException> { deriver.derive(account, index) }.reason,
            )
        }
    }

    @Test
    fun hardenedNetworksUseOnlyAddressesPreviouslyExportedByHardware() = runTest {
        for (v in PublicWalletFixtures.standard.filter { it.curve == "ed25519" }) {
            val account = v.account().copy(cachedAddresses = v.addresses.take(2))
            assertEquals(v.addresses[1].address, deriver.derive(account, 1).address)
            assertEquals(
                InputError.PUBLIC_DATA_SYNC_REQUIRED,
                assertFailsWith<InputException> { deriver.derive(account, 2) }.reason,
            )
            assertFailsWith<InputException> {
                deriver.derive(account.copy(xpub = PublicWalletFixtures.standard.first().xpub), 0)
            }
        }
    }
}
