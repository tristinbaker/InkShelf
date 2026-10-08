package com.tristinbaker.inkshelf

import com.tristinbaker.inkshelf.data.Sorters
import com.tristinbaker.inkshelf.ui.BrowseListEntry
import com.tristinbaker.inkshelf.ui.components.sectionTarget
import com.tristinbaker.inkshelf.ui.headerIndices
import com.tristinbaker.inkshelf.ui.letterSegments
import org.junit.Assert.assertEquals
import org.junit.Test

class LetterSectionsTest {

    @Test
    fun `title buckets use the plain first letter`() {
        assertEquals("W", Sorters.titleLetterBucket("Warbreaker"))
        assertEquals("H", Sorters.titleLetterBucket("horizon"))
    }

    @Test
    fun `titles leading with a digit share one bucket`() {
        // "01 Warbreaker" and "1984" must not each earn their own bar.
        assertEquals("#", Sorters.titleLetterBucket("01 Warbreaker"))
        assertEquals("#", Sorters.titleLetterBucket("1984"))
        assertEquals("#", Sorters.titleLetterBucket("---"))
        assertEquals(Sorters.NON_LETTER_BUCKET, Sorters.titleLetterBucket(""))
    }

    @Test
    fun `titles keep their article because the server sorts them that way`() {
        // Items are ordered by the plain title, so "The Hobbit" genuinely sits
        // in the T run. Stripping here would misplace its bar.
        assertEquals("T", Sorters.titleLetterBucket("The Hobbit"))
    }

    @Test
    fun `name buckets strip the leading article`() {
        // Series and authors are ordered with the article-stripped key.
        assertEquals("H", Sorters.nameLetterBucket("The Horus Heresy"))
        assertEquals("S", Sorters.nameLetterBucket("A Song of Ice and Fire"))
        assertEquals("E", Sorters.nameLetterBucket("the Expanse"))
    }

    @Test
    fun `name bucket does not strip an initialism`() {
        assertEquals("A", Sorters.nameLetterBucket("A.T. Field"))
    }

    @Test
    fun `name bucket survives a name that is only an article`() {
        assertEquals("T", Sorters.nameLetterBucket("The"))
    }

    @Test
    fun `segments interleave a header only when the letter changes`() {
        val titles = listOf("Apple", "Avocado", "Banana", "Blueberry", "Cherry")
        val segments = letterSegments(titles) { Sorters.titleLetterBucket(it) }

        assertEquals(
            listOf(
                BrowseListEntry.Header("A"),
                BrowseListEntry.Row(0),
                BrowseListEntry.Row(1),
                BrowseListEntry.Header("B"),
                BrowseListEntry.Row(2),
                BrowseListEntry.Row(3),
                BrowseListEntry.Header("C"),
                BrowseListEntry.Row(4),
            ),
            segments,
        )
    }

    @Test
    fun `one header covers a whole run including the digit bucket`() {
        val titles = listOf("01 Warbreaker", "1984", "Apple")
        val segments = letterSegments(titles) { Sorters.titleLetterBucket(it) }

        assertEquals(
            listOf(
                BrowseListEntry.Header("#"),
                BrowseListEntry.Row(0),
                BrowseListEntry.Row(1),
                BrowseListEntry.Header("A"),
                BrowseListEntry.Row(2),
            ),
            segments,
        )
    }

    @Test
    fun `reversing the list reverses the bars without extra handling`() {
        val titles = listOf("Apple", "Banana", "Cherry")
        val segments = letterSegments(titles.reversed()) { Sorters.titleLetterBucket(it) }

        assertEquals(
            listOf(
                BrowseListEntry.Header("C"),
                BrowseListEntry.Row(0),
                BrowseListEntry.Header("B"),
                BrowseListEntry.Row(1),
                BrowseListEntry.Header("A"),
                BrowseListEntry.Row(2),
            ),
            segments,
        )
    }

    @Test
    fun `an empty list produces no bars`() {
        assertEquals(emptyList<BrowseListEntry>(), letterSegments(emptyList<String>()) { "A" })
    }

    @Test
    fun `every row index is emitted exactly once and in order`() {
        val titles = (1..40).map { "Book $it" }
        val segments = letterSegments(titles) { Sorters.titleLetterBucket(it) }
        val rows = segments.filterIsInstance<BrowseListEntry.Row>().map { it.index }

        assertEquals((0 until titles.size).toList(), rows)
    }
}

class SectionJumpTest {

    // Bars at 0 (A), 5 (B), 12 (C) in a 20-item list.
    private val starts = listOf(0, 5, 12)

    private fun jump(first: Int, forward: Boolean, offset: Int = 0) =
        sectionTarget(starts, first, offset, lastIndex = 19, forward = forward)

    @Test
    fun `down goes to the next bar`() {
        assertEquals(5, jump(first = 2, forward = true))
        assertEquals(12, jump(first = 5, forward = true))
    }

    @Test
    fun `down past the last bar goes to the end`() {
        assertEquals(19, jump(first = 14, forward = true))
    }

    @Test
    fun `up from inside a section goes to its own bar first`() {
        assertEquals(5, jump(first = 8, forward = false))
    }

    @Test
    fun `up from exactly on a bar goes to the one before`() {
        assertEquals(5, jump(first = 12, forward = false))
    }

    @Test
    fun `up from a bar scrolled partly off the top goes back to that bar`() {
        assertEquals(12, jump(first = 12, forward = false, offset = 30))
    }

    @Test
    fun `lists with no bars jump to the ends`() {
        assertEquals(0, sectionTarget(emptyList(), 7, 0, lastIndex = 19, forward = false))
        assertEquals(19, sectionTarget(emptyList(), 7, 0, lastIndex = 19, forward = true))
    }

    @Test
    fun `header positions skip whatever comes before the first bar`() {
        val segments = letterSegments(listOf("Apple", "Avocado", "Banana")) { it.take(1) }
        assertEquals(listOf(1, 4), segments.headerIndices(leadingItems = 1))
    }
}
