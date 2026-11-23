package pub.airlock.data

import kotlinx.serialization.json.*
import pub.airlock.domain.*

/**
 * Converts the existing firmware's public-only get_accounts response. Never stores raw responses.
 */
class PublicAccountImporter(private val deriver: PublicKeyDeriver) {
    suspend fun decode(response: JsonElement, mode: WalletMode): WalletData {
        val rows = response as? JsonArray ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
        requireInput(rows.size in 1..4096, InputError.PUBLIC_DATA_INVALID)
        val allowed =
            setOf(
                "coin",
                "symbol",
                "name",
                "address",
                "xpub",
                "path",
                "decimals",
                "blockchain",
                "curve",
                "is_main",
                "address_index",
            )
        val selected =
            rows
                .map { element ->
                    val row =
                        element as? JsonObject
                            ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
                    requireInput(row.keys.all { it in allowed }, InputError.PUBLIC_DATA_INVALID)
                    requireInput(
                        row["is_main"]?.jsonPrimitive?.intOrNull in 0..1,
                        InputError.PUBLIC_DATA_INVALID,
                    )
                    row
                }
                .filter {
                    it["is_main"]!!.jsonPrimitive.int == if (mode == WalletMode.HIDDEN) 1 else 0
                }
        val accounts = mutableListOf<Account>()
        val assets = mutableListOf<Asset>()
        for (network in PublicNetworks.entries + PublicNetworks.optional) {
            val matches = selected.filter { it.string("coin") == network.hardwareCoin }
            if (matches.isEmpty() && network in PublicNetworks.optional) continue
            requireInput(matches.isNotEmpty(), InputError.PUBLIC_DATA_INCOMPLETE)
            val coin = CoinCatalog.get(network.coin)
            val curve =
                when {
                    network == PublicNetworks.cardano -> "ed25519ExtendedCardano"
                    network.extended -> "secp256k1"
                    else -> "ed25519"
                }
            requireInput(
                matches.all { it.string("curve") == curve },
                InputError.PUBLIC_DATA_INVALID,
            )
            requireInput(
                matches.all {
                    it["decimals"]?.jsonPrimitive?.intOrNull == coin.decimals &&
                        it.string("symbol") == coin.symbol
                },
                InputError.PUBLIC_DATA_INVALID,
            )
            val first = matches.first()
            val cached =
                if (network.extended) {
                    requireInput(
                        matches.size == 1 && first.string("path") == network.path,
                        InputError.PUBLIC_DATA_INVALID,
                    )
                    emptyList()
                } else
                    matches
                        .map { row ->
                            requireInput(
                                row.string("xpub").isEmpty(),
                                InputError.PUBLIC_DATA_INVALID,
                            )
                            val index =
                                row["address_index"]?.jsonPrimitive?.longOrNull
                                    ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
                            val path = PublicNetworks.path(coin.coin, index)
                            val address = row.string("address")
                            requireInput(
                                row.string("path") == path &&
                                    address.length in 20..128 &&
                                    address.none { it.isWhitespace() },
                                InputError.PUBLIC_DATA_INVALID,
                            )
                            if (coin.coin == "stellar")
                                requireInput(
                                    validStellarAccount(address),
                                    InputError.PUBLIC_DATA_INVALID,
                                )
                            if (network == PublicNetworks.cardano)
                                requireInput(
                                    validCardanoBaseAddress(address),
                                    InputError.PUBLIC_DATA_INVALID,
                                )
                            PublicAddress(address, path, index)
                        }
                        .sortedBy { it.index }
                        .also { list ->
                            requireInput(
                                list.firstOrNull()?.index == 0L &&
                                    list.map { it.index }.distinct().size == list.size &&
                                    list.map { it.address }.distinct().size == list.size,
                                InputError.PUBLIC_DATA_INVALID,
                            )
                        }
            val account =
                Account(
                    "${mode.name}:${coin.coin}",
                    coin.coin,
                    coin.name,
                    coin.symbol,
                    coin.icon,
                    mode,
                    xpub = first.string("xpub"),
                    path = network.path,
                    enabled = coin.coin in PublicNetworks.initiallyEnabled,
                    cachedAddresses = cached,
                )
            val asset = deriver.derive(account, 0)
            if (network.extended) {
                val hardwareAddress = first.string("address")
                val equal =
                    if (asset.address.startsWith("0x")) asset.address.equals(hardwareAddress, true)
                    else asset.address == hardwareAddress
                requireInput(equal, InputError.PUBLIC_DATA_INVALID)
            }
            accounts += account
            assets += asset
        }
        return WalletData(accounts, assets)
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

/** CIP-19 mainnet key/key base addresses are longer than Bitcoin's Bech32 limit. */
internal fun validCardanoBaseAddress(address: String): Boolean {
    return cardanoPaymentAddress(address)?.let { it.size == 57 && it[0] == 1.toByte() } == true
}

internal fun cardanoPaymentAddress(address: String): ByteArray? {
    if (address.length !in setOf(103, 58) || !address.startsWith("addr1")) return null
    val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    var checksum = 1
    fun feed(value: Int) {
        val top = checksum ushr 25
        checksum = ((checksum and 0x1ffffff) shl 5) xor value
        for (i in generators.indices) if ((top ushr i) and 1 != 0)
            checksum = checksum xor generators[i]
    }
    for (char in "addr") feed(char.code ushr 5)
    feed(0)
    for (char in "addr") feed(char.code and 31)
    val payload = ByteArray(if (address.length == 103) 57 else 29)
    var written = 0
    var acc = 0
    var bits = 0
    for (i in 5 until address.length) {
        val value = alphabet.indexOf(address[i])
        if (value < 0) return null
        feed(value)
        if (i >= address.length - 6) continue
        acc = (acc shl 5) or value
        bits += 5
        if (bits >= 8) {
            bits -= 8
            payload[written++] = (acc ushr bits).toByte()
        }
    }
    if (checksum != 1 || acc and ((1 shl bits) - 1) != 0) return null
    return payload.takeIf {
        (it.size == 57 && it[0].toInt() in setOf(1, 0x21)) ||
            (it.size == 29 && it[0] == 0x61.toByte())
    }
}

/** Stellar StrKey account IDs encode a version byte, an Ed25519 public key and CRC16-XModem. */
internal fun validStellarAccount(address: String): Boolean {
    if (!Regex("G[A-Z2-7]{55}").matches(address)) return false
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val bytes =
        address.chunked(8).flatMap { block ->
            val bits =
                block.fold(0L) { value, char -> (value shl 5) or alphabet.indexOf(char).toLong() }
            (32 downTo 0 step 8).map { shift -> ((bits shr shift) and 255).toInt() }
        }
    if (bytes.first() != 48) return false
    var crc = 0
    for (byte in bytes.take(33)) {
        crc = crc xor (byte shl 8)
        repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xffff }
    }
    return crc == (bytes[33] or (bytes[34] shl 8))
}
