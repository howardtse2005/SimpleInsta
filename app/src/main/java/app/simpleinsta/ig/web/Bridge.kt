package app.simpleinsta.ig.web

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * JS -> Kotlin channel.
 *
 * Prefers `addWebMessageListener`, which scopes the injected object to an origin
 * allowlist. Falls back to `addJavascriptInterface` only on WebViews old enough
 * to lack it; that fallback exposes the object to every document in the WebView,
 * which is why [WebHost] refuses to load non-Instagram pages.
 */
object Bridge {

    const val NAME = "simpleinstaBridge"

    private val ORIGINS = setOf(
        "https://www.instagram.com",
        "https://instagram.com"
    )

    fun supportsModernApi(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    fun attach(webView: WebView, onEvent: (JSONObject) -> Unit) {
        if (supportsModernApi()) {
            WebViewCompat.addWebMessageListener(webView, NAME, ORIGINS) { _, message, _, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                runCatching { JSONObject(data) }.onSuccess(onEvent)
            }
        } else {
            webView.addJavascriptInterface(NativeBridge(onEvent), "SimpleInstaNative")
        }
    }

    class NativeBridge(private val onEvent: (JSONObject) -> Unit) {
        @JavascriptInterface
        fun post(raw: String) {
            runCatching { JSONObject(raw) }.onSuccess(onEvent)
        }
    }
}
