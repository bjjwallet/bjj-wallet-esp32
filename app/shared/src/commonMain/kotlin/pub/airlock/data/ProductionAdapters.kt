package pub.airlock.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import pub.airlock.ble.HardwareConnection
import pub.airlock.ble.HardwareRejectedException
import pub.airlock.data.network.CardanoPayment
import pub.airlock.data.network.EvmEncoding
import pub.airlock.data.network.LedgerPayment
import pub.airlock.data.network.UtxoStrategy
import pub.airlock.domain.*

/**
 * Access PIN exists only in this signing coroutine; never in wallet storage or a reusable cache.
 */
private class SigningAccessPin(val value: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SigningAccessPin>
}

/** Uses paired BLE for public account import and on-device transaction confirmation. */
class ProductionHardwareWallet(
    private val connection: HardwareConnection,
    private val deriver: PublicKeyDeriver,
) : HardwareWallet {
    override val simulated = false
    override val requiresSigningAccessPin = true

    override suspend fun withSigningAccessPin(pin: String, sign: suspend () -> String): String {
        HardwarePinPolicy.validate(pin)
        return withContext(SigningAccessPin(pin)) { sign() }
    }

    private val progress = kotlinx.coroutines.flow.MutableStateFlow(TransferStage.CONNECTING)
    override val signingProgress: kotlinx.coroutines.flow.StateFlow<TransferStage> = progress
    private var info: DeviceInfo? = null

    override fun bindWalletDevice(deviceId: String?) {
        if (synchronizedDeviceId != deviceId) info = null
        synchronizedDeviceId = deviceId
    }

    override suspend fun discover() = connection.discover()

    private var connectedDeviceId: String? = null
    private var synchronizedDeviceId: String? = null

    override suspend fun connect(deviceId: String) {
        progress.value = TransferStage.CONNECTING
        connection.connect(deviceId)
        connectedDeviceId = deviceId
        currentCoroutineContext()[SigningAccessPin]?.let { credential ->
            progress.value = TransferStage.AUTHORIZING
            try {
                requireInput(unlockAccess(credential.value), InputError.INCORRECT_PIN)
            } catch (failure: Exception) {
                withContext(NonCancellable) { disconnect() }
                throw failure
            }
        }
        progress.value = TransferStage.APPROVAL
    }

    override suspend fun connectMonero(deviceId: String, onPairing: () -> Unit) {
        connection.connectMonero(deviceId, onPairing)
        connectedDeviceId = deviceId
    }

    override suspend fun requiresAccessPin(): Boolean {
        val response =
            try {
                connection.request("get_device_info") as? JsonObject
                    ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
            } catch (error: HardwareRejectedException) {
                throw publicAccessFailure(error)
            }
        val value = response["pin_required"]
        requireInput(
            value != null && value.jsonPrimitive.booleanOrNull == true,
            InputError.HARDWARE_PIN_SETUP_REQUIRED,
        )
        return true
    }

    override suspend fun unlockAccess(pin: String): Boolean {
        HardwarePinPolicy.validate(pin)
        val result =
            try {
                connection.request("verify_pin", buildJsonObject { put("pin", pin) }) as? JsonObject
                    ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
            } catch (error: HardwareRejectedException) {
                throw publicAccessFailure(error)
            }
        return result["accepted"]?.jsonPrimitive?.booleanOrNull
            ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
    }

    override suspend fun synchronize(mode: WalletMode): WalletData =
        synchronizeModes(listOf(mode)).getValue(mode)

    override suspend fun synchronizeWallets(): Map<WalletMode, WalletData> =
        synchronizeModes(WalletMode.entries)

    override suspend fun synchronizePublicWallets(): WalletSynchronization =
        synchronizePublicWallets(emptySet())

    override suspend fun synchronizePublicWallets(
        skipMoneroWatchWalletIds: Set<String>
    ): WalletSynchronization {
        try {
            val rawInfo = connection.request("get_device_info").jsonObject
            captureInfo(rawInfo)
            val version = rawInfo["wallet_sync_version"]
            val result =
                if (version == null)
                    WalletSynchronization.Legacy(readLegacyModes(WalletMode.entries))
                else {
                    requireInput(version == JsonPrimitive(1), InputError.INTEGRATION_UNAVAILABLE)
                    val importer = PublicWalletSyncImporter(PublicAccountImporter(deriver))
                    val manifest = importer.manifest(connection.request("get_wallets"))
                    val groups =
                        WalletMode.entries.associateWith { mode ->
                            val response = requestPublicAccounts(mode) { put("all_wallets", true) }
                            importer.groups(response, manifest, mode)
                        }
                    val supportsMonero = rawInfo["monero_sync_version"] == JsonPrimitive(1)
                    WalletSynchronization.Stable(
                        manifest.map { descriptor ->
                            val mode = WalletMode.STANDARD
                            val monero =
                                if (
                                    supportsMonero &&
                                        descriptor.config.moneroEnabled != false &&
                                        descriptor.id !in skipMoneroWatchWalletIds
                                ) {
                                    try {
                                        mapOf(mode to readMoneroWatchWallet(descriptor.id, mode))
                                    } catch (_: HardwareRejectedException) {
                                        // Monero export is optional and has its own confirmation on
                                        // the cold wallet.
                                        emptyMap()
                                    }
                                } else emptyMap()
                            SynchronizedPublicWallet(
                                descriptor,
                                WalletMode.entries.associateWith {
                                    groups.getValue(it).getValue(descriptor.id)
                                },
                                monero,
                            )
                        }
                    )
                }
            synchronizedDeviceId = connectedDeviceId
            return result
        } catch (error: HardwareRejectedException) {
            throw publicAccessFailure(error)
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    private fun publicAccessFailure(error: HardwareRejectedException) =
        InputException(
            when (error.code) {
                "PIN_LOCKED" -> InputError.HARDWARE_PIN_LOCKED
                "BUSY",
                "WALLET_BUSY" -> InputError.HARDWARE_BUSY
                "DEVICE_APPROVAL_REQUIRED" -> InputError.HARDWARE_APPROVAL_REQUIRED
                "DEVICE_DISCONNECTED",
                "REQUEST_TIMEOUT" -> InputError.DEVICE_DISCONNECTED
                "PIN_REQUIRED" -> InputError.HARDWARE_PIN_REQUIRED
                "PUBLIC_DATA_INVALID",
                "PUBLIC_DATA_UNAVAILABLE" -> InputError.PUBLIC_DATA_INVALID
                "WALLET_GENERATION_CHANGED" -> InputError.PUBLIC_DATA_CHANGED
                "METHOD_NOT_FOUND",
                "INVALID_PARAMS" -> InputError.INTEGRATION_UNAVAILABLE
                else -> InputError.HARDWARE_FAILURE
            }
        )

    override suspend fun synchronizeWallet(walletId: String, mode: WalletMode): WalletData {
        requireInput(validColdWalletId(walletId), InputError.PUBLIC_DATA_INVALID)
        try {
            val rawInfo = connection.request("get_device_info").jsonObject
            captureInfo(rawInfo)
            requireInput(
                rawInfo["wallet_sync_version"] == JsonPrimitive(1),
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val response = requestPublicAccounts(mode) { put("wallet_id", walletId) }
            val data =
                PublicWalletSyncImporter(PublicAccountImporter(deriver)).accountRows(response, mode)
            synchronizedDeviceId = connectedDeviceId
            return data
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    private suspend fun requestPublicAccounts(
        mode: WalletMode,
        scope: JsonObjectBuilder.() -> Unit = {},
    ): JsonElement {
        val networks = PublicNetworks.entries + PublicNetworks.optional
        suspend fun request(selection: List<PublicNetwork>) =
            connection.request(
                "get_accounts",
                buildJsonObject {
                    put("coins", JsonArray(selection.map { JsonPrimitive(it.hardwareCoin) }))
                    put("mode", mode.name)
                    put("include_all_addresses", true)
                    scope()
                },
            )
        val legacyNetworks = networks - PublicNetworks.cardano
        for (selection in listOf(networks, legacyNetworks)) {
            try {
                return request(selection)
            } catch (error: HardwareRejectedException) {
                // Older firmware requires every requested coin to be present.
                if (error.code != "PUBLIC_DATA_INVALID") throw error
            }
        }
        return request(legacyNetworks - PublicNetworks.ecash)
    }

    override suspend fun supportsMoneroKeyImages(): Boolean =
        (connection.request("get_device_info") as? JsonObject)?.get("monero_sync_version") ==
            JsonPrimitive(1)

    override suspend fun supportsMoneroSigning(): Boolean =
        (connection.request("get_device_info") as? JsonObject)?.let {
            it["monero_signing_version"] == JsonPrimitive(1) &&
                it["monero_network"] == JsonPrimitive("stagenet")
        } == true

    override suspend fun signMoneroTransfer(
        walletId: String,
        mode: WalletMode,
        quote: TransferQuote,
        unsigned: MoneroUnsignedTransfer,
    ): MoneroSignedTransfer {
        requireMoneroDevice()
        requireInput(
            quote.asset.coin == "monero" &&
                quote.asset.decimals == 12 &&
                isMoneroAddress(quote.destination, MoneroNetwork.STAGENET) &&
                Amount.atomic(quote.fee, 12) == unsigned.feeAtomic,
            InputError.INVALID_QUOTE,
        )
        val data =
            try {
                moneroRequest("monero_sign_transfer", walletId, mode) {
                    put("network", "stagenet")
                    put("recipient", quote.destination)
                    put("amount_atomic", Amount.atomic(quote.amount, 12))
                    put("fee_atomic", unsigned.feeAtomic)
                    put("unsigned_txset", unsigned.hex)
                }
            } catch (failure: HardwareRejectedException) {
                throw InputException(
                    when (failure.code) {
                        "USER_REJECTED" -> InputError.MONERO_SYNC_REJECTED
                        "DEVICE_DISCONNECTED",
                        "DEVICE_APPROVAL_REQUIRED" -> InputError.DEVICE_DISCONNECTED
                        "MONERO_UNAVAILABLE",
                        "MONERO_TRANSACTION_UNSUPPORTED" -> InputError.INTEGRATION_UNAVAILABLE
                        "INVALID_MONERO_TRANSACTION",
                        "MONERO_REVIEW_MISMATCH",
                        "MONERO_INPUT_NOT_OWNED",
                        "INVALID_PARAMS",
                        "PAYLOAD_TOO_LARGE" -> InputError.INVALID_QUOTE
                        else -> InputError.OPERATION_FAILED
                    }
                )
            }
        requireInput(
            data.keys == setOf("wallet_id", "mode", "network", "txid", "tx_hex") &&
                data.text("network") == "stagenet",
            InputError.PUBLIC_DATA_INVALID,
        )
        return MoneroSignedTransfer(data.text("txid"), data.text("tx_hex"))
    }

    override suspend fun moneroReceiveAddress(
        walletId: String,
        mode: WalletMode,
    ): MoneroReceiveAddress {
        requireMoneroDevice()
        val info = connection.request("get_device_info").jsonObject
        requireInput(
            info["monero_receive_address_version"] == JsonPrimitive(1),
            InputError.INTEGRATION_UNAVAILABLE,
        )
        val data =
            try {
                moneroRequest("monero_get_receive_address", walletId, mode)
            } catch (_: HardwareRejectedException) {
                throw InputException(InputError.MONERO_SYNC_REJECTED)
            }
        requireInput(
            data.keys == setOf("wallet_id", "mode", "network", "address"),
            InputError.PUBLIC_DATA_INVALID,
        )
        return MoneroReceiveAddress(data.text("address"), moneroNetwork(data))
    }

    override suspend fun moneroWatchWallet(walletId: String, mode: WalletMode): MoneroWatchWallet {
        requireMoneroDevice()
        requireInput(supportsMoneroKeyImages(), InputError.MONERO_VIEW_SYNC_REQUIRED)
        return try {
            readMoneroWatchWallet(walletId, mode)
        } catch (_: HardwareRejectedException) {
            throw InputException(InputError.MONERO_SYNC_REJECTED)
        }
    }

    private suspend fun readMoneroWatchWallet(
        walletId: String,
        mode: WalletMode,
    ): MoneroWatchWallet {
        val data = moneroRequest("monero_get_watch_wallet", walletId, mode)
        requireInput(
            data.keys ==
                setOf(
                    "wallet_id",
                    "mode",
                    "network",
                    "address",
                    "private_view_key",
                    "restore_height",
                ),
            InputError.PUBLIC_DATA_INVALID,
        )
        val height =
            (data["restore_height"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
                ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
        return MoneroWatchWallet(
            data.text("address"),
            data.text("private_view_key"),
            height,
            emptyList(),
            moneroNetwork(data),
        )
    }

    private fun moneroNetwork(data: JsonObject): MoneroNetwork =
        when (data.text("network")) {
            "mainnet" -> MoneroNetwork.MAINNET
            "stagenet" -> MoneroNetwork.STAGENET
            else -> throw InputException(InputError.NETWORK_MISMATCH)
        }

    override suspend fun moneroSignKeyImages(
        walletId: String,
        mode: WalletMode,
        outputs: MoneroOutputs,
    ): List<MoneroKeyImage> {
        requireMoneroDevice()
        requireInput(supportsMoneroKeyImages(), InputError.MONERO_KEY_IMAGES_UNAVAILABLE)
        val data =
            try {
                moneroRequest("monero_sign_key_images", walletId, mode) {
                    put("outputs_data_hex", outputs.hex)
                }
            } catch (_: HardwareRejectedException) {
                throw InputException(InputError.MONERO_SYNC_REJECTED)
            }
        val rows = data["signed_key_images"] as? JsonArray
        requireInput(
            data["offset"] == JsonPrimitive(0) && rows?.size == outputs.count,
            InputError.PUBLIC_DATA_INVALID,
        )
        return rows!!
            .map { row ->
                val image =
                    row as? JsonObject ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
                MoneroKeyImage(image.text("key_image"), image.text("signature"))
            }
            .also { images ->
                requireInput(
                    images.distinctBy { it.image }.size == images.size,
                    InputError.PUBLIC_DATA_INVALID,
                )
            }
    }

    private suspend fun moneroRequest(
        method: String,
        walletId: String,
        mode: WalletMode,
        payload: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject {
        requireInput(validColdWalletId(walletId), InputError.PUBLIC_DATA_INVALID)
        val data =
            connection.request(
                method,
                buildJsonObject {
                    put("wallet_id", walletId)
                    put("mode", mode.name)
                    payload()
                },
            ) as? JsonObject ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
        requireInput(
            data.text("wallet_id") == walletId && data.text("mode") == mode.name,
            InputError.PUBLIC_DATA_INVALID,
        )
        return data
    }

    private fun requireMoneroDevice() =
        requireInput(
            connectedDeviceId != null && connectedDeviceId == synchronizedDeviceId,
            InputError.DEVICE_DISCONNECTED,
        )

    private fun JsonObject.text(field: String): String =
        (get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InputException(InputError.PUBLIC_DATA_INVALID)

    private suspend fun readLegacyModes(modes: List<WalletMode>): Map<WalletMode, WalletData> =
        modes.associateWith { mode ->
            PublicAccountImporter(deriver).decode(requestPublicAccounts(mode), mode)
        }

    private suspend fun synchronizeModes(modes: List<WalletMode>): Map<WalletMode, WalletData> {
        try {
            captureInfo(connection.request("get_device_info").jsonObject)
            val wallets = readLegacyModes(modes)
            synchronizedDeviceId = connectedDeviceId
            return wallets
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    private fun captureInfo(rawInfo: JsonObject) {
        info =
            DeviceInfo(
                name = rawInfo["name"]?.jsonPrimitive?.content ?: "BJJ-Cold-Wallet",
                firmware = rawInfo["version"]?.jsonPrimitive?.content ?: "—",
                battery = 0,
                connected = false,
                platform = rawInfo["platform"]?.jsonPrimitive?.content ?: "—",
                hardware = rawInfo["hardware"]?.jsonPrimitive?.content ?: "BJJ-Cold-Wallet",
                display = rawInfo["screen"]?.jsonPrimitive?.content ?: "—",
                cpuTemperature = rawInfo["CPU"]?.jsonPrimitive?.content ?: "—",
            )
    }

    override suspend fun sign(quote: TransferQuote, paymentPin: String, mode: WalletMode): String {
        val asset = quote.asset
        if (asset.coin in UtxoNetworks.supported) return signUtxo(quote, paymentPin, mode)
        requireInput(
            asset.coin in setOf("hyperevm", "robinhood"),
            InputError.INTEGRATION_UNAVAILABLE,
        )
        val plan = quote.evmPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            plan.chainId == EvmNetworks.chainIds[asset.coin] &&
                EvmEncoding.payment(asset, quote.destination, quote.amount) ==
                    Triple(plan.to, plan.value, plan.data),
            InputError.INVALID_QUOTE,
        )
        val index =
            asset.derivationPath.substringAfterLast('/').toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            asset.derivationPath == PublicNetworks.path(asset.coin, index),
            InputError.INVALID_QUOTE,
        )
        val deviceId =
            synchronizedDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        try {
            connect(deviceId)
            val result =
                connection.request(
                    "sign_tx",
                    buildJsonObject {
                        put("mode", mode.name)
                        put("password", paymentPin)
                        put(
                            "asset",
                            buildJsonObject {
                                put("coin", asset.coin)
                                put("symbol", asset.symbol)
                                put("name", asset.name)
                                put("blockchain", "Ethereum")
                                put("contract", asset.contract)
                                put("decimals", asset.decimals)
                                put("curve", "secp256k1")
                                put("balance", asset.balance)
                                put("icon", asset.icon)
                                put("address", asset.address)
                                put("derivation_path", asset.derivationPath)
                                put("derivation_index", index)
                            },
                        )
                        put(
                            "txData",
                            buildJsonObject {
                                put("amount", Amount.atomic(quote.amount, asset.decimals))
                                put("toAddress", quote.destination)
                                put("chainId", plan.chainId.toString())
                                put("nonce", plan.nonce)
                                put("gasLimit", plan.gasLimit)
                                put("gasPrice", plan.gasPrice)
                                if (asset.contract.isNotEmpty())
                                    put("contractAddress", asset.contract)
                            },
                        )
                    },
                )
            val encoded =
                (result as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw InputException(InputError.INVALID_QUOTE)
            val raw = "0x" + encoded.removePrefix("0x")
            EvmEncoding.transactionId(raw, quote)
            return raw
        } catch (_: HardwareRejectedException) {
            throw InputException(InputError.TRANSACTION_REJECTED)
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun signBrz(quote: TransferQuote, walletId: String, mode: WalletMode): String {
        requireInput(EvmNetworks.supportsPayment(quote.asset), InputError.INVALID_QUOTE)
        return signReviewedEvmPayment(quote, walletId, mode)
    }

    override suspend fun signLedger(
        quote: TransferQuote,
        walletId: String,
        mode: WalletMode,
    ): String {
        requireInput(
            LedgerNetworks.supportsPayment(quote.asset) &&
                validColdWalletId(walletId) &&
                quote.asset.derivationPath == PublicNetworks.get(quote.asset.coin).path,
            InputError.INVALID_QUOTE,
        )
        LedgerPayment.body(quote)
        val deviceId =
            synchronizedDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        try {
            connect(deviceId)
            requireInput(
                connection.request("get_device_info").jsonObject["ledger_signing_version"] ==
                    JsonPrimitive(1),
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val result =
                connection.request(
                    "ledger_sign_transfer",
                    buildJsonObject {
                        for ((key, value) in quote.ledgerPlan!!) put(key, value)
                        put("wallet_id", walletId)
                        put("mode", mode.name)
                        put("sender", quote.asset.address)
                        put("recipient", quote.destination)
                        put("asset", quote.asset.symbol)
                        put("amount_atomic", Amount.atomic(quote.amount, quote.asset.decimals))
                        put("fee_atomic", Amount.atomic(quote.fee, quote.feeCurrency.decimals))
                    },
                )
            return (result as? JsonPrimitive)
                ?.takeIf {
                    it.isString &&
                        Regex(
                                when (quote.asset.coin) {
                                    "ton",
                                    "sui" -> "[0-9a-f]{192}"
                                    "ripple" -> "[0-9a-f]{196}"
                                    "tron" -> "[0-9a-f]{130}"
                                    else -> "[0-9a-f]{128}"
                                }
                            )
                            .matches(it.content)
                }
                ?.content ?: throw InputException(InputError.INVALID_QUOTE)
        } catch (error: HardwareRejectedException) {
            throw InputException(
                when (error.code) {
                    "TRANSACTION_REJECTED",
                    "USER_REJECTED" -> InputError.TRANSACTION_REJECTED
                    "PIN_REQUIRED" -> InputError.HARDWARE_PIN_REQUIRED
                    "DEVICE_DISCONNECTED",
                    "DEVICE_APPROVAL_REQUIRED",
                    "REQUEST_TIMEOUT" -> InputError.DEVICE_DISCONNECTED
                    "UNSUPPORTED_SIGNING",
                    "METHOD_NOT_FOUND" -> InputError.INTEGRATION_UNAVAILABLE
                    "INVALID_PARAMS" -> InputError.INVALID_QUOTE
                    else -> InputError.OPERATION_FAILED
                }
            )
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun signCardano(
        quote: TransferQuote,
        walletId: String,
        mode: WalletMode,
    ): String {
        val asset = quote.asset
        val plan = quote.cardanoPlan ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            CardanoNetwork.supportsPayment(asset) &&
                validColdWalletId(walletId) &&
                asset.derivationPath == PublicNetworks.cardano.path,
            InputError.INVALID_QUOTE,
        )
        CardanoPayment.body(quote)
        val deviceId =
            synchronizedDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        try {
            connect(deviceId)
            requireInput(
                connection.request("get_device_info").jsonObject["cardano_signing_version"] ==
                    JsonPrimitive(1),
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val result =
                connection.request(
                    "cardano_sign_transfer",
                    buildJsonObject {
                        put("network", "cardano-mainnet")
                        put("wallet_id", walletId)
                        put("mode", mode.name)
                        put("sender", asset.address)
                        put("recipient", quote.destination)
                        put("amount_atomic", Amount.atomic(quote.amount, asset.decimals))
                        put("fee_atomic", Amount.atomic(quote.fee, 6))
                        put("change_atomic", plan.change.toString())
                        put("ttl", plan.ttl.toString())
                        if (asset.contract.isNotEmpty()) {
                            put("asset", asset.symbol)
                            put("recipient_lovelace", plan.recipientLovelace.toString())
                        }
                        putJsonArray("inputs") {
                            for (input in plan.inputs) addJsonObject {
                                put("tx_hash", input.txHash)
                                put("tx_index", input.index.toString())
                                put("value", input.lovelace.toString())
                                if (asset.contract.isNotEmpty())
                                    put("token_quantity", input.tokenQuantity.toString())
                            }
                        }
                    },
                )
            return (result as? JsonPrimitive)
                ?.takeIf { it.isString && Regex("[0-9a-f]{192}").matches(it.content) }
                ?.content ?: throw InputException(InputError.INVALID_QUOTE)
        } catch (error: HardwareRejectedException) {
            throw InputException(
                when (error.code) {
                    "TRANSACTION_REJECTED",
                    "USER_REJECTED" -> InputError.TRANSACTION_REJECTED
                    "PIN_REQUIRED" -> InputError.HARDWARE_PIN_REQUIRED
                    "DEVICE_DISCONNECTED",
                    "DEVICE_APPROVAL_REQUIRED",
                    "REQUEST_TIMEOUT" -> InputError.DEVICE_DISCONNECTED
                    "UNSUPPORTED_SIGNING",
                    "METHOD_NOT_FOUND" -> InputError.INTEGRATION_UNAVAILABLE
                    "INVALID_PARAMS",
                    "INVALID_CARDANO_PLAN" -> InputError.INVALID_QUOTE
                    else -> InputError.OPERATION_FAILED
                }
            )
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun signSolana(
        quote: TransferQuote,
        walletId: String,
        mode: WalletMode,
    ): String {
        val asset = quote.asset
        val plan = quote.solanaPlan ?: throw InputException(InputError.INVALID_QUOTE)
        val index =
            asset.derivationPath.substringAfterLast('/').removeSuffix("'").toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            (SolanaNetwork.isNative(asset) || SolanaNetwork.supportsToken(asset)) &&
                validColdWalletId(walletId) &&
                asset.derivationPath == PublicNetworks.path("solana", index),
            InputError.INVALID_QUOTE,
        )
        val deviceId =
            synchronizedDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        try {
            connect(deviceId)
            val capability =
                if (asset.contract.isEmpty()) "solana_signing_version"
                else "solana_token_signing_version"
            requireInput(
                connection.request("get_device_info").jsonObject[capability] == JsonPrimitive(1),
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val result =
                connection.request(
                    "solana_sign_transfer",
                    buildJsonObject {
                        put("network", "solana-mainnet")
                        put("wallet_id", walletId)
                        put("mode", mode.name)
                        put("sender", asset.address)
                        put("recipient", quote.destination)
                        put("path", asset.derivationPath)
                        put("amount_atomic", Amount.atomic(quote.amount, asset.decimals))
                        put("fee_atomic", Amount.atomic(quote.fee, 9))
                        put("blockhash", plan.blockhash)
                        if (asset.contract.isNotEmpty()) {
                            put("asset", asset.symbol)
                            put("rent_atomic", plan.rentAtomic.toString())
                        }
                    },
                )
            return (result as? JsonPrimitive)
                ?.takeIf { it.isString && Regex("[0-9a-f]{128}").matches(it.content) }
                ?.content ?: throw InputException(InputError.INVALID_QUOTE)
        } catch (error: HardwareRejectedException) {
            throw InputException(
                when (error.code) {
                    "TRANSACTION_REJECTED",
                    "USER_REJECTED" -> InputError.TRANSACTION_REJECTED
                    "PIN_REQUIRED" -> InputError.HARDWARE_PIN_REQUIRED
                    "DEVICE_DISCONNECTED",
                    "DEVICE_APPROVAL_REQUIRED",
                    "REQUEST_TIMEOUT" -> InputError.DEVICE_DISCONNECTED
                    "UNSUPPORTED_SIGNING",
                    "METHOD_NOT_FOUND" -> InputError.INTEGRATION_UNAVAILABLE
                    "INVALID_PARAMS",
                    "INVALID_SOLANA_PLAN" -> InputError.INVALID_QUOTE
                    else -> InputError.OPERATION_FAILED
                }
            )
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun signPolygon(
        quote: TransferQuote,
        walletId: String,
        mode: WalletMode,
    ): String {
        requireInput(EvmNetworks.isNativePolygon(quote.asset), InputError.INVALID_QUOTE)
        return signReviewedEvmPayment(quote, walletId, mode)
    }

    override suspend fun signEthereum(
        quote: TransferQuote,
        walletId: String,
        mode: WalletMode,
    ): String {
        requireInput(EvmNetworks.isNativeEthereum(quote.asset), InputError.INVALID_QUOTE)
        return signReviewedEvmPayment(quote, walletId, mode)
    }

    private suspend fun signReviewedEvmPayment(
        quote: TransferQuote,
        walletId: String,
        mode: WalletMode,
    ): String {
        val asset = quote.asset
        val plan = quote.evmPlan ?: throw InputException(InputError.INVALID_QUOTE)
        val recipient = EvmEncoding.address(quote.destination)
        val index =
            asset.derivationPath.substringAfterLast('/').toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        val ethereum = Stablecoins.ethereumBrz.matches(asset)
        val polygon = EvmNetworks.isNativePolygon(asset)
        val nativeEthereum = EvmNetworks.isNativeEthereum(asset)
        val additionalToken = Stablecoins.supportsAdditionalEvmToken(asset)
        requireInput(
            validColdWalletId(walletId) &&
                plan.chainId == EvmNetworks.chainIds[asset.coin] &&
                asset.derivationPath == PublicNetworks.path(asset.coin, index) &&
                recipient != "0x0000000000000000000000000000000000000000" &&
                EvmEncoding.payment(asset, recipient, quote.amount) ==
                    Triple(plan.to, plan.value, plan.data),
            InputError.INVALID_QUOTE,
        )
        val deviceId =
            synchronizedDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        try {
            connect(deviceId)
            val capabilities = connection.request("get_device_info").jsonObject
            val generic = capabilities["evm_transfer_signing_version"] == JsonPrimitive(1)
            requireInput(
                generic ||
                    polygon ||
                    nativeEthereum ||
                    ethereum ||
                    Stablecoins.supportsHardwareBrz(asset) ||
                    additionalToken,
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val version =
                when {
                    generic -> "evm_transfer_signing_version"
                    additionalToken -> "evm_token_signing_version"
                    polygon -> "polygon_signing_version"
                    nativeEthereum -> "ethereum_native_signing_version"
                    ethereum -> "ethereum_brz_signing_version"
                    else -> "brz_signing_version"
                }
            requireInput(
                capabilities[version]?.jsonPrimitive?.intOrNull == 1,
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val method =
                when {
                    generic -> "evm_sign_transfer"
                    additionalToken -> "evm_sign_token"
                    polygon -> "polygon_sign_pol"
                    nativeEthereum -> "eth_sign_native"
                    ethereum -> "eth_sign_brz"
                    else -> "bsc_sign_brz"
                }
            val result =
                connection.request(
                    method,
                    buildJsonObject {
                        if (generic || ethereum || polygon || nativeEthereum || additionalToken)
                            put("chain_id", plan.chainId.toString())
                        if (generic || nativeEthereum || additionalToken) put("asset", asset.symbol)
                        if (generic && asset.coin == "base")
                            put(
                                "extra_fee_atomic",
                                Amount.add(
                                    plan.l1Fee ?: throw InputException(InputError.INVALID_QUOTE),
                                    plan.operatorFee
                                        ?: throw InputException(InputError.INVALID_QUOTE),
                                ),
                            )
                        put("wallet_id", walletId)
                        put("mode", mode.name)
                        put("sender", EvmEncoding.address(asset.address))
                        put("recipient", recipient)
                        put("path", asset.derivationPath)
                        put("amount_atomic", Amount.atomic(quote.amount, asset.decimals))
                        put("nonce", plan.nonce)
                        put("gas_price", plan.gasPrice)
                        put("gas_limit", plan.gasLimit)
                    },
                )
            val raw =
                (result as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw InputException(InputError.INVALID_QUOTE)
            EvmEncoding.transactionId(raw, quote)
            return raw
        } catch (failure: HardwareRejectedException) {
            throw InputException(
                when (failure.code) {
                    "TRANSACTION_REJECTED",
                    "USER_REJECTED" -> InputError.TRANSACTION_REJECTED
                    "PIN_REQUIRED" -> InputError.HARDWARE_PIN_REQUIRED
                    "DEVICE_DISCONNECTED",
                    "DEVICE_APPROVAL_REQUIRED",
                    "REQUEST_TIMEOUT" -> InputError.DEVICE_DISCONNECTED
                    "UNSUPPORTED_SIGNING",
                    "METHOD_NOT_FOUND" -> InputError.INTEGRATION_UNAVAILABLE
                    "INVALID_PARAMS",
                    "INVALID_BRZ_PLAN" -> InputError.INVALID_QUOTE
                    else -> InputError.OPERATION_FAILED
                }
            )
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    private suspend fun signUtxo(
        quote: TransferQuote,
        paymentPin: String,
        mode: WalletMode,
    ): String {
        val asset = quote.asset
        val plan =
            try {
                UtxoStrategy.validate(quote)
            } catch (_: Exception) {
                throw InputException(InputError.INVALID_QUOTE)
            }
        val index =
            asset.derivationPath.substringAfterLast('/').toLongOrNull()
                ?: throw InputException(InputError.INVALID_QUOTE)
        requireInput(
            asset.derivationPath == PublicNetworks.path(asset.coin, index),
            InputError.INVALID_QUOTE,
        )
        val deviceId =
            synchronizedDeviceId ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
        try {
            connect(deviceId)
            val capabilities = connection.request("get_device_info").jsonObject
            requireInput(
                capabilities["utxo_signing_version"]?.jsonPrimitive?.intOrNull == 1,
                InputError.INTEGRATION_UNAVAILABLE,
            )
            requireInput(
                asset.coin != "ecash" || capabilities["ecash_signing_version"] == JsonPrimitive(1),
                InputError.INTEGRATION_UNAVAILABLE,
            )
            val result =
                connection.request(
                    "sign_tx",
                    buildJsonObject {
                        put("mode", mode.name)
                        put("password", paymentPin)
                        put(
                            "asset",
                            buildJsonObject {
                                put("coin", PublicNetworks.get(asset.coin).hardwareCoin)
                                put("symbol", asset.symbol)
                                put("name", asset.name)
                                put("blockchain", CoinCatalog.get(asset.coin).name)
                                put("contract", "")
                                put("decimals", asset.decimals)
                                put("curve", "secp256k1")
                                put("balance", asset.balance)
                                put("address", asset.address)
                                put("derivation_path", asset.derivationPath)
                                put("derivation_index", index)
                            },
                        )
                        put(
                            "txData",
                            buildJsonObject {
                                put("utxoVersion", 1)
                                put("amount", Amount.atomic(quote.amount, asset.decimals))
                                put("toAddress", quote.destination)
                                put("changeAddress", plan.changeAddress)
                                put("changeAmount", plan.change.toString())
                                put("fee", plan.fee.toString())
                                put("byteFee", plan.feeRate.toString())
                                put("useMax", false)
                                put("recipientScript", plan.recipientScript)
                                put("changeScript", plan.changeScript)
                                put(
                                    "utxos",
                                    JsonArray(
                                        plan.inputs.map { input ->
                                            buildJsonObject {
                                                put("txHash", input.txId)
                                                put("index", input.index)
                                                put("amount", input.amount.toString())
                                                put("script", input.script)
                                                put(
                                                    "previousTransaction",
                                                    input.previousTransaction,
                                                )
                                                put("sequence", 4294967295L)
                                            }
                                        }
                                    ),
                                )
                            },
                        )
                    },
                )
            val raw =
                (result as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw InputException(InputError.INVALID_QUOTE)
            UtxoStrategy.transactionId(raw, quote)
            return raw
        } catch (failure: HardwareRejectedException) {
            throw InputException(
                when (failure.code) {
                    "PIN_REQUIRED" -> InputError.HARDWARE_PIN_REQUIRED
                    "DEVICE_DISCONNECTED",
                    "DEVICE_APPROVAL_REQUIRED",
                    "REQUEST_TIMEOUT" -> InputError.DEVICE_DISCONNECTED
                    else -> InputError.TRANSACTION_REJECTED
                }
            )
        } finally {
            withContext(NonCancellable) { disconnect() }
        }
    }

    override suspend fun deviceInfo(): DeviceInfo =
        info ?: throw InputException(InputError.DEVICE_DISCONNECTED)

    override suspend fun availableCoins() = CoinCatalog.coins

    override suspend fun disconnect() {
        connectedDeviceId = null
        connection.disconnect()
    }
}
