package pub.airlock.data.network

import com.ionspin.kotlin.bignum.integer.BigInteger
import fr.acinq.bitcoin.Base58
import fr.acinq.bitcoin.Crypto
import pub.airlock.domain.*

/** Public PDA arithmetic only. Signing keys stay in the cold wallet. */
internal object SolanaTokenAddress {
    const val tokenProgram = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val associatedProgram = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private val prime = (BigInteger.ONE shl 255) - BigInteger.fromInt(19)
    private val d =
        BigInteger.parseString(
            "37095705934669439343138083508754565189542113879843219016388785533085940283555",
            10,
        )

    private fun power(value: BigInteger, exponent: BigInteger): BigInteger {
        var base = value % prime
        var remaining = exponent
        var result = BigInteger.ONE
        while (remaining > BigInteger.ZERO) {
            if ((remaining and BigInteger.ONE) == BigInteger.ONE) result = result * base % prime
            base = base * base % prime
            remaining = remaining shr 1
        }
        return result
    }

    private fun onCurve(encoded: ByteArray): Boolean {
        val bytes = encoded.copyOf()
        bytes[31] = (bytes[31].toInt() and 127).toByte()
        val y =
            BigInteger.parseString(
                bytes.reversedArray().joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                },
                16,
            )
        val square = y * y % prime
        val xSquare =
            (square + prime - BigInteger.ONE) *
                power((d * square + BigInteger.ONE) % prime, prime - BigInteger.TWO) % prime
        return xSquare == BigInteger.ZERO ||
            power(xSquare, (prime - BigInteger.ONE) shr 1) == BigInteger.ONE
    }

    fun associated(owner: String, mint: String, program: String = tokenProgram): ByteArray {
        val ownerKey = Base58.decode(owner)
        val mintKey = Base58.decode(mint)
        requireInput(ownerKey.size == 32 && mintKey.size == 32, InputError.INVALID_DESTINATION)
        requireInput(
            program == SolanaNetwork.classicProgram || program == SolanaNetwork.extensionsProgram,
            InputError.INVALID_QUOTE,
        )
        val seeds = ownerKey + Base58.decode(program) + mintKey
        for (bump in 255 downTo 0) {
            val hash =
                Crypto.sha256(
                    seeds +
                        byteArrayOf(bump.toByte()) +
                        Base58.decode(associatedProgram) +
                        "ProgramDerivedAddress".encodeToByteArray()
                )
            if (!onCurve(hash)) return hash
        }
        throw InputException(InputError.INVALID_DESTINATION)
    }
}
