package com.tristinbaker.inkshelf.playback

import com.tristinbaker.inkshelf.core.abs.Chapter

/**
 * Constructs a [ChapterBoundary] from whatever chapter data the server (or a
 * fallback) supplied.
 *
 * Skipping buttons must do *something* even on books with no embedded chapters,
 * which is most of them; otherwise the buttons feel broken. Synthetic markers
 * at fixed intervals are the same fallback Audible uses, and are cheap enough
 * to compute on demand.
 */
internal object ChapterBoundaryBuilder {

    /**
     * @param serverChapters as returned in the expanded item payload.
     * @param durationMs book length in milliseconds; only used to backfill
     *  synthetic intervals so the last chapter does not run to infinity.
     */
    fun build(
        serverChapters: List<Chapter>,
        durationMs: Long,
        syntheticIntervalMs: Long = SYNTHETIC_INTERVAL_MS,
    ): ChapterBoundary {
        if (serverChapters.isNotEmpty()) {
            val starts = LongArray(serverChapters.size) { (serverChapters[it].start * 1000).toLong() }
            return ChapterBoundary(sanitizeStarts(starts))
        }
        if (durationMs <= 0 || syntheticIntervalMs <= 0) return ChapterBoundary(LongArray(0))
        val count = (durationMs / syntheticIntervalMs).toInt() + 1
        val starts = LongArray(count) { i -> i * syntheticIntervalMs }
        return ChapterBoundary(sanitizeStarts(starts))
    }

    /**
     * The server has been known to send out-of-order or duplicate starts when a
     * book is re-scanned. The boundary search assumes a sorted, deduplicated,
     * monotonically increasing list, so clean it up here once instead of every
     * press of the skip button.
     */
    private fun sanitizeStarts(raw: LongArray): LongArray {
        // Drop negatives up front: a chapter starting before time zero is
        // nonsense, and they would survive a sort and land at the front.
        val positive = LongArray(raw.size) { i -> if (raw[i] < 0) 0L else raw[i] }
        positive.sort()
        if (positive.size < 2) return positive
        val out = ArrayList<Long>(positive.size)
        var last = Long.MIN_VALUE
        for (v in positive) {
            if (v == last) continue
            out += v
            last = v
        }
        return out.toLongArray()
    }

    /**
     * Five minutes. Long enough that a synthetic chapter actually represents
     * some listening, short enough that skip feels responsive.
     */
    const val SYNTHETIC_INTERVAL_MS = 5 * 60 * 1000L
}
