package pub.airlock.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** BIP39 configuration metadata only. No passphrase or secret material. */
@Serializable
data class PublicWalletConfig(
    @SerialName("word_count") val wordCount: Int,
    @SerialName("passphrase_enabled") val passphraseEnabled: Boolean,
    val imported: Boolean,
    @SerialName("monero_enabled") val moneroEnabled: Boolean? = null,
)

data class PublicWalletDescriptor(val id: String, val name: String, val config: PublicWalletConfig)

data class SynchronizedPublicWallet(
    val descriptor: PublicWalletDescriptor,
    val modes: Map<WalletMode, WalletData>,
    val monero: Map<WalletMode, MoneroWatchWallet> = emptyMap(),
)

sealed interface WalletSynchronization {
    data class Legacy(val modes: Map<WalletMode, WalletData>) : WalletSynchronization

    data class Stable(val wallets: List<SynchronizedPublicWallet>) : WalletSynchronization
}

fun validColdWalletId(value: String) =
    value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }
