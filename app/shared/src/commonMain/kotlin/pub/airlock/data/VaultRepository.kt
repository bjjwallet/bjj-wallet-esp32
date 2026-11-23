package pub.airlock.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import pub.airlock.domain.*

class VaultRepository(
    private val storage: VaultStorage,
    private val crypto: PinCrypto,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private var cache: VaultSnapshot? = null

    suspend fun snapshot(): VaultSnapshot = mutex.withLock { load() }

    private suspend fun load(): VaultSnapshot {
        cache?.let {
            return it
        }
        val raw = withContext(dispatcher) { storage.read().contents }
        val decoded = raw?.let { json.decodeFromString<VaultSnapshot>(it) } ?: VaultSnapshot()
        val snapshot =
            if (decoded.networkSettings.preferred !in NetworkProvider.available)
                decoded.copy(networkSettings = NetworkSettings())
            else decoded
        require(snapshot.schemaVersion == 1) { "Unsupported vault version" }
        if (snapshot != decoded) return save(snapshot)
        // Remove device PINs persisted by the previous build before exposing the vault.
        if (raw != null && "hardwareCredentials" in json.parseToJsonElement(raw).jsonObject)
            return save(snapshot)
        val normalized = captureActiveWallet(snapshot)
        if (normalized != snapshot) return save(normalized)
        cache = normalized
        return normalized
    }

    suspend fun update(transform: (VaultSnapshot) -> VaultSnapshot): VaultSnapshot =
        mutex.withLock { save(transform(load())) }

    private suspend fun save(candidate: VaultSnapshot): VaultSnapshot {
        val next = captureActiveWallet(candidate)
        try {
            withContext(dispatcher) { storage.write(json.encodeToString(next)) }
        } catch (e: Exception) {
            // A write may have committed just before cancellation. Reload the durable state next
            // time.
            cache = null
            throw e
        }
        // Publish only after durable storage succeeded.
        cache = next
        return next
    }

    /** Root wallet fields are the active projection, kept for existing network/address adapters. */
    private fun captureActiveWallet(candidate: VaultSnapshot): VaultSnapshot {
        val snapshot =
            candidate.moneroWatchWallets.entries.fold(candidate) { current, (mode, watch) ->
                withMoneroAddress(current, mode, watch.address)
            }
        if (snapshot.savedWallets.isEmpty()) {
            require(snapshot.activeWalletId == null)
            if (snapshot.accounts.isEmpty() && snapshot.assets.isEmpty()) return snapshot
            val wallet = SavedWallet("wallet-1", "Carteira 1").capture(snapshot)
            return snapshot.copy(activeWalletId = wallet.id, savedWallets = listOf(wallet))
        }
        require(snapshot.savedWallets.map { it.id }.distinct().size == snapshot.savedWallets.size)
        require(snapshot.savedWallets.all { it.id.isNotBlank() && validWalletName(it.name) })
        require(snapshot.savedWallets.any { it.id == snapshot.activeWalletId })
        val coldIds = snapshot.savedWallets.mapNotNull { it.coldWalletId }
        require(coldIds.distinct().size == coldIds.size && coldIds.all(::validColdWalletId))
        return snapshot.copy(
            savedWallets =
                snapshot.savedWallets.map {
                    if (it.id == snapshot.activeWalletId) it.capture(snapshot) else it
                }
        )
    }

    suspend fun selectWallet(id: String) = update { current ->
        val selected =
            current.savedWallets.singleOrNull { it.id == id }
                ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
        selected.activate(current)
    }

    suspend fun renameWallet(id: String, name: String) = update { current ->
        requireInput(validWalletName(name), InputError.INVALID_WALLET_NAME)
        requireInput(current.savedWallets.any { it.id == id }, InputError.PUBLIC_DATA_INVALID)
        current.copy(
            savedWallets =
                current.savedWallets.map { if (it.id == id) it.copy(name = name.trim()) else it }
        )
    }

    private fun publicIdentities(
        accounts: List<Account>,
        assets: List<Asset>,
    ): Map<String, String> =
        accounts
            .filterNot { it.pendingPublicData || it.coin in PublicNetworks.ethereumAliases }
            .mapNotNull { account ->
                val value =
                    account.xpub.ifEmpty {
                        account.cachedAddresses.firstOrNull { it.index == 0L }?.address
                            ?: assets
                                .firstOrNull {
                                    it.accountId == account.id &&
                                        it.contract.isEmpty() &&
                                        (it.derivationPath == account.path || it.id.endsWith(":0"))
                                }
                                ?.address
                                .orEmpty()
                    }
                value.takeIf { it.isNotEmpty() }?.let { "${account.mode}:${account.coin}" to it }
            }
            .toMap()

    /** Atomic local reset. It never sends a command to the cold wallet. */
    suspend fun resetForSynchronization(): VaultSnapshot = mutex.withLock { save(VaultSnapshot()) }

    suspend fun prepareAppSecurity(pin: String?): AppSecurityConfiguration {
        val record =
            pin?.let {
                PinPolicy.validate(it)
                withContext(dispatcher) {
                    val salt = crypto.randomHex(16)
                    PinRecord(salt, crypto.derive(it, salt), "")
                }
            }
        return AppSecurityConfiguration(record)
    }

    suspend fun configureAppSecurity(pin: String?) {
        val configuration = prepareAppSecurity(pin)
        update { it.copy(appSecurityConfigured = true, appPin = configuration.pin) }
    }

    suspend fun unlockApp(pin: String): Boolean {
        val record = snapshot().appPin ?: return false
        if (PinPolicy.editError(pin) != null) return false
        val hash = withContext(dispatcher) { crypto.derive(pin, record.salt) }
        return constantTimeEquals(hash, record.standardHash)
    }

    suspend fun configurePins(standard: String, hidden: String) {
        PinPolicy.validate(standard)
        PinPolicy.validate(hidden)
        requireInput(standard != hidden, InputError.PIN_SAME)
        val record =
            withContext(dispatcher) {
                val salt = crypto.randomHex(16)
                PinRecord(salt, crypto.derive(standard, salt), crypto.derive(hidden, salt))
            }
        update { current ->
            check(current.pins == null) { "Wallet already configured" }
            current.copy(pins = record)
        }
    }

    suspend fun unlock(pin: String): WalletMode? {
        if (pin.length !in 6..32) return null
        val record = snapshot().pins ?: return null
        val hash = withContext(dispatcher) { crypto.derive(pin, record.salt) }
        val standard = constantTimeEquals(hash, record.standardHash)
        val hidden = constantTimeEquals(hash, record.hiddenHash)
        return when {
            standard -> WalletMode.STANDARD
            hidden -> WalletMode.HIDDEN
            else -> null
        }
    }

    suspend fun changePin(mode: WalletMode, old: String, new: String) {
        PinPolicy.validate(new)
        if (snapshot().appSecurityConfigured) {
            requireInput(unlockApp(old), InputError.CURRENT_PIN)
            configureAppSecurity(new)
            return
        }
        requireInput(unlock(old) == mode, InputError.CURRENT_PIN)
        val record = snapshot().pins ?: error("Wallet not configured")
        val hash = withContext(dispatcher) { crypto.derive(new, record.salt) }
        val other = if (mode == WalletMode.STANDARD) record.hiddenHash else record.standardHash
        requireInput(!constantTimeEquals(hash, other), InputError.PIN_SAME)
        update {
            it.copy(
                pins =
                    if (mode == WalletMode.STANDARD) record.copy(standardHash = hash)
                    else record.copy(hiddenHash = hash)
            )
        }
    }

    suspend fun merge(mode: WalletMode, data: WalletData) = mergeWallets(mapOf(mode to data))

    /**
     * Both wallets are committed together, so a rejected second export leaves the vault unchanged.
     */
    suspend fun mergeWallets(wallets: Map<WalletMode, WalletData>) {
        require(wallets.isNotEmpty())
        update { current ->
            BaseAccounts.register(
                wallets.entries.fold(current) { snapshot, (mode, data) ->
                    merged(snapshot, mode, data)
                }
            )
        }
    }

    /** Persist only the app PIN verifier with the public export, in one encrypted write. */
    suspend fun commitHardwareSynchronization(
        wallets: Map<WalletMode, WalletData>,
        appSecurity: AppSecurityConfiguration? = null,
        walletName: String? = null,
        sourceDeviceId: String? = null,
    ) {
        requireInput(wallets.keys == WalletMode.entries.toSet(), InputError.PUBLIC_DATA_INCOMPLETE)
        if (walletName != null)
            requireInput(validWalletName(walletName), InputError.INVALID_WALLET_NAME)
        update { current ->
            // Validate the complete export before selecting or creating a destination.
            val incoming =
                wallets.entries.fold(VaultSnapshot()) { snapshot, (mode, data) ->
                    merged(snapshot, mode, data)
                }
            val identity = publicIdentities(incoming.accounts, incoming.assets)
            val matching =
                current.savedWallets.filter { saved ->
                    val known = publicIdentities(saved.accounts, saved.assets)
                    val common = identity.keys.intersect(known.keys)
                    common.isNotEmpty() && common.all { identity[it] == known[it] }
                }
            requireInput(matching.size <= 1, InputError.PUBLIC_DATA_CHANGED)
            val active = current.savedWallets.firstOrNull { it.id == current.activeWalletId }
            val target =
                matching.singleOrNull()
                    ?: active?.takeIf { publicIdentities(it.accounts, it.assets).isEmpty() }
            val destination =
                if (target != null) target.activate(current)
                else {
                    val number =
                        (current.savedWallets
                            .mapNotNull { it.id.removePrefix("wallet-").toIntOrNull() }
                            .maxOrNull() ?: 0) + 1
                    val fresh =
                        SavedWallet("wallet-$number", walletName?.trim() ?: "Carteira $number")
                    fresh.activate(current.copy(savedWallets = current.savedWallets + fresh))
                }
            var imported =
                BaseAccounts.register(
                    wallets.entries.fold(destination) { snapshot, (mode, data) ->
                        merged(snapshot, mode, data)
                    }
                )
            if (walletName != null)
                imported =
                    imported.copy(
                        savedWallets =
                            imported.savedWallets.map {
                                if (it.id == imported.activeWalletId)
                                    it.copy(name = walletName.trim())
                                else it
                            }
                    )
            val assets =
                WalletMode.entries.fold(imported.assets) { assets, mode ->
                    val ids =
                        imported.accounts
                            .filter { it.mode == mode && it.enabled }
                            .map { it.id }
                            .toSet()
                    assets + Stablecoins.missing(assets.filter { it.accountId in ids })
                }
            imported.copy(
                assets = assets,
                sourceDeviceId = sourceDeviceId ?: target?.sourceDeviceId,
                appSecurityConfigured =
                    if (appSecurity != null) true else current.appSecurityConfigured,
                appPin = if (appSecurity != null) appSecurity.pin else current.appPin,
            )
        }
    }

    /** Commit the complete manifest and both modes in a single encrypted write. */
    suspend fun commitPublicWalletSynchronization(
        result: WalletSynchronization,
        appSecurity: AppSecurityConfiguration? = null,
        walletName: String? = null,
        sourceDeviceId: String? = null,
    ) {
        if (result is WalletSynchronization.Legacy) {
            commitHardwareSynchronization(result.modes, appSecurity, walletName, sourceDeviceId)
            return
        }
        val wallets = (result as WalletSynchronization.Stable).wallets
        requireInput(
            wallets.size in 1..128 &&
                wallets.map { it.descriptor.id }.distinct().size == wallets.size,
            InputError.PUBLIC_DATA_INVALID,
        )
        val incoming =
            wallets.associate { wallet ->
                val descriptor = wallet.descriptor
                requireInput(
                    validColdWalletId(descriptor.id) &&
                        validWalletName(descriptor.name) &&
                        descriptor.config.wordCount in setOf(12, 15, 18, 21, 24),
                    InputError.PUBLIC_DATA_INVALID,
                )
                requireInput(
                    wallet.modes.keys == WalletMode.entries.toSet(),
                    InputError.PUBLIC_DATA_INCOMPLETE,
                )
                descriptor.id to
                    wallet.modes.entries.fold(VaultSnapshot()) { snapshot, (mode, data) ->
                        merged(snapshot, mode, data)
                    }
            }
        fun matches(saved: SavedWallet, candidate: VaultSnapshot): Boolean {
            val known = publicIdentities(saved.accounts, saved.assets)
            val fresh = publicIdentities(candidate.accounts, candidate.assets)
            val common = known.keys.intersect(fresh.keys)
            return common.isNotEmpty() && common.all { known[it] == fresh[it] }
        }
        update { current ->
            var next = current
            val synchronizedIds = mutableListOf<String>()
            for (wallet in wallets) {
                val descriptor = wallet.descriptor
                val stable = next.savedWallets.singleOrNull { it.coldWalletId == descriptor.id }
                // Migrate a legacy profile only when its public identity unambiguously identifies
                // one incoming wallet.
                val legacy =
                    if (stable == null)
                        next.savedWallets.filter {
                            it.coldWalletId == null &&
                                matches(it, incoming.getValue(descriptor.id)) &&
                                incoming.values.count { candidate -> matches(it, candidate) } == 1
                        }
                    else emptyList()
                requireInput(legacy.size <= 1, InputError.PUBLIC_DATA_CHANGED)
                val placeholder =
                    if (wallets.size == 1)
                        next.savedWallets.singleOrNull {
                            it.id == current.activeWalletId &&
                                it.coldWalletId == null &&
                                publicIdentities(it.accounts, it.assets).isEmpty()
                        }
                    else null
                val target = stable ?: legacy.singleOrNull() ?: placeholder
                val id = target?.id ?: "cold:${descriptor.id}"
                requireInput(
                    id !in synchronizedIds &&
                        (target != null || next.savedWallets.none { it.id == id }),
                    InputError.PUBLIC_DATA_INVALID,
                )
                val destination =
                    target
                        ?: SavedWallet(
                            id,
                            descriptor.name,
                            coldWalletId = descriptor.id,
                            config = descriptor.config,
                        )
                var active = destination.activate(next)
                active =
                    BaseAccounts.register(
                        wallet.modes.entries.fold(active) { snapshot, (mode, data) ->
                            merged(snapshot, mode, data)
                        }
                    )
                for ((mode, watch) in wallet.monero) active = withMoneroWatch(active, mode, watch)
                val assets =
                    WalletMode.entries.fold(active.assets) { assets, mode ->
                        val ids =
                            active.accounts
                                .filter { it.mode == mode && it.enabled }
                                .map { it.id }
                                .toSet()
                        assets + Stablecoins.missing(assets.filter { it.accountId in ids })
                    }
                active =
                    active.copy(
                        assets = assets,
                        sourceDeviceId = sourceDeviceId ?: target?.sourceDeviceId,
                    )
                val captured =
                    destination
                        .copy(
                            name = descriptor.name,
                            coldWalletId = descriptor.id,
                            config = descriptor.config,
                        )
                        .capture(active)
                next =
                    active.copy(
                        savedWallets = next.savedWallets.filterNot { it.id == id } + captured
                    )
                synchronizedIds += id
            }
            val selected =
                current.activeWalletId?.takeIf { it in synchronizedIds } ?: synchronizedIds.first()
            next.savedWallets
                .single { it.id == selected }
                .activate(next)
                .copy(
                    appSecurityConfigured =
                        if (appSecurity != null) true else current.appSecurityConfigured,
                    appPin = if (appSecurity != null) appSecurity.pin else current.appPin,
                )
        }
    }

    /**
     * Backfills existing public vaults without Bluetooth, a new seed or copied Ethereum balances.
     */
    suspend fun registerBaseAccounts() =
        mutex.withLock {
            val current = load()
            val next = BaseAccounts.register(current)
            if (next != current) save(next)
        }

    private fun merged(current: VaultSnapshot, mode: WalletMode, data: WalletData): VaultSnapshot {
        require(data.accounts.all { it.mode == mode }) { "Wallet mode mismatch" }
        require(data.accounts.map { it.id }.distinct().size == data.accounts.size)
        require(data.assets.map { it.id }.distinct().size == data.assets.size)
        val incomingIds = data.accounts.map { it.id }.toSet()
        require(data.assets.all { it.accountId in incomingIds })
        val otherIds = current.accounts.filter { it.mode != mode }.map { it.id }.toSet()
        require(incomingIds.intersect(otherIds).isEmpty()) { "Account ID collision" }
        val otherAssetIds = current.assets.filter { it.accountId in otherIds }.map { it.id }.toSet()
        require(data.assets.none { it.id in otherAssetIds }) { "Asset ID collision" }
        val previousAccounts = current.accounts.associateBy { it.id }
        val imported =
            data.accounts.associate { incoming ->
                val old = previousAccounts[incoming.id]
                if (old?.pendingPublicData == true) {
                    requireInput(
                        old.coin == incoming.coin &&
                            old.xpub.isEmpty() &&
                            old.path.isEmpty() &&
                            old.cachedAddresses.isEmpty() &&
                            current.assets.none { it.accountId == old.id },
                        InputError.PUBLIC_DATA_CHANGED,
                    )
                } else if (old != null) {
                    requireInput(
                        old.coin == incoming.coin &&
                            old.xpub == incoming.xpub &&
                            (old.path.isEmpty() || old.path == incoming.path),
                        InputError.PUBLIC_DATA_CHANGED,
                    )
                    val known = old.cachedAddresses.associateBy { it.index }
                    requireInput(
                        incoming.cachedAddresses.all {
                            known[it.index] == null || known[it.index] == it
                        },
                        InputError.PUBLIC_DATA_CHANGED,
                    )
                }
                incoming.id to
                    incoming.copy(
                        enabled = old?.enabled ?: incoming.enabled,
                        nextReceiveIndex =
                            maxOf(old?.nextReceiveIndex ?: 1, incoming.nextReceiveIndex),
                        cachedAddresses =
                            ((old?.cachedAddresses.orEmpty().associateBy { it.index }) +
                                    incoming.cachedAddresses.associateBy { it.index })
                                .values
                                .sortedBy { it.index },
                    )
            }
        val accounts = previousAccounts + imported
        val previousAssets = current.assets.associateBy { it.id }
        val assets =
            previousAssets +
                data.assets.associate { asset ->
                    val old = previousAssets[asset.id]
                    requireInput(
                        old == null ||
                            (old.accountId == asset.accountId &&
                                old.coin == asset.coin &&
                                old.address == asset.address &&
                                old.contract == asset.contract),
                        InputError.PUBLIC_DATA_CHANGED,
                    )
                    asset.id to asset.copy(balance = old?.balance ?: asset.balance)
                }
        require(assets.values.all { asset -> accounts.containsKey(asset.accountId) })
        return current.copy(accounts = accounts.values.toList(), assets = assets.values.toList())
    }

    suspend fun saveMoneroReceiveAddress(
        mode: WalletMode,
        data: MoneroReceiveAddress,
        walletId: String,
    ) {
        update { snapshot ->
            requireInput(snapshot.activeWalletId == walletId, InputError.PUBLIC_DATA_CHANGED)
            withMoneroAddress(snapshot, mode, data.address)
        }
    }

    /** Provision view-only material from an authenticated hardware export. */
    suspend fun saveMoneroWatchWallet(
        mode: WalletMode,
        wallet: MoneroWatchWallet,
        walletId: String? = null,
    ) {
        update { snapshot ->
            requireInput(
                walletId == null || snapshot.activeWalletId == walletId,
                InputError.PUBLIC_DATA_CHANGED,
            )
            withMoneroWatch(snapshot, mode, wallet)
        }
    }

    suspend fun setMoneroRestoreHeight(mode: WalletMode, walletId: String, height: Long?) {
        update { snapshot ->
            requireInput(
                snapshot.activeWalletId == walletId && mode !in snapshot.moneroScanHeights,
                InputError.PUBLIC_DATA_CHANGED,
            )
            val watch =
                snapshot.moneroWatchWallets[mode]
                    ?: throw InputException(InputError.MONERO_VIEW_SYNC_REQUIRED)
            val updated = watch.copy(restoreHeightOverride = height)
            snapshot.copy(moneroWatchWallets = snapshot.moneroWatchWallets + (mode to updated))
        }
    }

    private fun withMoneroWatch(
        snapshot: VaultSnapshot,
        mode: WalletMode,
        wallet: MoneroWatchWallet,
    ): VaultSnapshot {
        val saved = snapshot.moneroWatchWallets[mode]
        if (saved != null) {
            requireInput(
                saved.network == wallet.network &&
                    saved.address == wallet.address &&
                    constantTimeEquals(saved.privateViewKey, wallet.privateViewKey),
                InputError.PUBLIC_DATA_CHANGED,
            )
        }
        val updated =
            if (saved == null) wallet
            else saved.copy(restoreHeight = minOf(saved.restoreHeight, wallet.restoreHeight))
        val provisioned =
            snapshot.copy(moneroWatchWallets = snapshot.moneroWatchWallets + (mode to updated))
        return withMoneroAddress(provisioned, mode, wallet.address)
    }

    private fun withMoneroAddress(
        snapshot: VaultSnapshot,
        mode: WalletMode,
        address: String,
    ): VaultSnapshot {
        val coin = CoinCatalog.get("monero")
        val accountId = "${mode.name}:monero"
        val existing = snapshot.accounts.singleOrNull { it.mode == mode && it.coin == coin.coin }
        val account =
            existing?.copy(pendingPublicData = false)
                ?: Account(accountId, coin.coin, coin.name, coin.symbol, coin.icon, mode)
        requireInput(account.id == accountId, InputError.PUBLIC_DATA_INVALID)
        val assetId = "$accountId:0"
        val asset = snapshot.assets.singleOrNull { it.id == assetId }
        requireInput(
            asset == null ||
                (asset.accountId == accountId &&
                    asset.coin == coin.coin &&
                    asset.address == address),
            InputError.PUBLIC_DATA_CHANGED,
        )
        if (account == existing && asset != null) return snapshot
        val accounts =
            if (existing == null) snapshot.accounts + account
            else snapshot.accounts.map { if (it.id == accountId) account else it }
        val assets =
            if (asset != null) snapshot.assets
            else
                snapshot.assets +
                    Asset(
                        assetId,
                        accountId,
                        coin.coin,
                        coin.symbol,
                        coin.name,
                        address,
                        coin.icon,
                        coin.decimals,
                    )
        return snapshot.copy(accounts = accounts, assets = assets)
    }

    /** Commit the verified balance, height and latest transaction in the same vault write. */
    suspend fun refreshMonero(
        mode: WalletMode,
        signedImages: List<MoneroKeyImage>? = null,
        scanner: MoneroScanner,
    ): Boolean {
        val source = snapshot()
        if (source.accounts.any { it.mode == mode && it.coin == "monero" && !it.enabled })
            return false
        val previous = source.moneroWatchWallets[mode] ?: return false
        val wallet = if (signedImages == null) previous else previous.copy(keyImages = signedImages)
        val scan = scanner.scan(wallet) ?: return false
        val coin = CoinCatalog.get("monero")
        val atomic = Amount.atomic(scan.balance, coin.decimals)
        val balance = Amount.display(atomic, coin.decimals)
        val unlockedBalance =
            scan.unlockedBalance?.let {
                val unlocked = Amount.atomic(it, coin.decimals)
                requireInput(Amount.compare(unlocked, atomic) <= 0, InputError.PUBLIC_DATA_INVALID)
                Amount.display(unlocked, coin.decimals)
            }
        val accountId = "${mode.name}:monero"
        val assetId = "$accountId:0"
        val transfer =
            scan.lastTransfer?.let {
                requireInput(
                    it.txId.matches(Regex("[0-9a-f]{64}")) &&
                        Amount.atomic(it.amount, coin.decimals) != "0",
                    InputError.PUBLIC_DATA_INVALID,
                )
                Transfer(
                    it.txId,
                    assetId,
                    it.amount,
                    if (it.incoming) wallet.address else "",
                    it.incoming,
                    TransferStatus.CONFIRMED,
                )
            }
        requireInput(scan.height >= wallet.scanFromHeight, InputError.PUBLIC_DATA_INVALID)
        return mutex.withLock {
            val current = load()
            if (
                current.activeWalletId != source.activeWalletId ||
                    current.moneroWatchWallets[mode] != previous ||
                    scan.height < (current.moneroScanHeights[mode] ?: 0)
            )
                return@withLock false
            val existing = current.accounts.singleOrNull { it.mode == mode && it.coin == coin.coin }
            if (existing?.enabled == false) return@withLock false
            val account =
                existing?.copy(pendingPublicData = false)
                    ?: Account(accountId, coin.coin, coin.name, coin.symbol, coin.icon, mode)
            requireInput(account.id == accountId, InputError.PUBLIC_DATA_INVALID)
            val asset =
                Asset(
                    assetId,
                    accountId,
                    coin.coin,
                    coin.symbol,
                    coin.name,
                    wallet.address,
                    coin.icon,
                    coin.decimals,
                    balance,
                    unlockedBalance = unlockedBalance,
                )
            val next =
                current.copy(
                    accounts = current.accounts.filterNot { it.id == accountId } + account,
                    assets = current.assets.filterNot { it.id == assetId } + asset,
                    transfers =
                        if (transfer == null) current.transfers
                        else current.transfers.filterNot { it.assetId == assetId } + transfer,
                    moneroWatchWallets = current.moneroWatchWallets + (mode to wallet),
                    moneroScanHeights = current.moneroScanHeights + (mode to scan.height),
                )
            if (next != current) save(next)
            true
        }
    }

    suspend fun publicAccounts(mode: WalletMode) = snapshot().accounts.filter { it.mode == mode }

    suspend fun accounts(mode: WalletMode) = publicAccounts(mode).filter { it.enabled }

    /** Hides the account without discarding its public data, history or receive index. */
    suspend fun disableAccount(mode: WalletMode, coin: String) {
        update { snapshot ->
            snapshot.copy(
                accounts =
                    snapshot.accounts.map {
                        if (it.mode == mode && it.coin == coin) it.copy(enabled = false) else it
                    }
            )
        }
    }

    suspend fun enableAccount(mode: WalletMode, coin: String) {
        update { snapshot ->
            val definition = CoinCatalog.get(coin)
            val account = snapshot.accounts.singleOrNull { it.mode == mode && it.coin == coin }
            if (account == null) {
                val id = "${mode.name}:$coin"
                requireInput(snapshot.accounts.none { it.id == id }, InputError.PUBLIC_DATA_INVALID)
                snapshot.copy(
                    accounts =
                        snapshot.accounts +
                            Account(
                                id,
                                coin,
                                definition.name,
                                definition.symbol,
                                definition.icon,
                                mode,
                                pendingPublicData = true,
                            )
                )
            } else
                snapshot.copy(
                    accounts =
                        snapshot.accounts.map {
                            if (it.id == account.id) it.copy(enabled = true) else it
                        }
                )
        }
        registerStablecoins(mode)
    }

    /**
     * Derivation and durable index allocation share a lock: failed writes do not consume an index.
     */
    suspend fun addPublicAddress(
        mode: WalletMode,
        accountId: String,
        deriver: PublicKeyDeriver,
    ): Asset =
        mutex.withLock {
            val current = load()
            val account =
                current.accounts.singleOrNull {
                    it.id == accountId && it.mode == mode && it.enabled
                } ?: throw InputException(InputError.PUBLIC_DATA_SYNC_REQUIRED)
            requireInput(!account.pendingPublicData, InputError.PUBLIC_DATA_SYNC_REQUIRED)
            val native =
                current.assets.filter { it.accountId == accountId && it.contract.isEmpty() }
            val lastUsed =
                native.maxOfOrNull { asset ->
                    asset.derivationPath.substringAfterLast('/').removeSuffix("'").toLongOrNull()
                        ?: asset.id.substringAfterLast(':').toLongOrNull()
                        ?: throw InputException(InputError.PUBLIC_DATA_INVALID)
                } ?: -1
            val index = maxOf(account.nextReceiveIndex, lastUsed + 1)
            requireInput(index in 0..0x7fffffff, InputError.ADDRESS_INDEX_EXHAUSTED)
            val asset = withContext(dispatcher) { deriver.derive(account, index) }
            requireInput(
                asset.accountId == account.id &&
                    asset.coin == account.coin &&
                    asset.contract.isEmpty() &&
                    asset.balance == "0" &&
                    asset.derivationPath == PublicNetworks.path(account.coin, index) &&
                    current.assets.none { it.id == asset.id } &&
                    native.none { it.address == asset.address },
                InputError.PUBLIC_DATA_INVALID,
            )
            val tokens = Stablecoins.forNetwork(account.coin).map { it.asset(asset) }
            requireInput(
                tokens.none { token -> current.assets.any { it.id == token.id } },
                InputError.PUBLIC_DATA_INVALID,
            )
            save(
                current.copy(
                    accounts =
                        current.accounts.map {
                            if (it.id == account.id) it.copy(nextReceiveIndex = index + 1) else it
                        },
                    assets = current.assets + asset + tokens,
                )
            )
            asset
        }

    suspend fun registerStablecoins(mode: WalletMode) {
        if (Stablecoins.missing(assets(mode)).isEmpty()) return
        update { snapshot ->
            val ids =
                snapshot.accounts.filter { it.mode == mode && it.enabled }.map { it.id }.toSet()
            val missing = Stablecoins.missing(snapshot.assets.filter { it.accountId in ids })
            require(missing.none { token -> snapshot.assets.any { it.id == token.id } }) {
                "Token ID collision"
            }
            snapshot.copy(assets = snapshot.assets + missing)
        }
    }

    suspend fun assets(mode: WalletMode): List<Asset> {
        val snapshot = snapshot()
        val ids = snapshot.accounts.filter { it.mode == mode && it.enabled }.map { it.id }.toSet()
        return snapshot.assets.filter { it.accountId in ids }
    }
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    var difference = a.length xor b.length
    for (i in 0 until maxOf(a.length, b.length)) difference =
        difference or (a.getOrElse(i) { '\u0000' }.code xor b.getOrElse(i) { '\u0000' }.code)
    return difference == 0
}
