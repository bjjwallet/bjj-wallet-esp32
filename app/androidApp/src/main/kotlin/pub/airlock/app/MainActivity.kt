package pub.airlock.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import pub.airlock.ble.BluetoothAccess
import pub.airlock.di.AppEnvironment
import pub.airlock.di.AppGraph
import pub.airlock.domain.InputError
import pub.airlock.domain.requireInput
import pub.airlock.platform.AndroidPinCrypto
import pub.airlock.platform.AndroidVault
import pub.airlock.ui.AirLockApp

class MainActivity : ComponentActivity() {
    private var permissionResult: CompletableDeferred<Map<String, Boolean>>? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            permissionResult?.complete(result)
        }

    private suspend fun ensureBluetoothAllowed() {
        val requested =
            if (android.os.Build.VERSION.SDK_INT >= 31)
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                )
            else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val missing =
            requested.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) {
            val result = CompletableDeferred<Map<String, Boolean>>()
            permissionResult = result
            try {
                permissionLauncher.launch(missing.toTypedArray())
                requireInput(result.await().values.all { it }, InputError.BLUETOOTH_PERMISSION)
            } finally {
                permissionResult = null
            }
        }
        requireInput(
            requested.all {
                checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            InputError.BLUETOOTH_PERMISSION,
        )
        val manager = getSystemService(android.bluetooth.BluetoothManager::class.java)
        requireInput(manager?.adapter?.isEnabled == true, InputError.BLUETOOTH_UNAVAILABLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val qrScanner = AndroidRecipientScanner(this)
        val graph =
            AppGraph(
                AndroidVault(applicationContext),
                AndroidPinCrypto(),
                AppEnvironment.RELEASE,
                AndroidAddressActions(this, qrScanner),
                bluetoothAccess = BluetoothAccess { ensureBluetoothAllowed() },
                biometrics = AndroidAppBiometrics(this),
                ed25519Verifier = pub.airlock.platform.AndroidEd25519Verifier,
                moneroRuntime = {
                    pub.airlock.data.monero.AndroidMoneroRuntime(applicationContext)
                },
                moneroDiagnostics =
                    if (BuildConfig.DEBUG) { detail -> android.util.Log.w("AirLockMonero", detail) }
                    else null,
                transactionDiagnostics = { event ->
                    if (BuildConfig.DEBUG) android.util.Log.i("AirLockTx", event)
                },
            )
        setContent { AirLockApp(graph) }
    }
}

private class AndroidAddressActions(
    private val activity: android.app.Activity,
    private val scanner: AndroidRecipientScanner,
) : pub.airlock.domain.AddressActions {
    override fun scanQrCode(
        prompt: String,
        cancelLabel: String,
        completion: (pub.airlock.domain.QrScanResult) -> Unit,
    ) = scanner.scan(prompt, cancelLabel, completion)

    override fun openAppSettings() {
        activity.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", activity.packageName, null),
            )
        )
    }

    override fun qrCode(text: String): ByteArray {
        val matrix =
            com.google.zxing.qrcode
                .QRCodeWriter()
                .encode(
                    text,
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    600,
                    600,
                    mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
                )
        val bitmap =
            android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
        val pixels =
            IntArray(600 * 600) { i ->
                if (matrix[i % 600, i / 600]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
        bitmap.setPixels(pixels, 0, 600, 0, 0, 600, 600)
        return java.io.ByteArrayOutputStream().use { bytes ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes)
            bitmap.recycle()
            bytes.toByteArray()
        }
    }

    override fun share(text: String) {
        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
        activity.startActivity(android.content.Intent.createChooser(intent, null))
    }
}

private class AndroidAppBiometrics(private val activity: android.app.Activity) :
    pub.airlock.domain.AppBiometrics {
    override fun available(): Boolean =
        activity
            .getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            ?.canAuthenticate() == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS

    override fun authenticate(reason: String, pinLabel: String, completion: (Boolean) -> Unit) {
        if (!available()) {
            completion(false)
            return
        }
        var completed = false
        fun finish(value: Boolean) {
            if (!completed) {
                completed = true
                completion(value)
            }
        }
        android.hardware.biometrics.BiometricPrompt.Builder(activity)
            .setTitle(activity.getText(R.string.app_name))
            .setSubtitle(reason)
            .setNegativeButton(pinLabel, activity.mainExecutor) { _, _ -> finish(false) }
            .build()
            .authenticate(
                android.os.CancellationSignal(),
                activity.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult
                    ) = finish(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                        finish(false)
                },
            )
    }
}
