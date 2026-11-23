package pub.airlock.data.network

import fr.acinq.bitcoin.Transaction
import pub.airlock.data.network.EvmEncoding.hex
import pub.airlock.domain.*

/** Public, deliberately unfunded fixture. No key generation or signing takes place here. */
internal object BitcoinSignatureTest {
    fun quote(asset: Asset, destination: String, amount: String, enabled: Boolean): TransferQuote {
        requireInput(
            enabled && asset.coin == "bitcoin" && asset.contract.isEmpty(),
            InputError.INVALID_QUOTE,
        )
        val value =
            Amount.atomic(amount, asset.decimals).toLongOrNull()
                ?: throw InputException(InputError.INVALID_AMOUNT)
        requireInput(value in 546..(2_100_000_000_000_000L - 10_000), InputError.INVALID_AMOUNT)
        val sender = UtxoStrategy.script(asset.coin, asset.address)
        // The parent spends a null hash with index zero, not a coinbase input. It is not
        // a chain transaction and can never provide spendable funds for this payment.
        val funding = value + 10_000
        fun littleEndian(n: Long, bytes: Int) =
            ByteArray(bytes) { (n ushr (8 * it)).toByte() }.hex()
        val raw =
            "0200000001" +
                "00".repeat(32) +
                "0000000000ffffffff01" +
                littleEndian(funding, 8) +
                littleEndian(sender.size.toLong(), 1) +
                sender.hex() +
                "00000000"
        val previous = Transaction.read(raw)
        val input = UtxoInput(previous.txid.toString(), 0, funding, sender.hex(), raw)
        return UtxoStrategy.plan(asset, destination, amount, listOf(input), 1)
            .copy(bitcoinSignatureTest = true)
    }

    fun validate(quote: TransferQuote, enabled: Boolean) {
        requireInput(
            quote.bitcoinSignatureTest &&
                quote == quote(quote.asset, quote.destination, quote.amount, enabled),
            InputError.INVALID_QUOTE,
        )
    }
}
