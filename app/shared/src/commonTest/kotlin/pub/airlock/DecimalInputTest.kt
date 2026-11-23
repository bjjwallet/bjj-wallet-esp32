package pub.airlock

import kotlin.test.*
import pub.airlock.domain.*

class DecimalInputTest {
    @Test
    fun everyNativeCoinAndTokenUsesItsOwnNetworkSpecificPrecision() {
        var checked = 0
        for (coin in CoinCatalog.coins) {
            val assets =
                listOf(coin.symbol to coin.decimals) +
                    Stablecoins.forNetwork(coin.coin).map { it.symbol to it.decimals }
            for ((symbol, decimals) in assets) {
                val unit = Amount.display("1", decimals)
                for (language in AppLanguage.entries) {
                    val edit = DecimalInput.edit(unit, decimals, language.code)
                    assertEquals(
                        "1",
                        Amount.atomic(DecimalInput.canonical(edit, decimals), decimals),
                        "${coin.coin}:$symbol",
                    )
                    val excessive = if (decimals == 0) "1.0" else unit + "1"
                    assertEquals(
                        InputError.DECIMAL_PLACES,
                        assertFailsWith<InputException> {
                                DecimalInput.edit(excessive, decimals, language.code)
                            }
                            .reason,
                        "${coin.coin}:$symbol",
                    )
                }
                checked++
            }
        }
        assertEquals(CoinCatalog.coins.size + Stablecoins.tokens.size, checked)
        assertEquals(0, Stablecoins.forNetwork("cardano").single { it.symbol == "SNEK" }.decimals)
        assertEquals(4, Stablecoins.forNetwork("solana").single { it.symbol == "BRZ" }.decimals)
        assertEquals(6, Stablecoins.forNetwork("solana").single { it.symbol == "USDC" }.decimals)
        assertEquals(18, Stablecoins.forNetwork("bsc").single { it.symbol == "USDC" }.decimals)
        assertEquals(7, Stablecoins.forNetwork("stellar").single { it.symbol == "USDC" }.decimals)
    }

    @Test
    fun placeholderShowsTheSelectedPrecisionAndSeparator() {
        assertEquals("0", DecimalInput.placeholder(0, "pt"))
        assertEquals("0,000000", DecimalInput.placeholder(6, "pt"))
        assertEquals("0.00000000", DecimalInput.placeholder(8, "en"))
        assertEquals("0.000000000", DecimalInput.placeholder(9, "en"))
        assertEquals("0.000000000000000000", DecimalInput.placeholder(18, "en"))
    }

    @Test
    fun supportsCommaAndDotWithoutChangingMagnitude() {
        assertEquals("0,00000001", DecimalInput.edit("0.00000001", 8, "pt"))
        assertEquals("1.25", DecimalInput.edit("1,25", 18, "en"))
        assertEquals("1.25", DecimalInput.canonical("1,25", 18))
        for (language in AppLanguage.entries) {
            val text = DecimalInput.edit("123.456", 8, language.code)
            assertEquals("123.456", DecimalInput.canonical(text, 8))
        }
    }

    @Test
    fun preservesIntermediateTypingAndTrailingZeros() {
        assertEquals("", DecimalInput.edit("", 8, "pt"))
        assertEquals("0,", DecimalInput.edit(",", 8, "pt"))
        assertEquals("1,", DecimalInput.edit("1,", 8, "pt"))
        assertEquals("1,2300", DecimalInput.edit("1,2300", 8, "pt"))
        assertEquals("1", DecimalInput.canonical("1,", 8))
        assertEquals("1.23", DecimalInput.canonical("0001,2300", 8))
    }

    @Test
    fun acceptsExactlyOneSatoshiWeiLamportAndSun() {
        for (decimals in listOf(6, 8, 9, 18)) {
            val text = "0." + "0".repeat(decimals - 1) + "1"
            assertEquals(text, DecimalInput.edit(text, decimals, "en"))
            assertEquals("1", Amount.atomic(DecimalInput.canonical(text, decimals), decimals))
            val error =
                assertFailsWith<InputException> { DecimalInput.edit(text + "1", decimals, "en") }
            assertEquals(InputError.DECIMAL_PLACES, error.reason)
        }
    }

    @Test
    fun rejectsMalformedPasteWithoutSilentlyTruncatingOrRounding() {
        for (value in
            listOf(
                "1,234.56",
                "1.234,56",
                "1..2",
                "1,,2",
                "1e-8",
                "-1",
                "+1",
                "NaN",
                "1 000",
                "1\n",
                "1€",
                "１２",
            )) {
            assertFailsWith<InputException>(value) { DecimalInput.edit(value, 18, "pt") }
        }
        assertFailsWith<InputException> { DecimalInput.canonical("0.000000019", 8) }
        assertFailsWith<InputException> { DecimalInput.edit("1.0", 0, "en") }
        assertEquals("123", DecimalInput.canonical("123", 0))
    }

    @Test
    fun editsInTheMiddleAndDeletionsRetainTheDigitsExactly() {
        assertEquals("12,0345", DecimalInput.edit("12,0345", 6, "pt"))
        assertEquals("12,045", DecimalInput.edit("12,045", 6, "pt"))
        assertEquals("12045", DecimalInput.edit("12045", 6, "pt"))
        assertEquals(
            "123456789012345678.123456789012345678",
            DecimalInput.canonical("123456789012345678,123456789012345678", 18),
        )
    }
}
