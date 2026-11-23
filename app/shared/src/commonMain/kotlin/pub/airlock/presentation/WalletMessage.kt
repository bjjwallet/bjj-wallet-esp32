package pub.airlock.presentation

/** UI events are translated when displayed, including after an in-app language change. */
enum class WalletMessage {
    PINS_CONFIGURED,
    SERVER_SAVED,
    PIN_UPDATED,
    TRANSFER_COMPLETED,
    TRANSFER_SUBMITTED,
    TRANSFER_CONFIRMED,
    TRANSFER_REVERTED,
    BITCOIN_SIGNATURE_TEST_COMPLETED,
    DEVICE_COMMAND,
}
