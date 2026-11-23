package pub.airlock.data.network

import kotlinx.serialization.json.*
import pub.airlock.domain.*

internal enum class Capability {
    BALANCE,
    HISTORY,
    PRICES,
    EVM_RPC,
}

/**
 * Providers own endpoint/authentication/response contracts. Chain rules are a separate strategy.
 */
internal interface WalletProvider {
    val id: NetworkProvider
    val nonceBlockTag: String
        get() = "pending"

    fun supports(coin: String, capability: Capability): Boolean

    suspend fun balance(asset: Asset): String

    suspend fun prices(symbols: List<String>): Map<String, String> =
        throw ProviderFailure(Failure.UNSUPPORTED)

    suspend fun rpc(
        coin: String,
        method: String,
        params: JsonArray = JsonArray(emptyList()),
    ): JsonElement = throw ProviderFailure(Failure.UNSUPPORTED)
}

internal abstract class EvmRpcProvider(protected val transport: ProviderTransport) :
    WalletProvider {
    abstract fun endpoint(coin: String): String

    override fun supports(coin: String, capability: Capability) =
        coin in EvmNetworks.chainIds && capability in setOf(Capability.BALANCE, Capability.EVM_RPC)

    override suspend fun rpc(coin: String, method: String, params: JsonArray) =
        transport.rpc(endpoint(coin), method, params)

    override suspend fun balance(asset: Asset): String {
        verifyChain(asset.coin)
        EvmEncoding.address(asset.address)
        val raw =
            if (asset.contract.isEmpty())
                rpc(asset.coin, "eth_getBalance", params(asset.address, "latest"))
            else {
                EvmEncoding.address(asset.contract)
                rpc(
                    asset.coin,
                    "eth_call",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("to", asset.contract)
                                put(
                                    "data",
                                    "0x70a08231" +
                                        asset.address
                                            .removePrefix("0x")
                                            .lowercase()
                                            .padStart(64, '0'),
                                )
                            },
                            JsonPrimitive("latest"),
                        )
                    ),
                )
            }
        return Amount.display(EvmEncoding.decimal(raw.stringValue()), asset.decimals)
    }
}

internal suspend fun WalletProvider.verifyChain(coin: String) {
    val expected = EvmNetworks.chainIds[coin] ?: throw ProviderFailure(Failure.UNSUPPORTED)
    val actual = EvmEncoding.decimal(rpc(coin, "eth_chainId").stringValue())
    if (actual != expected.toString()) throw ProviderFailure(Failure.WRONG_CHAIN)
}

internal fun params(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
