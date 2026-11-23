package pub.airlock.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import pub.airlock.data.AirLockApi
import pub.airlock.domain.*

internal class AirLockRestWallet(private val api: AirLockApi) {
    private fun backendAsset(asset: Asset) =
        asset.copy(coin = PublicNetworks.get(asset.coin).hardwareCoin)

    suspend fun balance(asset: Asset): String = request {
        val backend = backendAsset(asset)
        val response = api.balance(backend)
        val evm = asset.coin in EvmNetworks.chainIds || asset.coin == "ethereumclassic"
        require(response.getValue("chain").stringValue() == backend.coin)
        require(response.getValue("address").stringValue().equals(asset.address, ignoreCase = evm))
        val contract = (response["contract"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(contract.equals(asset.contract, ignoreCase = evm))
        decimalValue(response.getValue("balance"), asset.decimals)
    }

    suspend fun prices(symbols: List<String>): Map<String, String> = request {
        val response = api.prices(symbols).getValue("data").jsonObject
        symbols
            .mapNotNull { symbol -> response[symbol]?.let { symbol to decimalValue(it, 18) } }
            .toMap()
    }

    suspend fun estimate(asset: Asset, destination: String, amount: String): TransferQuote {
        requireInput(Stablecoins.supportsHardwareBrz(asset), InputError.NETWORK_UNSUPPORTED)
        val sender = EvmEncoding.address(asset.address)
        val recipient = EvmEncoding.address(destination)
        requireInput(
            recipient != "0x0000000000000000000000000000000000000000",
            InputError.INVALID_DESTINATION,
        )
        val (to, value, data) = EvmEncoding.payment(asset, recipient, amount)
        val backend = backendAsset(asset.copy(address = sender))
        return request {
            val nonce = api.nonce(backend).stringValue().toULong()
            val gas = api.estimateGas(backend).jsonObject
            val price = gas.getValue("gasPrice").stringValue().toULong()
            val limit = gas.getValue("gasLimit").stringValue().toULong()
            require(price > 0u && limit >= 21000u && limit <= ULong.MAX_VALUE / price)
            val plan =
                EvmTransactionPlan(
                    EvmNetworks.chainIds.getValue(asset.coin),
                    EvmEncoding.hex(nonce.toString()),
                    EvmEncoding.hex(price.toString()),
                    EvmEncoding.hex(limit.toString()),
                    to,
                    value,
                    data,
                )
            TransferQuote(
                asset,
                destination,
                amount,
                Amount.display((limit * price).toString(), 18),
                evmPlan = plan,
            )
        }
    }

    suspend fun submit(asset: Asset, signed: String, hash: String): TransferStatus {
        requireInput(Stablecoins.supportsHardwareBrz(asset), InputError.NETWORK_UNSUPPORTED)
        val backend = backendAsset(asset)
        try {
            known(backend, hash)?.let {
                return it
            }
            try {
                val returned = api.broadcast(backend.coin, JsonPrimitive(signed))
                require(returned.equals(hash, ignoreCase = true))
                return TransferStatus.SUBMITTED
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return known(backend, hash) ?: throw InputException(InputError.SUBMISSION_PENDING)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw InputException(InputError.SUBMISSION_PENDING)
        }
    }

    private suspend fun known(asset: Asset, hash: String): TransferStatus? {
        // Ethereum native history includes failed contract calls. BSC token history indexes
        // transfer events.
        val ethereum = asset.coin == "ethereum"
        val lookup = if (ethereum) asset.copy(contract = "") else asset
        val row =
            api.history(lookup, limit = 50)
                .jsonArray
                .map { it.jsonObject }
                .firstOrNull {
                    (it["txid"] as? JsonPrimitive)?.contentOrNull.equals(hash, ignoreCase = true)
                } ?: return null
        return when ((row["status"] as? JsonPrimitive)?.contentOrNull) {
            "0" -> TransferStatus.FAILED
            "1" -> TransferStatus.CONFIRMED
            null -> if (ethereum) TransferStatus.SUBMITTED else TransferStatus.CONFIRMED
            else -> throw ProviderFailure(Failure.INVALID_RESPONSE)
        }
    }

    private suspend fun <T> request(action: suspend () -> T): T =
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw InputException(InputError.PROVIDER_UNAVAILABLE)
        }
}
