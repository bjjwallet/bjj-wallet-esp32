package pub.airlock

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.*
import pub.airlock.domain.*
import pub.airlock.ui.AmountVisualTransformation
import pub.airlock.ui.atomicAmountEdit

class AmountMaskTest {
    private fun mask(digits: String, decimals: Int, language: String = "pt") =
        AmountVisualTransformation(decimals, language).filter(AnnotatedString(digits))

    @Test
    fun oneDigitMeansTheSmallestUnitOfTheSelectedCoin() {
        assertEquals("0,00000001", mask("1", 8).text.text)
        assertEquals("0,000001", mask("1", 6).text.text)
        assertEquals("0,000000000000000001", mask("1", 18).text.text)
        assertEquals("1", mask("1", 0).text.text)
        assertEquals("0.00000001", mask("1", 8, "en").text.text)
    }

    @Test
    fun sequentialTypingAndBackspaceShiftTheMaskExactly() {
        val expected =
            listOf(
                "0,00000001",
                "0,00000012",
                "0,00000123",
                "0,00001234",
                "0,00012345",
                "0,00123456",
                "0,01234567",
                "0,12345678",
                "1,23456789",
            )
        var field = TextFieldValue()
        for ((index, digit) in "123456789".withIndex()) {
            val proposed = field.text + digit
            field = atomicAmountEdit(TextFieldValue(proposed, TextRange(proposed.length)), 8)
            assertEquals(expected[index], mask(field.text, 8).text.text)
            assertEquals(TextRange(field.text.length), field.selection)
            assertEquals(
                field.text,
                Amount.atomic(
                    DecimalInput.canonical(AtomicAmountInput.decimal(field.text, 8, "pt"), 8),
                    8,
                ),
            )
        }
        for (length in 8 downTo 0) {
            val proposed = field.text.dropLast(1)
            field = atomicAmountEdit(TextFieldValue(proposed, TextRange(proposed.length)), 8)
            assertEquals(
                if (length == 0) "" else expected[length - 1],
                mask(field.text, 8).text.text,
            )
        }
    }

    @Test
    fun preservesZeroEmptyAndLeadingZeroEditing() {
        assertEquals("", AtomicAmountInput.decimal("", 8, "pt"))
        assertEquals("", mask("", 8).text.text)
        assertEquals("0,00000000", mask("0", 8).text.text)
        val field = atomicAmountEdit(TextFieldValue("0001", TextRange(4)), 8)
        assertEquals("1", field.text)
        assertEquals(TextRange(1), field.selection)
        assertEquals("0", atomicAmountEdit(TextFieldValue("000", TextRange(3)), 8).text)
    }

    @Test
    fun middleEditsAndSelectionReplacementKeepAllDigits() {
        val middle = atomicAmountEdit(TextFieldValue("120345", TextRange(3)), 8)
        assertEquals("0,00120345", mask(middle.text, 8).text.text)
        assertEquals(TextRange(3), middle.selection)
        val deleted = atomicAmountEdit(TextFieldValue("12345", TextRange(2)), 8)
        assertEquals("0,00012345", mask(deleted.text, 8).text.text)
        val replacement = atomicAmountEdit(TextFieldValue("19945", TextRange(3)), 8)
        assertEquals("0,00019945", mask(replacement.text, 8).text.text)
        val selection = atomicAmountEdit(TextFieldValue("123456", TextRange(2, 5)), 8)
        assertEquals(TextRange(2, 5), selection.selection)
    }

    @Test
    fun groupsWholeUnitsAndPreservesLongValuesWithoutFloatingPoint() {
        assertEquals("1.234.567,89000000", mask("123456789000000", 8).text.text)
        assertEquals("1,234,567.89000000", mask("123456789000000", 8, "en").text.text)
        assertEquals("1\u202f234,500000", mask("1234500000", 6, "fr").text.text)
        val digits = "123456789012345678123456789012345678"
        assertEquals("123.456.789.012.345.678,123456789012345678", mask(digits, 18).text.text)
        assertEquals(
            "123456789012345678,123456789012345678",
            AtomicAmountInput.decimal(digits, 18, "pt"),
        )
    }

    @Test
    fun restoresCoinAmountDraftsWithoutReinterpretingThemAsAtomicDigits() {
        assertEquals("100000000", AtomicAmountInput.restore("1", 8))
        assertEquals("1,00000000", mask(AtomicAmountInput.restore("1", 8), 8).text.text)
        assertEquals("123000000", AtomicAmountInput.restore("1,2300", 8))
        assertEquals("1", AtomicAmountInput.restore("0.00000001", 8))
        assertEquals("", AtomicAmountInput.restore("", 8))
        assertFailsWith<InputException> { AtomicAmountInput.restore("0.000000019", 8) }
        assertFailsWith<InputException> { AtomicAmountInput.restore("1.5", 0) }
    }

    @Test
    fun rejectsPastedPunctuationInsteadOfSilentlyChangingItsMagnitude() {
        for (value in
            listOf("1.23", "1,23", "1,234.56", "1e8", "-1", "+1", "１２", "1\n", "NaN", "1 BTC")) {
            assertFailsWith<InputException>(value) { AtomicAmountInput.edit(value, 8) }
        }
        assertFailsWith<InputException> { AtomicAmountInput.edit("9".repeat(129), 8) }
        assertEquals("1", AtomicAmountInput.edit("0001", 8))
    }

    @Test
    fun exactCanonicalAmountsRoundTripForEveryCoinTokenAndLanguage() {
        for (coin in CoinCatalog.coins) {
            val precisions =
                listOf(coin.decimals) + Stablecoins.forNetwork(coin.coin).map { it.decimals }
            for (precision in precisions) for (language in AppLanguage.entries) {
                for (digits in listOf("0", "1", "123456789", "1" + "0".repeat(precision))) {
                    val decimal = AtomicAmountInput.decimal(digits, precision, language.code)
                    assertEquals(
                        digits,
                        Amount.atomic(DecimalInput.canonical(decimal, precision), precision),
                    )
                    assertEquals(digits, AtomicAmountInput.restore(decimal, precision))
                    val display = mask(digits, precision, language.code).text.text
                    if (precision > 0)
                        assertEquals(
                            precision,
                            display.substringAfter(DecimalInput.separator(language.code)).length,
                        )
                }
            }
        }
    }

    @Test
    fun cursorMappingsRemainBoundedMonotonicAndReversible() {
        for (precision in listOf(0, 2, 4, 6, 7, 8, 9, 12, 18, 30)) for (language in
            AppLanguage.entries) {
            for (raw in
                listOf("", "0", "1", "1234", "12345678901234567890", "1" + "0".repeat(precision))) {
                val transformed = mask(raw, precision, language.code)
                val mapping = transformed.offsetMapping
                val forward = (0..raw.length).map(mapping::originalToTransformed)
                val backward = (0..transformed.text.length).map(mapping::transformedToOriginal)
                assertTrue(forward.all { it in 0..transformed.text.length })
                assertTrue(backward.all { it in 0..raw.length })
                assertEquals(forward.sorted(), forward)
                assertEquals(backward.sorted(), backward)
                for (offset in 0..raw.length) assertEquals(
                    offset,
                    mapping.transformedToOriginal(mapping.originalToTransformed(offset)),
                )
            }
        }
        assertEquals(10, mask("1", 8).offsetMapping.originalToTransformed(1))
        assertEquals(0, mask("1", 8).offsetMapping.transformedToOriginal(3))
    }
}
