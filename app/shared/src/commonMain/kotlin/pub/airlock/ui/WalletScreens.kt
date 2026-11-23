@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pub.airlock.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.*
import pub.airlock.presentation.*
import pub.airlock.resources.*

private val PageGrey: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val LineGrey: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val Corner = RoundedCornerShape(4.dp)

@Composable
internal fun WalletContent(state: WalletUiState, vm: WalletViewModel, actions: AddressActions) {
    when (state.screen) {
        Screen.ADD_ACCOUNT -> {
            AddAccountScreen(state, vm)
            return
        }
        Screen.WALLETS -> {
            AssetsScreen(state, vm)
            return
        }
        else -> Unit
    }
    Column(Modifier.fillMaxSize().background(PageGrey)) {
        if (state.screen == Screen.ACCOUNTS && state.savedWallets.isNotEmpty())
            WalletSelector(state, vm)
        Box(Modifier.weight(1f)) {
            when (state.screen) {
                Screen.ACCOUNTS -> AccountsScreen(state, vm)
                Screen.HARDWARE_SELECT -> HardwareSelectionScreen(state, vm)
                Screen.MONERO_DEVICE,
                Screen.MONERO_PIN -> MoneroConnectionScreen(state, vm)
                Screen.MONERO_SYNC -> MoneroSyncScreen(state, vm)
                Screen.ASSET,
                Screen.RECEIVE -> AssetScreen(state, vm, actions)
                Screen.TRANSACTIONS -> TransactionsScreen(state, vm)
                Screen.SEND,
                Screen.CONFIRM -> TransferScreen(state, vm, actions)
                Screen.DEVICE -> DeviceScreen(state, vm)
                Screen.SETTINGS -> SettingsScreen(state, vm, actions)
                else -> Unit
            }
        }
        if (state.screen in listOf(Screen.ACCOUNTS, Screen.DEVICE, Screen.SETTINGS)) {
            OriginalTabs(state.screen, vm::navigate)
        }
    }
}

@Composable
private fun WalletSelector(state: WalletUiState, vm: WalletViewModel) {
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WalletChoice?>(null) }
    var adding by remember { mutableStateOf(false) }
    Surface(color = AirLockHeaderBlue) {
        TextButton(
            onClick = { open = true },
            enabled = !state.busy,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = Color.White,
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
            modifier = Modifier.fillMaxWidth().testTag("wallet-selector"),
        ) {
            Icon(Icons.Default.AccountBalanceWallet, null)
            Text(
                state.activeWalletName,
                Modifier.padding(horizontal = 10.dp).weight(1f),
                textAlign = TextAlign.Start,
            )
            Icon(Icons.Default.ExpandMore, stringResource(Res.string.wallet_choose))
        }
    }
    if (open)
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Text(
                stringResource(Res.string.wallet_choose),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            LazyColumn(Modifier.weight(1f, false)) {
                items(state.savedWallets, key = { it.id }) { wallet ->
                    ListItem(
                        headlineContent = { Text(wallet.name) },
                        leadingContent = {
                            RadioButton(
                                selected = wallet.id == state.activeWalletId,
                                onClick = null,
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    open = false
                                    editing = wallet
                                },
                                enabled = !state.busy,
                                modifier = Modifier.testTag("rename-wallet-${wallet.id}"),
                            ) {
                                Icon(Icons.Default.Edit, stringResource(Res.string.wallet_rename))
                            }
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("select-wallet-${wallet.id}")
                                .selectable(
                                    wallet.id == state.activeWalletId,
                                    enabled = !state.busy,
                                    role = Role.RadioButton,
                                    onClick = {
                                        open = false
                                        vm.selectWallet(wallet.id)
                                    },
                                ),
                    )
                }
            }
            TextButton(
                onClick = {
                    open = false
                    adding = true
                },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("sync-another-wallet"),
            ) {
                Icon(Icons.Default.Add, null)
                Text(stringResource(Res.string.wallet_sync_another), Modifier.padding(start = 8.dp))
            }
        }
    if (adding || editing != null) {
        var name by remember(adding, editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = {
                adding = false
                editing = null
            },
            title = {
                Text(
                    stringResource(
                        if (adding) Res.string.wallet_sync_another else Res.string.wallet_rename
                    )
                )
            },
            text = {
                Column {
                    if (!adding)
                        OutlinedTextField(
                            name,
                            { if (it.length <= 128) name = it },
                            singleLine = true,
                            label = { Text(stringResource(Res.string.wallet_name)) },
                            modifier = Modifier.testTag("wallet-name"),
                        )
                    if (adding)
                        Text(
                            stringResource(Res.string.wallet_sync_help),
                            Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (adding || validWalletName(name)) && !state.busy,
                    onClick = {
                        if (adding) vm.beginWalletSynchronization()
                        else editing?.let { vm.renameWallet(it.id, name) }
                        adding = false
                        editing = null
                    },
                ) {
                    Text(
                        stringResource(
                            if (adding) Res.string.common_sync else Res.string.common_save
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        adding = false
                        editing = null
                    }
                ) {
                    Text(stringResource(Res.string.confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun OriginalTabs(selected: Screen, navigate: (Screen) -> Unit) {

    val tabs =
        listOf(
            Triple(Screen.ACCOUNTS, Res.drawable.tab_wallet, Res.string.menu_accounts),
            Triple(Screen.DEVICE, Res.drawable.tab_cpu, Res.string.menu_device),
            Triple(Screen.SETTINGS, Res.drawable.tab_person, Res.string.menu_me),
        )
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = LineGrey, thickness = 1.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                tabs.forEach { (screen, icon, key) ->
                    val active = selected == screen
                    val color =
                        if (active) AirLockBlue else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = 72.dp)
                            .testTag("tab-${screen.name}")
                            .selectable(active, role = Role.Tab, onClick = { navigate(screen) })
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = color)
                        Text(
                            stringResource(key),
                            color = color,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun WalletUiState.isMoneroBalancePending(coin: String): Boolean =
    coin == "monero" && moneroHeight == null

private fun WalletUiState.usd(assets: List<Asset>): String {
    val priced =
        assets.filterNot {
            isMoneroBalancePending(it.coin) ||
                (it.coin == "monero" && moneroNetwork == MoneroNetwork.STAGENET)
        }
    if (
        priced.isEmpty() && assets.isNotEmpty() ||
            priced.any { it.balance != "0" && it.symbol !in prices }
    )
        return "—"
    return Amount.usd(
        priced.fold("0") { total, asset ->
            Amount.add(total, Amount.usdCents(asset.balance, prices[asset.symbol] ?: "0"))
        }
    )
}

private fun shortAddress(value: String) =
    if (value.length > 24) value.take(10) + "…" + value.takeLast(8) else value

@Composable
private fun HardwareSelectionScreen(state: WalletUiState, vm: WalletViewModel) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageTitle(stringResource(Res.string.hardware_select_title), close = vm::back)
        Text(
            stringResource(Res.string.hardware_select_help),
            Modifier.padding(16.dp),
            fontSize = 14.sp,
            color = AirLockGrey,
        )
        LazyColumn {
            items(state.hardwareDevices, key = { it.id }) { device ->
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text(device.id, fontSize = 12.sp) },
                    modifier =
                        Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            vm.synchronizeWith(device.id)
                        },
                )
            }
        }
    }
}

@Composable
private fun MoneroConnectionScreen(state: WalletUiState, vm: WalletViewModel) {
    var pin by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("monero-connection")
    ) {
        PageTitle(stringResource(Res.string.monero_connection_title), back = vm::back)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoinIcon(CoinCatalog.get("monero").icon, "XMR", 48)
                Text("Monero (XMR)", style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                stringResource(
                    if (state.moneroSending) Res.string.monero_send_hardware
                    else Res.string.monero_only_help
                )
            )
            val device = state.hardwareDevices.singleOrNull()
            if (device != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (state.screen == Screen.MONERO_PIN) {
                Text(stringResource(Res.string.hardware_pin_description))
                PinInput(
                    stringResource(Res.string.hardware_pin_title),
                    pin,
                    { pin = it },
                    !state.busy,
                    coldWallet = true,
                    onSubmit = {
                        vm.unlockHardware(pin)
                        pin = ""
                    },
                )
                Button(
                    onClick = {
                        vm.unlockHardware(pin)
                        pin = ""
                    },
                    modifier = Modifier.fillMaxWidth().testTag("monero-confirm-pin"),
                    enabled = !state.busy && HardwarePinPolicy.isValid(pin),
                ) {
                    Text(
                        stringResource(
                            if (state.moneroSending) Res.string.transfer_confirm_send
                            else Res.string.monero_sync
                        )
                    )
                }
            } else if (device != null) {
                Button(
                    onClick = { vm.connectMonero(device.id) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("connect-monero"),
                ) {
                    Text(stringResource(Res.string.monero_connect))
                }
            }
            if (state.busy) {
                val status =
                    when (state.hardwareOperation) {
                        HardwareOperation.CONNECT_MONERO -> Res.string.monero_connecting
                        HardwareOperation.PAIR_MONERO -> Res.string.monero_pairing
                        HardwareOperation.VERIFY_PIN -> Res.string.hardware_pin_verifying
                        else ->
                            if (state.moneroSending) Res.string.monero_send_hardware
                            else Res.string.monero_hardware_help
                    }
                Text(stringResource(status))
            }
            TextButton(onClick = vm::back) { Text(stringResource(Res.string.confirm_cancel)) }
        }
    }
}

@Composable
private fun MoneroSyncScreen(state: WalletUiState, vm: WalletViewModel) {
    if (state.busy) KeepMoneroScanScreenAwake()
    var showRestoreHeight by remember(state.activeWalletId, state.mode) { mutableStateOf(false) }
    val asset =
        state.assets.singleOrNull {
            it.coin == "monero" && it.accountId == state.selectedAccount?.id
        }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageTitle(stringResource(Res.string.monero_sync), back = vm::back)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoinIcon(CoinCatalog.get("monero").icon, "XMR", 40)
                Text(
                    if (state.moneroNetwork == MoneroNetwork.STAGENET) "Monero (XMR) · Stagenet"
                    else "Monero (XMR)",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(stringResource(Res.string.monero_only_help))
            if (asset == null) Text(stringResource(Res.string.monero_waiting_for_wallet))
            if (asset != null) {
                Text(
                    asset.address,
                    modifier = Modifier.testTag("monero-public-address"),
                    fontSize = 12.sp,
                )
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(asset.address)) }) {
                    Text(stringResource(Res.string.receive_copy))
                }
                if (state.isMoneroBalancePending(asset.coin)) {
                    Text(stringResource(Res.string.monero_scan_pending))
                } else {
                    Text(stringResource(Res.string.monero_total_balance, asset.balance))
                    asset.unlockedBalance?.let {
                        Text(stringResource(Res.string.monero_unlocked_balance, it))
                    }
                }
            }
            state.moneroHeight?.let {
                Text(stringResource(Res.string.monero_last_verified, it.toString()))
            }
            if (state.moneroWatchImported && !state.moneroScannerAvailable) {
                Text(stringResource(Res.string.monero_scanner_unavailable))
            }
            if (state.moneroNeedsImages && !state.busy)
                Text(stringResource(Res.string.monero_balance_pending))
            val restoreHeight = state.moneroRestoreHeight
            if (restoreHeight != null && state.moneroHeight == null && !state.busy) {
                TextButton(onClick = { showRestoreHeight = true }) {
                    Text(stringResource(Res.string.monero_restore_height) + " · " + restoreHeight)
                }
            }
            if (state.busy) {
                Text(stringResource(Res.string.monero_scanning))
                OutlinedButton(onClick = vm::cancelMoneroScan) {
                    Text(stringResource(Res.string.confirm_cancel))
                }
            } else {
                Button(
                    onClick = vm::synchronizeMonero,
                    enabled = !state.moneroWatchImported || state.moneroScannerAvailable,
                    modifier = Modifier.testTag("sync-monero"),
                ) {
                    Text(stringResource(Res.string.monero_sync))
                }
                if (asset == null && !state.moneroWatchImported) {
                    OutlinedButton(
                        onClick = vm::synchronizeMoneroAddress,
                        modifier = Modifier.testTag("sync-monero-address"),
                    ) {
                        Text(stringResource(Res.string.monero_sync_address_only))
                    }
                }
            }
        }
    }
    if (showRestoreHeight) {
        var height by remember { mutableStateOf(state.moneroRestoreHeight?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRestoreHeight = false },
            title = { Text(stringResource(Res.string.monero_restore_height)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.monero_restore_height_help))
                    OutlinedTextField(
                        height,
                        { next ->
                            if (next.length <= 19 && next.all { it in '0'..'9' }) height = next
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(Res.string.monero_restore_height)) },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = height.isEmpty() || height.toLongOrNull() != null,
                    onClick = {
                        showRestoreHeight = false
                        vm.setMoneroRestoreHeight(height.toLongOrNull())
                    },
                ) {
                    Text(stringResource(Res.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreHeight = false }) {
                    Text(stringResource(Res.string.confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    change: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        query,
        change,
        modifier.fillMaxWidth(),
        placeholder = { Text(label, fontSize = 14.sp) },
        leadingIcon = {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        singleLine = true,
        shape = Corner,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
    )
}

@Composable
private fun AccountsScreen(state: WalletUiState, vm: WalletViewModel) {

    var query by remember { mutableStateOf("") }
    val assetsByAccount = state.assets.groupBy { it.accountId }
    val accounts =
        state.accounts.filter { account ->
            account.name.contains(query, true) ||
                account.symbol.contains(query, true) ||
                account.path.contains(query, true) ||
                assetsByAccount[account.id].orEmpty().any {
                    it.symbol.contains(query, true) || it.name.contains(query, true)
                } ||
                Stablecoins.forNetwork(account.coin).any {
                    it.symbol.contains(query, true) || it.name.contains(query, true)
                } ||
                PendingAssets.tokens[account.coin].orEmpty().any { it.contains(query, true) }
        }
    Column {
        Surface(color = AirLockHeaderBlue, shadowElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp)
            ) {
                Text(
                    stringResource(Res.string.account_page_total_assets),
                    color = Color.White,
                    fontSize = 12.sp,
                )
                Text(
                    "$ ${state.usd(state.assets)}",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Surface(
            Modifier.fillMaxSize().padding(8.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = Corner,
        ) {
            Column {
                SearchField(
                    query,
                    { query = it },
                    stringResource(Res.string.account_page_search_placeholder),
                    Modifier.padding(8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${stringResource(Res.string.account_page_account_list)} (${accounts.size})",
                        Modifier.weight(1f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AirLockGrey,
                    )
                    IconButton(
                        onClick = vm::refreshBalances,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("refresh-balances"),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            stringResource(Res.string.network_refresh),
                            tint = AirLockBlue,
                        )
                    }
                    IconButton(
                        onClick = vm::showAddAccount,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("add-account"),
                    ) {
                        Icon(
                            Icons.Default.AddCircle,
                            stringResource(Res.string.account_add),
                            tint = AirLockBlue,
                        )
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item { NetworkFailureSection(state, vm) }
                    items(accounts, key = { it.id }) { account ->
                        val assets = assetsByAccount[account.id].orEmpty()
                        val standalone = CoinCatalog.isStandalone(account.coin)
                        val balancePending = state.isMoneroBalancePending(account.coin)
                        Surface(
                            onClick = { vm.open(account) },
                            enabled = !state.busy,
                            shape = Corner,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, LineGrey),
                            modifier = Modifier.testTag("account-${account.coin}"),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CoinIcon(account.displayIcon, account.name, 38)
                                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                    Text(
                                        account.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (standalone) {
                                        val native = assets.filter { it.contract.isEmpty() }
                                        val amount =
                                            if (account.pendingPublicData || balancePending) "—"
                                            else
                                                Amount.display(
                                                    currencyBalance(native),
                                                    native.firstOrNull()?.decimals ?: 8,
                                                )
                                        Text(
                                            "$amount ${account.symbol}",
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                        )
                                    }
                                    if (account.enabled && account.pendingPublicData)
                                        Text(
                                            stringResource(Res.string.account_public_data_pending),
                                            color = AirLockGrey,
                                            fontSize = 12.sp,
                                        )
                                    if (balancePending)
                                        Text(
                                            stringResource(Res.string.monero_scan_pending),
                                            color = AirLockGrey,
                                            fontSize = 12.sp,
                                        )
                                    if (
                                        account.coin == "monero" &&
                                            state.moneroNetwork == MoneroNetwork.STAGENET
                                    ) {
                                        Text("Stagenet", color = AirLockGrey, fontSize = 12.sp)
                                    }
                                }
                                Text(
                                    "≈ $${if (account.pendingPublicData || balancePending) "—" else state.usd(assets)}",
                                    color = AirLockGrey,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                    if (accounts.isEmpty())
                        item {
                            Text(
                                stringResource(Res.string.account_page_no_account_match),
                                Modifier.padding(16.dp),
                                color = AirLockGrey,
                            )
                        }
                }
            }
        }
    }
}

@Composable
internal fun PageTitle(title: String, back: (() -> Unit)? = null, close: (() -> Unit)? = null) {

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back != null)
            IconButton(onClick = back) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(Res.string.common_back),
                    tint = AirLockBlue,
                )
            }
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 8.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        if (close != null)
            IconButton(onClick = close) {
                Icon(
                    Icons.Default.Close,
                    stringResource(Res.string.common_dismiss),
                    tint = AirLockGrey,
                )
            }
    }
}

@Composable
private fun AddAccountScreen(state: WalletUiState, vm: WalletViewModel) {

    var search by remember { mutableStateOf("") }
    val available = state.availableCoins.toSet()
    val enabled = state.accounts.map { it.coin }.toSet()
    val rows = CoinCatalog.coins
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageTitle(stringResource(Res.string.account_add), close = { vm.back() })
        Text(
            stringResource(Res.string.offline_accounts_hint),
            Modifier.padding(horizontal = 16.dp),
            color = AirLockGrey,
            fontSize = 12.sp,
        )
        SearchField(
            search,
            { search = it },
            stringResource(Res.string.account_search_coins),
            Modifier.padding(16.dp),
        )
        LazyColumn {
            items(
                rows.filter { coin ->
                    coin.name.contains(search, true) ||
                        coin.symbol.contains(search, true) ||
                        coin.protocol.contains(search, true) ||
                        (coin.coin == "solana" && search.equals("native", true)) ||
                        Stablecoins.forNetwork(coin.coin).any {
                            it.symbol.contains(search, true) || it.name.contains(search, true)
                        } ||
                        PendingAssets.tokens[coin.coin].orEmpty().any { it.contains(search, true) }
                },
                key = { it.coin },
            ) { coin ->
                val canAdd = coin in available
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = canAdd && !state.busy) { vm.addAccount(coin) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoinIcon(coin.networkIcon, coin.name, 32)
                    Column(Modifier.weight(1f).padding(start = 24.dp)) {
                        Text(coin.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        val supported = state.supportsAccount(coin.coin)
                        val (tokens, unsupportedTokens) =
                            Stablecoins.forNetwork(coin.coin).partition(state::supportsToken)
                        val symbols =
                            if (supported) listOf(coin.symbol) + tokens.map { it.symbol }
                            else emptyList()
                        val unsupported =
                            (listOfNotNull(coin.symbol.takeUnless { supported }) +
                                    unsupportedTokens.map { it.symbol } +
                                    PendingAssets.tokens[coin.coin].orEmpty())
                                .distinct()
                        if (symbols.isNotEmpty())
                            Text(symbols.joinToString(" · "), color = AirLockGrey, fontSize = 12.sp)
                        if (unsupported.isNotEmpty())
                            Text(
                                stringResource(
                                    Res.string.account_unsupported_assets,
                                    unsupported.joinToString(", "),
                                ),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                            )
                        if (canAdd && coin.coin !in state.publicAccountCoins) {
                            Text(
                                stringResource(Res.string.account_public_data_pending),
                                color = AirLockGrey,
                                fontSize = 12.sp,
                            )
                        }
                        if (
                            canAdd &&
                                coin.coin in PendingAssets.networks &&
                                !state.moneroScannerAvailable
                        ) {
                            Text(
                                stringResource(Res.string.catalog_network_pending),
                                color = AirLockGrey,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (canAdd)
                        OutlinedButton(
                            onClick = { vm.addAccount(coin) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("add-account-${coin.coin}"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Text(stringResource(Res.string.common_add_account_action))
                        }
                    else if (coin.coin in enabled)
                        OutlinedButton(
                            onClick = { vm.removeAccount(coin) },
                            enabled = !state.busy,
                            modifier = Modifier.testTag("remove-account-${coin.coin}"),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.Remove, null, Modifier.size(18.dp))
                            Text(stringResource(Res.string.common_remove_account_action))
                        }
                }
                HorizontalDivider(color = LineGrey)
            }
        }
    }
}

@Composable
private fun AssetsScreen(state: WalletUiState, vm: WalletViewModel) {
    val language = LocalAppLanguage.current.code

    var query by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(false) }
    val account = state.selectedAccount ?: return
    val all =
        if (state.showingAddresses) state.selectedCurrencyAssets
        else state.assets.filter { it.accountId == account.id }
    val catalog =
        if (state.showingAddresses) emptyList()
        else
            buildList {
                add(
                    Triple(
                        account.symbol,
                        account.name,
                        CoinCatalog.find(account.coin)?.icon ?: account.icon,
                    )
                )
                Stablecoins.forNetwork(account.coin).forEach {
                    add(Triple(it.symbol, it.name, it.icon))
                }
                PendingAssets.tokens[account.coin].orEmpty().forEach {
                    add(Triple(it, it, PendingAssets.tokenIcons.getValue(it)))
                }
            }
    val savedSymbols = all.map { it.symbol }.toSet()
    val missing =
        catalog
            .filter { (symbol, name, _) ->
                symbol !in savedSymbols &&
                    (name.contains(query, true) || symbol.contains(query, true)) &&
                    (minimum.isBlank() || minimum == "0")
            }
            .distinctBy { it.first }
    val currencies =
        if (state.showingAddresses) all.map { listOf(it) }
        else all.groupBy { it.contract }.values.toList()
    val rows =
        currencies.filter { assets ->
            val asset = assets.first()
            val min =
                runCatching { Amount.atomic(minimum.ifBlank { "0" }, asset.decimals) }
                    .getOrDefault("0")
            Amount.compare(currencyBalance(assets), min) >= 0 &&
                (asset.name.contains(query, true) ||
                    asset.symbol.contains(query, true) ||
                    assets.any { it.address.contains(query, true) })
        }
    Column {
        Surface(color = AirLockHeaderBlue, shadowElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.back() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(Res.string.common_back),
                        tint = Color.White,
                    )
                }
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$ ${if (state.selectedAccount?.pendingPublicData == true) "—" else state.usd(all)}",
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.showingAddresses) state.selectedAsset?.symbol.orEmpty()
                        else state.selectedAccount?.name.orEmpty(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier.padding(start = 4.dp)
                                .background(Color(0xFF00C853), Corner)
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                if (state.showingAddresses)
                    IconButton(
                        onClick = vm::addAsset,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("add-asset"),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            stringResource(Res.string.assets_add),
                            tint = Color.White,
                        )
                    }
                else Spacer(Modifier.width(48.dp))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.FilterList, null, Modifier.size(16.dp), tint = AirLockBlue)
                val title =
                    stringResource(
                        if (state.showingAddresses) Res.string.account_addresses
                        else Res.string.assets_all
                    )
                Text(
                    "$title (${rows.size + missing.size})",
                    Modifier.weight(1f).padding(start = 6.dp),
                    fontSize = 12.sp,
                    color = AirLockGrey,
                )
                Button(
                    onClick = { filter = true },
                    shape = Corner,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(stringResource(Res.string.assets_filter), fontSize = 12.sp)
                    Icon(Icons.Default.Tune, null, Modifier.padding(start = 6.dp).size(18.dp))
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.first().id }) { assets ->
                val asset = assets.first()
                Surface(
                    onClick = { vm.open(asset) },
                    enabled = !state.busy,
                    color = MaterialTheme.colorScheme.surface,
                    shape = Corner,
                    border = BorderStroke(1.dp, LineGrey),
                    modifier = Modifier.testTag("asset-${asset.id}"),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoinIcon(asset.displayIcon, asset.symbol, 38)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text(
                                "${asset.name} · ${asset.symbol}",
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.showingAddresses)
                                Text(
                                    shortAddress(asset.address),
                                    fontSize = 12.sp,
                                    color = AirLockGrey,
                                    maxLines = 1,
                                )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val balance = Amount.display(currencyBalance(assets), asset.decimals)
                            Text("$balance ${asset.symbol}", fontSize = 14.sp)
                            Text("≈ $${state.usd(assets)}", color = AirLockGrey, fontSize = 12.sp)
                        }
                    }
                }
            }
            items(missing, key = { "catalog-${it.first}" }) { (symbol, name, icon) ->
                Surface(
                    onClick = { vm.openCatalogAsset(symbol) },
                    enabled = !state.busy,
                    shape = Corner,
                    border = BorderStroke(1.dp, LineGrey),
                    modifier = Modifier.testTag("catalog-asset-$symbol"),
                ) {
                    ListItem(
                        headlineContent = {
                            Text(if (name == symbol) symbol else "$name · $symbol")
                        },
                        leadingContent = { CoinIcon(icon, symbol, 38) },
                        trailingContent = { Text("—", color = AirLockGrey) },
                    )
                }
            }
            if (rows.isEmpty() && missing.isEmpty())
                item {
                    Column(Modifier.padding(16.dp)) {
                        val pendingPublicData = state.selectedAccount?.pendingPublicData == true
                        Text(
                            stringResource(
                                if (pendingPublicData) Res.string.account_public_data_pending
                                else Res.string.assets_empty
                            ),
                            color = AirLockGrey,
                        )
                        if (pendingPublicData)
                            TextButton(
                                onClick = { vm.navigate(Screen.SYNC) },
                                enabled = !state.busy,
                            ) {
                                Text(stringResource(Res.string.account_sync_public_data))
                            }
                    }
                }
        }
    }
    if (filter)
        ModalBottomSheet(
            onDismissRequest = { filter = false },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null,
        ) {
            var draftQuery by remember { mutableStateOf(query) }
            var draftMinimum by remember { mutableStateOf(DecimalInput.display(minimum, language)) }
            var error by remember { mutableStateOf<StringResource?>(null) }
            PageTitle(stringResource(Res.string.assets_filter), close = { filter = false })
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.selectedAccount?.name.orEmpty(), fontWeight = FontWeight.Medium)
                AmountInput(
                    draftMinimum,
                    {
                        draftMinimum = it
                        error = null
                    },
                    all.firstOrNull()?.decimals ?: 8,
                    language,
                    stringResource(Res.string.assets_min_balance),
                )
                SearchField(
                    draftQuery,
                    { draftQuery = it },
                    stringResource(Res.string.assets_search),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        onClick = {
                            draftQuery = ""
                            draftMinimum = ""
                            error = null
                        }
                    ) {
                        Text(stringResource(Res.string.common_reset))
                    }
                    Button(
                        onClick = {
                            try {
                                val canonical =
                                    DecimalInput.canonical(
                                        draftMinimum.ifBlank { "0" },
                                        all.firstOrNull()?.decimals ?: 8,
                                    )
                                query = draftQuery
                                minimum = canonical
                                filter = false
                            } catch (e: InputException) {
                                error = e.reason.resource
                            }
                        },
                        shape = Corner,
                    ) {
                        Text(stringResource(Res.string.common_apply))
                    }
                }
            }
        }
}

private fun currencyBalance(assets: List<Asset>): String =
    assets.fold("0") { sum, asset -> Amount.add(sum, Amount.atomic(asset.balance, asset.decimals)) }

@Composable
private fun AssetScreen(state: WalletUiState, vm: WalletViewModel, actions: AddressActions) {

    val asset = state.selectedAsset
    if (asset == null) {
        val account = state.selectedAccount ?: return
        val symbol = state.selectedCatalogSymbol ?: account.symbol
        val token = Stablecoins.forNetwork(account.coin).firstOrNull { it.symbol == symbol }
        val name = token?.name ?: account.name.takeIf { symbol == account.symbol }
        val pendingToken = symbol in PendingAssets.tokens[account.coin].orEmpty()
        Column(Modifier.fillMaxSize()) {
            PageTitle(
                if (name == null || name == symbol) symbol else "$name · $symbol",
                back = vm::back,
            )
            NetworkSendSupport(state, vm)
            Text(
                stringResource(Res.string.asset_detail_page_title_history),
                Modifier.padding(16.dp),
                fontWeight = FontWeight.Bold,
            )
            if (pendingToken)
                Text(
                    stringResource(Res.string.catalog_assets_pending, symbol),
                    Modifier.padding(horizontal = 16.dp),
                )
            else if (!account.enabled)
                TextButton(onClick = vm::showAddAccount, enabled = !state.busy) {
                    Text(stringResource(Res.string.account_add))
                }
            else {
                Text(
                    stringResource(Res.string.account_public_data_pending),
                    Modifier.padding(horizontal = 16.dp),
                )
                TextButton(
                    onClick = { vm.navigate(Screen.SYNC) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(Res.string.account_sync_public_data))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(Res.string.asset_detail_page_btn_send))
            }
        }
        return
    }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Surface(color = AirLockHeaderBlue, shadowElevation = 2.dp) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.back() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(Res.string.common_back),
                            tint = Color.White,
                        )
                    }
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (state.isMoneroBalancePending(asset.coin)) "—" else asset.balance,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Box(
                            Modifier.padding(start = 4.dp)
                                .background(Color.White, RoundedCornerShape(24.dp))
                                .padding(2.dp)
                        ) {
                            CoinIcon(asset.displayIcon, asset.symbol, 22)
                        }
                    }
                    Spacer(Modifier.width(48.dp))
                }
                val network =
                    if (asset.coin == "monero" && state.moneroNetwork == MoneroNetwork.STAGENET)
                        "Stagenet"
                    else networkName(asset)
                Text(
                    "${asset.symbol} · $network",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (state.isMoneroBalancePending(asset.coin)) {
                    Text(
                        stringResource(Res.string.monero_scan_pending),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (asset.coin == "monero")
                    asset.unlockedBalance?.let {
                        Text(
                            stringResource(Res.string.monero_unlocked_balance, it),
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                Surface(
                    onClick = { clipboard.setText(AnnotatedString(asset.address)) },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            shortAddress(asset.address),
                            fontSize = 12.sp,
                            color = AirLockBlue,
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Default.ContentCopy,
                            stringResource(Res.string.receive_copy),
                            Modifier.padding(start = 4.dp).size(16.dp),
                            tint = AirLockBlue,
                        )
                    }
                }
            }
        }
        if (asset.coin == "monero") {
            state.moneroHeight?.let {
                Text(
                    stringResource(Res.string.monero_last_verified, it.toString()),
                    Modifier.padding(horizontal = 16.dp),
                    fontSize = 12.sp,
                )
            }
            OutlinedButton(
                onClick = vm::showMoneroSync,
                enabled = !state.busy,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("open-monero-sync"),
            ) {
                Text(stringResource(Res.string.monero_sync))
            }
        } else {
            TextButton(
                onClick = vm::openAccountAddresses,
                enabled = !state.busy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(Res.string.account_addresses))
            }
        }
        NetworkSendSupport(state, vm)
        Text(
            stringResource(Res.string.asset_detail_page_title_history),
            Modifier.padding(16.dp),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = vm::showTransactions,
            enabled = !state.busy,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(Res.string.transfer_transactions_title, asset.symbol))
        }
        val historyIds = state.selectedCurrencyAssets.map { it.id }.toSet() + asset.id
        val localTransfers = state.transfers.filter { it.assetId in historyIds }.reversed()
        val moralis =
            !state.simulated &&
                state.networkSettings.preferred == NetworkProvider.MORALIS &&
                asset.coin != "monero"
        val moralisHasMore =
            moralis && historyIds.any { state.moralisHistory[it]?.nextCursor != null }
        val moralisFailure =
            if (moralis)
                historyIds.firstNotNullOfOrNull {
                    state.moralisHistoryErrors[it] ?: state.failedBalances[it]
                }
            else null
        val transfers =
            if (moralis) {
                if (moralisFailure != null) {
                    Text(
                        stringResource(moralisFailure.resource),
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = vm::refreshSelectedMoralisAsset,
                        enabled = !state.busy,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(Res.string.network_retry))
                    }
                }
                if (!ProviderCoverage.canReadHistory(NetworkProvider.MORALIS, asset)) {
                    Text(
                        stringResource(Res.string.network_moralis_history_unsupported),
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val remote = historyIds.flatMap { state.moralisHistory[it]?.transfers.orEmpty() }
                val indexedHashes = remote.map { it.id.substringBefore(':') }.toSet()
                remote + localTransfers.filterNot { it.id.lowercase() in indexedHashes }
            } else if (asset.coin == "ecash") {
                val failure = state.failedBalances[asset.id]
                if (failure != null)
                    Text(
                        stringResource(failure.resource),
                        Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                historyIds.flatMap { state.ecashHistory[it].orEmpty() } + localTransfers
            } else localTransfers
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (transfers.isEmpty()) {
                if (
                    !moralis ||
                        (!state.busy &&
                            !moralisHasMore &&
                            moralisFailure == null &&
                            ProviderCoverage.canReadHistory(NetworkProvider.MORALIS, asset))
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 52.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.History,
                            null,
                            Modifier.size(60.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            stringResource(Res.string.asset_detail_page_no_history),
                            Modifier.padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else
                LazyColumn {
                    items(transfers, key = { it.id }) { tx ->
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = PageGrey),
                            headlineContent = {
                                Text(
                                    stringResource(
                                        if (tx.incoming) Res.string.asset_detail_page_btn_receive
                                        else Res.string.asset_detail_page_btn_send
                                    )
                                )
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        shortAddress(
                                            if (asset.coin == "monero") tx.id else tx.destination
                                        ),
                                        fontSize = 12.sp,
                                    )
                                    if (tx.status != TransferStatus.SIMULATED)
                                        Text(
                                            stringResource(
                                                when (tx.status) {
                                                    TransferStatus.CONFIRMED ->
                                                        Res.string.network_status_confirmed
                                                    TransferStatus.FAILED ->
                                                        Res.string.network_status_failed
                                                    else -> Res.string.network_status_submitted
                                                }
                                            ),
                                            fontSize = 12.sp,
                                        )
                                }
                            },
                            leadingContent = {
                                Icon(
                                    if (tx.incoming) Icons.Default.CallReceived
                                    else Icons.Default.CallMade,
                                    null,
                                    tint = AirLockGreen,
                                )
                            },
                            trailingContent = {
                                Text(
                                    "${if (tx.incoming) "+" else "−"}${tx.amount}",
                                    color = AirLockGreen,
                                )
                            },
                        )
                        HorizontalDivider(color = LineGrey)
                    }
                }
        }
        if (moralisHasMore) {
            TextButton(
                onClick = vm::loadMoreMoralisHistory,
                enabled = !state.busy,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(Res.string.network_history_more))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedButton(
                    onClick = vm::receive,
                    Modifier.weight(1f).height(42.dp),
                    contentPadding = PaddingValues(4.dp),
                    border = BorderStroke(1.dp, AirLockBlue),
                ) {
                    Icon(Icons.Default.CallReceived, null, Modifier.size(20.dp))
                    Text(
                        stringResource(Res.string.asset_detail_page_btn_receive).uppercase(),
                        Modifier.padding(start = 4.dp),
                        fontSize = 14.sp,
                    )
                }
                Button(
                    onClick = vm::send,
                    Modifier.weight(1f).height(42.dp),
                    enabled = !state.busy && state.canSendSelectedAsset,
                    contentPadding = PaddingValues(4.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(20.dp))
                    Text(
                        stringResource(Res.string.asset_detail_page_btn_send).uppercase(),
                        Modifier.padding(start = 4.dp),
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
    if (state.screen == Screen.RECEIVE)
        ModalBottomSheet(
            onDismissRequest = { vm.back() },
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = null,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val network =
                if (asset.coin == "monero" && state.moneroNetwork == MoneroNetwork.STAGENET)
                    "Monero · Stagenet"
                else networkName(asset)
            PageTitle(stringResource(Res.string.receive_title, asset.name), close = { vm.back() })
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(Res.string.receive_network, network),
                    color = AirLockGrey,
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                )
                if (asset.contract.isNotEmpty())
                    Text(
                        "${stringResource(Res.string.token_contract)}: ${asset.contract}",
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                val qr = remember(asset.address) { actions.qrCode(asset.address) }
                // Keep a white quiet zone around the QR code in both themes.
                Surface(
                    shadowElevation = 2.dp,
                    color = Color.White,
                    shape = Corner,
                    modifier = Modifier.padding(16.dp),
                ) {
                    AsyncImage(
                        qr,
                        stringResource(Res.string.receive_qr),
                        Modifier.padding(16.dp).size(200.dp),
                    )
                }
                Surface(
                    onClick = { clipboard.setText(AnnotatedString(asset.address)) },
                    color = MaterialTheme.colorScheme.background,
                    shape = Corner,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(Res.string.receive_copy),
                            color = AirLockGrey,
                            fontSize = 12.sp,
                        )
                        Text(
                            asset.address,
                            Modifier.padding(top = 8.dp),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                PrimaryButton(stringResource(Res.string.receive_share)) {
                    actions.share(asset.address)
                }
            }
        }
}

@Composable
private fun NetworkSendSupport(state: WalletUiState, vm: WalletViewModel) {
    if (state.selectedAssetHasSendSupport) return
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(Res.string.network_selected, state.networkSettings.preferred.label),
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            stringResource(Res.string.network_send_unsupported),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { open = true }, enabled = !state.busy) {
            Text(stringResource(Res.string.network_choose))
        }
    }
    if (open) NetworkSettingsDialog(state, vm, keepAssetOpen = true) { open = false }
}

@Composable
private fun TransferScreen(state: WalletUiState, vm: WalletViewModel, actions: AddressActions) {
    val language = LocalAppLanguage.current.code

    val asset = state.selectedAsset ?: return
    val confirming = state.screen == Screen.CONFIRM
    val localApproval =
        !state.simulated &&
            (LedgerNetworks.supportsPayment(asset) ||
                CardanoNetwork.supportsPayment(asset) ||
                SolanaNetwork.supportsPayment(asset) ||
                EvmNetworks.supportsPayment(asset))
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val draft = state.transferDraft?.takeIf { it.assetId == asset.id }
    var destination by
        remember(asset.id) {
            mutableStateOf(state.quote?.destination ?: draft?.destination.orEmpty())
        }
    var amount by
        remember(asset.id) {
            mutableStateOf(
                DecimalInput.display(state.quote?.amount ?: draft?.amount.orEmpty(), language)
            )
        }
    val available = TransferAmountPolicy.availableBalance(asset)
    val amountWithinBalance =
        amount.isNotEmpty() &&
            runCatching { TransferAmountPolicy.requireWithinBalance(amount, asset) }.isSuccess
    var scanning by remember(asset.id) { mutableStateOf(false) }
    var scanError by remember(asset.id) { mutableStateOf<QrScanResult?>(null) }
    var invalidQr by remember(asset.id) { mutableStateOf(false) }
    val scanPrompt = stringResource(Res.string.recipient_scan_prompt)
    val cancelLabel = stringResource(Res.string.confirm_cancel)
    val scanActive = remember(asset.id, confirming) { mutableStateOf(true) }
    DisposableEffect(scanActive) { onDispose { scanActive.value = false } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        PageTitle(
            if (confirming) stringResource(Res.string.transfer_confirm)
            else stringResource(Res.string.send_title, asset.symbol),
            back =
                if (confirming) {
                    { vm.back() }
                } else null,
            close = vm::cancelTransfer,
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (confirming) {
                val quote = state.quote ?: return
                if (quote.bitcoinSignatureTest)
                    Text(
                        stringResource(Res.string.bitcoin_signature_test),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                DetailValue(
                    stringResource(Res.string.transfer_network),
                    if (asset.coin == "monero") "Monero · Stagenet" else networkName(asset),
                )
                DetailValue(stringResource(Res.string.transfer_source), asset.address)
                DetailValue(
                    stringResource(Res.string.transfer_recipient_amount),
                    "${DecimalInput.display(quote.amount, language)} ${asset.symbol}",
                )
                DetailValue(
                    stringResource(Res.string.transaction_input_to_label),
                    quote.destination,
                )
                DetailValue(
                    stringResource(
                        when {
                            quote.evmPlan?.l1Fee != null -> Res.string.network_estimated_fee
                            quote.evmPlan == null -> Res.string.transfer_fee
                            else -> Res.string.network_max_fee
                        }
                    ),
                    "${DecimalInput.display(quote.fee, language)} ${quote.feeCurrency.symbol}",
                )
                quote.utxoPlan?.let { plan ->
                    DetailValue(
                        stringResource(Res.string.utxo_change),
                        "${DecimalInput.display(Amount.display(plan.change.toString(), asset.decimals), language)} ${asset.symbol}",
                    )
                    if (plan.change > 0 && plan.changeAddress != asset.address)
                        DetailValue(
                            stringResource(Res.string.utxo_change_address),
                            plan.changeAddress,
                        )
                    Text(
                        stringResource(Res.string.utxo_confirm_hardware),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                quote.cardanoPlan
                    ?.takeIf { it.change > 0 }
                    ?.let { plan ->
                        val change = Amount.display(plan.change.toString(), 6)
                        DetailValue(
                            stringResource(Res.string.utxo_change),
                            "${DecimalInput.display(change, language)} ADA",
                        )
                    }
                if (quote.evmPlan?.l1Fee != null)
                    Text(
                        stringResource(Res.string.network_base_fee_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (asset.contract.isNotEmpty())
                    DetailValue(stringResource(Res.string.token_contract), asset.contract)
                if (asset.coin == "monero")
                    Text(
                        stringResource(Res.string.monero_send_hardware),
                        style = MaterialTheme.typography.bodySmall,
                    )
                else if (localApproval)
                    Text(
                        stringResource(
                            Res.string.evm_confirm_hardware,
                            quote.feeCurrency.symbol,
                            asset.symbol,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
            } else {
                if (asset.coin == "bitcoin" && state.bitcoinTestSignatureVerified)
                    Text(
                        stringResource(Res.string.bitcoin_signature_test_verified),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                Row(
                    Modifier.fillMaxWidth().testTag("transfer-balance"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CoinIcon(asset.displayIcon, asset.name)
                    Text(
                        stringResource(
                            Res.string.send_available,
                            DecimalInput.display(available, language),
                            asset.symbol,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TransferSourceSelector(state, vm)
                OutlinedTextField(
                    destination,
                    { destination = it },
                    enabled = !state.busy && !scanning,
                    label = { Text(stringResource(Res.string.transaction_input_to_label)) },
                    trailingIcon = {
                        IconButton(
                            enabled = !state.busy && !scanning,
                            modifier = Modifier.testTag("transfer-scan-qr"),
                            onClick = {
                                keyboard?.hide()
                                scanning = true
                                actions.scanQrCode(scanPrompt, cancelLabel) { result ->
                                    if (scanActive.value) {
                                        scanning = false
                                        if (result.permissionDenied || result.failed)
                                            scanError = result
                                        else
                                            result.text?.let { payload ->
                                                val address =
                                                    RecipientQr.address(payload, asset.coin)
                                                if (address == null) invalidQr = true
                                                else destination = address
                                            }
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                stringResource(Res.string.recipient_scan_button),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("transfer-to"),
                )
                key(asset.id) {
                    AmountInput(
                        amount,
                        { amount = it },
                        asset.decimals,
                        language,
                        stringResource(Res.string.transaction_input_amount_label),
                        Modifier.testTag("transfer-amount"),
                        enabled = !state.busy,
                        unit = asset.symbol,
                        maximum = available,
                    )
                }
                if (asset.contract.isNotEmpty()) {
                    val feeAsset = TransferAccounting.feeAsset(asset, state.assets)
                    Text(
                        stringResource(
                            Res.string.transfer_fee_balance,
                            DecimalInput.display(feeAsset?.balance ?: "0", language),
                            CoinCatalog.get(asset.coin).symbol,
                        ),
                        color = AirLockGrey,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Box(Modifier.padding(16.dp)) {
            PrimaryButton(
                if (confirming) stringResource(Res.string.transfer_confirm_send)
                else stringResource(Res.string.transaction_btn_next),
                !state.busy && !scanning && (confirming || amountWithinBalance),
            ) {
                keyboard?.hide()
                if (confirming) {
                    if (asset.coin == "monero") vm.confirmTransfer("")
                    else vm.requestTransferConnection()
                } else vm.prepareTransfer(destination, amount)
            }
        }
    }
    if (invalidQr || scanError != null)
        AlertDialog(
            onDismissRequest = {
                invalidQr = false
                scanError = null
            },
            title = { Text(stringResource(Res.string.recipient_scan_button)) },
            text = {
                Text(
                    stringResource(
                        when {
                            invalidQr -> Res.string.recipient_scan_invalid
                            scanError?.permissionDenied == true ->
                                Res.string.recipient_scan_permission
                            else -> Res.string.recipient_scan_failed
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        invalidQr = false
                        scanError = null
                    }
                ) {
                    Text(stringResource(Res.string.common_dismiss))
                }
            },
            dismissButton = {
                if (scanError?.permissionDenied == true)
                    TextButton(
                        onClick = {
                            scanError = null
                            actions.openAppSettings()
                        }
                    ) {
                        Text(stringResource(Res.string.recipient_scan_settings))
                    }
            },
        )
}

private fun networkName(asset: Asset) =
    CoinCatalog.coins.firstOrNull { it.coin == asset.coin }?.name ?: asset.coin

@Composable
private fun TransferSourceSelector(state: WalletUiState, vm: WalletViewModel) {
    val asset = state.selectedAsset ?: return
    val sources = Stablecoins.sources(asset, state.assets)
    val token = Stablecoins.definition(asset)
    val networks =
        if (token == null) listOf(asset.coin)
        else
            sources
                .filter {
                    state.simulated ||
                        ProviderCoverage.canSubmit(state.networkSettings.preferred, it)
                }
                .map { it.coin }
                .distinct()
    var networkMenu by remember(asset.id) { mutableStateOf(false) }
    var addressMenu by remember(asset.id) { mutableStateOf(false) }
    Text(stringResource(Res.string.transfer_network), fontSize = 12.sp, color = AirLockGrey)
    Box {
        OutlinedButton(
            onClick = { networkMenu = true },
            enabled = !state.busy && networks.size > 1,
            modifier = Modifier.fillMaxWidth().testTag("transfer-network"),
        ) {
            Text(networkName(asset), Modifier.weight(1f), textAlign = TextAlign.Start)
            if (networks.size > 1) Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(networkMenu, { networkMenu = false }) {
            networks.forEach { coin ->
                val source =
                    sources.firstOrNull { it.id == asset.id && it.coin == coin }
                        ?: sources.firstOrNull { it.coin == coin }
                DropdownMenuItem(
                    enabled = source != null && !state.busy,
                    text = {
                        Column {
                            Text(CoinCatalog.get(coin).name)
                            if (source == null)
                                Text(
                                    stringResource(Res.string.transfer_network_missing),
                                    fontSize = 12.sp,
                                )
                        }
                    },
                    onClick = {
                        networkMenu = false
                        source?.let { vm.selectTransferSource(it.id) }
                    },
                    modifier = Modifier.testTag("transfer-network-$coin"),
                )
            }
        }
    }
    if (token != null)
        Text(
            stringResource(Res.string.transfer_network_hint),
            color = AirLockGrey,
            fontSize = 12.sp,
        )
    val addresses = sources.filter { it.coin == asset.coin }
    Text(stringResource(Res.string.transfer_source), fontSize = 12.sp, color = AirLockGrey)
    if (addresses.size > 1)
        Box {
            OutlinedButton(
                onClick = { addressMenu = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("transfer-source"),
            ) {
                Text(shortAddress(asset.address), Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(addressMenu, { addressMenu = false }) {
                addresses.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(source.address, fontSize = 12.sp)
                                Text("${source.balance} ${source.symbol}", fontSize = 12.sp)
                            }
                        },
                        onClick = {
                            addressMenu = false
                            vm.selectTransferSource(source.id)
                        },
                    )
                }
            }
        }
    else Text(asset.address, fontSize = 12.sp)
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(label, color = AirLockGrey, fontSize = 12.sp)
        Text(value, Modifier.padding(top = 4.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
    HorizontalDivider(color = LineGrey)
}

@Composable
private fun DeviceScreen(state: WalletUiState, vm: WalletViewModel) {

    var menu by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf<DeviceCommand?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.device_page_title),
                Modifier.weight(1f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("device-menu")) {
                    Icon(
                        Icons.Default.MoreVert,
                        stringResource(Res.string.device_options),
                        tint = AirLockGrey,
                    )
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.device_page_menu_refresh)) },
                        onClick = {
                            menu = false
                            vm.navigate(Screen.DEVICE)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_sync)) },
                        onClick = {
                            menu = false
                            vm.navigate(Screen.SYNC)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.device_reboot)) },
                        onClick = {
                            menu = false
                            command = DeviceCommand.REBOOT
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.device_shutdown)) },
                        onClick = {
                            menu = false
                            command = DeviceCommand.SHUTDOWN
                        },
                    )
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            val d = state.device
            DeviceRow(
                Icons.Default.Memory,
                AirLockBlue,
                stringResource(Res.string.device_name),
                d?.name ?: "—",
                d?.connected,
            )
            DeviceRow(
                Icons.Default.Verified,
                Color(0xFF009688),
                stringResource(Res.string.device_page_label_firmware_version),
                d?.firmware ?: "—",
            )
            DeviceRow(
                Icons.Default.Terminal,
                Color(0xFF607D8B),
                stringResource(Res.string.device_platform),
                d?.platform ?: "—",
            )
            DeviceRow(
                Icons.Default.Hardware,
                Color(0xFF9C27B0),
                stringResource(Res.string.device_hardware),
                d?.hardware ?: "—",
            )
            DeviceRow(
                Icons.Default.ScreenshotMonitor,
                Color(0xFF5D4037),
                stringResource(Res.string.device_display),
                d?.display ?: "—",
            )
            DeviceRow(
                Icons.Default.Thermostat,
                Color(0xFFFF5722),
                stringResource(Res.string.device_cpu),
                d?.cpuTemperature ?: "—",
            )
        }
    }
    command?.let { selected ->
        AlertDialog(
            onDismissRequest = { command = null },
            title = {
                Text(
                    stringResource(
                        if (selected == DeviceCommand.REBOOT) Res.string.device_reboot
                        else Res.string.device_shutdown
                    )
                )
            },
            text = { Text(stringResource(Res.string.device_confirm_command)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deviceCommand(selected)
                        command = null
                    }
                ) {
                    Text(stringResource(Res.string.common_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { command = null }) {
                    Text(stringResource(Res.string.confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    icon: ImageVector,
    color: Color,
    label: String,
    value: String,
    connected: Boolean? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(28.dp), tint = color)
        Column(Modifier.padding(start = 28.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (connected != null)
                    Icon(
                        Icons.Default.Circle,
                        null,
                        Modifier.padding(end = 6.dp).size(12.dp),
                        tint =
                            if (connected) Color(0xFF21BA45)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                Text(value, fontSize = 16.sp)
            }
        }
    }
    HorizontalDivider(color = LineGrey)
}

@Composable
private fun SettingsScreen(state: WalletUiState, vm: WalletViewModel, actions: AddressActions) {
    val uri = LocalUriHandler.current
    var dialog by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
                AppLogo(Modifier.size(72.dp))
                Text(
                    stringResource(Res.string.app_name),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(Res.string.settings_subtitle),
                    Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsRow(
                Icons.Default.Sync,
                AirLockBlue,
                stringResource(Res.string.me_sync_cold_wallet),
                stringResource(Res.string.me_sync_reset_caption),
            ) {
                vm.requestResetSynchronization()
            }
            SettingsRow(
                Icons.Default.Dns,
                AirLockBlue,
                stringResource(Res.string.me_page_list_node_setting),
                state.networkSettings.preferred.label,
            ) {
                vm.clearMessage()
                dialog = "server"
            }
            SettingsRow(
                Icons.Default.Translate,
                Color(0xFFE91E63),
                stringResource(Res.string.me_page_list_language),
                state.language?.let { AppLanguage.fromCode(it).nativeName }
                    ?: stringResource(Res.string.language_system),
            ) {
                dialog = "language"
            }
            SettingsRow(
                Icons.Default.Brightness6,
                AirLockBlue,
                stringResource(Res.string.theme_title),
                stringResource(state.theme.label),
            ) {
                dialog = "theme"
            }
            SettingsRow(
                Icons.Default.Password,
                Color(0xFFFF9800),
                stringResource(Res.string.me_page_list_app_lock),
                stringResource(Res.string.me_page_list_lock_caption),
            ) {
                dialog = "pin"
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = LineGrey)
            SettingsRow(
                null,
                Color(0xFF009688),
                stringResource(Res.string.me_page_list_export_title),
                stringResource(Res.string.settings_export_caption),
                Res.drawable.bi_export,
            ) {
                dialog = "export"
            }
            SettingsRow(
                Icons.Default.AdminPanelSettings,
                Color(0xFF2196F3),
                stringResource(Res.string.settings_security),
                stringResource(Res.string.settings_security_caption),
            ) {
                uri.openUri("https://www.airlock.pub/docs/en/security")
            }
            SettingsRow(
                Icons.Default.MenuBook,
                Color(0xFF9C27B0),
                stringResource(Res.string.me_page_list_project_docs),
                stringResource(Res.string.settings_docs_caption),
                external = true,
            ) {
                uri.openUri("https://www.airlock.pub/docs/en/app")
            }
            SettingsRow(
                null,
                MaterialTheme.colorScheme.onSurface,
                stringResource(Res.string.me_page_list_source_code),
                stringResource(Res.string.settings_source_caption),
                Res.drawable.bi_github,
                true,
            ) {
                uri.openUri("https://github.com/airlock-wallet/airlock")
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = LineGrey)
            SettingsRow(
                Icons.Default.PrivacyTip,
                Color(0xFF009688),
                stringResource(Res.string.me_page_list_privacy),
                stringResource(Res.string.me_page_list_privacy_caption),
            ) {
                uri.openUri("https://www.airlock.pub/docs/en/privacy")
            }
            SettingsRow(
                Icons.Default.Gavel,
                Color(0xFF3F51B5),
                stringResource(Res.string.settings_terms),
                stringResource(Res.string.settings_terms_caption),
            ) {
                uri.openUri("https://www.airlock.pub/docs/en/terms")
            }
            Text(
                "0.1.0",
                Modifier.fillMaxWidth().padding(12.dp),
                color = AirLockGrey,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = vm::lock, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.common_lock_wallet))
            }
        }
    }
    if (state.resetSyncConfirmation)
        AlertDialog(
            onDismissRequest = vm::cancelResetSynchronization,
            title = { Text(stringResource(Res.string.me_sync_reset_title)) },
            text = { Text(stringResource(Res.string.me_sync_reset_warning)) },
            confirmButton = {
                TextButton(
                    onClick = vm::confirmResetAndSynchronize,
                    enabled = !state.busy,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    modifier = Modifier.testTag("confirm-reset-sync"),
                ) {
                    Text(stringResource(Res.string.me_sync_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = vm::cancelResetSynchronization,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("cancel-reset-sync"),
                ) {
                    Text(stringResource(Res.string.confirm_cancel))
                }
            },
        )
    when (dialog) {
        "server" -> NetworkSettingsDialog(state, vm) { dialog = null }
        "language" ->
            LanguageDialog(state.language, state.busy, { dialog = null }) {
                vm.language(it)
                dialog = null
            }
        "theme" ->
            ThemeDialog(state.theme, state.busy, { dialog = null }) {
                vm.theme(it)
                dialog = null
            }
        "pin" ->
            ChangePinDialog({ dialog = null }) { old, new ->
                vm.changePin(old, new)
                dialog = null
            }
        "export" -> PublicKeyExport(state, actions) { dialog = null }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector?,
    color: Color,
    title: String,
    subtitle: String,
    resource: DrawableResource? = null,
    external: Boolean = false,
    action: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(PageGrey, Corner)
            .clickable(onClick = action)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(color.copy(alpha = 0.08f), Corner),
            contentAlignment = Alignment.Center,
        ) {
            if (resource != null)
                Icon(painterResource(resource), null, Modifier.size(24.dp), tint = color)
            else if (icon != null) Icon(icon, null, Modifier.size(24.dp), tint = color)
        }
        Column(Modifier.weight(1f).padding(horizontal = 24.dp)) {
            Text(title, fontSize = 14.sp)
            Text(
                subtitle,
                Modifier.padding(top = 4.dp),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = AirLockGrey,
            )
        }
        if (external)
            Icon(
                painterResource(Res.drawable.bi_link),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else
            Icon(
                Icons.Default.ChevronRight,
                null,
                Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
