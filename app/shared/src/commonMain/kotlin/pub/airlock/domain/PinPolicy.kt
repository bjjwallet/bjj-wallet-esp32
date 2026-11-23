package pub.airlock.domain

/** Character repertoire of the original Cordova keyboard, shared by UI and persistence. */
object PinPolicy {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 32

    fun isAllowed(char: Char): Boolean = (char in ' '..'~' && char != '`') || char in "€£¥•"

    /** Empty/partial input is allowed while editing; invalid pastes are rejected as a whole. */
    fun editError(value: String): InputError? =
        when {
            value.length > MAX_LENGTH -> InputError.PIN_LENGTH
            value.any { !isAllowed(it) } -> InputError.PIN_CHARACTERS
            else -> null
        }

    fun validate(value: String) {
        requireInput(value.length in MIN_LENGTH..MAX_LENGTH, InputError.PIN_LENGTH)
        requireInput(value.all(::isAllowed), InputError.PIN_CHARACTERS)
    }
}
