package app.simpleinsta.ig.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Three knobs, all of them load-bearing, none of them exposed in the UI.
 *
 * The plan is explicit about this: a settings screen is a bypass surface. If the
 * user can turn the cap off in-app, the cap is off by design. These values are
 * changed only from the debug sheet, which is behind a long-press.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("simpleinsta", Context.MODE_PRIVATE)

    /** Epoch seconds. 0 means "never completed a session". */
    var watermark: Long
        get() = sp.getLong(KEY_WATERMARK, 0L)
        set(value) = sp.edit().putLong(KEY_WATERMARK, value).apply()

    /** How many feed pages a single session may fetch. */
    var feedPageCap: Int
        get() = sp.getInt(KEY_PAGE_CAP, 2)
        set(value) = sp.edit().putInt(KEY_PAGE_CAP, value.coerceIn(1, 10)).apply()

    /** Reels older than this drop out of the queue. */
    var queueMaxAgeDays: Int
        get() = sp.getInt(KEY_QUEUE_AGE, 14)
        set(value) = sp.edit().putInt(KEY_QUEUE_AGE, value.coerceIn(1, 90)).apply()

    /** 0 = FULL, 1 = FEED_ONLY, 2 = HIDE_ONLY. */
    var degradation: Int
        get() = sp.getInt(KEY_DEGRADATION, 0)
        set(value) = sp.edit().putInt(KEY_DEGRADATION, value.coerceIn(0, 2)).apply()

    /** Consecutive loads where the policy never saw a feed response. */
    var consecutiveFailures: Int
        get() = sp.getInt(KEY_FAILURES, 0)
        set(value) = sp.edit().putInt(KEY_FAILURES, value).apply()

    var lastCaughtUpAt: Long
        get() = sp.getLong(KEY_CAUGHT_UP, 0L)
        set(value) = sp.edit().putLong(KEY_CAUGHT_UP, value).apply()

    var debug: Boolean
        get() = sp.getBoolean(KEY_DEBUG, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG, value).apply()

    /**
     * One-time repair.
     *
     * Builds before limits v2 could mistake the logged-out landing page for an
     * empty feed, report "caught up", and move the watermark to *now*. That
     * watermark then makes every future feed look stale, so the feed ends after
     * one page. Clearing it once on upgrade is safer than asking someone to find
     * a reset button.
     */
    fun migrateIfNeeded() {
        val stored = sp.getInt(KEY_LIMITS_VERSION, 0)
        if (stored >= LIMITS_VERSION) return

        sp.edit()
            .putLong(KEY_WATERMARK, 0L)
            .putInt(KEY_DEGRADATION, 0)
            .putInt(KEY_FAILURES, 0)
            .putInt(KEY_LIMITS_VERSION, LIMITS_VERSION)
            .apply()
    }

    /**
     * Whether the deliberate "hold to open Instagram" door is available.
     *
     * This does NOT re-enable automatic app handoffs — Instagram still cannot
     * drag you out of the wrapper. It only decides whether the escape hatch is
     * offered when a handoff has already been refused.
     */
    /**
     * Reel framing. `true` fills the screen and crops the edges; `false`
     * shows the whole frame with bars. There is no third option, so it is a
     * preference rather than something to keep guessing at.
     */
    var reelFitFill: Boolean
        get() = sp.getBoolean(KEY_REEL_FIT, true)
        set(value) = sp.edit().putBoolean(KEY_REEL_FIT, value).apply()

    var allowAppHandoff: Boolean
        get() = sp.getBoolean(KEY_ALLOW_HANDOFF, true)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_HANDOFF, value).apply()

    private companion object {
        const val KEY_WATERMARK = "watermark"
        const val KEY_PAGE_CAP = "page_cap"
        const val KEY_QUEUE_AGE = "queue_age_days"
        const val KEY_DEGRADATION = "degradation"
        const val KEY_FAILURES = "failures"
        const val KEY_CAUGHT_UP = "caught_up_at"
        const val KEY_DEBUG = "debug"
        const val KEY_ALLOW_HANDOFF = "allow_handoff"
        const val KEY_LIMITS_VERSION = "limits_version"
        const val KEY_REEL_FIT = "reel_fit_fill"

        /** Bump when stored limits may have been written by a buggy build. */
        const val LIMITS_VERSION = 4
    }
}
