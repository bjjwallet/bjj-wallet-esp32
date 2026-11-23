package pub.airlock.domain

/**
 * Reuses the saved Ethereum public key with independent balances and receive indices per network.
 */
object BaseAccounts {
    fun register(snapshot: VaultSnapshot): VaultSnapshot {
        var result = snapshot
        for (mode in WalletMode.entries) for (network in PublicNetworks.ethereumAliases) {
            val ethereum =
                result.accounts.singleOrNull { it.mode == mode && it.coin == "ethereum" }
                    ?: continue
            val existing = result.accounts.singleOrNull { it.mode == mode && it.coin == network }
            if (existing != null && !existing.pendingPublicData) continue
            if (ethereum.xpub.isEmpty() || ethereum.path != PublicNetworks.get("ethereum").path)
                continue
            val first =
                result.assets.firstOrNull {
                    it.accountId == ethereum.id &&
                        it.coin == "ethereum" &&
                        it.contract.isEmpty() &&
                        it.derivationPath == PublicNetworks.path("ethereum", 0)
                } ?: continue
            val coin = CoinCatalog.get(network)
            val id = "${mode.name}:$network"
            requireInput(
                result.accounts.none { it.id == id && it != existing } &&
                    (existing == null ||
                        (existing.id == id &&
                            existing.xpub.isEmpty() &&
                            existing.path.isEmpty() &&
                            existing.cachedAddresses.isEmpty() &&
                            result.assets.none { it.accountId == id })) &&
                    result.assets.none { it.id == "$id:0" },
                InputError.PUBLIC_DATA_INVALID,
            )
            val account =
                Account(
                    id,
                    coin.coin,
                    coin.name,
                    coin.symbol,
                    coin.icon,
                    mode,
                    xpub = ethereum.xpub,
                    path = ethereum.path,
                    enabled = existing?.enabled ?: false,
                )
            val asset =
                first.copy(
                    id = "$id:0",
                    accountId = id,
                    coin = coin.coin,
                    name = coin.name,
                    icon = coin.icon,
                    symbol = coin.symbol,
                    decimals = coin.decimals,
                    balance = "0",
                )
            result =
                result.copy(
                    accounts = result.accounts.filterNot { it.id == id } + account,
                    assets = result.assets + asset,
                )
        }
        return result
    }
}
