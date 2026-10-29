package app.simpleinsta.ig.core

/**
 * Watermark arithmetic. Kept pure and free of Android types so it can be unit
 * tested — this is the part that decides whether you see a post twice or never
 * see it at all, and it is easy to get subtly wrong.
 */
object Watermark {

    /**
     * Where the watermark should move to after a completed feed session.
     *
     * The rule that matters: only advance when the session actually *finished*.
     * An interrupted session (user closed the app mid-scroll) must leave the
     * watermark alone, otherwise everything below the interruption point is
     * silently swallowed and never shown again.
     *
     * - Exhausted: Instagram ran out. Everything is seen. Jump to now.
     * - Watermark hit: resume from the oldest post we actually loaded, so the
     *   next session picks up where this one stopped instead of skipping ahead.
     * - Cap hit: same, but we never reached old content, so resume from the
     *   oldest loaded post too.
     *
     * @param current existing watermark, epoch seconds (0 = none)
     * @param oldestSeen oldest post timestamp loaded this session (0 = unknown)
     * @param now current epoch seconds
     */
    fun advance(current: Long, oldestSeen: Long, now: Long, reason: StopReason): Long {
        // A watermark in the future is not a watermark. It happens when an empty
        // payload is mistaken for "end of feed" (see 10_feed.js) or after a clock
        // change. Treat it as unset rather than letting it hide the feed forever.
        val sane = if (current > now + CLOCK_SKEW_SECONDS) 0L else current

        val target = when (reason) {
            StopReason.EXHAUSTED -> now
            StopReason.WATERMARK, StopReason.CAP -> if (oldestSeen > 0) oldestSeen else now
            StopReason.UNKNOWN -> return sane
        }
        // Never move backwards: a clock change or a stale response must not
        // re-open content the user has already cleared.
        return maxOf(sane, target)
    }

    /** Anything further ahead than this is assumed to be corruption, not intent. */
    const val CLOCK_SKEW_SECONDS = 60L

    /**
     * Should this feed response be allowed to continue paginating?
     *
     * @param oldestOnPage oldest post timestamp on the current page
     * @param watermark stored watermark
     * @param pagesFetched pages fetched so far, including this one
     * @param pageCap hard ceiling
     */
    fun shouldContinue(oldestOnPage: Long, watermark: Long, pagesFetched: Int, pageCap: Int): Boolean {
        if (pagesFetched >= pageCap) return false
        if (watermark > 0 && oldestOnPage > 0 && oldestOnPage <= watermark) return false
        return true
    }
}
