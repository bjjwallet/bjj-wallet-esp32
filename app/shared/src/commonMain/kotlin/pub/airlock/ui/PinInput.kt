package pub.airlock.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.HardwarePinPolicy
import pub.airlock.domain.PinPolicy
import pub.airlock.resources.*

/** Native text input supports both the system keyboard and a connected physical keyboard. */
@Composable
fun PinInput(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    coldWallet: Boolean = false,
    onSubmit: (() -> Unit)? = null,
) {

    var visible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Do not key this state by value: rapid native edits may reject a character before the
    // parent recomposes with the last accepted value, which would otherwise erase the error.
    var inputError by remember { mutableStateOf<StringResource?>(null) }
    OutlinedTextField(
        value = value,
        onValueChange = { proposed ->
            val rejected =
                if (coldWallet) HardwarePinPolicy.editError(proposed)
                else PinPolicy.editError(proposed)
            if (rejected != null || proposed != value) inputError = rejected?.resource
            // Reject the whole edit/paste instead of silently changing the user's secret.
            if (rejected == null) onChange(proposed)
        },
        modifier = Modifier.fillMaxWidth().testTag("pin-field-$label"),
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Lock, null, tint = AirLockBlue) },
        trailingIcon = {
            IconButton(
                onClick = { visible = !visible },
                enabled = enabled,
                modifier = Modifier.testTag("pin-visibility-$label"),
            ) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription =
                        stringResource(if (visible) Res.string.pin_hide else Res.string.pin_show),
                )
            }
        },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = {
                    keyboard?.hide()
                    focus.clearFocus()
                    if (enabled && (!coldWallet || HardwarePinPolicy.isValid(value)))
                        onSubmit?.invoke()
                }
            ),
        isError = inputError != null,
        supportingText = {
            Text(
                stringResource(
                    inputError
                        ?: if (coldWallet) Res.string.hardware_pin_format
                        else Res.string.pin_allowed_characters
                )
            )
        },
    )
}
