package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.core.abs.Chapter
import com.tristinbaker.inkshelf.playback.ChapterBoundary
import com.tristinbaker.inkshelf.playback.ChapterBoundaryBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterBoundaryTest {

    private fun boundary(vararg ms: Long) = ChapterBoundary(LongArray(ms.size) { ms[it] })

    @Test
    fun `empty boundary reports no index and no skip targets`() {
        val b = ChapterBoundary(LongArray(0))
        assertEquals(-1, b.indexAt(0))
        assertEquals(-1, b.indexAt(123_456))
        assertEquals(-1, b.nextIndex(123_456))
        assertEquals(-1, b.previousIndex(123_456))
    }

    @Test
    fun `position inside a chapter returns that chapter`() {
        val b = boundary(0, 60_000, 120_000, 180_000)
        assertEquals(0, b.indexAt(0))
        assertEquals(0, b.indexAt(59_999))
        assertEquals(1, b.indexAt(60_000))
        assertEquals(1, b.indexAt(119_999))
        assertEquals(2, b.indexAt(120_000))
        assertEquals(3, b.indexAt(180_000))
    }

    @Test
    fun `position past the last chapter start clamps to the last chapter`() {
        // The listener is three minutes into a chapter that started at 10:00 of
        // a 12-minute book. They are still in that chapter, not beyond the end.
        val b = boundary(0, 600_000, 660_000)
        assertEquals(2, b.indexAt(680_000))
        assertEquals(2, b.indexAt(9_999_999))
    }

    @Test
    fun `boundary on the chapter start belongs to that chapter`() {
        // A chapter starting exactly at the position is the chapter the listener
        // is now in. Holding them in the previous chapter would feel wrong.
        val b = boundary(0, 60_000, 120_000)
        assertEquals(1, b.indexAt(60_000))
    }

    @Test
    fun `next index moves forward and clamps at the end`() {
        val b = boundary(0, 60_000, 120_000, 180_000)
        assertEquals(1, b.nextIndex(0))
        assertEquals(2, b.nextIndex(60_000))
        assertEquals(3, b.nextIndex(120_000))
        // Already on the last chapter: no further skip, signalled by -1 so the
        // player can disable the button rather than seek to the same place.
        assertEquals(-1, b.nextIndex(180_000))
        assertEquals(-1, b.nextIndex(999_999))
    }

    @Test
    fun `previous index from the middle of a chapter restarts the current one`() {
        // The whole point of the dual rule: pressing previous from 0:30 into a
        // chapter should not bounce the listener back where they came from.
        val b = boundary(0, 60_000, 120_000)
        assertEquals(1, b.previousIndex(90_000))
    }

    @Test
    fun `previous index inside the restart window jumps back a chapter`() {
        val b = boundary(0, 60_000, 120_000, 180_000)
        // One second into chapter 1: inside the two-second window, back up.
        assertEquals(0, b.previousIndex(61_000))
        // Exactly at the threshold: still inside, so still back up.
        assertEquals(0, b.previousIndex(62_000))
        // Past the threshold: restart the current chapter instead.
        assertEquals(1, b.previousIndex(62_001))
    }

    @Test
    fun `previous index on the first chapter is a no-op stay`() {
        val b = boundary(0, 60_000)
        assertEquals(0, b.previousIndex(0))
        assertEquals(0, b.previousIndex(30_000))
    }

    @Test
    fun `start ms returns the chapter start`() {
        val b = boundary(10_000, 70_000)
        assertEquals(10_000, b.startMs(0))
        assertEquals(70_000, b.startMs(1))
    }

    @Test
    fun `synthetic intervals cover the book length`() {
        val b = ChapterBoundaryBuilder.build(
            serverChapters = emptyList(),
            durationMs = 60 * 60 * 1000L, // one hour
        )
        // Every five minutes plus a final marker past the end.
        val expected = (0..12).map { it * ChapterBoundaryBuilder.SYNTHETIC_INTERVAL_MS }
        assertEquals(expected.size, b.starts.size)
        for ((i, ms) in expected.withIndex()) {
            assertEquals(ms, b.starts[i])
        }
    }

    @Test
    fun `real chapters take precedence over synthetic ones`() {
        val server = listOf(
            Chapter(id = 1, start = 0.0, end = 60.0, title = "Cold open"),
            Chapter(id = 2, start = 60.0, end = 120.0, title = "Act 1"),
        )
        val b = ChapterBoundaryBuilder.build(serverChapters = server, durationMs = 7_200_000L)
        // The builder must not synthesise a fallback over real data.
        assertEquals(2, b.starts.size)
        assertEquals(0L, b.starts[0])
        assertEquals(60_000L, b.starts[1])
    }

    @Test
    fun `out of order or duplicate server starts are normalised`() {
        // Server has been observed to send duplicate starts after a re-scan, and
        // some old metadata has them in reverse. The builder must hand back a
        // monotonic, deduplicated list so the binary search stays sound.
        val raw = longArrayOf(0L, 60_000L, 60_000L, 30_000L, 120_000L)
        val b = ChapterBoundaryBuilder.build(
            serverChapters = listOf(
                Chapter(id = 0, start = 0.0, end = 60.0),
                Chapter(id = 0, start = 60.0, end = 90.0),
                Chapter(id = 0, start = 60.0, end = 90.0),
                Chapter(id = 0, start = 30.0, end = 60.0),
                Chapter(id = 0, start = 120.0, end = 180.0),
            ),
            durationMs = 180_000L,
        )
        assertEquals(listOf(0L, 30_000L, 60_000L, 120_000L), b.starts.toList())
        assertEquals(2, b.indexAt(60_000))
        // 90s is in the 60s-started chapter (index 2), not the 120s one.
        assertEquals(2, b.indexAt(90_000))
    }
}
