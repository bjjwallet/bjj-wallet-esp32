package pub.airlock.ui

import org.jetbrains.compose.resources.StringResource
import pub.airlock.domain.InputError
import pub.airlock.presentation.WalletMessage
import pub.airlock.resources.*

internal val InputError.resource: StringResource
    get() =
        when (this) {
            InputError.HARDWARE_PIN_REQUIRED -> Res.string.transfer_hardware_pin_required
            InputError.INVALID_WALLET_NAME -> Res.string.wallet_name_invalid
            InputError.HARDWARE_PIN_SETUP_REQUIRED -> Res.string.hardware_pin_setup_required
            InputError.HARDWARE_PIN_FORMAT -> Res.string.hardware_pin_format
            InputError.HARDWARE_PIN_LOCKED -> Res.string.hardware_pin_locked
            InputError.HARDWARE_BUSY -> Res.string.hardware_busy
            InputError.HARDWARE_APPROVAL_REQUIRED -> Res.string.hardware_approval_required
            InputError.HARDWARE_FAILURE -> Res.string.hardware_failure
            InputError.PIN_LENGTH -> Res.string.error_pin_length
            InputError.PIN_CHARACTERS -> Res.string.error_pin_characters
            InputError.PIN_SAME -> Res.string.error_pin_same
            InputError.CURRENT_PIN -> Res.string.error_current_pin
            InputError.INCORRECT_PIN -> Res.string.error_incorrect_pin
            InputError.DESTINATION_REQUIRED -> Res.string.error_destination_required
            InputError.AMOUNT_POSITIVE -> Res.string.error_amount_positive
            InputError.INVALID_AMOUNT -> Res.string.error_invalid_amount
            InputError.DECIMAL_PLACES -> Res.string.error_decimal_places
            InputError.INSUFFICIENT_BALANCE -> Res.string.error_insufficient_balance
            InputError.INSUFFICIENT_FEE_BALANCE -> Res.string.error_insufficient_fee_balance
            InputError.INVALID_QUOTE -> Res.string.error_invalid_quote
            InputError.HTTPS_REQUIRED -> Res.string.error_https_required
            InputError.INVALID_SERVER -> Res.string.error_invalid_server
            InputError.INVALID_LANGUAGE -> Res.string.error_invalid_language
            InputError.INTEGRATION_UNAVAILABLE -> Res.string.error_integration_unavailable
            InputError.MONERO_SCANNER_UNAVAILABLE -> Res.string.monero_scanner_unavailable
            InputError.MONERO_SCAN_PENDING -> Res.string.monero_scan_pending
            InputError.MONERO_SCAN_PAUSED -> Res.string.monero_scan_paused
            InputError.MONERO_SCAN_TIMEOUT -> Res.string.monero_scan_timeout
            InputError.MONERO_SYNC_REJECTED -> Res.string.monero_sync_rejected
            InputError.MONERO_CONNECTION_FAILED -> Res.string.monero_connection_failed
            InputError.MONERO_PAIRING_FAILED -> Res.string.monero_pairing_failed
            InputError.MONERO_KEY_IMAGES_UNAVAILABLE -> Res.string.monero_key_images_unavailable
            InputError.MONERO_VIEW_SYNC_REQUIRED -> Res.string.monero_view_sync_required
            InputError.DEVICE_DISCONNECTED -> Res.string.error_device_disconnected
            InputError.VAULT_OPEN -> Res.string.error_vault_open
            InputError.OPERATION_FAILED -> Res.string.error_operation_failed
            InputError.PUBLIC_DATA_INVALID -> Res.string.error_public_data_invalid
            InputError.PUBLIC_DATA_INCOMPLETE -> Res.string.error_public_data_incomplete
            InputError.PUBLIC_DATA_SYNC_REQUIRED -> Res.string.error_public_data_sync_required
            InputError.PUBLIC_DATA_CHANGED -> Res.string.error_public_data_changed
            InputError.ADDRESS_INDEX_EXHAUSTED -> Res.string.error_address_index_exhausted
            InputError.BLUETOOTH_PERMISSION -> Res.string.error_bluetooth_permission
            InputError.BLUETOOTH_UNAVAILABLE -> Res.string.error_bluetooth_unavailable
            InputError.INVALID_API_KEY -> Res.string.network_invalid_key
            InputError.API_KEY_REQUIRED -> Res.string.network_key_required
            InputError.PROVIDER_UNAVAILABLE -> Res.string.network_unavailable
            InputError.NETWORK_UNSUPPORTED -> Res.string.network_unsupported
            InputError.PROVIDER_RATE_LIMITED -> Res.string.network_rate_limited
            InputError.NETWORK_MISMATCH -> Res.string.network_mismatch
            InputError.INVALID_DESTINATION -> Res.string.network_invalid_destination
            InputError.TRANSACTION_REJECTED -> Res.string.network_rejected
            InputError.SUBMISSION_PENDING -> Res.string.network_pending
            InputError.XRPL_TRUSTLINE_REQUIRED -> Res.string.xrpl_trustline_required
        }

internal val WalletMessage.resource: StringResource
    get() =
        when (this) {
            WalletMessage.PINS_CONFIGURED -> Res.string.message_pins_configured
            WalletMessage.SERVER_SAVED -> Res.string.message_server_saved
            WalletMessage.PIN_UPDATED -> Res.string.message_pin_updated
            WalletMessage.TRANSFER_COMPLETED -> Res.string.message_transfer_completed
            WalletMessage.TRANSFER_SUBMITTED -> Res.string.network_submitted
            WalletMessage.TRANSFER_CONFIRMED -> Res.string.network_confirmed
            WalletMessage.TRANSFER_REVERTED -> Res.string.network_reverted
            WalletMessage.BITCOIN_SIGNATURE_TEST_COMPLETED ->
                Res.string.bitcoin_signature_test_completed
            WalletMessage.DEVICE_COMMAND -> Res.string.message_device_command
        }
