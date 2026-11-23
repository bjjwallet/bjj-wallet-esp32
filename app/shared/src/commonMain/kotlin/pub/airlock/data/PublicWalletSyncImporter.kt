package pub.airlock.data

import kotlinx.serialization.json.*
import pub.airlock.domain.*

/** Strict wallet_sync_version=1 envelopes; never persist raw hardware responses. */
internal class PublicWalletSyncImporter(private val accounts: PublicAccountImporter) {
    fun manifest(response: JsonElement): List<PublicWalletDescriptor> {
        val rows = response as? JsonArray ?: invalid()
        requireInput(rows.size in 1..128, InputError.PUBLIC_DATA_INCOMPLETE)
        val result =
            rows.map { value ->
                val row = value as? JsonObject ?: invalid()
                requireInput(
                    row.keys == setOf("id", "name", "config"),
                    InputError.PUBLIC_DATA_INVALID,
                )
                descriptor(row, "id", "name")
            }
        requireInput(
            result.map { it.id }.distinct().size == result.size,
            InputError.PUBLIC_DATA_INVALID,
        )
        return result
    }

    suspend fun groups(
        response: JsonElement,
        manifest: List<PublicWalletDescriptor>,
        mode: WalletMode,
    ): Map<String, WalletData> {
        val rows = response as? JsonArray ?: invalid()
        requireInput(rows.size == manifest.size, InputError.PUBLIC_DATA_INCOMPLETE)
        val expected = manifest.associateBy { it.id }
        val result = mutableMapOf<String, WalletData>()
        for (value in rows) {
            val row = value as? JsonObject ?: invalid()
            requireInput(
                row.keys == setOf("wallet_id", "wallet_name", "config", "accounts"),
                InputError.PUBLIC_DATA_INVALID,
            )
            val descriptor = descriptor(row, "wallet_id", "wallet_name")
            requireInput(expected[descriptor.id] == descriptor, InputError.PUBLIC_DATA_CHANGED)
            requireInput(descriptor.id !in result, InputError.PUBLIC_DATA_INVALID)
            result[descriptor.id] = accountRows(row.getValue("accounts"), mode)
        }
        requireInput(result.keys == expected.keys, InputError.PUBLIC_DATA_INCOMPLETE)
        return result
    }

    suspend fun accountRows(response: JsonElement, mode: WalletMode): WalletData {
        val rows = response as? JsonArray ?: invalid()
        val expected = if (mode == WalletMode.HIDDEN) 1 else 0
        val normalized =
            JsonArray(
                rows.map { value ->
                    val row = value as? JsonObject ?: invalid()
                    // The group/request supplies the mode; legacy rows may also carry is_main.
                    row["is_main"]?.let {
                        requireInput(
                            (it as? JsonPrimitive)?.intOrNull == expected,
                            InputError.PUBLIC_DATA_CHANGED,
                        )
                    }
                    JsonObject(row + ("is_main" to JsonPrimitive(expected)))
                }
            )
        return accounts.decode(normalized, mode)
    }

    private fun descriptor(
        row: JsonObject,
        idKey: String,
        nameKey: String,
    ): PublicWalletDescriptor {
        val id = string(row[idKey])
        val name = string(row[nameKey])
        requireInput(validColdWalletId(id) && validWalletName(name), InputError.PUBLIC_DATA_INVALID)
        val config = row["config"] as? JsonObject ?: invalid()
        requireInput(
            config.keys - "monero_enabled" == setOf("word_count", "passphrase_enabled", "imported"),
            InputError.PUBLIC_DATA_INVALID,
        )
        val wordCount =
            (config["word_count"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: invalid()
        requireInput(wordCount in setOf(12, 15, 18, 21, 24), InputError.PUBLIC_DATA_INVALID)
        fun flag(key: String) =
            (config[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
        val moneroEnabled = if ("monero_enabled" in config) flag("monero_enabled") else null
        return PublicWalletDescriptor(
            id,
            name.trim(),
            PublicWalletConfig(
                wordCount,
                flag("passphrase_enabled"),
                flag("imported"),
                moneroEnabled,
            ),
        )
    }

    private fun string(value: JsonElement?) =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun invalid(): Nothing = throw InputException(InputError.PUBLIC_DATA_INVALID)
}
