package pub.airlock.domain

/** Mandatory cold wallet access PIN, validated again by the hardware. */
object HardwarePinPolicy {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 32

    fun editError(value: String): InputError? =
        if (
            value.length > MAX_LENGTH ||
                value.any { it !in '0'..'9' && it !in 'a'..'z' && it !in 'A'..'Z' }
        )
            InputError.HARDWARE_PIN_FORMAT
        else null

    fun isValid(value: String) =
        value.length in MIN_LENGTH..MAX_LENGTH &&
            editError(value) == null &&
            value.any { it in 'a'..'z' } &&
            value.any { it in 'A'..'Z' } &&
            value.any { it in '0'..'9' }

    fun validate(value: String) {
        requireInput(isValid(value), InputError.HARDWARE_PIN_FORMAT)
    }
}
