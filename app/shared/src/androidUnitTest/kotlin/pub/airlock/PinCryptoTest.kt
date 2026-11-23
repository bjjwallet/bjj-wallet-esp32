package pub.airlock

import kotlin.test.*
import pub.airlock.platform.AndroidPinCrypto

class PinCryptoTest {
    @Test
    fun matchesIndependentPbkdf2Sha256VectorIncludingUnicode() {
        val actual = AndroidPinCrypto().derive("airlock🔒", "00112233445566778899aabbccddeeff")
        assertEquals("abe4145d8db8eb883ceb169061f4542a0698b2a395c52f49d2df0723b3e6d0a3", actual)
    }

    @Test
    fun randomSaltHasExpectedSizeAndChanges() {
        val crypto = AndroidPinCrypto()
        val a = crypto.randomHex(16)
        val b = crypto.randomHex(16)
        assertEquals(32, a.length)
        assertNotEquals(a, b)
        assertTrue(a.all { it in "0123456789abcdef" })
    }
}
