package pub.airlock.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import pub.airlock.domain.AtomicAmountInput
import pub.airlock.domain.DecimalInput
import pub.airlock.domain.InputException
import pub.airlock.domain.TransferAmountPolicy
import pub.airlock.domain.TransferTestConfig
import pub.airlock.resources.*

@Composable
internal fun AmountInput(
    value: String,
    change: (String) -> Unit,
    decimals: Int,
    language: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    unit: String? = null,
    maximum: String? = null,
) {

    val keyboard = LocalSoftwareKeyboardController.current
    var field by
        remember(decimals, language) {
            mutableStateOf(TextFieldValue(value, TextRange(value.length)))
        }
    var error by remember(decimals, language) { mutableStateOf<StringResource?>(null) }
    val mask = remember(decimals, language) { AmountVisualTransformation(decimals, language) }
    LaunchedEffect(value, decimals, language, maximum) {
        try {
            val digits = AtomicAmountInput.restore(value, decimals)
            val restored = TextFieldValue(digits, TextRange(digits.length))
            if (restored.text != field.text) field = restored
            error = null
            if (maximum != null && digits.isNotEmpty())
                TransferAmountPolicy.requireAtomicWithinBalance(digits, maximum, decimals)
        } catch (failure: InputException) {
            // A restored draft is validated too; never truncate it when the selected precision
            // changes.
            val digits = runCatching { AtomicAmountInput.restore(value, decimals) }.getOrNull()
            field = TextFieldValue(digits ?: value, TextRange((digits ?: value).length))
            error = failure.reason.resource
        }
    }
    OutlinedTextField(
        value = field,
        enabled = enabled,
        onValueChange = { proposed ->
            try {
                field = atomicAmountEdit(proposed, decimals, maximum)
                error = null
                change(AtomicAmountInput.decimal(field.text, decimals, language))
            } catch (e: InputException) {
                error = e.reason.resource
            }
        },
        label = { Text(label) },
        placeholder = { Text(DecimalInput.placeholder(decimals, language)) },
        singleLine = true,
        isError = error != null,
        visualTransformation = mask,
        suffix = unit?.let { symbol -> { Text(symbol) } },
        supportingText = {
            Text(
                error?.let { stringResource(it) }
                    ?: stringResource(Res.string.amount_precision, decimals)
            )
        },
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Strip leading zeros while retaining cursor and selection positions in the typed digits. */
internal fun atomicAmountEdit(
    proposed: TextFieldValue,
    decimals: Int,
    maximum: String? = null,
    testMode: Boolean = TransferTestConfig.TEST_MODE,
): TextFieldValue {
    val digits = AtomicAmountInput.edit(proposed.text, decimals)
    if (maximum != null && digits.isNotEmpty())
        TransferAmountPolicy.requireAtomicWithinBalance(digits, maximum, decimals, testMode)
    val removed = proposed.text.length - digits.length
    return proposed.copy(
        text = digits,
        selection =
            TextRange(
                (proposed.selection.start - removed).coerceIn(0, digits.length),
                (proposed.selection.end - removed).coerceIn(0, digits.length),
            ),
        composition = null,
    )
}
