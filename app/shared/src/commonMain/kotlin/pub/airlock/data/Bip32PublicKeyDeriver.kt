package pub.airlock.data

import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kotlincrypto.hash.sha3.Keccak256
import pub.airlock.domain.*

/** Watch-only: accepts public account keys. No seed, mnemonic, xprv or signing operation. */
class Bip32PublicKeyDeriver(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) :
    PublicKeyDeriver {
    override suspend fun derive(account: Account, index: Long): Asset =
        withContext(dispatcher) {
            val path = PublicNetworks.path(account.coin, index)
            val network = PublicNetworks.get(account.coin)
            val address =
                if (network.extended) {
                    val key = decode(account).derivePublicKey(listOf(0L, index)).publicKey
                    address(account.coin, key)
                } else {
                    requireInput(account.xpub.isEmpty(), InputError.PUBLIC_DATA_INVALID)
                    account.cachedAddresses
                        .singleOrNull { it.index == index && it.path == path }
                        ?.address ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
                }
            val coin = CoinCatalog.get(account.coin)
            Asset(
                "${account.id}:$index",
                account.id,
                coin.coin,
                coin.symbol,
                coin.name,
                address,
                coin.icon,
                coin.decimals,
                derivationPath = path,
            )
        }

    private fun decode(account: Account): DeterministicWallet.ExtendedPublicKey {
        try {
            require(account.path == PublicNetworks.get(account.coin).path)
            require(account.xpub.length in 100..120)
            val (version, payload) = Base58Check.decodeWithIntPrefix(account.xpub)
            // A depth-three public account key; only /0/index non-hardened children are derived.
            require(payload.size == 74 && payload[0] == 3.toByte())
            require(payload.sliceArray(5..8).contentEquals(byteArrayOf(0x80.toByte(), 0, 0, 0)))
            require(payload[41] == 2.toByte() || payload[41] == 3.toByte())
            val expectedVersion =
                when (account.coin) {
                    "bitcoin",
                    "litecoin" -> DeterministicWallet.zpub
                    "dogecoin" -> 0x02facafd // SLIP-0132 dgub
                    else -> DeterministicWallet.xpub
                }
            require(version == expectedVersion)
            // ACINQ decodes standard versions; dgub carries the same BIP32 public payload.
            val standard = Base58Check.encode(DeterministicWallet.xpub, payload)
            return DeterministicWallet.ExtendedPublicKey.decode(standard).second
        } catch (e: InputException) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw InputException(InputError.PUBLIC_DATA_INVALID)
        } catch (_: IndexOutOfBoundsException) {
            throw InputException(InputError.PUBLIC_DATA_INVALID)
        }
    }

    private fun address(coin: String, key: PublicKey): String =
        when (coin) {
            "bitcoin" -> Bech32.encodeWitnessAddress("bc", 0, key.hash160())
            "litecoin" -> Bech32.encodeWitnessAddress("ltc", 0, key.hash160())
            "dogecoin" -> Base58Check.encode(30.toByte(), key.hash160())
            "dash" -> Base58Check.encode(76.toByte(), key.hash160())
            "bitcoincash",
            "ecash" -> cashAddress(coin, key.hash160())
            "ripple" -> {
                val bitcoin = Base58Check.encode(0.toByte(), key.hash160())
                val standard = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
                val ripple = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
                bitcoin.map { ripple[standard.indexOf(it)] }.joinToString("")
            }
            "tron" -> Base58Check.encode(0x41.toByte(), ethereumBytes(key))
            "ethereum",
            "bsc",
            "avalanche",
            "ethereumclassic",
            "arbitrum",
            "polygon",
            in PublicNetworks.ethereumAliases -> {
                val hex = ethereumBytes(key).hex()
                val checksum = Keccak256().digest(hex.encodeToByteArray()).hex()
                "0x" +
                    hex.mapIndexed { i, c ->
                            if (checksum[i].digitToInt(16) >= 8) c.uppercaseChar() else c
                        }
                        .joinToString("")
            }
            else -> throw InputException(InputError.PUBLIC_DATA_INVALID)
        }

    private fun ethereumBytes(key: PublicKey) =
        Keccak256().digest(key.toUncompressedBin().drop(1).toByteArray()).takeLast(20).toByteArray()

    /**
     * CashAddr encoding of a P2PKH HASH160; checksum/encoding only, no cryptographic primitives.
     */
    private fun cashAddress(prefix: String, hash: ByteArray): String {
        val data = Bech32.eight2five(byteArrayOf(0) + hash).map { it.toInt() }
        val values = prefix.map { it.code and 31 } + 0 + data + List(8) { 0 }
        val generators =
            longArrayOf(0x98f2bc8e61, 0x79b76d99e2, 0xf33e5fb3c4, 0xae2eabe2a8, 0x1e4f43e470)
        var checksum = 1L
        for (value in values) {
            val top = checksum ushr 35
            checksum = ((checksum and 0x07ffffffff) shl 5) xor value.toLong()
            for (i in 0..4) if (((top ushr i) and 1L) != 0L) checksum = checksum xor generators[i]
        }
        checksum = checksum xor 1L
        val suffix = (0..7).map { ((checksum ushr (5 * (7 - it))) and 31).toInt() }
        return prefix + ":" + (data + suffix).map { Bech32.alphabet[it] }.joinToString("")
    }
}

private fun ByteArray.hex() =
    joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
