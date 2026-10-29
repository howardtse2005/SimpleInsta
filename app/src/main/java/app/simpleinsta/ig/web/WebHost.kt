package app.simpleinsta.ig.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.simpleinsta.ig.SimpleInstaApp
import org.json.JSONObject

/**
 * One WebView, configured once, kept alive for the process lifetime.
 *
 * The WebView is the floor of the memory budget; everything else in this app is
 * noise next to it. So: no second instance, no recreate-on-tab-switch, and no
 * background work that would keep the renderer warm.
 */
object WebHost {

    /** Callbacks the UI cares about. */
    class Hooks(
        val onEvent: (JSONObject) -> Unit,
        val onPath: (String) -> Unit,
        val onLoading: (Boolean) -> Unit,
        val onBlocked: (String) -> Unit,
        val onPolicyCheck: (Boolean) -> Unit,
        val onFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
        val onRendererGone: () -> Unit
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, app: SimpleInstaApp, hooks: Hooks): WebView {
        val webView = WebView(context)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Reels opened from a DM start without a tap on the video itself, so
            // the default gesture requirement silences them. Instagram's own app
            // plays them with sound; match that.
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadsImagesAutomatically = true
            blockNetworkImage = false
            textZoom = 100
            userAgentString = desktopFreeUserAgent(userAgentString)
        }

        // Match the system theme, otherwise dark mode flashes white before
        // the page paints.
        val nightMode = (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        webView.setBackgroundColor(if (nightMode) Color.BLACK else Color.WHITE)
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        Bridge.attach(webView, hooks.onEvent)

        // Declared before the clients so the page-start fallback can reach it.
        val injector = PolicyInjector(webView, app)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                hooks.onLoading(newProgress < 100)
            }

            /**
             * Without this, `<input type="file">` does nothing at all in a
             * WebView — no picker, no error, no feedback. That is why posting
             * appeared broken: Instagram's create flow opens a file picker and
             * the WebView silently swallowed it.
             */
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                hooks.onFileChooser(filePathCallback, fileChooserParams)
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {

            /** Last http(s) Instagram page we successfully rendered. */
            private var lastGoodUrl: String? = null

            /** Debounce for handoff recovery, so a retrying page cannot loop us. */
            private var lastRecoveryAt = 0L

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val url = uri.toString()

                // Scheme is the question, not host. `intent://instagram.com/_n/mainfeed`
                // has an Instagram host and would otherwise be treated as allowed,
                // then fail with ERR_UNKNOWN_URL_SCHEME.
                if (!UrlGate.isWeb(url)) {
                    when {
                        UrlGate.isExternalApp(url) -> {
                            openExternally(context, uri)
                            return true
                        }

                        // Instagram's own handoff. Never automatic: the chip offers a
                        // hold-to-open control instead (see BlockedChip).
                        UrlGate.isAppHandoff(url) -> {
                            hooks.onBlocked(UrlPolicy.LABEL_APP)
                            return true
                        }

                        // `about:` and `blob:` are how an SPA tears a view down.
                        // Blocking them leaves the page stuck mid-navigation —
                        // which is what "Discard does nothing" looks like.
                        UrlGate.isTeardownScheme(url) -> return false
                    }

                    // javascript:, file:, data: — none have business here.
                    return true
                }

                if (!UrlPolicy.isInstagram(url)) {
                    // Never load third-party pages in a WebView that may hold a
                    // javascript bridge. Hand them to the real browser instead.
                    openExternally(context, uri)
                    return true
                }

                if (UrlPolicy.isBlocked(url)) {
                    hooks.onBlocked(UrlPolicy.labelFor(url))
                    return true
                }

                return false
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                hooks.onPath(UrlPolicy.pathOf(url))
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hooks.onLoading(true)
                // Old WebViews without DOCUMENT_START_SCRIPT: inject as early as
                // we can. Racy by nature, which is why it is only a fallback.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    injector.injectNow()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                hooks.onLoading(false)
                if (UrlGate.isWeb(url) && UrlPolicy.isInstagram(url)) {
                    lastGoodUrl = url
                }
                verifyPolicy(view)
            }

            /**
             * The single most useful diagnostic in the app.
             *
             * Every behaviour depends on the document-start script running. When
             * it does not — unsupported WebView, a rejected origin rule, a syntax
             * error in a policy file — the app silently degrades into a plain
             * browser, and the symptom is "it just doesn't work". So ask the page
             * directly instead of assuming.
             */
            private fun verifyPolicy(view: WebView) {
                view.evaluateJavascript(
                    "(function(){try{var e=window.__SIMPLEINSTA__;return e&&e.version?e.version:'';}catch(x){return '';}})()"
                ) { raw ->
                    val version = raw?.trim()?.trim('"').orEmpty()
                    hooks.onPolicyCheck(version.isNotEmpty() && version != "null")
                }
            }

            /**
             * Safety net. Some WebViews skip shouldOverrideUrlLoading for
             * JS-initiated scheme changes, and the user is left staring at
             * "net::ERR_UNKNOWN_URL_SCHEME". Put them back on the last page we
             * actually rendered — at most once every few seconds, so a page that
             * keeps firing the handoff cannot turn into a reload loop.
             */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (!request.isForMainFrame) return

                val failed = request.url.toString()
                if (UrlGate.isWeb(failed)) return // a real page failed; let it show

                // Only chase app handoffs. `about:blank`, `blob:` and `data:` are
                // ordinary byproducts of SPA teardown — reloading the last page
                // when one fails is what made "Discard" appear to do nothing.
                if (!UrlGate.isAppHandoff(failed)) return

                val now = SystemClock.elapsedRealtime()
                if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) return
                lastRecoveryAt = now

                hooks.onBlocked(UrlPolicy.LABEL_APP)
                val fallback = lastGoodUrl ?: UrlPolicy.HOME
                view.post { view.loadUrl(fallback) }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning true keeps the app alive; the UI recreates the WebView.
                hooks.onRendererGone()
                return true
            }
        }

        injector.install()

        return webView
    }

    /**
     * The UA is the whole ballgame for a wrapper: a raw `; wv` WebView UA gets
     * served a degraded or blocked experience. Strip the markers that identify
     * an embedded browser while keeping the device model.
     */
    private fun desktopFreeUserAgent(original: String?): String {
        val ua = original ?: return FALLBACK_UA
        return ua
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .trim()
    }

    private fun openExternally(context: Context, uri: Uri) {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private const val RECOVERY_COOLDOWN_MS = 4_000L

    private const val FALLBACK_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
}

/**
 * Owns the document-start script so it can be re-registered with a fresh
 * watermark before every navigation.
 *
 * Without this, the config baked into the script at Activity creation goes stale
 * the moment the feed reaches caught-up, and the next reload would fetch two more
 * pages using the old watermark.
 */
class PolicyInjector(private val webView: WebView, private val app: SimpleInstaApp) {

    private var handler: ScriptHandler? = null

    /** @return false when document-start injection is unavailable or rejected. */
    fun install(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "document-start scripts unsupported; using page-start fallback")
            return false
        }
        return runCatching {
            handler?.remove()
            handler = WebViewCompat.addDocumentStartJavaScript(webView, buildScript(), setOf("*"))
        }.onFailure {
            // Swallowing this is how the app becomes a silent plain browser.
            Log.e(TAG, "policy injection rejected", it)
        }.isSuccess
    }

    /** Fallback path: run the policy in the page that is already loading. */
    fun injectNow() {
        runCatching { webView.evaluateJavascript(buildScript(), null) }
            .onFailure { Log.e(TAG, "fallback injection failed", it) }
    }

    private fun buildScript(): String {
        val config = JSONObject().apply {
            put("policyVersion", app.policy.version)
            put("feedPageCap", app.prefs.feedPageCap)
            put("watermark", app.prefs.watermark)
            put("queueMaxAgeDays", app.prefs.queueMaxAgeDays)
            put("debug", app.prefs.debug)
            put("reelFit", if (app.prefs.reelFitFill) "cover" else "contain")
        }
        return app.policy.script(config, bridgeShim = !Bridge.supportsModernApi())
    }

    private companion object {
        const val TAG = "SimpleInsta"
    }
}
