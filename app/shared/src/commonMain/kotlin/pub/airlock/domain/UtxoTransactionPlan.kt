package pub.airlock.domain

import kotlinx.serialization.Serializable

@Serializable
data class UtxoInput(
    val txId: String,
    val index: Long,
    val amount: Long,
    val script: String,
    // Authenticate the amount and script against the funding transaction on both sides of BLE.
    val previousTransaction: String,
)

@Serializable
data class UtxoTransactionPlan(
    val inputs: List<UtxoInput>,
    val recipientScript: String,
    val changeScript: String,
    val changeAddress: String,
    val change: Long,
    val fee: Long,
    val feeRate: Long,
)

object UtxoNetworks {
    val supported = setOf("bitcoin", "litecoin", "dogecoin", "dash", "bitcoincash", "ecash")

    fun usesForkId(coin: String) = coin == "bitcoincash" || coin == "ecash"

    fun dust(coin: String): Long =
        when (coin) {
            "dogecoin" -> 1_000_000L
            "litecoin" -> 1_000L
            else -> 546L
        }
}
