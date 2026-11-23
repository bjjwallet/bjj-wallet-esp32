@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pub.airlock.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.svg.SvgDecoder
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import pub.airlock.di.AppGraph
import pub.airlock.domain.*
import pub.airlock.presentation.*
import pub.airlock.resources.*

@Composable
fun AirLockApp(graph: AppGraph) {
    val vm = viewModel { graph.viewModel() }
    val state by vm.state.collectAsStateWithLifecycle()
    BlockSystemBack(
        state.screen == Screen.PIN_BLOCKED ||
            state.screen == Screen.MONERO_SYNC ||
            state.screen in setOf(Screen.ADD_ACCOUNT, Screen.WALLETS, Screen.ASSET) ||
            state.moneroSync
    ) {
        if (state.screen != Screen.PIN_BLOCKED) vm.back()
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                    vm.state.value.screen !in setOf(Screen.LOADING, Screen.LOGIN, Screen.FATAL)
            )
                vm.lock()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner, vm, state.mode) {
        if (state.mode != null) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(60_000)
                    vm.refreshVisibleBalances()
                }
            }
        }
    }
    AirLockTheme(state.theme) {
        ProvideAppLanguage(state.language) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                // safeDrawing includes the keyboard inset; apply it once to keep fields and errors
                // visible.
                val headerColor =
                    when (state.screen) {
                        Screen.ACCOUNTS,
                        Screen.WALLETS,
                        Screen.ASSET,
                        Screen.RECEIVE -> AirLockHeaderBlue
                        Screen.SETTINGS -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    }
                SystemBarAppearance(
                    darkStatusIcons = headerColor.luminance() > 0.5f,
                    darkNavigationIcons = MaterialTheme.colorScheme.surface.luminance() > 0.5f,
                )
                Column(
                    Modifier.fillMaxSize()
                        .background(headerColor)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                            )
                        )
                ) {
                    if (
                        state.screen in
                            listOf(
                                Screen.SETUP,
                                Screen.LOGIN,
                                Screen.SYNC,
                                Screen.APP_SECURITY,
                                Screen.HARDWARE_SELECT,
                                Screen.HARDWARE_PIN,
                            )
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ThemeButton(state.theme, state.busy, vm::theme)
                            LanguageButton(state.language, state.busy, vm::language)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (
                            state.screen in
                                listOf(
                                    Screen.LOADING,
                                    Screen.SETUP,
                                    Screen.LOGIN,
                                    Screen.SYNC,
                                    Screen.APP_SECURITY,
                                    Screen.HARDWARE_PIN,
                                    Screen.PIN_BLOCKED,
                                    Screen.FATAL,
                                )
                        ) {
                            AuthContent(state, vm)
                        } else {
                            WalletContent(state, vm, graph.addressActions)
                        }
                    }
                    if (state.error != null || state.message != null) {
                        Surface(
                            color =
                                if (state.error != null) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(
                                        state.error?.resource ?: state.message!!.resource
                                    ),
                                    Modifier.weight(1f),
                                    fontSize = 13.sp,
                                )
                                IconButton(onClick = vm::clearMessage) {
                                    Icon(
                                        Icons.Default.Close,
                                        stringResource(Res.string.common_dismiss),
                                    )
                                }
                            }
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                SigningTransferSheet(state, vm)
            }
        }
    }
}

@Composable
private fun AuthContent(state: WalletUiState, vm: WalletViewModel) {

    when (state.screen) {
        Screen.LOADING ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        Screen.SETUP,
        Screen.APP_SECURITY ->
            AppSecurityScreen(
                state.busy || !state.hardwareConnectionAccepted,
                vm::configureAppSecurity,
            )
        Screen.HARDWARE_PIN -> {
            var pin by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(Res.string.hardware_pin_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(Res.string.hardware_pin_description),
                    Modifier.padding(vertical = 16.dp),
                )
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
                PrimaryButton(
                    stringResource(Res.string.login_page_btn_unlock),
                    !state.busy && HardwarePinPolicy.isValid(pin),
                ) {
                    vm.unlockHardware(pin)
                    pin = ""
                }
                state.hardwareOperation?.let { operation ->
                    Text(
                        stringResource(
                            if (operation == HardwareOperation.VERIFY_PIN)
                                Res.string.hardware_pin_verifying
                            else Res.string.hardware_public_syncing
                        ),
                        Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
        Screen.PIN_BLOCKED ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Lock, null, Modifier.size(64.dp))
                Text(
                    stringResource(Res.string.hardware_block_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(Res.string.hardware_block_description),
                    Modifier.padding(vertical = 16.dp),
                )
                Text("${state.blockedSeconds} s", style = MaterialTheme.typography.displayMedium)
            }
        Screen.LOGIN -> {
            val biometricLabel = stringResource(Res.string.app_biometric_unlock)
            val pinLabel = stringResource(Res.string.app_pin_label)
            LaunchedEffect(Unit) {
                if (state.biometricAvailable) vm.unlockBiometric(biometricLabel, pinLabel)
            }
            var pin by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppLogo()
                Text(
                    stringResource(Res.string.app_name),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = AirLockBlue,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(Res.string.login_page_welcome),
                    color = AirLockGrey,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                PinInput(pinLabel, pin, { pin = it }, !state.busy)
                PrimaryButton(
                    stringResource(Res.string.login_page_btn_unlock),
                    !state.busy && pin.length >= 6,
                ) {
                    vm.unlock(pin)
                    pin = ""
                }
                if (state.biometricAvailable)
                    TextButton(
                        onClick = { vm.unlockBiometric(biometricLabel, pinLabel) },
                        enabled = !state.busy,
                    ) {
                        Text(biometricLabel)
                    }
            }
        }
        Screen.SYNC ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.BluetoothSearching,
                    null,
                    Modifier.size(64.dp),
                    tint = AirLockBlue,
                )
                Text(
                    stringResource(Res.string.load_wallet_page_title),
                    fontSize = 24.sp,
                    modifier = Modifier.padding(16.dp),
                )
                Text(stringResource(Res.string.sync_instructions), color = AirLockGrey)
                PrimaryButton(
                    stringResource(Res.string.load_wallet_page_btn_connect),
                    !state.busy,
                    vm::synchronize,
                )
                if (state.savedWallets.isNotEmpty())
                    TextButton(onClick = { vm.navigate(Screen.ACCOUNTS) }, enabled = !state.busy) {
                        Text(stringResource(Res.string.common_back))
                    }
            }
        Screen.FATAL ->
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(Res.string.fatal_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(Res.string.fatal_description),
                    Modifier.padding(vertical = 16.dp),
                )
                PrimaryButton(stringResource(Res.string.common_retry), !state.busy, vm::initialize)
            }
        else -> Unit
    }
}

@Composable
private fun AppSecurityScreen(busy: Boolean, finish: (String?) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var first by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppLogo()
        Text(
            stringResource(Res.string.app_security_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            stringResource(Res.string.app_security_description),
            Modifier.padding(vertical = 16.dp),
        )
        if (step == 0) {
            PrimaryButton(stringResource(Res.string.app_security_enable), !busy) { step = 1 }
            TextButton(onClick = { finish(null) }, enabled = !busy) {
                Text(stringResource(Res.string.app_security_skip))
            }
        } else {
            key(step) {
                PinInput(
                    stringResource(
                        if (step == 1) Res.string.app_pin_label else Res.string.pin_repeat
                    ),
                    pin,
                    {
                        pin = it
                        mismatch = false
                    },
                    !busy,
                )
            }
            if (mismatch)
                Text(
                    stringResource(Res.string.setup_page_error_pin_mismatch),
                    color = MaterialTheme.colorScheme.error,
                )
            PrimaryButton(
                stringResource(
                    if (step == 1) Res.string.setup_page_step1_btn_next
                    else Res.string.setup_page_step4_btn_finish
                ),
                !busy && pin.length >= PinPolicy.MIN_LENGTH,
            ) {
                if (step == 1) {
                    first = pin
                    pin = ""
                    step = 2
                } else if (pin == first) {
                    finish(pin)
                    first = ""
                    pin = ""
                } else {
                    mismatch = true
                    pin = ""
                }
            }
            TextButton(
                onClick = {
                    step--
                    pin = ""
                    first = ""
                    mismatch = false
                },
                enabled = !busy,
            ) {
                Text(stringResource(Res.string.setup_page_step2_btn_back))
            }
        }
    }
}

@Composable
internal fun ChangePinDialog(dismiss: () -> Unit, submit: (String, String) -> Unit) {

    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.pin_update_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PinInput(stringResource(Res.string.pin_current), old, { old = it })
                PinInput(stringResource(Res.string.pin_new), new, { new = it })
                PinInput(stringResource(Res.string.pin_repeat), repeat, { repeat = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = new.length >= 6 && new == repeat && new != old,
                onClick = { submit(old, new) },
            ) {
                Text(stringResource(Res.string.common_update))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

internal val Account.displayIcon: String
    get() = CoinCatalog.find(coin)?.networkIcon ?: icon

internal val Asset.displayIcon: String
    get() =
        icon.ifBlank {
            if (contract.isEmpty()) CoinCatalog.find(coin)?.icon.orEmpty()
            else Stablecoins.definition(this)?.icon.orEmpty()
        }

@Composable
internal fun CoinIcon(file: String, label: String, size: Int = 40) {
    if (file.isEmpty()) {
        Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
            Text(
                label.take(4).uppercase(),
                fontSize = (size / 3).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        return
    }
    val context = LocalPlatformContext.current
    val loader =
        remember(context) {
            ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build()
        }
    AsyncImage(
        model = Res.getUri("files/coins/$file"),
        imageLoader = loader,
        contentDescription = label,
        modifier = Modifier.size(size.dp),
    )
}

@Composable
internal fun PrimaryButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    Button(
        onClick = action,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(50.dp),
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun LanguageButton(language: String?, busy: Boolean, select: (String?) -> Unit) {

    var show by remember { mutableStateOf(false) }
    TextButton(
        onClick = { show = true },
        enabled = !busy,
        modifier = Modifier.testTag("language-selector"),
    ) {
        Icon(Icons.Default.Language, stringResource(Res.string.me_page_list_language))
        Text(
            language?.let { AppLanguage.fromCode(it).nativeName }
                ?: stringResource(Res.string.language_system),
            Modifier.padding(start = 8.dp),
        )
    }
    if (show)
        LanguageDialog(language, busy, { show = false }) {
            select(it)
            show = false
        }
}

@Composable
internal fun LanguageDialog(
    language: String?,
    busy: Boolean,
    dismiss: () -> Unit,
    select: (String?) -> Unit,
) {

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.me_page_list_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth()
                        .testTag("language-system")
                        .selectable(
                            selected = language == null,
                            enabled = !busy,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { select(null) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = language == null, onClick = null)
                    Text(
                        stringResource(Res.string.language_system),
                        Modifier.padding(start = 12.dp),
                    )
                }
                AppLanguage.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("language-${option.code}")
                            .selectable(
                                selected = language == option.code,
                                enabled = !busy,
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                                onClick = { select(option.code) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == option.code, onClick = null)
                        Text(option.nativeName, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

@Composable
private fun ThemeButton(theme: AppTheme, busy: Boolean, select: (AppTheme) -> Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(
        onClick = { show = true },
        enabled = !busy,
        modifier = Modifier.testTag("theme-selector"),
    ) {
        Icon(Icons.Default.Brightness6, stringResource(Res.string.theme_title))
        Text(stringResource(theme.label), Modifier.padding(start = 8.dp))
    }
    if (show)
        ThemeDialog(theme, busy, { show = false }) {
            select(it)
            show = false
        }
}

@Composable
internal fun ThemeDialog(
    theme: AppTheme,
    busy: Boolean,
    dismiss: () -> Unit,
    select: (AppTheme) -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(Res.string.theme_title)) },
        text = {
            Column {
                AppTheme.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth()
                            .testTag("theme-${option.name}")
                            .selectable(
                                selected = theme == option,
                                enabled = !busy,
                                role = androidx.compose.ui.semantics.Role.RadioButton,
                                onClick = { select(option) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = theme == option, onClick = null)
                        Text(stringResource(option.label), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}
