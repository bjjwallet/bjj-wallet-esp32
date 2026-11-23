package pub.airlock.domain

import kotlinx.serialization.Serializable

/** Public wallet data and its encrypted local history. App security/preferences remain global. */
@Serializable
data class SavedWallet(
    val id: String,
    val name: String,
    val accounts: List<Account> = emptyList(),
    val assets: List<Asset> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    val pendingSubmissions: List<PendingSubmission> = emptyList(),
    val moneroWatchWallets: Map<WalletMode, MoneroWatchWallet> = emptyMap(),
    val moneroScanHeights: Map<WalletMode, Long> = emptyMap(),
    val sourceDeviceId: String? = null,
    val coldWalletId: String? = null,
    val config: PublicWalletConfig? = null,
) {
    override fun toString() = "SavedWallet(id=$id)"

    fun capture(snapshot: VaultSnapshot) =
        copy(
            accounts = snapshot.accounts,
            assets = snapshot.assets,
            transfers = snapshot.transfers,
            pendingSubmissions = snapshot.pendingSubmissions,
            moneroWatchWallets = snapshot.moneroWatchWallets,
            moneroScanHeights = snapshot.moneroScanHeights,
            sourceDeviceId = snapshot.sourceDeviceId,
        )

    fun activate(snapshot: VaultSnapshot) =
        snapshot.copy(
            activeWalletId = id,
            accounts = accounts,
            assets = assets,
            transfers = transfers,
            pendingSubmissions = pendingSubmissions,
            moneroWatchWallets = moneroWatchWallets,
            moneroScanHeights = moneroScanHeights,
            sourceDeviceId = sourceDeviceId,
        )
}

data class WalletChoice(val id: String, val name: String)

fun validWalletName(value: String) =
    value.trim().length in 1..128 && value.none { it.isISOControl() }
