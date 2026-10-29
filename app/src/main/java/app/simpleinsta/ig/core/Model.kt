package app.simpleinsta.ig.core

/**
 * A reel that a human deliberately sent, extracted from a DM thread payload.
 *
 * Deliberately contains no engagement metadata — no like counts, no view counts,
 * no comment counts. The player has nothing to gamify.
 */
data class Reel(
    val mediaId: String,
    val shortcode: String,
    val author: String,
    val sender: String,
    val sentAt: Long,
    val videoUrl: String,
    val thumbUrl: String,
    val durationMs: Int,
    val playedAt: Long? = null
) {
    val label: String
        get() = when {
            sender.isNotBlank() && author.isNotBlank() && sender != author -> "@$sender sent @$author"
            sender.isNotBlank() -> "@$sender"
            author.isNotBlank() -> "@$author"
            else -> "a friend"
        }
}

/** Why the feed stopped. Drives how the watermark advances. */
enum class StopReason {
    /** Reached content older than your last completed visit. */
    WATERMARK,

    /** Hit the page cap while content was still fresh. */
    CAP,

    /** Instagram genuinely ran out of posts. */
    EXHAUSTED,

    UNKNOWN;

    companion object {
        fun from(raw: String?): StopReason = when (raw) {
            "watermark" -> WATERMARK
            "cap" -> CAP
            "exhausted" -> EXHAUSTED
            else -> UNKNOWN
        }
    }
}

/** Snapshot of the queue player. `current == null` means caught up. */
data class QueueState(
    val items: List<Reel> = emptyList(),
    val index: Int = 0,
    val started: Boolean = false
) {
    val current: Reel? get() = items.getOrNull(index)
    val done: Boolean get() = started && index >= items.size
    val position: String get() = if (items.isEmpty()) "" else "${(index + 1).coerceAtMost(items.size)} of ${items.size}"
}
