package com.tristinbaker.inkshelf.core.abs

/**
 * Sort keys accepted by Audiobookshelf v2.37.0. Anything outside these lists is
 * silently ignored by the server, which would look like a broken sort button
 * rather than a rejected key, so we keep the allowlists explicit.
 *
 * Verified against:
 *   server/utils/queries/libraryItemsBookFilters.js:265-297  (items)
 *   server/utils/queries/seriesFilters.js:129-151            (series)
 *   server/controllers/LibraryController.js                  (authors)
 */
object ItemSort {
    const val TITLE = "media.metadata.title"
    const val AUTHOR = "media.metadata.authorName"
    const val AUTHOR_LF = "media.metadata.authorNameLF"
    const val ADDED_AT = "addedAt"
    const val DURATION = "media.duration"
    const val PUBLISHED_YEAR = "media.metadata.publishedYear"
    const val PROGRESS = "progress"

    /** Only honoured when the query is filtered by `series` (libraryItemsBookFilters.js:404). */
    const val SEQUENCE = "sequence"
}

object SeriesSort {
    const val NAME = "name"
    const val NUM_BOOKS = "numBooks"
    const val ADDED_AT = "addedAt"
    const val TOTAL_DURATION = "totalDuration"
    const val LAST_BOOK_ADDED = "lastBookAdded"
    const val LAST_BOOK_UPDATED = "lastBookUpdated"
}

object AuthorSort {
    const val NAME = "name"
    const val LAST_FIRST = "lastFirst"
    const val ADDED_AT = "addedAt"
    const val NUM_BOOKS = "numBooks"
}

/** The three browse orders the UI offers, each with a direction. */
enum class BrowseOrder(
    val label: String,
    val itemSort: String,
    val seriesSort: String,
    val authorSort: String,
) {
    TITLE("Title", ItemSort.TITLE, SeriesSort.NAME, AuthorSort.NAME),
    AUTHOR("Author", ItemSort.AUTHOR, SeriesSort.NAME, AuthorSort.LAST_FIRST),
    SERIES("Series", ItemSort.TITLE, SeriesSort.NAME, AuthorSort.NAME),
    ;

    /**
     * Within a series the books are ordered by their position in that series
     * rather than alphabetically, and the server only honours `sequence` when
     * the query is filtered by series (libraryItemsBookFilters.js:404).
     */
    fun sortForItems(isFilteredBySeries: Boolean): String =
        if (this == SERIES && isFilteredBySeries) ItemSort.SEQUENCE else itemSort
}
