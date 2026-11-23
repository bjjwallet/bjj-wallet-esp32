package pub.airlock

import kotlin.test.*
import pub.airlock.domain.Amount

class AmountTest {
    @Test
    fun preservesEthereumPrecision() {
        assertEquals(
            "1234567890123456789012345678",
            Amount.atomic("1234567890.123456789012345678", 18),
        )
        assertEquals(
            "1234567890.123456789012345678",
            Amount.display("1234567890123456789012345678", 18),
        )
    }

    @Test
    fun smallestUnitsAndZero() {
        assertEquals("1", Amount.atomic("0.00000001", 8))
        assertEquals("0", Amount.atomic("000.000", 8))
        assertEquals("0.00000001", Amount.display("1", 8))
        assertEquals("42", Amount.display("42", 0))
    }

    @Test
    fun rejectsLossyOrAmbiguousInput() {
        listOf("1.000000001", "-1", "1e8", "NaN", "1,2", "1.", " 1", "١").forEach { input ->
            assertFailsWith<IllegalArgumentException>(input) { Amount.atomic(input, 8) }
        }
    }

    @Test
    fun subtractsWithoutFloatingPoint() {
        assertEquals("999999999999999999", Amount.subtract("1000000000000000000", "1"))
        assertEquals("0", Amount.subtract("100", "100"))
        assertFailsWith<IllegalArgumentException> { Amount.subtract("1", "2") }
    }
}
