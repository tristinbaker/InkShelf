package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.core.abs.AuthorEntry
import com.tristinbaker.inkshelf.core.abs.FilterGroup
import com.tristinbaker.inkshelf.core.abs.LibraryItem
import com.tristinbaker.inkshelf.core.abs.ListEnvelope
import com.tristinbaker.inkshelf.core.abs.buildFilter
import com.tristinbaker.inkshelf.core.abs.decodeFilterValue
import com.tristinbaker.inkshelf.core.abs.encodeFilterValue
import com.tristinbaker.inkshelf.core.net.AbsJson
import com.tristinbaker.inkshelf.data.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {

    @Test
    fun `encodes a plain ascii value the way Node's Buffer does`() {
        // Buffer.from("The Hobbit", "base64") on the server.
        assertEquals("VGhlIEhvYmJpdA==", encodeFilterValue("The Hobbit"))
    }

    @Test
    fun `round trips unicode series names`() {
        listOf("Wheel of Time", "Café", "Trombone", "Böcklin").forEach { name ->
            assertEquals(name, decodeFilterValue(encodeFilterValue(name)))
        }
    }

    @Test
    fun `produces padding that Node's decoder accepts`() {
        val encoded = encodeFilterValue("a")
        assertTrue("expected padded base64, got $encoded", encoded.endsWith("=="))
    }

    @Test
    fun `filters series and authors by id, not by name`() {
        assertEquals("series.TTFfYWJj", buildFilter(FilterGroup.SERIES, "M1_abc"))
        assertEquals("authors.RjlfMTIz", buildFilter(FilterGroup.AUTHORS, "F9_123"))
    }

    @Test
    fun `encodes name-based groups the same way`() {
        assertEquals("tags.RmFuY3k=", buildFilter(FilterGroup.TAGS, "Fancy"))
    }
}

class ListEnvelopeTest {

    /**
     * Regression guard for the v2.37.0 quirk: `/api/libraries/:id/authors`
     * returns `{authors: [...]}` unless *both* `limit` and `page` are present as
     * numbers, in which case it returns `{results, total, ...}`. Missing this
     * means an empty author list with no error.
     */
    @Test
    fun `reads the unpaginated authors shape`() {
        val json = """{"authors":[{"id":"a1","name":"Tolkien","numBooks":3}]}"""
        val envelope = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(AuthorEntry.serializer()),
            json,
        )
        assertEquals(1, envelope.items.size)
        assertEquals("Tolkien", envelope.items.first().name)
        assertEquals(1, envelope.totalCount)
    }

    @Test
    fun `reads the paginated results shape`() {
        val json = """
            {"results":[{"id":"a1","name":"Tolkien"}],
             "total":42,"limit":200,"page":0}
        """.trimIndent()
        val envelope = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(AuthorEntry.serializer()),
            json,
        )
        assertEquals(1, envelope.items.size)
        assertEquals(42, envelope.totalCount)
        assertEquals(200, envelope.limit)
    }

    @Test
    fun `an empty envelope yields no rows rather than throwing`() {
        val envelope = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(AuthorEntry.serializer()),
            "{}",
        )
        assertTrue(envelope.items.isEmpty())
    }

    @Test
    fun `tolerates unknown fields the server may add later`() {
        val json = """{"results":[{"id":"i1","name":"Dune","brandNewField":42}]}"""
        val envelope = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(AuthorEntry.serializer()),
            json,
        )
        assertEquals("Dune", envelope.items.first().name)
    }

    @Test
    fun `minified items keep the libraryItemId in the top level id`() {
        // media.id is the book id; the row's own id is what /api/items/:id wants.
        val json = """
            {"results":[{"id":"li_abc","libraryId":"lib","mediaType":"book",
              "media":{"id":"bk_xyz","metadata":{"title":"Dune","authorName":"Herbert",
              "series":[{"id":"s1","name":"Dune","sequence":"1"}]}}}]}
        """.trimIndent()
        val envelope = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(LibraryItem.serializer()),
            json,
        )
        val item = envelope.items.single()
        assertEquals("li_abc", item.id)
        assertEquals("bk_xyz", item.media.id)
        assertEquals("Dune", item.media.metadata.title)
        assertEquals("s1", item.media.metadata.series.single().id)
    }

    @Test
    fun `minified items default a missing title to untitled`() {
        val json = """{"results":[{"id":"li_1","media":{"metadata":{}}}]}"""
        val envelope = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(LibraryItem.serializer()),
            json,
        )
        assertEquals("Untitled", envelope.items.single().toEntity(0L).title)
    }
}

/**
 * Audiobookshelf sends `media.metadata.series` as a bare object when a book is in
 * one series and as an array when it is in several. Typed as a plain list, the
 * object form threw and failed the entire items response, so a single
 * nested-series book broke the book list and every drill-down.
 */
class SingleOrListTest {
    private fun parse(series: String): LibraryItem {
        val body = """
            {"results":[{
              "id":"li_1","libraryId":"lib","path":"/b","title":"T",
              "media":{"id":"m1","metadata":{"title":"Fool's Assassin",
                "series":$series}}
            }],"total":1}
        """.trimIndent()
        return AbsJson.json.decodeFromString(
            ListEnvelope.serializer(LibraryItem.serializer()),
            body,
        ).items.single()
    }

    @Test
    fun `a lone series sent as an object parses`() {
        val item = parse("""{"id":"s1","name":"Fitz and the Fool","sequence":"1"}""")

        assertEquals(listOf("Fitz and the Fool"), item.media.metadata.series.map { it.name })
        assertEquals(listOf("1"), item.media.metadata.series.map { it.sequence })
    }

    @Test
    fun `several series sent as an array parse`() {
        val item = parse(
            """[{"id":"s1","name":"Fitz and the Fool","sequence":"1"},
                {"id":"s2","name":"Realm of the Elderlings","sequence":"14"}]""",
        )

        assertEquals(
            listOf("Fitz and the Fool", "Realm of the Elderlings"),
            item.media.metadata.series.map { it.name },
        )
    }

    @Test
    fun `a lone author sent as an object parses`() {
        val item = parse("[]").copy(
            media = itemMediaWithAuthor("""{"id":"a1","name":"Robin Hobb"}"""),
        )

        assertEquals(listOf("Robin Hobb"), item.media.metadata.authors.map { it.name })
    }

    private fun itemMediaWithAuthor(authors: String) =
        AbsJson.json.decodeFromString(
            ListEnvelope.serializer(LibraryItem.serializer()),
            """
            {"results":[{
              "id":"li_1","libraryId":"lib","path":"/b","title":"T",
              "media":{"id":"m1","metadata":{"title":"X","authors":$authors}}
            }],"total":1}
            """.trimIndent(),
        ).items.single().media

    @Test
    fun `absent series and authors stay empty rather than throwing`() {
        val item = AbsJson.json.decodeFromString(
            ListEnvelope.serializer(LibraryItem.serializer()),
            """{"results":[{"id":"li_1","libraryId":"lib","path":"/b","title":"T",
                "media":{"id":"m1","metadata":{"title":"X"}}}],"total":1}""",
        ).items.single()

        assertTrue(item.media.metadata.series.isEmpty())
        assertTrue(item.media.metadata.authors.isEmpty())
    }
}
