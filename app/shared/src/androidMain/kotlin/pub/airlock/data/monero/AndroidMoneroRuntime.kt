package pub.airlock.data.monero

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json

class AndroidMoneroRuntime(context: Context) : MoneroRuntime {
    private val view = WebView(context.applicationContext)

    @SuppressLint("SetJavaScriptEnabled")
    override fun load(files: Map<String, ByteArray>) {
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
        }
        view.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    true

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse {
                    val url = request.url
                    val path = url.path.orEmpty()
                    val bytes =
                        if (
                            url.scheme == "https" &&
                                url.host == host &&
                                url.port == -1 &&
                                url.query == null &&
                                request.method == "GET"
                        )
                            files[path]
                        else null
                    return if (bytes != null)
                        WebResourceResponse(
                            if (path == "/index.html") "text/html" else "text/javascript",
                            "UTF-8",
                            bytes.inputStream(),
                        )
                    else
                        WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            403,
                            "Forbidden",
                            emptyMap(),
                            byteArrayOf().inputStream(),
                        )
                }
            }
        view.loadUrl("https://$host/index.html")
    }

    override suspend fun evaluate(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resumeWith(
                        Result.success(
                            result?.let {
                                runCatching { Json.decodeFromString<String>(it) }.getOrNull()
                            }
                        )
                    )
                }
            }
        }

    override fun close() {
        view.stopLoading()
        view.destroy()
    }

    private companion object {
        const val host = "airlock-monero.invalid"
    }
}
