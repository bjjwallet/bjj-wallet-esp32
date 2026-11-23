package pub.airlock.domain

object TransferTestConfig {
    /**
     * Set true and rebuild to allow Amount entry above the available balance. Bitcoin
     * insufficient-funding tests use an explicitly labelled synthetic input, real hardware signing
     * and an API broadcast attempt. The input cannot be spent. No signature checks are bypassed.
     * Set false for normal funded payments only.
     */
    const val TEST_MODE = true
}
