package pub.airlock.domain

/**
 * Right-to-left amount entry. Digits are atomic units; drafts and sending still use exact coin
 * amounts.
 */
object AtomicAmountInput {
    fun edit(digits: String, decimals: Int): String {
        require(decimals in 0..30)
        requireInput(
            digits.length <= 128 + decimals && digits.all { it in '0'..'9' },
            InputError.INVALID_AMOUNT,
        )
        if (digits.isEmpty()) return ""
        val normalized = digits.trimStart('0').ifEmpty { "0" }
        requireInput(Amount.display(normalized, decimals).length <= 128, InputError.INVALID_AMOUNT)
        return normalized
    }

    /** Existing decimal drafts are converted, never reinterpreted as newly typed digits. */
    fun restore(value: String, decimals: Int): String =
        if (value.isEmpty()) ""
        else edit(Amount.atomic(DecimalInput.canonical(value, decimals), decimals), decimals)

    fun decimal(digits: String, decimals: Int, language: String): String {
        val normalized = edit(digits, decimals)
        return if (normalized.isEmpty()) ""
        else DecimalInput.display(Amount.display(normalized, decimals), language)
    }
}
