package com.tristinbaker.inkshelf.playback

/**
 * Navigation primitives over a sorted chapter list.
 *
 * Book-global positions everywhere: [ChapterBoundary] expects the chapter start
 * times already in milliseconds, with the cumulative track offset added. That
 * matches the playback service's own `bookPositionMs()` so the player does not
 * have to know anything about how the book is split across files.
 *
 * All operations are O(log n) over [ChapterBoundary.starts]. The list is tiny
 * (under two hundred entries for a full-length audiobook), so the binary search
 * itself is a non-issue; the more useful property is that nothing here ever
 * allocates, and so is safe to call on the per-tick reporter loop.
 *
 * The previous-chapter rule mirrors a physical previous-track button: pressing
 * it inside the first few seconds of a chapter goes to the *previous* chapter,
 * pressing it after that restarts the current one. Without that rule the user
 * has no way to restart a chapter they have only just entered.
 */
internal class ChapterBoundary(val starts: LongArray) {

    /** -1 when there are no chapters. Otherwise the index whose start <= position. */
    fun indexAt(positionMs: Long): Int {
        if (starts.isEmpty()) return -1
        // The largest i such that starts[i] <= positionMs. Binary search returns
        // the insertion point (-(insertion) - 1) when not found.
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        return if (starts[lo] <= positionMs) lo else 0
    }

    /**
     * Returns the index to seek to for a "previous chapter" press.
     *
     * Within [restartThresholdMs] of the start of the current chapter, jumps to
     * the previous chapter's start. Otherwise restarts the current chapter.
     */
    fun previousIndex(positionMs: Long, restartThresholdMs: Long = RESTART_THRESHOLD_MS): Int {
        if (starts.isEmpty()) return -1
        val current = indexAt(positionMs)
        val intoChapter = positionMs - starts[current]
        return if (intoChapter <= restartThresholdMs && current > 0) current - 1 else current
    }

    /** Returns the index to seek to for a "next chapter" press, or -1 if already last. */
    fun nextIndex(positionMs: Long): Int {
        if (starts.isEmpty()) return -1
        val current = indexAt(positionMs)
        return if (current + 1 < starts.size) current + 1 else -1
    }

    fun startMs(index: Int): Long = starts[index]

    companion object {
        /**
         * Two seconds, the convention audiobook players use. Small enough that a
         * listener who has paused in the first beat of a chapter can still back
         * up, large enough that they are not forced out of their place by a
         * stray press a few seconds in.
         */
        const val RESTART_THRESHOLD_MS = 2_000L
    }
}
