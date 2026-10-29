package app.simpleinsta.ig.web

import android.net.Uri

/**
 * Route policy. This is the only layer Instagram cannot undo, because it runs
 * before a navigation is committed.
 *
 * The distinction that matters:
 *   - DOM affordances are hidden with CSS (deleting React nodes crashes the page).
 *   - Routes are refused here, natively.
 *
 * Hiding a button that still has a live route leaves a reachable surface for a
 * stray tap or an internal redirect. Refusing the route is the version that holds.
 */
object UrlPolicy {

    const val HOME = "https://www.instagram.com/"

    /** Shown when Instagram tries to bounce us into its own app. */
    const val LABEL_APP = "The Instagram app"

    private const val HOST_PRIMARY = "www.instagram.com"
    private const val HOST_BARE = "instagram.com"

    /**
     * Plural `reels` and singular `reel/<code>` both go: Instagram's single-reel
     * view is still a vertical pager that rolls into recommendations when the
     * sent reel ends. The queue replaces it entirely.
     */
    private val BLOCKED_PREFIXES = listOf(
        "/reels",
        "/reel/",
        "/explore"
    )

    /**
     * The one app deep link we refuse. Blocking the whole `/_n/` namespace
     * was too broad: other routes under it are ordinary web navigation, and
     * blocking them can break flows such as creating a post.
     */
    private const val APP_LINK_PATH = "/_n/mainfeed"

    fun isInstagram(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host == HOST_PRIMARY || host == HOST_BARE || host.endsWith(".$HOST_BARE")
    }

    /**
     * @param allowSingleReel true while showing the locked fallback viewer, so a
     *        single `/reel/<code>/` can load. Never enables the `/reels` pager.
     */
    fun isBlocked(url: String?, allowSingleReel: Boolean = false): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!isInstagram(url)) return false

        val path = uri.path ?: return false

        if (allowSingleReel && (path.startsWith("/reel/") || path.startsWith("/p/"))) {
            return false
        }

        if (path.startsWith(APP_LINK_PATH)) return true

        return BLOCKED_PREFIXES.any { prefix ->
            path == prefix || path.startsWith("$prefix/")
        }
    }

    /** Human name for the blocked-navigation chip. */
    fun labelFor(url: String?): String {
        val path = pathOf(url)
        return when {
            path.startsWith(APP_LINK_PATH) -> LABEL_APP
            path.startsWith("/reels") -> "Reels"
            path.startsWith("/explore") -> "Explore"
            path.isBlank() -> "That page"
            else -> path
        }
    }

    /** Canonical form used to decide whether a tab switch needs a reload. */
    fun pathOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return runCatching { Uri.parse(url).path }.getOrNull().orEmpty()
    }
}
