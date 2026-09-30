package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.core.abs.AuthorEntry
import com.tristinbaker.inkshelf.core.abs.BrowseOrder
import com.tristinbaker.inkshelf.data.ItemEntity
import com.tristinbaker.inkshelf.data.Sorters
import com.tristinbaker.inkshelf.ui.AuthorRow
import com.tristinbaker.inkshelf.ui.distinctAuthors
import com.tristinbaker.inkshelf.ui.distinctSeries
import com.tristinbaker.inkshelf.ui.toAuthorRows
import com.tristinbaker.inkshelf.ui.splitSeriesName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortersTest {

    @Test
    fun `sorts titles case insensitively`() {
        val rows = listOf(
            seriesItem("1", title = "banana"),
            seriesItem("2", title = "Apple"),
            seriesItem("3", title = "cherry"),
        )
        val sorted = Sorters.sortedItems(rows, BrowseOrder.TITLE, descending = false)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun `reverses cleanly for descending`() {
        val rows = listOf(
            seriesItem("1", title = "Apple"),
            seriesItem("2", title = "banana"),
            seriesItem("3", title = "cherry"),
        )
        val sorted = Sorters.sortedItems(rows, BrowseOrder.TITLE, descending = true)
        assertEquals(listOf("cherry", "banana", "Apple"), sorted.map { it.title })
    }

    @Test
    fun `breaks ties on title with author then id so order is stable`() {
        val rows = listOf(
            seriesItem("z", title = "Dune", author = "Herbert"),
            seriesItem("a", title = "Dune", author = "Herbert"),
        )
        val sorted = Sorters.sortedItems(rows, BrowseOrder.TITLE, descending = false)
        assertEquals(listOf("a", "z"), sorted.map { it.id })
    }

    @Test
    fun `sorts by author using the author name`() {
        val rows = listOf(
            seriesItem("1", title = "A", author = "Zelazny"),
            seriesItem("2", title = "B", author = "borges"),
        )
        val sorted = Sorters.sortedItems(rows, BrowseOrder.AUTHOR, descending = false)
        assertEquals(listOf("borges", "Zelazny"), sorted.map { it.authorName })
    }

    @Test
    fun `groups by series in alphabetical order and appends the loose bucket`() {
        val rows = listOf(
            seriesItem("1", title = "Book B2", seriesId = "s2", series = "Zeta"),
            seriesItem("2", title = "Book A1", seriesId = "s1", series = "Alpha"),
            seriesItem("3", title = "Standalone"),
        )
        val groups = Sorters.groupBySeries(rows, descending = false)
        assertEquals(listOf("Alpha", "Zeta", "No series"), groups.map { it.name })
        assertEquals(Sorters.LOOSE_SERIES_ID, groups.last().id)
    }

    @Test
    fun `orders books inside a series by sequence numerically not lexically`() {
        val rows = listOf(
            seriesItem("1", title = "Ten", seriesId = "s1", series = "Alpha", sequence = "10"),
            seriesItem("2", title = "Two", seriesId = "s1", series = "Alpha", sequence = "2"),
            seriesItem("3", title = "One", seriesId = "s1", series = "Alpha", sequence = "1"),
        )
        val groups = Sorters.groupBySeries(rows, descending = false)
        assertEquals(
            listOf("One", "Two", "Ten"),
            groups.first().books.map { it.title },
        )
    }

    @Test
    fun `falls back to title order when a book has no sequence`() {
        val rows = listOf(
            seriesItem("1", title = "Beta", seriesId = "s1", series = "Alpha"),
            seriesItem("2", title = "Alpha", seriesId = "s1", series = "Alpha"),
        )
        val groups = Sorters.groupBySeries(rows, descending = false)
        assertEquals(listOf("Alpha", "Beta"), groups.first().books.map { it.title })
    }

    @Test
    fun `groups every item exactly once`() {
        val rows = listOf(
            seriesItem("1", seriesId = "s1", series = "Alpha"),
            seriesItem("2", seriesId = "s1", series = "Alpha"),
            seriesItem("3", seriesId = "s2", series = "Beta"),
            seriesItem("4"),
        )
        val groups = Sorters.groupBySeries(rows, descending = false)
        assertEquals(rows.size, groups.sumOf { it.books.size })
        assertTrue(groups.all { it.books.isNotEmpty() })
    }

    @Test
    fun `author names display surname first`() {
        assertEquals("Weir, Andy", Sorters.toLastFirst("Andy Weir"))
        assertEquals("Martin, George R. R.", Sorters.toLastFirst("George R. R. Martin"))
    }

    @Test
    fun `single word author is left alone`() {
        assertEquals("Plato", Sorters.toLastFirst("Plato"))
    }

    @Test
    fun `co-author list files under the first author`() {
        // Audiobookshelf joins credits with commas, and the whole joined string
        // was previously filed under whichever word happened to come last.
        assertEquals("Orwell, George", Sorters.toLastFirst("George Orwell, Joe White"))
        assertEquals(
            "Forstrom, Amanda",
            Sorters.toLastFirst("Amanda Forstrom, Andy Clemence, Pierce Brown"),
        )
    }

    @Test
    fun `role suffix is not treated as the surname`() {
        // A role trailing the only credited author.
        assertEquals("White, Joe", Sorters.toLastFirst("Joe White - adaptation"))
        // A role trailing a co-author list must not displace the first author.
        assertEquals(
            "Orwell, George",
            Sorters.toLastFirst("George Orwell, Joe White - adaptation"),
        )
        assertEquals(
            "McMurtry, Larry",
            Sorters.toLastFirst("Larry McMurtry, Taylor Sheridan - foreword"),
        )
        assertEquals(
            "Paolini, Christopher",
            Sorters.toLastFirst("Christopher Paolini - Read by Gerard Doyle/Christopher Paolini"),
        )
    }

    @Test
    fun `hyphenated surname survives because the role marker is spaced`() {
        assertEquals("Dembski-Bowden, Aaron", Sorters.toLastFirst("Aaron Dembski-Bowden"))
        assertEquals("Dembski-Bowden", Sorters.lastNameSortKey("Aaron Dembski-Bowden"))
    }

    @Test
    fun `generational suffix stays with the given names`() {
        assertEquals("King, Martin Luther Jr.", Sorters.toLastFirst("Martin Luther King Jr."))
        assertEquals("King", Sorters.lastNameSortKey("Martin Luther King Jr."))
    }

    @Test
    fun `last name sort key is the surname alone`() {
        assertEquals("Weir", Sorters.lastNameSortKey("Andy Weir"))
        assertEquals("Plato", Sorters.lastNameSortKey("Plato"))
    }

    @Test
    fun `author bucket comes from the surname not the given name`() {
        assertEquals("W", Sorters.lastNameLetterBucket("Andy Weir"))
        assertEquals("B", Sorters.lastNameLetterBucket("Pierce Brown"))
        assertEquals("O", Sorters.lastNameLetterBucket("George Orwell, Joe White - adaptation"))
        assertEquals("W", Sorters.lastNameLetterBucket("Joe White - adaptation"))
        assertEquals("K", Sorters.lastNameLetterBucket("Martin Luther King Jr."))
        // The header has to agree with the sort, and a one-word name sorts under
        // itself, so this is L and not # even though the name opens with a digit.
        assertEquals("L", Sorters.lastNameLetterBucket("9 Lives"))
        assertEquals(Sorters.NON_LETTER_BUCKET, Sorters.lastNameLetterBucket(""))
    }

    @Test
    fun `authors sharing a surname sort next to each other`() {
        val rows = listOf("Beth Smith", "Peter Smith", "Aaron Adams", "Zoe Baker")
            .map { AuthorRow(it, 1) }
            .sortedWith { a, b -> a.compare(b) }
        assertEquals(
            listOf("Adams, Aaron", "Baker, Zoe", "Smith, Beth", "Smith, Peter"),
            rows.map { it.displayName },
        )
    }
}

/**
 * The Author and Series tabs used to require a non-null `authorId`/`seriesId`
 * and dropped every book without one. Minified list responses carry only names,
 * so both tabs rendered empty. These pin the name-based behaviour.
 */
class BrowseGroupingTest {

    @Test
    fun `authors are grouped with no server ids present`() {
        val authors = listOf(
            seriesItem("1", author="Adrian Tchaikovsky"),
            seriesItem("2", author="Adrian Tchaikovsky"),
            seriesItem("3", author="Agatha Christie"),
        ).distinctAuthors()

        assertEquals(2, authors.size)
        assertEquals(2, authors.first { it.name == "Adrian Tchaikovsky" }.bookCount)
    }

    @Test
    fun `author spellings that differ only in punctuation are one author`() {
        val authors = listOf(
            seriesItem("1", author="Aaron Dembski Bowden"),
            seriesItem("2", author="Aaron Dembski-Bowden"),
        ).distinctAuthors()

        assertEquals(1, authors.size)
        assertEquals(2, authors.single().bookCount)
    }

    @Test
    fun `blank authors are omitted`() {
        assertTrue(listOf(seriesItem("1", author="  ")).distinctAuthors().isEmpty())
    }

    @Test
    fun `series sequence is split off the name before grouping`() {
        val series = listOf(
            seriesItem("1", series="The Horus Heresy #1"),
            seriesItem("2", series="The Horus Heresy #24"),
        ).distinctSeries()

        assertEquals(1, series.size)
        assertEquals("The Horus Heresy", series.single().name)
        assertEquals(2, series.single().bookCount)
    }

    @Test
    fun `books with no series are omitted from the series tab`() {
        val series = listOf(
            seriesItem("1", series=null),
            seriesItem("2", series=""),
            seriesItem("3", series="Cedar Cove"),
        ).distinctSeries()

        assertEquals(listOf("Cedar Cove"), series.map { it.name })
    }

    @Test
    fun `a lone series name is not treated as a sequence`() {
        assertEquals("Fandemonium" to null, splitSeriesName("Fandemonium"))
        assertEquals("The Tyrant Philosophers" to "4", splitSeriesName("The Tyrant Philosophers #4"))
        assertEquals("War and Peace" to "1", splitSeriesName("War and Peace #1"))
        assertEquals("" to null, splitSeriesName(null))
    }

    @Test
    fun `authors with no books are dropped from the author tab`() {
        val rows = listOf(
            AuthorEntry(id = "a1", name = "Tolkien", numBooks = 3),
            AuthorEntry(id = "a2", name = "Nobody", numBooks = 0),
        ).toAuthorRows()

        assertEquals(listOf("Tolkien"), rows.map { it.name })
    }

    @Test
    fun `an author with a missing count is dropped rather than shown as zero`() {
        val rows = listOf(
            AuthorEntry(id = "a1", name = "Tolkien", numBooks = 3),
            AuthorEntry(id = "a2", name = "Nobody", numBooks = null),
        ).toAuthorRows()

        assertEquals(listOf("Tolkien"), rows.map { it.name })
    }

    @Test
    fun `author rows keep their server count and surname key`() {
        val rows = listOf(
            AuthorEntry(
                id = "a1",
                name = "Andy Weir",
                numBooks = 2,
                nameIgnorePrefix = "Andy Weir",
            ),
        ).toAuthorRows()

        assertEquals(1, rows.size)
        assertEquals(2, rows[0].bookCount)
        assertEquals("Weir, Andy", rows[0].displayName)
    }

    @Test
    fun `blank and duplicated authors are dropped`() {
        val rows = listOf(
            AuthorEntry(id = "a1", name = "Tolkien", numBooks = 3),
            AuthorEntry(id = "a1", name = "Tolkien", numBooks = 3),
            AuthorEntry(id = "a3", name = "   ", numBooks = 1),
        ).toAuthorRows()

        assertEquals(listOf("Tolkien"), rows.map { it.name })
    }
}

/** Series are filed under the first meaningful word, but keep their full name. */
class LeadingArticleTest {
    @Test
    fun `articles are stripped from the first word only`() {
        assertEquals("Song of Fire and Ice", Sorters.stripLeadingArticle("A Song of Fire and Ice"))
        assertEquals("Ember in the Ashes", Sorters.stripLeadingArticle("An Ember in the Ashes"))
        assertEquals("Horus Heresy", Sorters.stripLeadingArticle("The Horus Heresy"))
    }

    @Test
    fun `an article is only stripped as a standalone first word`() {
        assertEquals("A.T. Field", Sorters.stripLeadingArticle("A.T. Field"))
        assertEquals("Ace of Spades", Sorters.stripLeadingArticle("Ace of Spades"))
        assertEquals("Theory of Everything", Sorters.stripLeadingArticle("Theory of Everything"))
    }

    @Test
    fun `a series named exactly the article keeps its name`() {
        assertEquals("The", Sorters.stripLeadingArticle("The"))
        assertEquals("A", Sorters.stripLeadingArticle("A"))
    }

    @Test
    fun `an embedded article is left alone`() {
        assertEquals("Lord of the Rings", Sorters.stripLeadingArticle("Lord of the Rings"))
    }

    @Test
    fun `series sort ignores the leading article`() {
        val sorted = listOf("A Song of Fire and Ice", "Codex Alera", "The Horus Heresy", "Expanse")
            .sortedWith { a, b -> Sorters.compareIgnoringArticle(a, b) }

        assertEquals(listOf("Codex Alera", "Expanse", "The Horus Heresy", "A Song of Fire and Ice"), sorted)
    }

    @Test
    fun `ties fall back to the full name so ordering is deterministic`() {
        val forward = listOf("The Expanse", "Expanse")
            .sortedWith { a, b -> Sorters.compareIgnoringArticle(a, b) }
        val reversed = listOf("Expanse", "The Expanse")
            .sortedWith { a, b -> Sorters.compareIgnoringArticle(a, b) }

        assertEquals(forward, reversed)
    }
}

/**
 * Books in two series arrive joined by a comma. They must file under the first
 * component, or each one becomes a "series" of its own.
 */
class NestedSeriesTest {
    @Test
    fun `a book in two series files under the first component`() {
        assertEquals(
            "Fitz and the Fool" to "1",
            splitSeriesName("Fitz and the Fool #1, Realm of the Elderlings #14"),
        )
        assertEquals(
            "The Tawny Man" to "1",
            splitSeriesName("The Tawny Man #1, Realm of the Elderlings #7"),
        )
    }

    @Test
    fun `the sequence comes from the first component not the parent`() {
        // Fool's Assassin is #1 of Fitz and the Fool and #14 of Realm. Filed
        // under Fitz and the Fool it must sort as 1, not 14.
        assertEquals("1", splitSeriesName("Fitz and the Fool #1, Realm of the Elderlings #14").second)
    }

    @Test
    fun `a parent series with no sequence of its own still splits`() {
        assertEquals("Rain Wilds Chronicles" to "2", splitSeriesName("Rain Wilds Chronicles #2, Realm of the Elderlings"))
    }

    @Test
    fun `a plain series name is unaffected`() {
        assertEquals("Cedar Cove" to "04", splitSeriesName("Cedar Cove #04"))
        assertEquals("Realm of the Elderlings" to "5", splitSeriesName("Realm of the Elderlings #5"))
    }

    @Test
    fun `nested books group into one series instead of one row each`() {
        val rows = listOf(
            seriesItem("1", series="Fitz and the Fool #1, Realm of the Elderlings #14"),
            seriesItem("2", series="Fitz and the Fool #2, Realm of the Elderlings #15"),
            seriesItem("3", series="Fitz and the Fool #3, Realm of the Elderlings #16"),
            seriesItem("4", series="The Tawny Man #1, Realm of the Elderlings #7"),
        ).distinctSeries()

        assertEquals(listOf("Fitz and the Fool", "The Tawny Man"), rows.map { it.name })
        assertEquals(listOf(3, 1), rows.map { it.bookCount })
    }

    @Test
    fun `a series that only appears as a parent is not listed on its own`() {
        val rows = listOf(
            seriesItem("1", series="Fitz and the Fool #1, Realm of the Elderlings #14"),
        ).distinctSeries()

        assertEquals(listOf("Fitz and the Fool"), rows.map { it.name })
    }
}

/**
 * Shared builder. One param name per field so every test class can share it.
 * `series` is the raw name exactly as the server sends it, nested
 * comma-joined form included, so the grouping code is exercised as-is.
 */
internal fun seriesItem(
    id: String,
    title: String = "",
    author: String = "",
    authorId: String? = null,
    series: String? = null,
    seriesId: String? = null,
    sequence: String? = null,
) = ItemEntity(
    id = id,
    libraryId = "lib",
    title = title,
    authorName = author,
    authorId = authorId,
    seriesName = series,
    seriesId = seriesId,
    seriesSequence = sequence,
    narratorName = null,
    durationSeconds = 0.0,
    coverPath = null,
    relPath = null,
    currentTime = 0.0,
    isFinished = false,
    cachedAt = 0L,
)
