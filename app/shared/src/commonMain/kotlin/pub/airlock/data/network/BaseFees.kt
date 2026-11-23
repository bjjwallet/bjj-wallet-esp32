package pub.airlock.data.network

import kotlinx.serialization.json.*
import org.kotlincrypto.hash.sha3.Keccak256
import pub.airlock.domain.*

/** Base/OP Stack fees queried from the chain's oracle, including upgrades to operator fees. */
internal object BaseFees {
    const val oracle = "0x420000000000000000000000000000000000000f"

    suspend fun estimate(provider: WalletProvider, plan: EvmTransactionPlan): Pair<String, String> {
        val unsignedSize = EvmEncoding.unsigned(plan).size
        val l1 = query(provider, "getL1FeeUpperBound(uint256)", unsignedSize.toString())
        if (l1 == "0") throw ProviderFailure(Failure.INVALID_RESPONSE)
        val operator =
            query(provider, "getOperatorFee(uint256)", EvmEncoding.decimal(plan.gasLimit))
        return EvmStrategy.withMargin(l1) to EvmStrategy.withMargin(operator)
    }

    private suspend fun query(
        provider: WalletProvider,
        signature: String,
        argument: String,
    ): String {
        val selector =
            Keccak256().digest(signature.encodeToByteArray()).take(4).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
        val result =
            provider
                .rpc(
                    "base",
                    "eth_call",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("to", oracle)
                                put(
                                    "data",
                                    "0x$selector" +
                                        EvmEncoding.hex(argument).drop(2).padStart(64, '0'),
                                )
                            },
                            JsonPrimitive("latest"),
                        )
                    ),
                )
                .stringValue()
        if (!Regex("0x[0-9a-fA-F]{64}").matches(result))
            throw ProviderFailure(Failure.INVALID_RESPONSE)
        return EvmEncoding.decimal(result)
    }
}
