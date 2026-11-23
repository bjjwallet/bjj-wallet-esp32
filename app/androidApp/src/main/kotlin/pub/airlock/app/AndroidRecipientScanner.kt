package pub.airlock.app

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import pub.airlock.domain.QrScanResult

/** In-activity scanner: opening it does not background (and lock) the wallet. */
internal class AndroidRecipientScanner(private val activity: ComponentActivity) :
    DefaultLifecycleObserver {
    private var completion: ((QrScanResult) -> Unit)? = null
    private var prompt = ""
    private var cancelLabel = ""
    private var dialog: Dialog? = null
    private var preview: DecoratedBarcodeView? = null
    private val permission =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (completion != null) {
                if (granted) showCamera() else finish(QrScanResult(permissionDenied = true))
            }
        }

    init {
        activity.lifecycle.addObserver(this)
    }

    fun scan(prompt: String, cancelLabel: String, completion: (QrScanResult) -> Unit) {
        if (this.completion != null) return
        this.completion = completion
        this.prompt = prompt
        this.cancelLabel = cancelLabel
        when {
            hasPermission() -> showCamera()
            activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                val rationale =
                    AlertDialog.Builder(activity)
                        .setMessage(prompt)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            permission.launch(Manifest.permission.CAMERA)
                        }
                        .setNegativeButton(cancelLabel) { _, _ -> finish(QrScanResult()) }
                        .setOnCancelListener { finish(QrScanResult()) }
                        .create()
                dialog = rationale
                rationale.show()
            }
            else -> permission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasPermission() =
        activity.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun showCamera() {
        if (!hasPermission()) {
            finish(QrScanResult(permissionDenied = true))
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            finish(QrScanResult())
            return
        }
        try {
            val camera =
                DecoratedBarcodeView(activity).apply {
                    setStatusText("")
                    decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                }
            preview = camera
            val content =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.BLACK)
                    fitsSystemWindows = true
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    addView(
                        TextView(activity).apply {
                            text = prompt
                            setTextColor(Color.WHITE)
                            textSize = 18f
                            setPadding(padding, padding, padding, padding)
                        }
                    )
                    addView(
                        camera,
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                    )
                    addView(
                        Button(activity).apply {
                            text = cancelLabel
                            setOnClickListener { finish(QrScanResult()) }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            val sheet =
                Dialog(activity, android.R.style.Theme_Material_NoActionBar).apply {
                    setContentView(content)
                    setOnCancelListener { finish(QrScanResult()) }
                    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            dialog = sheet
            camera.barcodeView.addStateListener(
                object : CameraPreview.StateListener {
                    override fun previewSized() = Unit

                    override fun previewStarted() = Unit

                    override fun previewStopped() = Unit

                    override fun cameraClosed() = Unit

                    override fun cameraError(error: Exception) = finish(QrScanResult(failed = true))
                }
            )
            camera.decodeSingle(
                object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        finish(QrScanResult(text = result.text))
                    }
                }
            )
            sheet.show()
            sheet.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            camera.resume()
        } catch (_: SecurityException) {
            finish(QrScanResult(permissionDenied = true))
        } catch (_: RuntimeException) {
            finish(QrScanResult(failed = true))
        }
    }

    private fun finish(result: QrScanResult) {
        val callback = completion ?: return
        completion = null
        preview?.pause()
        preview = null
        dialog?.dismiss()
        dialog = null
        callback(result)
    }

    override fun onPause(owner: LifecycleOwner) {
        preview?.pause()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (preview != null) {
            if (hasPermission()) preview?.resume()
            else finish(QrScanResult(permissionDenied = true))
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        finish(QrScanResult())
    }

    override fun onDestroy(owner: LifecycleOwner) {
        finish(QrScanResult())
        activity.lifecycle.removeObserver(this)
    }
}
