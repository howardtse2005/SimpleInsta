package app.simpleinsta.ig.web

/**
 * Scheme classification. Pure string work, no Android types, so it is unit
 * testable — which matters, because this is the difference between "the app
 * opens Instagram" and "the app does not open Instagram".
 *
 * The bug this exists to prevent: `intent://instagram.com/_n/mainfeed` has an
 * Instagram *host*, so a host-only check says "allowed" and the WebView tries to
 * load it, producing net::ERR_UNKNOWN_URL_SCHEME. Host is not the question.
 * Scheme is.
 */
object UrlGate {

    /** Instagram's own app-handoff links. Never honoured, never loaded. */
    private val APP_HANDOFF = setOf(
        "intent",
        "instagram",
        "fb",
        "fb-messenger",
        "market",
        "whatsapp"
    )

    /** Schemes that belong to another app and should be handed to the system. */
    private val EXTERNAL = setOf("mailto", "tel", "sms", "smsto")

    /**
     * RFC 3986 scheme extraction: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ).
     * Returns "" for relative or malformed input, which callers treat as "not a
     * navigation we understand".
     */
    fun schemeOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val colon = url.indexOf(':')
        if (colon <= 0) return ""

        val raw = url.substring(0, colon)
        if (!raw[0].isLetter()) return ""
        for (ch in raw) {
            if (!ch.isLetterOrDigit() && ch != '+' && ch != '-' && ch != '.') return ""
        }
        return raw.lowercase()
    }

    /** Only these two may ever be loaded inside the WebView. */
    fun isWeb(url: String?): Boolean {
        val scheme = schemeOf(url)
        return scheme == "http" || scheme == "https"
    }

    fun isAppHandoff(url: String?): Boolean = schemeOf(url) in APP_HANDOFF

    /** Schemes an SPA uses while unmounting a view. Safe to let the WebView handle. */
    fun isTeardownScheme(url: String?): Boolean {
        val scheme = schemeOf(url)
        return scheme == "about" || scheme == "blob"
    }

    fun isExternalApp(url: String?): Boolean = schemeOf(url) in EXTERNAL
}
