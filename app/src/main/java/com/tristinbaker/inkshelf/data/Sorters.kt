package com.tristinbaker.inkshelf.data

import com.tristinbaker.inkshelf.core.abs.BrowseOrder
import java.text.Collator
import java.util.Locale

/**
 * Client-side ordering, used for the offline path and for grouping books under
 * a series. Online browsing normally sorts server-side with the keys in
 * Sorts.kt, but the cache still has to produce the same order on its own.
 *
 * `Collator` with PRIMARY strength gives the natural, case- and
 * accent-insensitive comparison the server's SQL `COLLATE NOCASE` approximates,
 * without needing ICU.
 */
object Sorters {

    private val collator: Collator = Collator.getInstance(Locale.ROOT).apply {
        strength = Collator.PRIMARY
    }

    fun compareText(a: String, b: String): Int = collator.compare(a, b)

    private val LEADING_ARTICLES = setOf("a", "an", "the")

    /**
     * Series are filed under the first word that carries meaning, so "A Song of
     * Fire and Ice" files under S and "The Horus Heresy" under H, the way a
     * library shelf would. Only the ordering changes: [stripLeadingArticle] is
     * never used to build what gets displayed.
     *
     * The article has to be a standalone first word, so "A.T. Field" is left
     * alone, and a series named exactly "The" keeps its name because there is
     * nothing left to file it under.
     */
    fun stripLeadingArticle(name: String): String {
        val trimmed = name.trim()
        val space = trimmed.indexOf(' ')
        if (space <= 0) return trimmed
        if (trimmed.substring(0, space).lowercase(Locale.ROOT) !in LEADING_ARTICLES) return trimmed
        val rest = trimmed.substring(space + 1).trim()
        return if (rest.isEmpty()) trimmed else rest
    }

    /**
     * Ties fall back to the full name so "Expanse" and "The Expanse" still have a
     * stable, deterministic order instead of depending on fetch order.
     */
    /**
     * Orders by a sort key that may already be article-stripped by the server
     * ("Weir, Andy"). Falls back to stripping it here so cache-derived rows, which
     * have no such key, file alongside server rows in the same place.
     */
    fun compareSortKey(a: String, b: String): Int =
        compareText(stripLeadingArticle(a), stripLeadingArticle(b))

    fun compareIgnoringArticle(a: String, b: String): Int {
        val primary = compareText(stripLeadingArticle(a), stripLeadingArticle(b))
        return if (primary != 0) primary else compareText(a, b)
    }

    fun compareItems(
        a: ItemEntity,
        b: ItemEntity,
        order: BrowseOrder,
        descending: Boolean,
    ): Int {
        val primary = when (order) {
            BrowseOrder.TITLE -> compareText(a.title, b.title)
            BrowseOrder.AUTHOR -> compareText(a.authorName, b.authorName)
            BrowseOrder.SERIES ->
                compareIgnoringArticle(a.seriesName.orEmpty(), b.seriesName.orEmpty())
        }
        // Secondary keys keep ordering stable and predictable when the primary
        // field ties, which is common with duplicate titles across editions.
        val secondary = when {
            primary != 0 -> primary
            order == BrowseOrder.AUTHOR -> compareText(a.title, b.title)
            else -> compareText(a.authorName, b.authorName)
        }
        val tertiary = if (secondary != 0) secondary else a.id.compareTo(b.id)
        return if (descending) -tertiary else tertiary
    }

    fun sortedItems(
        items: List<ItemEntity>,
        order: BrowseOrder,
        descending: Boolean,
    ): List<ItemEntity> = items.sortedWith { a, b -> compareItems(a, b, order, descending) }

    /**
     * Groups books by series, ordered alphabetically, with ungrouped books
     * collected under a single trailing bucket. Books inside a group follow
     * their series sequence so reading order is correct.
     */
    fun groupBySeries(items: List<ItemEntity>, descending: Boolean): List<SeriesGroup> {
        val grouped = items.filter { !it.seriesId.isNullOrBlank() }
            .groupBy { it.seriesId!! to (it.seriesName.orEmpty()) }
        val loose = items.filter { it.seriesId.isNullOrBlank() }

        val groups = grouped.map { (key, books) ->
            SeriesGroup(
                id = key.first,
                name = key.second,
                books = books.sortedWith { a, b -> compareSequence(a, b) },
            )
        }.sortedWith { a, b ->
            val c = compareIgnoringArticle(a.name, b.name)
            if (descending) -c else c
        }

        return if (loose.isEmpty()) {
            groups
        } else {
            groups + SeriesGroup(
                id = LOOSE_SERIES_ID,
                name = "No series",
                books = loose.sortedWith { a, b -> compareText(a.title, b.title) },
            )
        }
    }

    /** Series sequence is a free-text string, so compare digit runs numerically. */
    fun compareSequence(a: ItemEntity, b: ItemEntity): Int {
        val result = naturalCompare(a.seriesSequence.orEmpty(), b.seriesSequence.orEmpty())
        return if (result != 0) result else compareText(a.title, b.title)
    }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(startB, j).trimStart('0').ifEmpty { "0" }
                if (numA.length != numB.length) return numA.length - numB.length
                val c = numA.compareTo(numB)
                if (c != 0) return c
            } else {
                val c = a[i].compareTo(b[j])
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    const val LOOSE_SERIES_ID = "__no_series__"

    /**
     * Bucket for a title, which is ordered as-is.
     *
     * The server sorts items by the plain title field, so a book called "The
     * Hobbit" really does sit in the T run and must be labelled T. Stripping the
     * article here would put its bar in the wrong place relative to its
     * neighbours.
     */
    fun titleLetterBucket(title: String): String = letterBucket(title, stripArticle = false)

    /**
     * Bucket for an author or series name.
     *
     * Series are ordered with the article-stripping name key the server uses for
     * `nameIgnorePrefix` (and [compareIgnoringArticle] mirrors it offline), so
     * "The Horus Heresy" sits in the H run. The bar has to say H, otherwise the
     * header lands in the middle of the block it is labelling.
     */
    fun nameLetterBucket(name: String): String = letterBucket(name, stripArticle = true)

    /**
     * Bucket for the author list, which is filed by surname.
     *
     * Taken from the surname rather than the leading character so "Weir, Andy"
     * lands under W. An article is not stripped here: "The Author" files under T
     * as a surname, which is what a library shelf would do.
     */
    fun lastNameLetterBucket(name: String): String {
        val surname = lastNameSortKey(name)
        val first = surname.trimStart().firstOrNull() ?: return NON_LETTER_BUCKET
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper.toString() else NON_LETTER_BUCKET
    }

    /**
     * Anything that does not open with an A-Z letter, chiefly titles that lead
     * with a digit like "01 Warbreaker", shares one leading bucket. Filing those
     * under their own bar per digit would produce a run of near-identical "#"
     * headers, and they sort together at whichever end the order puts them.
     */
    const val NON_LETTER_BUCKET = "#"

    private fun letterBucket(raw: String, stripArticle: Boolean): String {
        val source = if (stripArticle) stripLeadingArticle(raw) else raw
        val first = source.trimStart().firstOrNull() ?: return NON_LETTER_BUCKET
        val upper = first.uppercaseChar()
        return if (upper in 'A'..'Z') upper.toString() else NON_LETTER_BUCKET
    }

    private val ROLE_MARKER = Regex("\\s+[-/]\\s+")
    private val GENERATIONAL_SUFFIX = setOf("jr.", "jr", "sr.", "sr", "ii", "iii", "iv", "v")

    /**
     * The surname is the last word of the *primary* credited author, with any
     * generational suffix left with the given names.
     *
     * Audiobookshelf does not hand us a clean "First Last" string. It joins
     * co-authors with commas ("George Orwell, Joe White") and appends the role
     * after a spaced dash or slash ("Joe White - adaptation",
     * "Christopher Paolini / Read by Gerard Doyle"). Taking the last word of
     * the whole string files all of those under "adaptation" and "Doyle", which
     * is why the role marker and the co-author list are cut away first.
     *
     * The dashed forms are spaced, so a real hyphenated surname such as
     * "Dembski-Bowden" is not mistaken for a role marker.
     */
    private fun primaryAuthorName(raw: String): String {
        val cut = ROLE_MARKER.find(raw)?.range?.first ?: raw.length
        return raw.substring(0, cut).substringBefore(',').trim()
    }

    private fun splitSurname(primary: String): Pair<String, String> {
        val parts = primary.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return primary to ""
        val last = parts.last()
        val head = parts.dropLast(1)
        // "Martin Luther King Jr." files under King, not under "King Jr.".
        if (last.lowercase() in GENERATIONAL_SUFFIX && head.isNotEmpty()) {
            return head.last() to (head.dropLast(1) + last).joinToString(" ")
        }
        return last to head.joinToString(" ")
    }

    /** "Andy Weir" shown as "Weir, Andy", the order a library shelves authors in. */
    fun toLastFirst(name: String): String {
        val primary = primaryAuthorName(name)
        if (primary.isEmpty()) return name.trim()
        val (surname, given) = splitSurname(primary)
        return if (given.isEmpty()) surname else "$surname, $given"
    }

    /**
     * Sort key for last-name ordering: the surname alone. Compared as its own
     * field rather than by reordering and re-parsing the display string, so that
     * "Smith, Anna" and "Smith, Peter" land next to each other.
     */
    fun lastNameSortKey(name: String): String {
        val primary = primaryAuthorName(name)
        if (primary.isEmpty()) return name.trim()
        return splitSurname(primary).first
    }
}

data class SeriesGroup(
    val id: String,
    val name: String,
    val books: List<ItemEntity>,
)
