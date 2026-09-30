package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.core.abs.AudioTrack
import com.tristinbaker.inkshelf.core.abs.ProgressUpdate
import com.tristinbaker.inkshelf.core.playback.TrackFormats
import com.tristinbaker.inkshelf.download.DownloadCoordinator
import com.tristinbaker.inkshelf.data.DownloadEntity
import com.tristinbaker.inkshelf.playback.PlaybackCoordinator
import com.tristinbaker.inkshelf.playback.PlayCommand
import com.tristinbaker.inkshelf.playback.PlayerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {

    @Test
    fun `wav is playable even though the server mislabels its mime type`() {
        val format = TrackFormats.resolve("wav", null)
        assertTrue(format.directPlay)
        assertEquals("audio/wav", format.mimeType)
    }

    @Test
    fun `mka and mkv play directly`() {
        assertTrue(TrackFormats.resolve("mka", "matroska").directPlay)
        assertTrue(TrackFormats.resolve("mkv", "matroska").directPlay)
    }

    @Test
    fun `wma and aiff need transcoding`() {
        assertFalse(TrackFormats.resolve("wma", null).directPlay)
        assertFalse(TrackFormats.resolve("aiff", null).directPlay)
    }

    @Test
    fun `flac is playable`() {
        assertTrue(TrackFormats.resolve("flac", "flac").directPlay)
    }

    @Test
    fun `an unknown extension is not assumed to be playable`() {
        assertFalse(TrackFormats.resolve("weird", null).directPlay)
    }

    @Test
    fun `extension matching ignores case and a leading dot`() {
        assertTrue(TrackFormats.resolve(".MP3", "mp3").directPlay)
        assertTrue(TrackFormats.resolve("MP3", "mpeg").directPlay)
    }

    @Test
    fun `one unsupported file makes the whole book need transcoding`() {
        val tracks = listOf("mp3" to "mp3", "wma" to "wmav2", "ogg" to "opus")
        assertFalse(TrackFormats.allDirectPlayable(tracks))
        assertEquals(listOf("wma"), TrackFormats.unsupported(tracks))
    }

    @Test
    fun `an all mp3 book needs no transcoding`() {
        val tracks = listOf("mp3" to "mp3", "m4b" to "aac")
        assertTrue(TrackFormats.allDirectPlayable(tracks))
        assertTrue(TrackFormats.unsupported(tracks).isEmpty())
    }

    @Test
    fun `start offsets are read in seconds from the track list`() {
        val track = AudioTrack(index = 1, ino = "2", startOffset = 61.5)
        assertEquals(61.5, track.startOffset, 0.0001)
    }

    @Test
    fun `a progress update always carries the zero finish threshold`() {
        val update = ProgressUpdate(
            currentTime = 10.0,
            progress = 0.25,
            isFinished = false,
        )
        assertEquals(0.0, update.markAsFinishedTimeRemaining, 0.0)
    }

    @Test
    fun `player progress clamps to the track bounds`() {
        val state = PlayerState(durationMs = 1000, positionMs = 2500)
        assertEquals(1f, state.progress, 0.0001f)
        val before = PlayerState(durationMs = 1000, positionMs = -5)
        assertEquals(0f, before.progress, 0.0001f)
    }

    @Test
    fun `an empty player has no book and zero progress`() {
        val state = PlayerState()
        assertFalse(state.hasBook)
        assertEquals(0f, state.progress, 0.0001f)
    }

    @Test
    fun `a command sent before the service attaches is held and replayed`() {
        val coordinator = PlaybackCoordinator()
        coordinator.send(PlayCommand.Play("item-1"))
        assertEquals(PlayCommand.Play("item-1"), coordinator.attach())
    }

    @Test
    fun `a held command is only delivered once`() {
        val coordinator = PlaybackCoordinator()
        coordinator.send(PlayCommand.Stop)
        assertNotNull(coordinator.attach())
        assertNull(coordinator.attach())
    }

    @Test
    fun `a detached coordinator holds the next command again`() {
        val coordinator = PlaybackCoordinator()
        coordinator.attach()
        coordinator.detach()
        coordinator.send(PlayCommand.Toggle)
        assertEquals(PlayCommand.Toggle, coordinator.attach())
    }
}

class DownloadTest {

    private fun row(
        id: String,
        state: String,
        written: Long = 0L,
        size: Long = 100L,
        error: String? = null,
        startOffset: Double = 0.0,
    ) = DownloadEntity(
        id = id,
        itemId = "book",
        libraryItemTitle = "Book",
        trackIndex = 0,
        fileName = "$id.mp3",
        url = "https://example.com/$id",
        mimeType = "audio/mpeg",
        size = size,
        bytesWritten = written,
        state = state,
        uri = null,
        error = error,
        startOffsetSeconds = startOffset,
        updatedAt = 0L,
    )

    @Test
    fun `a finished book reports every track done and no progress bar`() {
        val rows = listOf(
            row("a", DownloadEntity.STATE_DONE, written = 100),
            row("b", DownloadEntity.STATE_DONE, written = 100),
        )
        val summary = DownloadCoordinator().summarise(rows).getValue("book")
        assertTrue(summary.isComplete)
        assertFalse(summary.isActive)
        assertEquals(1f, summary.progress, 0.0001f)
    }

    @Test
    fun `a queued track counts as active so the notification shows progress`() {
        val rows = listOf(
            row("a", DownloadEntity.STATE_DONE, written = 100),
            row("b", DownloadEntity.STATE_RUNNING, written = 50),
        )
        val summary = DownloadCoordinator().summarise(rows).getValue("book")
        assertTrue(summary.isActive)
        assertFalse(summary.isComplete)
        assertEquals(0.75f, summary.progress, 0.0001f)
    }

    @Test
    fun `a queued but not yet started track still counts as active`() {
        val rows = listOf(
            row("a", DownloadEntity.STATE_DONE, written = 100),
            row("b", DownloadEntity.STATE_QUEUED, written = 0),
        )
        assertTrue(DownloadCoordinator().summarise(rows).getValue("book").isActive)
    }

    @Test
    fun `a failure surfaces its message and blocks completion`() {
        val rows = listOf(
            row("a", DownloadEntity.STATE_DONE, written = 100),
            row("b", DownloadEntity.STATE_FAILED, error = "Server said 503"),
        )
        val summary = DownloadCoordinator().summarise(rows).getValue("book")
        assertTrue(summary.hasFailure)
        assertFalse(summary.isComplete)
        assertFalse(summary.isActive)
        assertEquals("Server said 503", summary.error)
    }

    @Test
    fun `tracks of different books do not merge into one summary`() {
        val rows = listOf(
            row("a", DownloadEntity.STATE_DONE, written = 100).copy(itemId = "one"),
            row("b", DownloadEntity.STATE_QUEUED).copy(itemId = "two"),
        )
        val summaries = DownloadCoordinator().summarise(rows)
        assertEquals(setOf("one", "two"), summaries.keys)
        assertTrue(summaries.getValue("one").isComplete)
        assertFalse(summaries.getValue("one").isActive)
    }

    @Test
    fun `a book with no known sizes does not divide by zero`() {
        val rows = listOf(row("a", DownloadEntity.STATE_RUNNING, written = 50, size = 0))
        assertEquals(0f, DownloadCoordinator().summarise(rows).getValue("book").progress, 0f)
    }

    @Test
    fun `no rows means no summaries rather than a crash`() {
        assertTrue(DownloadCoordinator().summarise(emptyList()).isEmpty())
    }

    @Test
    fun `an enqueue sent before the service attaches is held and replayed`() {
        val coordinator = DownloadCoordinator()
        coordinator.send(com.tristinbaker.inkshelf.download.DownloadCommand.Enqueue("book"))
        assertEquals(
            com.tristinbaker.inkshelf.download.DownloadCommand.Enqueue("book"),
            coordinator.attach(),
        )
    }
}
