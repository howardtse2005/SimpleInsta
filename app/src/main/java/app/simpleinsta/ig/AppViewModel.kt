package app.simpleinsta.ig

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.simpleinsta.ig.core.QueueState
import app.simpleinsta.ig.core.Reel
import app.simpleinsta.ig.core.StopReason
import app.simpleinsta.ig.core.Watermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val simpleinsta = application as SimpleInstaApp
    private val io = Dispatchers.IO

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _lastStop = MutableStateFlow<StopReason?>(null)
    val lastStop: StateFlow<StopReason?> = _lastStop.asStateFlow()

    /** One line of feed diagnostics for the debug sheet. */
    private val _feedStats = MutableStateFlow("no feed response yet")
    val feedStats: StateFlow<String> = _feedStats.asStateFlow()

    private val _followingTab = MutableStateFlow<Boolean?>(null)
    val followingTab: StateFlow<Boolean?> = _followingTab.asStateFlow()

    /** null = not reported yet. false is the "policy is attached but blind" case. */
    private val _feedSeen = MutableStateFlow<Boolean?>(null)
    val feedSeen: StateFlow<Boolean?> = _feedSeen.asStateFlow()

    private val _degradation = MutableStateFlow(DEGRADATION_FULL)
    val degradation: StateFlow<Int> = _degradation.asStateFlow()

    /** True when Instagram is showing its login page rather than a feed. */
    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut.asStateFlow()

    /**
     * Rolling log from the page. This is the only window into why the policy
     * does or does not work on a device, so it is always on rather than behind a
     * build flag.
     */
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    /** Set when a reel link is tapped, so the queue can open at that reel. */
    private var pendingShortcode: String? = null

    init {
        _degradation.value = simpleinsta.prefs.degradation
        refreshPending()
    }

    /* ------------------------------------------------------------------ *
     * Queue
     * ------------------------------------------------------------------ */

    fun onReelsExtracted(reels: List<Reel>) {
        if (reels.isEmpty()) return
        viewModelScope.launch(io) {
            simpleinsta.store.upsertAll(reels)
            simpleinsta.store.pruneSeen()
            val count = simpleinsta.store.unplayedCount(simpleinsta.prefs.queueMaxAgeDays)
            _pending.value = count
        }
    }

    /** Snapshot the unplayed set. Called once when the Queue tab opens. */
    fun beginSession() {
        viewModelScope.launch(io) {
            simpleinsta.store.pruneOldQueue(simpleinsta.prefs.queueMaxAgeDays)
            val items = simpleinsta.store.unplayed(simpleinsta.prefs.queueMaxAgeDays)

            val wanted = pendingShortcode
            pendingShortcode = null
            val start = if (wanted.isNullOrBlank()) {
                0
            } else {
                items.indexOfFirst { it.shortcode == wanted }.coerceAtLeast(0)
            }

            _queue.value = QueueState(items = items, index = start, started = true)
            _pending.value = items.size
        }
    }

    /**
     * A reel link was tapped inside Instagram. Never let the pager open — jump
     * the queue to that reel instead. If it is not in the queue (already
     * watched, or the thread payload changed shape) the queue simply starts at
     * the beginning rather than failing.
     */
    fun openReel(shortcode: String) {
        pendingShortcode = shortcode
        beginSession()
    }

    fun next() {
        val state = _queue.value
        val current = state.current ?: return
        viewModelScope.launch(io) { simpleinsta.store.markPlayed(current.mediaId) }
        _queue.value = state.copy(index = state.index + 1)
        refreshPending()
    }

    fun prev() {
        val state = _queue.value
        if (state.index == 0) return
        _queue.value = state.copy(index = state.index - 1)
    }

    fun endSession() {
        _queue.value = QueueState()
    }

    fun markAllCaughtUp() {
        viewModelScope.launch(io) {
            simpleinsta.store.markAllPlayed()
            _pending.value = 0
            _queue.value = _queue.value.copy(index = _queue.value.items.size)
        }
    }

    private fun refreshPending() {
        viewModelScope.launch(io) {
            _pending.value = simpleinsta.store.unplayedCount(simpleinsta.prefs.queueMaxAgeDays)
        }
    }

    /* ------------------------------------------------------------------ *
     * Feed
     * ------------------------------------------------------------------ */

    /**
     * Called when the policy engine ends the feed.
     *
     * The watermark only advances here — i.e. only when a session actually
     * finished. An interrupted session leaves it untouched so nothing is
     * silently swallowed.
     */
    fun onFeedCaughtUp(reason: StopReason, oldestSeen: Long) {
        _lastStop.value = reason
        viewModelScope.launch(io) {
            val now = System.currentTimeMillis() / 1000
            simpleinsta.prefs.watermark = Watermark.advance(
                current = simpleinsta.prefs.watermark,
                oldestSeen = oldestSeen,
                now = now,
                reason = reason
            )
            simpleinsta.prefs.lastCaughtUpAt = now
        }
    }

    /**
     * Degradation ladder.
     *
     * The distinction that matters: "we read feed data and got nothing usable"
     * is a failure worth warning about. "We never saw feed data" is usually just
     * a logged-out page, and warning about it is noise — the sign-in prompt
     * covers that case instead.
     */
    fun onHealth(feedSeen: Boolean, payloads: Int, loggedOut: Boolean, path: String) {
        _feedSeen.value = feedSeen
        _loggedOut.value = loggedOut

        // Health is reported from whatever page is open. Failing to find posts
        // on the Messages tab is not a feed failure.
        val onFeedRoute = path.isEmpty() || path == "/" || path == "/feed/"

        viewModelScope.launch(io) {
            val prefs = simpleinsta.prefs
            when {
                loggedOut -> Unit

                feedSeen -> {
                    prefs.consecutiveFailures = 0
                    prefs.degradation = DEGRADATION_FULL
                }

                payloads > 0 && onFeedRoute -> {
                    val failures = prefs.consecutiveFailures + 1
                    prefs.consecutiveFailures = failures
                    if (failures >= 3 && prefs.degradation < DEGRADATION_HIDE_ONLY) {
                        prefs.degradation = DEGRADATION_HIDE_ONLY
                    }
                }

                // Not on the feed, or never saw a feed-shaped payload.
                else -> Unit
            }
            _degradation.value = prefs.degradation
        }
    }

    /** Clear a watermark that a bad payload may have poisoned. */
    fun resetLimits() {
        viewModelScope.launch(io) {
            val prefs = simpleinsta.prefs
            prefs.watermark = 0
            prefs.degradation = DEGRADATION_FULL
            prefs.consecutiveFailures = 0

            _degradation.value = DEGRADATION_FULL
            _feedSeen.value = null
            _feedStats.value = "no feed response yet"
        }
    }

    /**
     * The status line has to separate "no feed has loaded" from "a feed loaded
     * and we found nothing in it". Reporting `kept 0` for both is how a working
     * build looks broken, and vice versa.
     */
    fun onFeedStats(kept: Int, removed: Int, pages: Int, payloads: Int, caughtUp: Boolean) {
        _feedStats.value = if (payloads == 0 && kept == 0 && removed == 0) {
            "not loaded yet"
        } else {
            "payloads $payloads · kept $kept · removed $removed · pages $pages" +
                if (caughtUp) " · ended" else ""
        }
    }

    fun onFollowingTab(found: Boolean) {
        _followingTab.value = found
    }

    fun onLog(name: String, detail: String) {
        val line = if (detail.isBlank()) name else "$name: $detail"
        val current = _logLines.value
        // Scroll and re-render produce repeats; only the first is interesting.
        if (current.lastOrNull() == line) return
        _logLines.value = (current + line).takeLast(LOG_CAP)
    }

    fun statusLine(): String = buildString {
        val prefs = simpleinsta.prefs
        append("policy v").append(simpleinsta.policy.version)
        append(" · cap ").append(prefs.feedPageCap)
        append(" · watermark ")
        append(if (prefs.watermark == 0L) "none" else prefs.watermark.toString())
        append(" · ")
        append(
            when (prefs.degradation) {
                DEGRADATION_HIDE_ONLY -> "HIDE_ONLY"
                DEGRADATION_FEED_ONLY -> "FEED_ONLY"
                else -> "FULL"
            }
        )
        append("\nfeed: ").append(_feedStats.value)
        append("\nfollowing tab: ").append(
            when (_followingTab.value) {
                true -> "selected"
                false -> "not found"
                null -> "not checked"
            }
        )
    }

    companion object {
        const val DEGRADATION_FULL = 0
        const val DEGRADATION_FEED_ONLY = 1
        const val DEGRADATION_HIDE_ONLY = 2
        private const val LOG_CAP = 60
    }
}
