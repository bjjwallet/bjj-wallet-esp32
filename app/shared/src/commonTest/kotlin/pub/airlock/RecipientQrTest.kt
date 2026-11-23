package pub.airlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pub.airlock.domain.RecipientQr

class RecipientQrTest {
    private val btc = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"

    @Test
    fun readsPlainAddressWithoutChangingCase() {
        assertEquals(btc, RecipientQr.address("  $btc\n", "bitcoin"))
        val legacy = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
        assertEquals(legacy, RecipientQr.address(legacy, "bitcoin"))
    }

    @Test
    fun extractsOnlyTheAddressFromBitcoinPaymentQr() {
        assertEquals(
            btc,
            RecipientQr.address("bitcoin:$btc?amount=1&label=Test%20wallet", "bitcoin"),
        )
        assertEquals(btc, RecipientQr.address("BITCOIN:$btc", "bitcoin"))
    }

    @Test
    fun refusesUnsupportedRequiredPaymentInstructions() {
        assertNull(RecipientQr.address("bitcoin:$btc?req-something=1", "bitcoin"))
        assertNull(RecipientQr.address("bitcoin:$btc?%72eq-something=1", "bitcoin"))
    }

    @Test
    fun doesNotTreatLinksOrOtherNetworksAsRecipientAddresses() {
        for (payload in
            listOf(
                "https://example.com/$btc",
                "bitcoin://$btc",
                "bitcoin:?r=https://example.com",
                "lightning:lnbc1234567890",
                "bitcoin:$btc#other",
                "bitcoin:abc def123456789",
                "bitcoin:" + "x".repeat(257),
            )) {
            assertNull(RecipientQr.address(payload, "bitcoin"), payload)
        }
        assertNull(RecipientQr.address("litecoin:$btc", "bitcoin"))
        assertNull(RecipientQr.address("bitcoin:$btc", "litecoin"))
    }

    @Test
    fun preservesCashAddressNetworkPrefix() {
        val cash = "qq07h6jcvz5c7vvh92zv4mslwpkfwmldsgq7a9mfn5"
        assertEquals(
            "bitcoincash:$cash",
            RecipientQr.address("bitcoincash:$cash?amount=1", "bitcoincash"),
        )
    }
}
