package pub.airlock.data.network

import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import org.kotlincrypto.hash.sha3.Keccak256
import pub.airlock.domain.*

/** Exact unsigned quantities and ABI encoding. No chain amount passes through floating point. */
internal object EvmEncoding {
    fun decimal(hex: String): String {
        if (!Regex("0x[0-9a-fA-F]{1,64}").matches(hex))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        return hex.drop(2).fold("0") { total, digit ->
            Amount.add(Amount.multiply(total, "16"), digit.digitToInt(16).toString())
        }
    }

    fun hex(decimal: String): String {
        requireInput(Regex("[0-9]{1,78}").matches(decimal), InputError.INVALID_AMOUNT)
        var input = decimal.trimStart('0').ifEmpty { "0" }
        if (input == "0") return "0x0"
        val result = StringBuilder()
        while (input != "0") {
            var carry = 0
            input =
                buildString {
                        for (digit in input) {
                            val value = carry * 10 + digit.digitToInt()
                            append(value / 16)
                            carry = value % 16
                        }
                    }
                    .trimStart('0')
                    .ifEmpty { "0" }
            result.append("0123456789abcdef"[carry])
        }
        requireInput(result.length <= 64, InputError.INVALID_AMOUNT)
        return "0x" + result.reverse()
    }

    fun address(value: String): String {
        requireInput(Regex("0x[0-9a-fA-F]{40}").matches(value), InputError.INVALID_DESTINATION)
        val digits = value.drop(2)
        if (digits.any { it in 'a'..'f' } && digits.any { it in 'A'..'F' }) {
            val hash = Keccak256().digest(digits.lowercase().encodeToByteArray()).hex()
            requireInput(
                digits.indices.all { i ->
                    !digits[i].isLetter() ||
                        digits[i].isUpperCase() == (hash[i].digitToInt(16) >= 8)
                },
                InputError.INVALID_DESTINATION,
            )
        }
        return value.lowercase()
    }

    fun payment(asset: Asset, destination: String, amount: String): Triple<String, String, String> {
        val receiver = address(destination)
        address(asset.address)
        val atomic = Amount.atomic(amount, asset.decimals)
        requireInput(atomic != "0", InputError.AMOUNT_POSITIVE)
        val value = hex(atomic)
        return if (asset.contract.isEmpty()) Triple(receiver, value, "0x")
        else
            Triple(
                address(asset.contract),
                "0x0",
                "0xa9059cbb" + receiver.drop(2).padStart(64, '0') + value.drop(2).padStart(64, '0'),
            )
    }

    fun bytes(hex: String): ByteArray {
        require(
            hex.startsWith("0x") &&
                hex.length % 2 == 0 &&
                hex.length <= 131074 &&
                hex.drop(2).all { it in "0123456789abcdefABCDEF" }
        )
        return hex.drop(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    fun unsigned(plan: EvmTransactionPlan): ByteArray {
        fun quantity(value: String): ByteArray {
            val canonical = hex(decimal(value)).drop(2)
            return if (canonical == "0") byteArrayOf()
            else bytes("0x" + canonical.padStart(canonical.length + canonical.length % 2, '0'))
        }
        return LegacyRlp.encode(
            listOf(
                quantity(plan.nonce),
                quantity(plan.gasPrice),
                quantity(plan.gasLimit),
                bytes(plan.to),
                quantity(plan.value),
                bytes(plan.data),
                quantity(hex(plan.chainId.toString())),
                byteArrayOf(),
                byteArrayOf(),
            )
        )
    }

    /**
     * Accept only the EIP-155 format produced by our plan. Recover the sender using libsecp256k1;
     * reject unsigned, cross-chain, altered and unsupported typed payloads before any HTTP request.
     */
    fun transactionId(raw: String, quote: TransferQuote): String =
        try {
            require(EvmNetworks.supportsPayment(quote.asset))
            val plan = quote.evmPlan ?: error("Missing plan")
            require(plan.chainId == EvmNetworks.chainIds[quote.asset.coin])
            val payment = payment(quote.asset, quote.destination, quote.amount)
            require(payment == Triple(plan.to, plan.value, plan.data))
            require(quote.feeCurrency == CoinCatalog.get(quote.asset.coin))
            var fee = Amount.multiply(decimal(plan.gasLimit), decimal(plan.gasPrice))
            if (quote.asset.coin == "base") {
                val l1 = plan.l1Fee ?: error("Missing L1 fee")
                val operator = plan.operatorFee ?: error("Missing operator fee")
                require(l1 != "0" && decimal(hex(l1)) == l1 && decimal(hex(operator)) == operator)
                fee = Amount.add(fee, Amount.add(l1, operator))
            } else require(plan.l1Fee == null && plan.operatorFee == null)
            require(Amount.atomic(quote.fee, 18) == fee)
            val bytes = bytes(raw)
            val fields = LegacyRlp.decode(bytes)
            require(fields.size == 9)
            fun number(i: Int): String {
                require(fields[i].size <= 32 && (fields[i].isEmpty() || fields[i][0] != 0.toByte()))
                return if (fields[i].isEmpty()) "0" else decimal("0x" + fields[i].hex())
            }
            require(
                number(0) == decimal(plan.nonce) &&
                    number(1) == decimal(plan.gasPrice) &&
                    number(2) == decimal(plan.gasLimit)
            )
            require(
                "0x" + fields[3].hex() == plan.to &&
                    number(4) == decimal(plan.value) &&
                    "0x" + fields[5].hex() == plan.data
            )
            val v = number(6).toLong()
            val recovery = v - (plan.chainId * 2 + 35)
            require(recovery in 0..1)
            require(number(7) != "0" && number(8) != "0")
            require(
                Amount.compare(
                    number(8),
                    decimal("0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0"),
                ) <= 0
            )
            val signingData = unsigned(plan)
            fun padded(b: ByteArray) = ByteArray(32 - b.size) + b
            val signature = ByteVector64(padded(fields[7]) + padded(fields[8]))
            val publicKey =
                Crypto.recoverPublicKey(
                    signature,
                    Keccak256().digest(signingData),
                    recovery.toInt(),
                )
            val sender =
                "0x" +
                    Keccak256()
                        .digest(publicKey.toUncompressedBin().drop(1).toByteArray())
                        .takeLast(20)
                        .toByteArray()
                        .hex()
            require(sender == quote.asset.address.lowercase())
            "0x" + Keccak256().digest(bytes).hex()
        } catch (_: Exception) {
            throw InputException(InputError.INVALID_QUOTE)
        }
}

/** Strict, bounded flat RLP for legacy Ethereum transactions, not a general recursive decoder. */
internal object LegacyRlp {
    fun decode(input: ByteArray): List<ByteArray> {
        var offset = 0
        fun next(): Int {
            require(offset < input.size)
            return input[offset++].toInt() and 255
        }
        fun length(prefix: Int, shortBase: Int, longBase: Int): Int {
            if (prefix <= longBase) return prefix - shortBase
            val count = prefix - longBase
            require(count in 1..3)
            var size = next()
            require(size != 0)
            repeat(count - 1) { size = size * 256 + next() }
            require(size > 55)
            return size
        }
        val first = next()
        require(first >= 0xc0)
        val size = length(first, 0xc0, 0xf7)
        require(size == input.size - offset)
        val result = mutableListOf<ByteArray>()
        while (offset < input.size) {
            require(result.size < 9)
            val prefix = next()
            require(prefix < 0xc0)
            if (prefix < 0x80) result += byteArrayOf(prefix.toByte())
            else {
                val n = length(prefix, 0x80, 0xb7)
                require(n <= input.size - offset)
                val value = input.copyOfRange(offset, offset + n)
                require(n != 1 || (value[0].toInt() and 255) >= 0x80)
                offset += n
                result += value
            }
        }
        return result
    }

    fun encode(fields: List<ByteArray>): ByteArray {
        fun prefix(size: Int, base: Int): ByteArray {
            if (size <= 55) return byteArrayOf((base + size).toByte())
            val hex = size.toString(16).let { if (it.length % 2 == 0) it else "0$it" }
            val bytes = EvmEncoding.bytes("0x$hex")
            return byteArrayOf((base + 55 + bytes.size).toByte()) + bytes
        }
        val body =
            fields.fold(byteArrayOf()) { acc, field ->
                acc +
                    if (field.size == 1 && (field[0].toInt() and 255) < 128) field
                    else prefix(field.size, 0x80) + field
            }
        return prefix(body.size, 0xc0) + body
    }
}
