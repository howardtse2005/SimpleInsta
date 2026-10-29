package app.simpleinsta.ig.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkTest {

    private val now = 1_700_000_000L

    @Test
    fun `exhausted feed jumps the watermark to now`() {
        val next = Watermark.advance(
            current = 0,
            oldestSeen = 1_600_000_000,
            now = now,
            reason = StopReason.EXHAUSTED
        )
        assertEquals(now, next)
    }

    @Test
    fun `watermark stop resumes from the oldest post actually loaded`() {
        val oldest = 1_699_000_000L
        val next = Watermark.advance(
            current = 1_698_000_000,
            oldestSeen = oldest,
            now = now,
            reason = StopReason.WATERMARK
        )
        assertEquals(oldest, next)
    }

    @Test
    fun `cap stop also resumes rather than skipping ahead`() {
        val oldest = 1_699_500_000L
        val next = Watermark.advance(
            current = 0,
            oldestSeen = oldest,
            now = now,
            reason = StopReason.CAP
        )
        assertEquals(oldest, next)
    }

    @Test
    fun `watermark never moves backwards`() {
        // A plausible past watermark, not a future one: the future case is
        // corruption and is covered separately below.
        val next = Watermark.advance(
            current = 1_699_999_000,
            oldestSeen = 1_600_000_000,
            now = now,
            reason = StopReason.CAP
        )
        assertEquals(1_699_999_000, next)
    }

    @Test
    fun `unknown reason leaves the watermark alone`() {
        val next = Watermark.advance(
            current = 1_234,
            oldestSeen = 1_600_000_000,
            now = now,
            reason = StopReason.UNKNOWN
        )
        assertEquals(1_234, next)
    }

    @Test
    fun `missing oldest timestamp falls back to now`() {
        val next = Watermark.advance(0, 0, now, StopReason.CAP)
        assertEquals(now, next)
    }

    @Test
    fun `a future watermark is treated as unset`() {
        // Exactly what happened on device: an empty payload was read as
        // "end of feed", the watermark jumped to now, and the real feed then
        // looked entirely stale.
        val poisoned = now + 86_400
        val next = Watermark.advance(poisoned, 1_699_000_000, now, StopReason.WATERMARK)
        assertEquals(1_699_000_000, next)
    }

    @Test
    fun `a slightly ahead watermark is tolerated`() {
        val slightlyAhead = now + 30
        val next = Watermark.advance(slightlyAhead, 0, now, StopReason.UNKNOWN)
        assertEquals(slightlyAhead, next)
    }

    @Test
    fun `feed continues while pages remain and content is fresh`() {
        assertTrue(Watermark.shouldContinue(1_699_000_000, 1_698_000_000, pagesFetched = 1, pageCap = 3))
    }

    @Test
    fun `feed stops at the page cap`() {
        assertFalse(Watermark.shouldContinue(1_699_000_000, 0, pagesFetched = 3, pageCap = 3))
    }

    @Test
    fun `feed stops at the watermark`() {
        assertFalse(Watermark.shouldContinue(1_697_000_000, 1_698_000_000, pagesFetched = 1, pageCap = 5))
    }

    @Test
    fun `no watermark means only the cap applies`() {
        assertTrue(Watermark.shouldContinue(1_000_000_000, 0, pagesFetched = 1, pageCap = 2))
    }
}
