@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pub.airlock.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.*
import pub.airlock.presentation.*
import pub.airlock.resources.*

@Composable
internal fun SigningTransferSheet(state: WalletUiState, vm: WalletViewModel) {
    val stage = state.transferStage ?: return
    val quote = state.signingQuote ?: return
    val waiting = stage == TransferStage.CONNECTION_REQUIRED && !state.busy
    val needsPin =
        state.simulated ||
            !(LedgerNetworks.supportsPayment(quote.asset) ||
                CardanoNetwork.supportsPayment(quote.asset) ||
                SolanaNetwork.supportsPayment(quote.asset) ||
                EvmNetworks.supportsPayment(quote.asset))
    var pin by remember(quote, waiting) { mutableStateOf("") }
    var hardwarePin by remember(quote, waiting) { mutableStateOf("") }
    val currentWaiting by rememberUpdatedState(waiting)
    ModalBottomSheet(
        onDismissRequest = vm::cancelTransferConnection,
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || currentWaiting },
            ),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag("transfer-hardware-sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(Res.string.transfer_sign_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CoinIcon(quote.asset.displayIcon, quote.asset.name, 32)
                Text(
                    "${DecimalInput.display(quote.amount, LocalAppLanguage.current.code)} ${quote.asset.symbol}",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (!waiting) CircularProgressIndicator()
            Text(
                stringResource(
                    when (stage) {
                        TransferStage.CONNECTION_REQUIRED -> Res.string.transfer_connection_required
                        TransferStage.CONNECTING -> Res.string.transfer_sign_connect
                        TransferStage.AUTHORIZING -> Res.string.transfer_sign_authorize
                        TransferStage.APPROVAL -> Res.string.transfer_sign_approve
                        TransferStage.VERIFYING -> Res.string.transfer_sign_verify
                        TransferStage.SUBMITTING -> Res.string.transfer_sign_submit
                    }
                )
            )
            if (waiting) {
                state.transferConnectionError?.let { error ->
                    Text(
                        stringResource(
                            if (
                                error in
                                    setOf(
                                        InputError.DEVICE_DISCONNECTED,
                                        InputError.BLUETOOTH_UNAVAILABLE,
                                        InputError.BLUETOOTH_PERMISSION,
                                    )
                            )
                                Res.string.transfer_connection_retry_help
                            else error.resource
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (vm.requiresSigningAccessPin) {
                    Text(
                        stringResource(Res.string.transfer_hardware_pin_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PinInput(
                        stringResource(Res.string.hardware_pin_title),
                        hardwarePin,
                        { hardwarePin = it },
                        true,
                        coldWallet = true,
                    )
                }
                if (needsPin)
                    PinInput(
                        stringResource(Res.string.transfer_transaction_phrase),
                        pin,
                        { pin = it },
                        true,
                    )
                Button(
                    onClick = {
                        vm.confirmTransfer(pin, hardwarePin)
                        pin = ""
                        hardwarePin = ""
                    },
                    enabled =
                        (!needsPin || runCatching { PinPolicy.validate(pin) }.isSuccess) &&
                            (!vm.requiresSigningAccessPin ||
                                HardwarePinPolicy.isValid(hardwarePin)),
                    modifier = Modifier.fillMaxWidth().testTag("transfer-connect"),
                ) {
                    Text(stringResource(Res.string.transfer_connect_sign))
                }
                TextButton(onClick = vm::cancelTransferConnection) {
                    Text(stringResource(Res.string.confirm_cancel))
                }
            }
            Text(
                stringResource(Res.string.transfer_sign_private),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun TransactionsScreen(state: WalletUiState, vm: WalletViewModel) {
    val asset = state.selectedAsset ?: return
    val ids = state.selectedCurrencyAssets.map { it.id }.toSet() + asset.id
    val remote =
        when {
            state.networkSettings.preferred == NetworkProvider.MORALIS ->
                ids.flatMap { state.moralisHistory[it]?.transfers.orEmpty() }
            asset.coin == "ecash" -> ids.flatMap { state.ecashHistory[it].orEmpty() }
            else -> ids.flatMap { state.chainHistory[it]?.transfers.orEmpty() }
        }
    val transfers =
        TransferHistory.merge(state.transfers.filter { it.assetId in ids }.reversed(), remote)
    val failure =
        ids.firstNotNullOfOrNull {
            state.historyErrors[it] ?: state.moralisHistoryErrors[it] ?: state.failedBalances[it]
        }
    Column(Modifier.fillMaxSize()) {
        PageTitle(
            stringResource(Res.string.transfer_transactions_title, asset.symbol),
            back = vm::back,
        )
        Text(
            stringResource(Res.string.transfer_pull_refresh),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        failure?.let {
            Text(
                stringResource(it.resource),
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        PullToRefreshBox(
            isRefreshing = state.refreshingTransactions,
            onRefresh = vm::refreshTransactions,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("transactions-pull-refresh"),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (transfers.isEmpty())
                    item {
                        Text(
                            stringResource(Res.string.asset_detail_page_no_history),
                            Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                items(transfers, key = { "${it.assetId}:${it.id}" }) { tx ->
                    ListItem(
                        headlineContent = {
                            Text(
                                "${if (tx.incoming) "+" else "−"}${DecimalInput.display(tx.amount, LocalAppLanguage.current.code)} ${asset.symbol}"
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(tx.destination, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    stringResource(
                                        when (tx.status) {
                                            TransferStatus.CONFIRMED ->
                                                Res.string.network_status_confirmed
                                            TransferStatus.FAILED ->
                                                Res.string.network_status_failed
                                            else -> Res.string.network_status_submitted
                                        }
                                    )
                                )
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
