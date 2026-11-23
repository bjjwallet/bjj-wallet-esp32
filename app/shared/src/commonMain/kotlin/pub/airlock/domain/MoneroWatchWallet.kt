package pub.airlock.domain

import kotlinx.serialization.Serializable
import org.kotlincrypto.hash.sha3.Keccak256

@Serializable
data class MoneroKeyImage(val image: String, val signature: String) {
    init {
        requireInput(
            image.matches(Regex("[0-9a-f]{64}")) && signature.matches(Regex("[0-9a-f]{128}")),
            InputError.PUBLIC_DATA_INVALID,
        )
    }

    override fun toString() = "MoneroKeyImage(redacted)"
}

@Serializable
enum class MoneroNetwork(val addressPrefix: Byte) {
    MAINNET(18),
    STAGENET(24),
}

/** Only for the encrypted vault and the in-process scanner. */
@Serializable
data class MoneroWatchWallet(
    val address: String,
    val privateViewKey: String,
    val restoreHeight: Long,
    // Complete, ordered, offset-zero export from the hardware.
    val keyImages: List<MoneroKeyImage>,
    val network: MoneroNetwork = MoneroNetwork.MAINNET,
    val restoreHeightOverride: Long? = null,
) {
    init {
        requireInput(
            isMoneroAddress(address, network) &&
                isMoneroSecretScalar(privateViewKey) &&
                restoreHeight >= 0 &&
                (restoreHeightOverride == null || restoreHeightOverride >= 0) &&
                keyImages.distinctBy { it.image }.size == keyImages.size,
            InputError.PUBLIC_DATA_INVALID,
        )
    }

    val scanFromHeight: Long
        get() = restoreHeightOverride ?: restoreHeight

    override fun toString() = "MoneroWatchWallet(redacted)"
}

data class MoneroReceiveAddress(val address: String, val network: MoneroNetwork) {
    init {
        requireInput(isMoneroAddress(address, network), InputError.PUBLIC_DATA_INVALID)
    }
}

data class MoneroScan(
    val balance: String,
    val height: Long,
    val lastTransfer: MoneroTransfer?,
    val unlockedBalance: String? = null,
)

data class MoneroTransfer(val txId: String, val amount: String, val incoming: Boolean)

/**
 * In-process watch-only engine. A result requires verifying the address/view-key pair, verifying
 * the signed images for all scanned outputs, and checking their spent status. Return null while
 * scanning or when any output lacks a verified image.
 */
fun interface MoneroScanner {
    suspend fun scan(wallet: MoneroWatchWallet): MoneroScan?

    /** Export the complete ordered set once a scan finds outputs without verified images. */
    suspend fun exportOutputs(wallet: MoneroWatchWallet): MoneroOutputs? = null

    suspend fun prepareTransfer(
        wallet: MoneroWatchWallet,
        recipient: String,
        amount: String,
    ): MoneroUnsignedTransfer = throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    suspend fun submitTransfer(txid: String, raw: String): TransferStatus =
        throw InputException(InputError.INTEGRATION_UNAVAILABLE)

    fun clear() = Unit
}

class MoneroUnsignedTransfer(val hex: String, val feeAtomic: String) {
    init {
        requireInput(
            hex.length in 2..262_144 &&
                hex.length % 2 == 0 &&
                hex.all { it in '0'..'9' || it in 'a'..'f' } &&
                feeAtomic.toULongOrNull()?.let { it > 0uL } == true &&
                feeAtomic.all { it in '0'..'9' },
            InputError.INVALID_QUOTE,
        )
    }

    override fun toString() = "MoneroUnsignedTransfer(redacted)"
}

class MoneroSignedTransfer(val txid: String, val hex: String) {
    init {
        requireInput(
            txid.matches(Regex("[0-9a-f]{64}")) &&
                hex.length in 2..32768 &&
                hex.length % 2 == 0 &&
                hex.all { it in '0'..'9' || it in 'a'..'f' },
            InputError.PUBLIC_DATA_INVALID,
        )
    }

    override fun toString() = "MoneroSignedTransfer(redacted)"
}

class MoneroOutputs(val hex: String, val count: Int) {
    init {
        // Leave room for the JSON envelope and framing inside the BLE frame limit.
        requireInput(
            count > 0 &&
                hex.length in 2..1_040_000 &&
                hex.length % 2 == 0 &&
                hex.all { it in '0'..'9' || it in 'a'..'f' },
            InputError.PUBLIC_DATA_INVALID,
        )
    }

    override fun toString() = "MoneroOutputs(redacted)"
}

private const val moneroBase58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

// Order of the Edwards25519 base point, expressed most significant byte first.
private const val moneroScalarOrder =
    "1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed"

private fun isMoneroSecretScalar(key: String): Boolean {
    if (!Regex("[0-9a-f]{64}").matches(key) || key.all { it == '0' }) return false
    return key.chunked(2).asReversed().joinToString("") < moneroScalarOrder
}

internal fun isMoneroAddress(address: String, network: MoneroNetwork): Boolean {
    if (address.length != 95) return false
    val bytes = ByteArray(69)
    var offset = 0
    for (block in address.chunked(11)) {
        var value = 0UL
        for (char in block) {
            val digit = moneroBase58.indexOf(char)
            if (digit < 0 || value > (ULong.MAX_VALUE - digit.toULong()) / 58UL) return false
            value = value * 58UL + digit.toULong()
        }
        val size = if (block.length == 11) 8 else 5
        for (index in offset + size - 1 downTo offset) {
            bytes[index] = value.toByte()
            value = value shr 8
        }
        if (value != 0UL) return false
        offset += size
    }
    return bytes[0] == network.addressPrefix &&
        Keccak256()
            .digest(bytes.copyOfRange(0, 65))
            .copyOf(4)
            .contentEquals(bytes.copyOfRange(65, 69))
}
