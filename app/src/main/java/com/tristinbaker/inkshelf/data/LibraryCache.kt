package com.tristinbaker.inkshelf.data

/**
 * Read-through cache over the minified library snapshot.
 *
 * Every successful server response is written here, so a cold start with no
 * network still renders a real library instead of an empty list. Server-side
 * sorting is preferred when online; [Sorters] reproduces the same order from
 * cache when it is not.
 */
class LibraryCache(
    private val libraryDao: LibraryDao,
    private val itemDao: ItemDao,
) {
    fun observeLibraries() = libraryDao.observeLibraries()

    fun observeItems(libraryId: String) = itemDao.observeItems(libraryId)

    fun observeSeriesItems(libraryId: String, seriesId: String) =
        itemDao.observeSeriesItems(libraryId, seriesId)

    suspend fun storeLibraries(libraries: List<com.tristinbaker.inkshelf.core.abs.Library>) {
        val now = System.currentTimeMillis()
        libraryDao.upsertLibraries(
            libraries.map {
                LibraryEntity(it.id, it.name, it.mediaType, now)
            },
        )
    }

    /**
     * Replaces the whole snapshot for a library in one transaction.
     *
     * This is only correct for an unfiltered, unpaginated fetch; a filtered or
     * page-limited response would look like the library shrank and evict the
     * rest. Callers must pass a complete listing.
     */
    suspend fun replaceItems(
        libraryId: String,
        items: List<com.tristinbaker.inkshelf.core.abs.LibraryItem>,
    ) {
        val now = System.currentTimeMillis()
        itemDao.replaceLibrarySnapshot(libraryId, items.map { it.toEntity(now) })
    }

    suspend fun itemCount(libraryId: String): Int = itemDao.count(libraryId)

    /** Used on sign-out so one account's library is not shown to the next. */
    suspend fun clear() {
        itemDao.clearItems()
        libraryDao.clearLibraries()
    }
}
