package pub.airlock.domain

/** Exact decimal editing. No grouping or floating point is used while editing an amount. */
object DecimalInput {
    fun separator(language: String): Char =
        if (language in listOf("pt", "es", "fr", "it", "de")) ',' else '.'

    /** Validate the entire edit, including paste. Excess precision is rejected, never rounded. */
    fun edit(proposed: String, decimals: Int, language: String): String {
        require(decimals in 0..30)
        requireInput(
            proposed.length <= 128 && proposed.all { it in '0'..'9' || it == '.' || it == ',' },
            InputError.INVALID_AMOUNT,
        )
        requireInput(proposed.count { it == '.' || it == ',' } <= 1, InputError.INVALID_AMOUNT)
        val normalized = proposed.replace(',', '.')
        requireInput(!(decimals == 0 && '.' in normalized), InputError.DECIMAL_PLACES)
        requireInput(
            normalized.substringAfter('.', "").length <= decimals,
            InputError.DECIMAL_PLACES,
        )
        val text = if (normalized.startsWith('.')) "0$normalized" else normalized
        return text.replace('.', separator(language))
    }

    fun canonical(value: String, decimals: Int): String {
        val clean = edit(value, decimals, "en").removeSuffix(".")
        val atomic = Amount.atomic(clean, decimals)
        return Amount.display(atomic, decimals)
    }

    fun display(canonical: String, language: String): String =
        canonical.replace('.', separator(language))

    fun placeholder(decimals: Int, language: String): String {
        require(decimals in 0..30)
        return if (decimals == 0) "0" else "0" + separator(language) + "0".repeat(decimals)
    }
}
