package pub.airlock.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import pub.airlock.domain.DecimalInput

/** Right-to-left mask: typing 1 means the smallest unit allowed by this asset. */
internal class AmountVisualTransformation(private val decimals: Int, private val language: String) :
    VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        // Invalid restored drafts stay visible with their validation error, without truncation.
        if (raw.isEmpty() || raw.any { it !in '0'..'9' })
            return TransformedText(text, OffsetMapping.Identity)
        val padding = (decimals + 1 - raw.length).coerceAtLeast(0)
        val padded = "0".repeat(padding) + raw
        val decimalIndex = padded.length - decimals
        val grouping =
            when (language) {
                "fr" -> '\u202f'
                "pt",
                "es",
                "it",
                "de" -> '.'
                else -> ','
            }
        val formatted = StringBuilder()
        val originalOffsets = IntArray(raw.length + 1)
        val transformedOffsets = mutableListOf(0)
        fun append(char: Char, originalOffset: Int) {
            formatted.append(char)
            transformedOffsets.add(originalOffset)
        }
        padded.forEachIndexed { index, char ->
            val originalIndex = (index - padding).coerceAtLeast(0)
            if (index in 1 until decimalIndex && (decimalIndex - index) % 3 == 0)
                append(grouping, originalIndex)
            if (decimals > 0 && index == decimalIndex)
                append(DecimalInput.separator(language), originalIndex)
            if (index >= padding) originalOffsets[originalIndex] = formatted.length
            append(char, if (index < padding) 0 else originalIndex + 1)
        }
        originalOffsets[raw.length] = formatted.length
        return TransformedText(
            AnnotatedString(formatted.toString()),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) =
                    originalOffsets[offset.coerceIn(0, raw.length)]

                override fun transformedToOriginal(offset: Int) =
                    transformedOffsets[offset.coerceIn(0, formatted.length)]
            },
        )
    }
}
