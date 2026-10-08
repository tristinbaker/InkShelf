package com.tristinbaker.inkshelf.ui

/**
 * One row in a letter-segmented browse list: either a section bar or a real
 * entry.
 *
 * The list is flattened into a single sequence rather than nested `groupBy`
 * buckets because the list is lazy. A `LazyColumn` needs one flat stream of
 * items so it can keep only the visible window composed, and because the source
 * lists are already in display order, walking them once and starting a new
 * section whenever the bucket letter changes is both simpler and cheaper than
 * grouping first.
 */
sealed interface BrowseListEntry {
    data class Header(val letter: String) : BrowseListEntry
    data class Row(val index: Int) : BrowseListEntry
}

/**
 * Interleaves section bars into an already-ordered list.
 *
 * [bucketOf] must return the same letter the list is sorted by, so the bars
 * land where the entries actually are. Because it emits a header only when the
 * letter changes, reversing the underlying order reverses the bars too, with no
 * separate handling for a descending sort.
 */
fun <T> letterSegments(
    entries: List<T>,
    bucketOf: (T) -> String,
): List<BrowseListEntry> {
    if (entries.isEmpty()) return emptyList()
    val out = ArrayList<BrowseListEntry>(entries.size + 8)
    var previous: String? = null
    entries.forEachIndexed { index, entry ->
        val letter = bucketOf(entry)
        if (letter != previous) {
            out += BrowseListEntry.Header(letter)
            previous = letter
        }
        out += BrowseListEntry.Row(index)
    }
    return out
}

/**
 * Where each section bar sits in the lazy list, for jumping between letters.
 * [leadingItems] counts whatever the list emits ahead of the first entry, so the
 * positions line up with the list's own item indices.
 */
fun List<BrowseListEntry>.headerIndices(leadingItems: Int = 0): List<Int> =
    indices.filter { this[it] is BrowseListEntry.Header }.map { it + leadingItems }
