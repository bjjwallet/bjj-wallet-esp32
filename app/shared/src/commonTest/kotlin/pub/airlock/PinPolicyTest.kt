package pub.airlock

import kotlin.test.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import pub.airlock.data.VaultRepository
import pub.airlock.domain.*

class PinPolicyTest {
    @Test
    fun coldWalletPinRequiresUppercaseLowercaseAndDigit() {
        for (valid in listOf("Aa1234", "0Ab123", "Aa123456789012345678901234567890")) {
            assertTrue(HardwarePinPolicy.isValid(valid))
            HardwarePinPolicy.validate(valid)
        }
        for (invalid in
            listOf(
                "",
                "123456",
                "abcdef",
                "ABC123",
                "abc123",
                "Aa123",
                "Aa123!",
                "Aa1234 ",
                "Aa123456789012345678901234567890123",
                "１２３４",
                "١٢٣٤",
                "1234\n",
            )) {
            assertFalse(HardwarePinPolicy.isValid(invalid), invalid)
            assertEquals(
                InputError.HARDWARE_PIN_FORMAT,
                assertFailsWith<InputException> { HardwarePinPolicy.validate(invalid) }.reason,
            )
        }
        assertNull(HardwarePinPolicy.editError(""))
        assertNull(HardwarePinPolicy.editError("0"))
        assertNull(HardwarePinPolicy.editError("Ab1"))
        assertEquals(InputError.HARDWARE_PIN_FORMAT, HardwarePinPolicy.editError("Aa123!"))
        // The local app PIN continues to accept letters and symbols.
        PinPolicy.validate("app123!")
    }

    @Test
    fun acceptsOriginalLettersNumbersSpaceAndSymbols() {
        val original =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -/:;()\$&@\".,?!'_[]{}#%^*+=\\|~<>€£¥•"
        assertTrue(original.all(PinPolicy::isAllowed))
        assertNull(PinPolicy.editError(" Ab9\$€£¥• "))
        PinPolicy.validate(" Ab9\$€£¥• ")
    }

    @Test
    fun rejectsAccentsEmojiControlCharactersAndOtherScripts() {
        for (invalid in
            listOf(
                "é",
                "ç",
                "中",
                "🔒",
                "\n",
                "\r",
                "\t",
                "\u0000",
                "\u007f",
                "\u200b",
                "\u00a0",
                "`",
            )) {
            assertEquals(InputError.PIN_CHARACTERS, PinPolicy.editError("abc123$invalid"), invalid)
        }
    }

    @Test
    fun editingAndFinalValidationHaveDifferentMinimums() {
        assertNull(PinPolicy.editError(""))
        assertNull(PinPolicy.editError("a"))
        assertNull(PinPolicy.editError("a".repeat(32)))
        assertEquals(InputError.PIN_LENGTH, PinPolicy.editError("a".repeat(33)))
        assertEquals(
            InputError.PIN_LENGTH,
            assertFailsWith<InputException> { PinPolicy.validate("12345") }.reason,
        )
    }

    @Test
    fun repositoryEnforcesPolicyWithoutUiAndPreservesSpaces() = runTest {
        val repository =
            VaultRepository(MemoryStorage(), TestCrypto(), StandardTestDispatcher(testScheduler))
        assertEquals(
            InputError.PIN_CHARACTERS,
            assertFailsWith<InputException> { repository.configurePins("abc🔒123", "654321") }
                .reason,
        )
        assertNull(repository.snapshot().pins)
        repository.configurePins(" Ab9\$€7 ", "654321")
        assertEquals(WalletMode.STANDARD, repository.unlock(" Ab9\$€7 "))
        assertNull(repository.unlock("Ab9\$€7"))
        assertEquals(
            InputError.PIN_CHARACTERS,
            assertFailsWith<InputException> {
                    repository.changePin(WalletMode.STANDARD, " Ab9\$€7 ", "12345é")
                }
                .reason,
        )
        assertEquals(WalletMode.STANDARD, repository.unlock(" Ab9\$€7 "))
    }
}
