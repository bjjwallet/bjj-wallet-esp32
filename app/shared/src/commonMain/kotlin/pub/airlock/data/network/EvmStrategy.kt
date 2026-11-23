package pub.airlock.data.network

import kotlinx.serialization.json.*
import pub.airlock.domain.*

internal object EvmStrategy {
    suspend fun estimate(
        provider: WalletProvider,
        asset: Asset,
        destination: String,
        amount: String,
    ): TransferQuote {
        requireInput(EvmNetworks.supportsPayment(asset), InputError.NETWORK_UNSUPPORTED)
        val (to, value, data) = EvmEncoding.payment(asset, destination, amount)
        provider.verifyChain(asset.coin)
        val nonce =
            provider
                .rpc(
                    asset.coin,
                    "eth_getTransactionCount",
                    params(asset.address, provider.nonceBlockTag),
                )
                .stringValue()
        val price = provider.rpc(asset.coin, "eth_gasPrice").stringValue()
        EvmEncoding.decimal(nonce)
        if (EvmEncoding.decimal(price) == "0") throw ProviderFailure(Failure.INVALID_RESPONSE)
        val gas =
            provider
                .rpc(
                    asset.coin,
                    "eth_estimateGas",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("from", asset.address)
                                put("to", to)
                                put("value", value)
                                put("data", data)
                                put("gasPrice", price)
                            }
                        )
                    ),
                )
                .stringValue()
        val estimatedGas = EvmEncoding.decimal(gas)
        if (Amount.compare(estimatedGas, "21000") < 0)
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        // Reserve a 20% integer margin; eth_estimateGas on Arbitrum includes the L1 posting
        // component.
        val gasLimit = EvmEncoding.hex(withMargin(estimatedGas))
        var plan =
            EvmTransactionPlan(
                EvmNetworks.chainIds.getValue(asset.coin),
                nonce,
                price,
                gasLimit,
                to,
                value,
                data,
            )
        var fee = Amount.multiply(EvmEncoding.decimal(gasLimit), EvmEncoding.decimal(price))
        if (asset.coin == "base") {
            val (l1, operator) = BaseFees.estimate(provider, plan)
            plan = plan.copy(l1Fee = l1, operatorFee = operator)
            fee = Amount.add(fee, Amount.add(l1, operator))
        }
        return TransferQuote(asset, destination, amount, Amount.display(fee, 18), evmPlan = plan)
    }

    internal fun withMargin(value: String) = Amount.add(value, divideByFive(Amount.add(value, "4")))

    private fun divideByFive(value: String): String {
        var carry = 0
        return buildString {
                for (digit in value) {
                    val n = carry * 10 + digit.digitToInt()
                    append(n / 5)
                    carry = n % 5
                }
            }
            .trimStart('0')
            .ifEmpty { "0" }
    }
}
