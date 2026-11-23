package pub.airlock.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.*
import pub.airlock.presentation.*
import pub.airlock.resources.*

@Composable
internal fun NetworkSettingsDialog(
    state: WalletUiState,
    vm: WalletViewModel,
    keepAssetOpen: Boolean = false,
    close: () -> Unit,
) {
    var settings by remember { mutableStateOf(state.networkSettings) }
    val preferred = settings.preferred
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(state.message, state.busy) {
        if (saving && !state.busy && state.message == WalletMessage.SERVER_SAVED) close()
    }
    AlertDialog(
        onDismissRequest = { if (!state.busy) close() },
        title = { Text(stringResource(Res.string.network_choose)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.network_manual_help))
                NetworkProvider.available.forEach { provider ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("provider-${provider.name}")
                            .selectable(
                                preferred == provider,
                                enabled = !state.busy,
                                role = Role.RadioButton,
                                onClick = {
                                    settings = settings.copy(preferred = provider)
                                    vm.clearMessage()
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = preferred == provider, onClick = null)
                        Text(provider.label, Modifier.padding(start = 8.dp))
                    }
                }
                Text(
                    stringResource(Res.string.network_catalog_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                val coverage =
                    when (preferred) {
                        NetworkProvider.AIRLOCK -> Res.string.network_airlock_coverage
                        NetworkProvider.MORALIS -> Res.string.network_moralis_coverage
                        else -> Res.string.network_utxo_coverage
                    }
                Text(stringResource(coverage), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        Res.string.network_balance_coverage,
                        CoinCatalog.coins
                            .filter { it.coin in ProviderCoverage.balances(preferred) }
                            .joinToString(", ") { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    preferred.serviceUrl(state.apiUrl),
                    {},
                    readOnly = true,
                    enabled = !state.busy,
                    label = { Text(stringResource(Res.string.settings_api_url)) },
                    singleLine = true,
                    modifier = Modifier.testTag("provider-url"),
                )
                if (preferred == NetworkProvider.MORALIS) {
                    OutlinedTextField(
                        settings.keyFor(preferred),
                        { settings = settings.withKey(preferred, it) },
                        enabled = !state.busy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        label = { Text(stringResource(Res.string.network_moralis_key)) },
                        supportingText = { Text(stringResource(Res.string.network_key_help)) },
                        modifier = Modifier.fillMaxWidth().testTag("provider-key-MORALIS"),
                    )
                }
                if (state.simulated)
                    Text(
                        stringResource(Res.string.network_demo),
                        style = MaterialTheme.typography.bodySmall,
                    )
                state.error?.let {
                    Text(stringResource(it.resource), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy,
                onClick = {
                    saving = true
                    vm.saveNetworkSettings(settings, state.apiUrl, keepAssetOpen)
                },
            ) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !state.busy, onClick = close) {
                Text(stringResource(Res.string.confirm_cancel))
            }
        },
    )
}

@Composable
internal fun NetworkFailureSection(state: WalletUiState, vm: WalletViewModel) {
    TextButton(onClick = { vm.navigate(Screen.SETTINGS) }, enabled = !state.busy) {
        Text(stringResource(Res.string.network_selected, state.networkSettings.preferred.label))
    }
    if (
        state.networkFailure == null &&
            state.pendingTransfers.isEmpty() &&
            state.failedBalances.isEmpty()
    )
        return
    Card(
        Modifier.fillMaxWidth().testTag("network-failures"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.network_failures_title),
                style = MaterialTheme.typography.titleMedium,
            )
            state.networkFailure?.let { Text(stringResource(it.resource)) }
            if (state.failedBalances.isNotEmpty()) {
                Text(stringResource(Res.string.network_cached_balances))
                Text(
                    state.assets
                        .filter { it.id in state.failedBalances }
                        .map { "${it.symbol} (${CoinCatalog.get(it.coin).name})" }
                        .distinct()
                        .joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(Res.string.network_retry_help),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { vm.navigate(Screen.SETTINGS) }, enabled = !state.busy) {
                Text(stringResource(Res.string.network_choose))
            }
            state.pendingTransfers.forEach { pending ->
                HorizontalDivider()
                Text("${pending.amount} ${pending.symbol} · ${CoinCatalog.get(pending.coin).name}")
                Text(pending.destination, style = MaterialTheme.typography.bodySmall)
                Text(pending.txId, style = MaterialTheme.typography.bodySmall)
                val supported = pending.canRetry
                Button(
                    onClick = { vm.retryPending(pending.txId) },
                    enabled = !state.busy && supported,
                    modifier = Modifier.testTag("retry-${pending.txId}"),
                ) {
                    Text(stringResource(Res.string.network_check_resume))
                }
                if (!supported)
                    Text(
                        stringResource(Res.string.network_resume_unsupported),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("unsupported-${pending.txId}"),
                    )
            }
            if (state.pendingTransfers.isEmpty()) {
                TextButton(
                    onClick = vm::retryNetworkFailure,
                    enabled = !state.busy && state.canRetryNetworkFailure,
                ) {
                    Text(stringResource(Res.string.network_retry))
                }
                if (!state.canRetryNetworkFailure)
                    Text(
                        stringResource(Res.string.network_resume_unsupported),
                        style = MaterialTheme.typography.labelMedium,
                    )
            }
        }
    }
}
