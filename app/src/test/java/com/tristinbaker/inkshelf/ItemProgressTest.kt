package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.core.abs.LibraryItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The server attaches listen progress to a single-item request as
 * `userMediaProgress`, never as `progress`. Getting that name wrong is silent:
 * every book simply reports "Play" instead of "Resume" and starts from zero, so
 * these decode real response shapes rather than trusting the field name.
 */
class ItemProgressTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `progress decodes from userMediaProgress on an expanded item`() {
        val body = """
            {
              "id": "li_abc123",
              "libraryId": "lib_1",
              "path": "/audiobooks/he-who-fights",
              "mediaType": "book",
              "media": {
                "id": "book_1",
                "metadata": { "title": "He Who Fights with Monsters" },
                "duration": 64206.0,
                "numTracks": 1,
                "tracks": [
                  {
                    "index": 0,
                    "ino": "inode-1",
                    "startOffset": 0,
                    "duration": 64206.0,
                    "codec": "aac",
                    "contentUrl": "/api/items/li_abc123/file/inode-1"
                  }
                ]
              },
              "userMediaProgress": {
                "id": "mp_1",
                "userId": "u_1",
                "libraryItemId": "li_abc123",
                "mediaItemId": "book_1",
                "mediaItemType": "book",
                "duration": 64206.0,
                "progress": 0.0285,
                "currentTime": 1834.5,
                "isFinished": false,
                "lastUpdate": 1700000000000
              }
            }
        """.trimIndent()

        val item = json.decodeFromString(LibraryItem.serializer(), body)
        val progress = item.userMediaProgress

        assertEquals(1834.5, progress?.currentTime ?: -1.0, 0.0001)
        assertEquals(64206.0, progress?.duration ?: -1.0, 0.0001)
        assertEquals(false, progress?.isFinished)
    }

    @Test
    fun `a book with no progress decodes to null rather than failing`() {
        val item = json.decodeFromString(
            LibraryItem.serializer(),
            """{"id":"li_abc123","mediaType":"book","media":{}}""",
        )
        assertNull(item.userMediaProgress)
    }

    @Test
    fun `track start offsets survive decoding so track navigation is book relative`() {
        val body = """
            {
              "id": "li_1",
              "mediaType": "book",
              "media": {
                "duration": 180.0,
                "tracks": [
                  { "index": 0, "ino": "a", "startOffset": 0,    "contentUrl": "/api/items/li_1/file/a" },
                  { "index": 1, "ino": "b", "startOffset": 60.5, "contentUrl": "/api/items/li_1/file/b" },
                  { "index": 2, "ino": "c", "startOffset": 120.0,"contentUrl": "/api/items/li_1/file/c" }
                ]
              }
            }
        """.trimIndent()

        val tracks = json.decodeFromString(LibraryItem.serializer(), body).media.tracks
        assertEquals(listOf(0.0, 60.5, 120.0), tracks.map { it.startOffset })
        assertEquals("/api/items/li_1/file/c", tracks.last().contentUrl)
    }

    @Test
    fun `a file name falls back sensibly when metadata is missing`() {
        val body = """
            {
              "id": "li_1",
              "mediaType": "book",
              "media": {
                "tracks": [
                  { "index": 0, "ino": "a", "contentUrl": "/api/items/li_1/file/a" },
                  { "index": 1, "ino": "b", "title": "Chapter Two", "contentUrl": "/api/items/li_1/file/b" }
                ]
              }
            }
        """.trimIndent()

        val tracks = json.decodeFromString(LibraryItem.serializer(), body).media.tracks
        assertEquals("Track 1", tracks[0].fileName)
        assertEquals("Chapter Two", tracks[1].fileName)
    }
}
