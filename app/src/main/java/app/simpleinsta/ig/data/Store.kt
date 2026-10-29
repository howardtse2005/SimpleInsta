package app.simpleinsta.ig.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.simpleinsta.ig.core.Reel

/**
 * Plain SQLite rather than Room.
 *
 * Three tiny tables, one bounded LRU, no migrations expected. Room would add a
 * Kotlin symbol processor and a version-alignment constraint between the Kotlin
 * and KSP plugins for zero benefit at this size. The schema is identical to the
 * plan; only the access layer changed.
 *
 * Nothing here stores media. URLs and shortcodes only — the app must never
 * become a cache of someone else's content.
 */
class Store(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE queue_item (
                media_id    TEXT PRIMARY KEY,
                shortcode   TEXT NOT NULL,
                author      TEXT NOT NULL DEFAULT '',
                sender      TEXT NOT NULL DEFAULT '',
                sent_at     INTEGER NOT NULL DEFAULT 0,
                video_url   TEXT NOT NULL DEFAULT '',
                thumb_url   TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                played_at   INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_queue_unplayed ON queue_item(played_at, sent_at)")
        db.execSQL(
            """
            CREATE TABLE seen_media (
                media_id TEXT PRIMARY KEY,
                seen_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_seen_at ON seen_media(seen_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first release. Nothing to migrate yet; when there is, do it
        // in explicit steps rather than dropping the table.
    }

    /** @return number of genuinely new rows, so the UI can tell "new" from "seen". */
    fun upsertAll(reels: List<Reel>): Int {
        if (reels.isEmpty()) return 0
        var inserted = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (reel in reels) {
                if (reel.shortcode.isBlank() || reel.videoUrl.isBlank()) continue
                val values = ContentValues().apply {
                    put("media_id", reel.mediaId)
                    put("shortcode", reel.shortcode)
                    put("author", reel.author)
                    put("sender", reel.sender)
                    put("sent_at", reel.sentAt)
                    put("video_url", reel.videoUrl)
                    put("thumb_url", reel.thumbUrl)
                    put("duration_ms", reel.durationMs)
                }
                // INSERT OR IGNORE: an existing row keeps its played_at.
                val row = db.insertWithOnConflict("queue_item", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (row != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    /**
     * Unplayed reels, oldest first — the order they were sent, which is the only
     * ordering a human can reason about.
     */
    fun unplayed(maxAgeDays: Int, now: Long = System.currentTimeMillis() / 1000): List<Reel> {
        val cutoff = now - maxAgeDays.toLong() * 86_400L
        val out = ArrayList<Reel>()
        readableDatabase.rawQuery(
            """
            SELECT media_id, shortcode, author, sender, sent_at, video_url, thumb_url, duration_ms, played_at
            FROM queue_item
            WHERE played_at IS NULL AND (sent_at = 0 OR sent_at >= ?)
            ORDER BY sent_at ASC
            LIMIT 200
            """.trimIndent(),
            arrayOf(cutoff.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Reel(
                        mediaId = c.getString(0),
                        shortcode = c.getString(1),
                        author = c.getString(2),
                        sender = c.getString(3),
                        sentAt = c.getLong(4),
                        videoUrl = c.getString(5),
                        thumbUrl = c.getString(6),
                        durationMs = c.getInt(7),
                        playedAt = if (c.isNull(8)) null else c.getLong(8)
                    )
                )
            }
        }
        return out
    }

    fun unplayedCount(maxAgeDays: Int): Int {
        val cutoff = System.currentTimeMillis() / 1000 - maxAgeDays.toLong() * 86_400L
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM queue_item WHERE played_at IS NULL AND (sent_at = 0 OR sent_at >= ?)",
            arrayOf(cutoff.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun markPlayed(mediaId: String, at: Long = System.currentTimeMillis() / 1000) {
        writableDatabase.execSQL(
            "UPDATE queue_item SET played_at = ? WHERE media_id = ? AND played_at IS NULL",
            arrayOf(at, mediaId)
        )
    }

    fun markAllPlayed() {
        writableDatabase.execSQL("UPDATE queue_item SET played_at = ? WHERE played_at IS NULL",
            arrayOf(System.currentTimeMillis() / 1000))
    }

    /** Bounded LRU: the table has a hard ceiling, not a growing one. */
    fun pruneSeen(cap: Int = 2000) {
        writableDatabase.execSQL(
            "DELETE FROM seen_media WHERE media_id NOT IN " +
                "(SELECT media_id FROM seen_media ORDER BY seen_at DESC LIMIT ?)",
            arrayOf(cap)
        )
    }

    fun pruneOldQueue(maxAgeDays: Int) {
        val cutoff = System.currentTimeMillis() / 1000 - maxAgeDays.toLong() * 86_400L
        writableDatabase.execSQL(
            "DELETE FROM queue_item WHERE played_at IS NOT NULL AND sent_at > 0 AND sent_at < ?",
            arrayOf(cutoff)
        )
    }

    private companion object {
        const val DB_NAME = "simpleinsta.db"
        const val DB_VERSION = 1
    }
}
