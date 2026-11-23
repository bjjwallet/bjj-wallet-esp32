@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package pub.airlock.data.monero

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.create
import platform.WebKit.*
import platform.darwin.NSObject

internal class IosMoneroRuntime : MoneroRuntime {
    private val handler = LocalFiles()
    private val navigation = LocalNavigation()
    private var view: WKWebView? = null

    override fun load(files: Map<String, ByteArray>) {
        handler.files = files
        val config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        config.setURLSchemeHandler(handler, forURLScheme = scheme)
        view =
            WKWebView(frame = CGRectZero.readValue(), configuration = config).also {
                it.navigationDelegate = navigation
                it.loadRequest(NSURLRequest(uRL = NSURL(string = "$scheme://local/index.html")))
            }
    }

    override suspend fun evaluate(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            val web = view
            if (web == null) continuation.resumeWith(Result.success(null))
            else
                web.evaluateJavaScript(script) { value, _ ->
                    if (continuation.isActive)
                        continuation.resumeWith(Result.success(value as? String))
                }
        }

    override fun close() {
        view?.stopLoading()
        view?.navigationDelegate = null
        view = null
        handler.files = emptyMap()
    }

    private class LocalNavigation : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(
            webView: WKWebView,
            decidePolicyForNavigationAction: WKNavigationAction,
            decisionHandler: (WKNavigationActionPolicy) -> Unit,
        ) {
            val url = decidePolicyForNavigationAction.request.URL
            decisionHandler(
                if (url?.scheme == scheme && url.host == "local" && url.path == "/index.html")
                    WKNavigationActionPolicy.WKNavigationActionPolicyAllow
                else WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            )
        }
    }

    private class LocalFiles : NSObject(), WKURLSchemeHandlerProtocol {
        var files: Map<String, ByteArray> = emptyMap()

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
            val url = startURLSchemeTask.request.URL
            val path = url?.path.orEmpty()
            val bytes = if (url?.host == "local" && url.query == null) files[path] else null
            if (url == null || bytes == null) {
                startURLSchemeTask.didFailWithError(
                    NSError.errorWithDomain("AirLockMonero", 1, null)
                )
                return
            }
            val mime = if (path == "/index.html") "text/html" else "text/javascript"
            startURLSchemeTask.didReceiveResponse(
                NSURLResponse(url, mime, bytes.size.toLong(), "UTF-8")
            )
            bytes.usePinned {
                startURLSchemeTask.didReceiveData(
                    NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong())
                )
            }
            startURLSchemeTask.didFinish()
        }

        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) = Unit
    }

    private companion object {
        const val scheme = "airlock-monero"
    }
}
