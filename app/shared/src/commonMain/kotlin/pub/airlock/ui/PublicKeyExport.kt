@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pub.airlock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.Account
import pub.airlock.domain.AddressActions
import pub.airlock.domain.Asset
import pub.airlock.presentation.WalletUiState
import pub.airlock.resources.*

internal data class PublicExportEntry(
    val id: String,
    val name: String,
    val symbol: String,
    val icon: String,
    val networkName: String?,
    val value: String?,
    val isKey: Boolean,
)

internal fun publicExportEntries(
    accounts: List<Account>,
    assets: List<Asset>,
): List<PublicExportEntry> {
    val byAccount = assets.groupBy { it.accountId }
    return accounts
        .filter { it.enabled }
        .flatMap { account ->
            val accountAssets = byAccount[account.id].orEmpty()
            val key = account.xpub.takeIf { it.isNotBlank() && !account.pendingPublicData }
            val address =
                account.cachedAddresses.firstOrNull { it.index == 0L }?.address
                    ?: accountAssets
                        .firstOrNull {
                            it.coin == account.coin &&
                                it.contract.isEmpty() &&
                                (it.id == "${account.id}:0" ||
                                    account.path.isNotEmpty() && it.derivationPath == account.path)
                        }
                        ?.address
            val value =
                if (account.pendingPublicData) null else key ?: address?.takeIf { it.isNotBlank() }
            val network =
                PublicExportEntry(
                    account.id,
                    account.name,
                    account.symbol,
                    account.displayIcon,
                    null,
                    value,
                    key != null,
                )
            val tokens =
                accountAssets
                    .filter { it.coin == account.coin && it.contract.isNotEmpty() }
                    .distinctBy { it.contract }
                    .map {
                        PublicExportEntry(
                            "${account.id}:token:${it.contract}",
                            it.name,
                            it.symbol,
                            it.displayIcon,
                            account.name,
                            value,
                            key != null,
                        )
                    }
            listOf(network) + tokens
        }
}

@Composable
internal fun PublicKeyExport(state: WalletUiState, actions: AddressActions, onDismiss: () -> Unit) {
    val entries =
        remember(state.accounts, state.assets) { publicExportEntries(state.accounts, state.assets) }
    val listState = rememberLazyListState()
    var selectedId by remember(state.activeWalletId, state.mode) { mutableStateOf<String?>(null) }
    val selected = entries.firstOrNull { it.id == selectedId && it.value != null }
    val value = selected?.value
    if (selected != null && value != null) {
        PublicKeySheet(selected, value, actions) { selectedId = null }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.export_pub_key_title)) },
            text = {
                if (entries.isEmpty()) Text(stringResource(Res.string.export_public_empty))
                else
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
                        items(entries, key = { it.id }) { entry ->
                            val subtitle =
                                when {
                                    entry.value == null -> Res.string.export_public_pending
                                    entry.isKey -> Res.string.export_public_key
                                    else -> Res.string.export_public_address
                                }
                            Surface(
                                onClick = { selectedId = entry.id },
                                enabled = entry.value != null,
                                modifier =
                                    Modifier.fillMaxWidth().testTag("public-export-${entry.id}"),
                            ) {
                                ListItem(
                                    headlineContent = { Text(entry.name) },
                                    supportingContent = {
                                        Text(
                                            listOfNotNull(
                                                    entry.networkName,
                                                    stringResource(subtitle),
                                                )
                                                .joinToString(" · ")
                                        )
                                    },
                                    leadingContent = { CoinIcon(entry.icon, entry.symbol, 32) },
                                )
                            }
                        }
                    }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
            },
        )
    }
}

@Composable
private fun PublicKeySheet(
    entry: PublicExportEntry,
    value: String,
    actions: AddressActions,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val qr = remember(actions, value) { actions.qrCode(value) }
    val label =
        stringResource(
            if (entry.isKey) Res.string.export_public_key else Res.string.export_public_address
        )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoinIcon(entry.icon, entry.symbol, 36)
                Text(entry.name, style = MaterialTheme.typography.titleLarge)
            }
            entry.networkName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Surface(color = Color.White, shape = MaterialTheme.shapes.small) {
                AsyncImage(
                    qr,
                    stringResource(Res.string.export_public_qr, label),
                    Modifier.padding(16.dp).size(200.dp),
                )
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(
                    value,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(value)) },
                modifier = Modifier.testTag("copy-public-export"),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.export_public_copy))
            }
        }
    }
}
