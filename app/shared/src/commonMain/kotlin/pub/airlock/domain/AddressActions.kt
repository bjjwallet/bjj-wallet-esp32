package pub.airlock.domain

/** Local platform capabilities; the same implementation is used in debug and release. */
interface AddressActions {
    fun qrCode(text: String): ByteArray

    fun share(text: String)

    fun scanQrCode(prompt: String, cancelLabel: String, completion: (QrScanResult) -> Unit)

    fun openAppSettings()
}

/** A cancelled scan has no text and no error. Camera frames never leave the device. */
data class QrScanResult(
    val text: String? = null,
    val permissionDenied: Boolean = false,
    val failed: Boolean = false,
)
