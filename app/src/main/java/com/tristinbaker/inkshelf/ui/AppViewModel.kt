package com.tristinbaker.inkshelf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tristinbaker.inkshelf.ServiceLocator
import com.tristinbaker.inkshelf.core.abs.AuthorEntry
import com.tristinbaker.inkshelf.core.abs.BrowseOrder
import com.tristinbaker.inkshelf.core.abs.FilterGroup
import com.tristinbaker.inkshelf.core.abs.ItemSort
import com.tristinbaker.inkshelf.core.abs.LibraryItem
import com.tristinbaker.inkshelf.data.BookMetadataEntity
import com.tristinbaker.inkshelf.data.toBookMetadata
import com.tristinbaker.inkshelf.data.toLibraryItem
import com.tristinbaker.inkshelf.core.abs.buildFilter
import android.content.Context
import android.net.Uri
import com.tristinbaker.inkshelf.core.eink.EinkMode
import com.tristinbaker.inkshelf.core.storage.DownloadFolder
import com.tristinbaker.inkshelf.core.net.ApiResult
import com.tristinbaker.inkshelf.core.net.ServerUrl
import com.tristinbaker.inkshelf.core.net.Session
import com.tristinbaker.inkshelf.core.net.TlsProbe
import com.tristinbaker.inkshelf.data.ItemEntity
import com.tristinbaker.inkshelf.data.LibraryEntity
import com.tristinbaker.inkshelf.data.Sorters
import com.tristinbaker.inkshelf.data.toEntity
import com.tristinbaker.inkshelf.download.DownloadCommand
import com.tristinbaker.inkshelf.download.DownloadSummary
import com.tristinbaker.inkshelf.playback.PlayCommand
import com.tristinbaker.inkshelf.playback.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface Route {
    data object Login : Route
    data object Libraries : Route
    data class Items(val title: String) : Route
    data object Downloaded : Route
    data class Book(val itemId: String) : Route
    data object Player : Route
    data object Settings : Route
    data object Diagnostics : Route
}

data class BrowseState(
    val loading: Boolean = false,
    val order: BrowseOrder = BrowseOrder.TITLE,
    val descending: Boolean = false,
    val items: List<ItemEntity> = emptyList(),
    val authors: List<AuthorRow> = emptyList(),
    val series: List<SeriesRow> = emptyList(),
    val filterLabel: String? = null,
    val error: String? = null,
    val fromCache: Boolean = false,
    val totalOnServer: Int = 0,
)

/**
 * `serverId` is the Audiobookshelf author/series id, used for the server-side
 * drill-down filter. It is null whenever the list response carried only the
 * display name, which is the common case: minified rows on this server have no
 * `authors[]` or `series[]` array at all. [name] is therefore what the tabs are
 * built from, and [serverId] is only ever an optimisation.
 */
data class AuthorRow(
    val name: String,
    val bookCount: Int,
    val serverId: String? = null,
    /** Server-computed article-stripped key; null when derived from the cache. */
    val sortKey: String? = null,
) {
    /** "Andy Weir" shown as "Weir, Andy", the order libraries file authors in. */
    val displayName: String get() = Sorters.toLastFirst(name)

    /**
     * Orders by surname. The server's `nameIgnorePrefix` key is the name with a
     * leading article removed, not reordered, so it cannot drive last-name order;
     * the surname is taken from the name itself. Ties fall back to the full name
     * so two authors with the same surname keep a stable, deterministic order.
     */
    fun compare(other: AuthorRow): Int =
        Sorters.compareText(Sorters.lastNameSortKey(name), Sorters.lastNameSortKey(other.name))
            .takeIf { it != 0 }
            ?: Sorters.compareText(name, other.name)
}

data class SeriesRow(
    val name: String,
    val bookCount: Int,
    val serverId: String? = null,
    val sortKey: String? = null,
) {
    fun compare(other: SeriesRow): Int = Sorters.compareSortKey(sortKey ?: name, other.sortKey ?: other.name)
        .takeIf { it != 0 }
        ?: Sorters.compareText(name, other.name)
}

data class AppState(
    val booting: Boolean = true,
    val route: Route = Route.Login,
    val session: Session? = null,
    val serverSupportsLocalLogin: Boolean = true,
    val tlsProbe: TlsProbe? = null,
    val libraries: List<LibraryEntity> = emptyList(),
    val selectedLibrary: LibraryEntity? = null,
    val browse: BrowseState = BrowseState(),
    val einkMode: EinkMode = EinkMode.DEFAULT,
    /** Display name of the folder downloads go to. */
    val downloadFolderLabel: String = DownloadFolder.DEFAULT_LABEL,
    /** False when a picked folder has gone, e.g. the SD card was removed. */
    val downloadFolderUsable: Boolean = true,
    val einkAvailable: Boolean = false,
    val einkError: String? = null,
    val einkTransacts: Int = 0,
    val message: String? = null,
    val busy: Boolean = false,
    val book: LibraryItem? = null,
    val itemsTitle: String = "",
    val player: PlayerState = PlayerState(),
    val downloads: Map<String, DownloadSummary> = emptyMap(),
    /** Books with files on this device, reachable with no connection. */
    val downloaded: List<BookMetadataEntity> = emptyList(),
    /** Cover art next to titles in lists. Off is the faster-refreshing option. */
    val showCovers: Boolean = true,
)

class AppViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _ui = MutableStateFlow(AppState())
    val ui: StateFlow<AppState> = _ui.asStateFlow()

    init {
        locator.meink.init()
        _ui.value = _ui.value.copy(
            einkMode = locator.settings.einkMode,
            downloadFolderLabel = locator.downloadFolderLabel(),
            downloadFolderUsable = locator.downloadFolderUsable(),
            showCovers = locator.settings.showCovers,
            einkAvailable = locator.meink.isAvailable,
            einkError = locator.meink.status.value.lastError,
        )
        applyEinkMode(locator.settings.einkMode)
        viewModelScope.launch { restoreSession() }
        resumeUnfinishedDownloads()
        observeCache()
        observePlayback()
        observeDownloads()
        observeOfflineShelf()
    }

    /**
     * Starts the download service if anything is still outstanding.
     *
     * The service is otherwise only started by the Download, Remove and Retry
     * taps, so a process killed mid-download came back with rows stuck in
     * RUNNING and nothing to recover them. Starting it here on launch is what
     * lets the service re-queue those rows and carry on by itself. It waits for
     * the session first, because a queued row cannot be fetched without one.
     */
    private fun resumeUnfinishedDownloads() {
        viewModelScope.launch {
            restoreSession()
            val dao = locator.database.downloadDao()
            if (dao.unfinished().isNotEmpty()) {
                locator.startDownloads()
            }
        }
    }

    /**
     * The shelf has to be built from the device, not the server: every screen
     * that lists books is otherwise server-driven, so a downloaded book would be
     * unreachable with the network off, which defeats having downloaded it.
     */
    private fun observeOfflineShelf() {
        viewModelScope.launch {
            // Both have to be watched together. Reading summaries.value inside a
            // collector for the metadata only worked when the metadata happened
            // to arrive second: on a cold start offline it arrives first, every
            // book looks incomplete, and nothing re-runs to correct it, so the
            // Downloads shelf stays invisible with books sitting on disk.
            combine(
                locator.database.bookMetadataDao().observeAll(),
                locator.downloads.summaries,
            ) { rows, summaries ->
                val complete = summaries.filterValues { it.isComplete }.keys
                rows.filter { it.itemId in complete }
            }.collect { downloaded ->
                _ui.value = _ui.value.copy(downloaded = downloaded)
            }
        }
    }

    fun openDownloaded() {
        _ui.value = _ui.value.copy(route = Route.Downloaded)
    }

    fun backFromDownloaded() {
        _ui.value = _ui.value.copy(route = Route.Libraries)
    }

    /** Opens a book straight from its cached description, never asking the server. */
    fun openDownloadedBook(itemId: String) {
        _ui.value = _ui.value.copy(route = Route.Book(itemId), busy = true)
        viewModelScope.launch {
            val cached = cachedBook(itemId)
            _ui.value = _ui.value.copy(busy = false, book = cached)
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            locator.playback.state.collect { player ->
                _ui.value = _ui.value.copy(player = player)
            }
        }
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            locator.downloads.summaries.collect { summaries ->
                _ui.value = _ui.value.copy(downloads = summaries)
            }
        }
    }

    // ---- session ----------------------------------------------------------

    private suspend fun restoreSession() {
        val session = locator.authStore.session.value
        if (session == null) {
            _ui.value = _ui.value.copy(booting = false, route = Route.Login)
            return
        }
        _ui.value = _ui.value.copy(session = session)
        when (val parsed = ServerUrl.parse(session.serverUrl)) {
            is ServerUrl.Result.Invalid -> {
                _ui.value = _ui.value.copy(
                    booting = false,
                    route = Route.Login,
                    message = "Saved server address is no longer valid: ${parsed.reason}",
                )
            }

            is ServerUrl.Result.Valid -> {
                resumeLastPlayed()
                _ui.value = _ui.value.copy(
                    booting = false,
                    route = if (resumeTarget() != null) Route.Player else Route.Libraries,
                )
                loadLibraries(parsed.url)
            }
        }
    }

    /**
     * The book to reopen on, or null to open the library.
     *
     * Prefers whatever the playback service still has loaded, so a listener
     * whose service survived gets their running book back untouched. Failing
     * that it falls back to the recorded last-played book, because closing the
     * app takes that service down with it.
     */
    private fun resumeTarget(): String? =
        locator.playback.state.value.itemId ?: locator.settings.lastPlayedItemId

    /**
     * Reopens on the player rather than the library when there is a book to go
     * back to.
     *
     * The player screen renders from the playback state alone, which is empty
     * after a process death, so the book is loaded again to fill it in. That
     * picks up the stored bookmark, so it comes back at the same place, and it
     * works with no connection for anything downloaded.
     */
    private fun resumeLastPlayed() {
        val itemId = resumeTarget() ?: return
        if (locator.playback.state.value.hasBook) return
        locator.startPlayback()
        // Loaded, not played. Closing the app stops the audio, so the book is
        // waiting at its bookmark instead of talking the moment we come back.
        locator.playback.send(PlayCommand.Load(itemId))
    }

    fun checkServer(rawUrl: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val parsed = ServerUrl.parse(rawUrl)) {
                is ServerUrl.Result.Invalid ->
                    _ui.value = _ui.value.copy(busy = false, message = parsed.reason)

                is ServerUrl.Result.Valid -> {
                    val probe = locator.tlsPinner.probe(parsed.url)
                    _ui.value = _ui.value.copy(
                        busy = false,
                        tlsProbe = probe,
                        message = null,
                    )
                    when (val status = locator.api.status(parsed.url)) {
                        is ApiResult.Failure -> _ui.value = _ui.value.copy(
                            serverSupportsLocalLogin = true,
                            message = status.message,
                        )

                        is ApiResult.Ok -> _ui.value = _ui.value.copy(
                            serverSupportsLocalLogin = status.value.supportsLocalLogin,
                        )
                    }
                }
            }
        }
    }

    fun login(rawUrl: String, username: String, password: String, trustCertificate: Boolean) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = null)
            when (val parsed = ServerUrl.parse(rawUrl)) {
                is ServerUrl.Result.Invalid -> {
                    _ui.value = _ui.value.copy(busy = false, message = parsed.reason)
                    return@launch
                }

                is ServerUrl.Result.Valid -> {
                    if (trustCertificate) {
                        val probe = locator.tlsPinner.probe(parsed.url)
                        if (probe is TlsProbe.SelfSigned) {
                            locator.tlsPinner.pin(parsed.url.hostPort, probe.fingerprint)
                        }
                    }
                    when (val result = locator.api.login(parsed.url, username, password)) {
                        is ApiResult.Failure ->
                            _ui.value = _ui.value.copy(busy = false, message = result.message)

                        is ApiResult.Ok -> {
                            _ui.value = _ui.value.copy(
                                busy = false,
                                session = result.value,
                                route = Route.Libraries,
                            )
                            loadLibraries(parsed.url)
                        }
                    }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            locator.api.signOut()
            locator.libraryCache.clear()
            _ui.value = AppState(
                booting = false,
                route = Route.Login,
                session = null,
                einkMode = locator.settings.einkMode,
                einkAvailable = locator.meink.isAvailable,
            )
        }
    }

    // ---- libraries --------------------------------------------------------

    private fun observeCache() {
        viewModelScope.launch {
            combine(
                locator.libraryCache.observeLibraries(),
                locator.authStore.session,
            ) { libs, session -> libs to session }
                .collect { (libs, _) ->
                    _ui.value = _ui.value.copy(libraries = libs)
                }
        }
    }

    fun loadLibraries(url: ServerUrl? = null) {
        viewModelScope.launch {
            val serverUrl = url ?: currentUrl() ?: return@launch
            _ui.value = _ui.value.copy(browse = _ui.value.browse.copy(loading = true, error = null))
            when (val result = locator.api.libraries(serverUrl)) {
                is ApiResult.Failure ->
                    _ui.value = _ui.value.copy(
                        browse = _ui.value.browse.copy(loading = false, error = result.message),
                    )

                is ApiResult.Ok -> {
                    locator.libraryCache.storeLibraries(result.value)
                    val target = _ui.value.selectedLibrary?.id
                        ?.takeIf { id -> result.value.any { it.id == id } }
                        ?: locator.settings.libraryId?.takeIf { id ->
                            result.value.any { it.id == id }
                        }
                        ?: result.value.firstOrNull()?.id
                    if (target != null) {
                        locator.settings.libraryId = target
                        _ui.value = _ui.value.copy(
                            selectedLibrary = result.value.firstOrNull { it.id == target }
                                ?.let { LibraryEntity(it.id, it.name, it.mediaType, 0L) },
                        )
                        refreshItems(serverUrl, target)
                    } else {
                        _ui.value = _ui.value.copy(
                            browse = _ui.value.browse.copy(
                                loading = false,
                                error = "No libraries on this server yet",
                            ),
                        )
                    }
                }
            }
        }
    }

    fun selectLibrary(library: LibraryEntity) {
        locator.settings.libraryId = library.id
        _ui.value = _ui.value.copy(selectedLibrary = library)
        refreshItems()
    }

    fun setOrder(order: BrowseOrder) {
        locator.settings.browseOrder = order
        _ui.value = _ui.value.copy(
            browse = _ui.value.browse.copy(order = order, filterLabel = null),
        )
        refreshItems()
    }

    fun toggleDirection() {
        val next = !_ui.value.browse.descending
        locator.settings.descending = next
        _ui.value = _ui.value.copy(browse = _ui.value.browse.copy(descending = next))
        refreshItems()
    }

    /**
     * Backs the "Try again" button.
     *
     * Starting the app with no connection fails at the library list, not the item
     * list, so there is no selected library to retry against and the button used
     * to do nothing at all. Restart from whichever step is actually missing.
     */
    fun refreshItems() {
        val url = currentUrl() ?: return
        val library = _ui.value.selectedLibrary
        if (library == null) {
            loadLibraries(url)
            return
        }
        viewModelScope.launch { refreshItems(url, library.id) }
    }

    private suspend fun refreshItems(url: ServerUrl, libraryId: String) {
        val state = _ui.value.browse
        _ui.value = _ui.value.copy(
            browse = state.copy(loading = true, error = null, filterLabel = null),
        )

        val order = state.order
        val descending = state.descending

        // The Author and Series tabs have their own endpoints, which are the
        // authoritative list: a book in two series is counted under both, and the
        // list is not truncated to one page of items the way an items query is.
        // Deriving them from item rows silently under-reported both.
        if (order == BrowseOrder.SERIES) return loadSeries(url, libraryId, descending)
        if (order == BrowseOrder.AUTHOR) return loadAuthors(url, libraryId, descending)

        val sort = order.itemSort

        when (val result = locator.api.items(url, libraryId, sort, descending)) {
            is ApiResult.Failure -> {
                // Fall back to the cache so browsing still works with no network.
                val cached = locator.libraryCache.observeItems(libraryId).first()
                if (cached.isEmpty()) {
                    _ui.value = _ui.value.copy(
                        browse = _ui.value.browse.copy(
                            loading = false,
                            error = result.message,
                            fromCache = false,
                        ),
                    )
                } else {
                    _ui.value = _ui.value.copy(
                        browse = _ui.value.browse.copy(
                            loading = false,
                            error = "Showing saved copy. ${result.message}",
                            fromCache = true,
                            items = Sorters.sortedItems(cached, order, descending),
                            authors = cached.distinctAuthors(),
                            series = cached.distinctSeries(),
                            totalOnServer = cached.size,
                        ),
                    )
                }
            }

            is ApiResult.Ok -> {
                val rows = result.value.items
                if (state.filterLabel == null) {
                    locator.libraryCache.replaceItems(libraryId, rows)
                }
                val entities = rows.map { it.toEntity(System.currentTimeMillis()) }
                _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(
                        loading = false,
                        items = entities,
                        // Left in place so switching tabs and coming back does not
                        // flash empty while the dedicated endpoint is in flight.
                        authors = _ui.value.browse.authors,
                        series = _ui.value.browse.series,
                        error = null,
                        fromCache = false,
                        totalOnServer = result.value.totalCount,
                    ),
                )
            }
        }
    }

    private suspend fun loadSeries(url: ServerUrl, libraryId: String, descending: Boolean) {
        val cached = locator.libraryCache.observeItems(libraryId).first()
        when (val result = locator.api.allSeries(url, libraryId, descending)) {
            is ApiResult.Failure -> _ui.value = _ui.value.copy(
                browse = _ui.value.browse.copy(
                    loading = false,
                    error = "Showing saved copy. ${result.message}",
                    fromCache = true,
                    series = cached.distinctSeries(),
                ),
            )

            is ApiResult.Ok -> {
                val rows = result.value
                    .map {
                        SeriesRow(
                            name = it.name.trim(),
                            bookCount = it.bookCount,
                            serverId = it.id,
                            sortKey = it.nameIgnorePrefix,
                        )
                    }
                    .filter { it.name.isNotEmpty() }
                    .distinctBy { it.serverId }
                _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(
                        loading = false,
                        series = rows.sortedWith { a, b -> a.compare(b) },
                        error = null,
                        fromCache = false,
                        totalOnServer = rows.size,
                    ),
                )
            }
        }
    }

    private suspend fun loadAuthors(url: ServerUrl, libraryId: String, descending: Boolean) {
        val cached = locator.libraryCache.observeItems(libraryId).first()
        when (val result = locator.api.allAuthors(url, libraryId, descending)) {
            is ApiResult.Failure -> _ui.value = _ui.value.copy(
                browse = _ui.value.browse.copy(
                    loading = false,
                    error = "Showing saved copy. ${result.message}",
                    fromCache = true,
                    authors = cached.distinctAuthors(),
                ),
            )

            is ApiResult.Ok -> {
                val rows = result.value.toAuthorRows()
                _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(
                        loading = false,
                        authors = rows.sortedWith { a, b -> a.compare(b) },
                        error = null,
                        fromCache = false,
                        totalOnServer = rows.size,
                    ),
                )
            }
        }
    }

    private fun currentUrl(): ServerUrl? =
        _ui.value.session?.serverUrl?.let {
            (ServerUrl.parse(it) as? ServerUrl.Result.Valid)?.url
        }

    // ---- drill-down -------------------------------------------------------

    /**
     * Series with no [SeriesRow.serverId] are matched against the cache by name.
     * The cache holds every book last fetched for the library, so this returns
     * the same set the server would have, just without the round trip.
     */
    private suspend fun seriesFromCache(libraryId: String, seriesName: String): List<ItemEntity> =
        locator.libraryCache.observeItems(libraryId)
            .first()
            .filter { splitSeriesName(it.seriesName).first.equals(seriesName, ignoreCase = true) }
            .sortedWith { a, b ->
                val bySequence = compareSeriesSequence(
                    splitSeriesName(a.seriesName).second ?: a.seriesSequence,
                    splitSeriesName(b.seriesName).second ?: b.seriesSequence,
                )
                if (bySequence != 0) bySequence else Sorters.compareText(a.title, b.title)
            }

    /** Books with no known sequence sort after the numbered ones. */
    private fun compareSeriesSequence(a: String?, b: String?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> {
            val left = a.toDoubleOrNull()
            val right = b.toDoubleOrNull()
            if (left != null && right != null) left.compareTo(right) else Sorters.compareText(a, b)
        }
    }

    fun openSeries(series: SeriesRow) {
        _ui.value = _ui.value.copy(
            route = Route.Items(series.name),
            itemsTitle = series.name,
            browse = _ui.value.browse.copy(loading = true, filterLabel = "Series: ${series.name}"),
        )
        val library = _ui.value.selectedLibrary ?: return
        if (series.serverId == null) {
            viewModelScope.launch {
                val matches = seriesFromCache(library.id, series.name)
                _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(
                        loading = false,
                        items = matches,
                        error = null,
                        fromCache = true,
                        totalOnServer = matches.size,
                    ),
                )
            }
            return
        }
        val url = currentUrl() ?: return
        viewModelScope.launch {
            val filter = buildFilter(FilterGroup.SERIES, series.serverId)
            val result = locator.api.items(
                url = url,
                libraryId = library.id,
                sort = ItemSort.SEQUENCE,
                descending = false,
                filter = filter,
            )
            when (result) {
                is ApiResult.Failure -> _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(loading = false, error = result.message),
                )

                is ApiResult.Ok -> {
                    val entities = result.value.items.map {
                        it.toEntity(System.currentTimeMillis())
                    }
                    _ui.value = _ui.value.copy(
                        browse = _ui.value.browse.copy(
                            loading = false,
                            items = entities,
                            error = null,
                            fromCache = false,
                            totalOnServer = result.value.totalCount,
                        ),
                    )
                }
            }
        }
    }

    fun openAuthor(author: AuthorRow) {
        _ui.value = _ui.value.copy(
            route = Route.Items(author.name),
            itemsTitle = author.name,
            browse = _ui.value.browse.copy(loading = true, filterLabel = "Author: ${author.name}"),
        )
        val library = _ui.value.selectedLibrary ?: return
        if (author.serverId == null) {
            viewModelScope.launch {
                val matches = locator.libraryCache.observeItems(library.id)
                    .first()
                    .filter { it.authorName.trim().equals(author.name, ignoreCase = true) }
                    .sortedWith { a, b ->
                        Sorters.compareText(a.title, b.title) * if (_ui.value.browse.descending) -1 else 1
                    }
                _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(
                        loading = false,
                        items = matches,
                        error = null,
                        fromCache = true,
                        totalOnServer = matches.size,
                    ),
                )
            }
            return
        }
        val url = currentUrl() ?: return
        viewModelScope.launch {
            val result = locator.api.items(
                url = url,
                libraryId = library.id,
                sort = BrowseOrder.TITLE.itemSort,
                descending = _ui.value.browse.descending,
                filter = buildFilter(FilterGroup.AUTHORS, author.serverId),
            )
            when (result) {
                is ApiResult.Failure -> _ui.value = _ui.value.copy(
                    browse = _ui.value.browse.copy(loading = false, error = result.message),
                )

                is ApiResult.Ok -> {
                    val entities = result.value.items.map {
                        it.toEntity(System.currentTimeMillis())
                    }
                    _ui.value = _ui.value.copy(
                        browse = _ui.value.browse.copy(
                            loading = false,
                            items = entities,
                            error = null,
                            fromCache = false,
                            totalOnServer = result.value.totalCount,
                        ),
                    )
                }
            }
        }
    }

    fun backFromItems() {
        _ui.value = _ui.value.copy(
            route = Route.Libraries,
            itemsTitle = "",
            browse = _ui.value.browse.copy(filterLabel = null, items = emptyList()),
        )
        refreshItems()
    }

    // ---- book detail, playback & downloads -------------------------------

    fun openBook(item: ItemEntity) {
        _ui.value = _ui.value.copy(route = Route.Book(item.id), busy = true)
        refreshBook(item.id)
    }

    /**
     * Progress lives on the server, not in the cached row, so the book screen has
     * to re-read it whenever it might be stale: on open, and again after
     * playback, which is the only thing that changes it.
     */
    fun refreshBook(itemId: String? = null) {
        val id = itemId ?: _ui.value.book?.id ?: return
        val url = currentUrl()
        if (url == null) {
            showCachedBook(id)
            return
        }
        viewModelScope.launch {
            when (val result = locator.api.itemDetail(url, id)) {
                is ApiResult.Failure -> {
                    // A download caches everything this screen shows, so a book on
                    // disk can still be opened with no connection. Only fall back
                    // when there is something to fall back to.
                    val cached = cachedBook(id)
                    _ui.value = if (cached != null) {
                        _ui.value.copy(busy = false, book = cached)
                    } else {
                        // Keep whatever is on screen; a failed refresh should not
                        // wipe the page or replace it with an error.
                        _ui.value.copy(busy = false)
                    }
                }

                is ApiResult.Ok -> {
                    // Persist the chapter list so an offline play of this book
                    // has skip-chapter available. The download and play paths
                    // upsert for themselves; the book page is the only entry
                    // that holds the expanded item purely in memory.
                    val fresh = result.value
                    viewModelScope.launch {
                        val dao = locator.database.bookMetadataDao()
                        val cached = dao.get(id)
                        dao.upsert(
                            fresh.toBookMetadata(
                                currentTime = maxOf(
                                    cached?.currentTime ?: 0.0,
                                    fresh.userMediaProgress?.currentTime ?: 0.0,
                                ),
                                isFinished = fresh.userMediaProgress?.isFinished
                                    ?: (cached?.isFinished ?: false),
                                cachedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    _ui.value = _ui.value.copy(
                        busy = false,
                        book = fresh,
                    )
                }
            }
        }
    }

    private suspend fun cachedBook(itemId: String): LibraryItem? =
        locator.database.bookMetadataDao().get(itemId)?.toLibraryItem()

    private fun showCachedBook(itemId: String) {
        viewModelScope.launch {
            val cached = cachedBook(itemId)
            _ui.value = _ui.value.copy(
                busy = false,
                book = cached ?: _ui.value.book,
            )
        }
    }

    fun backFromBook() {
        val fromShelf = _ui.value.route == Route.Downloaded ||
            _ui.value.downloaded.any { it.itemId == _ui.value.book?.id }
        _ui.value = _ui.value.copy(
            route = if (fromShelf) Route.Downloaded else Route.Items(_ui.value.itemsTitle),
            book = null,
        )
    }

    /**
     * Hand off to the service. The command only lands if the service is already
     * collecting, so the intent starts it first and the command is sent from the
     * activity's resumed state.
     */
    fun playBook() {
        val id = _ui.value.book?.id ?: return
        _ui.value = _ui.value.copy(route = Route.Player)
        locator.startPlayback()
        locator.playback.send(PlayCommand.Play(id))
    }

    /**
     * Starts the book at a chapter tapped on the book page. The start position
     * rides on the Play command rather than following it with a SeekTo, which
     * would land before the media items exist and be overwritten by the
     * bookmark restore.
     */
    fun playChapterAt(startMs: Long) {
        val id = _ui.value.book?.id ?: return
        _ui.value = _ui.value.copy(route = Route.Player)
        locator.startPlayback()
        locator.playback.send(PlayCommand.Play(id, startAtMs = startMs))
    }

    fun openPlayer() {
        if (_ui.value.player.hasBook) _ui.value = _ui.value.copy(route = Route.Player)
    }

    fun backFromPlayer() {
        val itemId = _ui.value.player.itemId ?: _ui.value.book?.id
        _ui.value = _ui.value.copy(route = Route.Book(itemId.orEmpty()))
        if (itemId != null) refreshBook(itemId)
    }

    fun sendPlay(command: PlayCommand) {
        // The player is the only place commands like Stop or SetSpeed matter.
        if (_ui.value.player.hasBook) locator.startPlayback()
        locator.playback.send(command)
    }

    fun skipForward() = sendPlay(PlayCommand.SeekBy(30_000))

    fun skipBack() = sendPlay(PlayCommand.SeekBy(-30_000))

    fun downloadBook() {
        val id = _ui.value.book?.id ?: return
        locator.startDownloads()
        locator.downloads.send(DownloadCommand.Enqueue(id))
    }

    fun removeDownload() {
        val id = _ui.value.book?.id ?: return
        locator.startDownloads()
        locator.downloads.send(DownloadCommand.Remove(id))
    }

    fun retryDownload() {
        val id = _ui.value.book?.id ?: return
        locator.startDownloads()
        locator.downloads.send(DownloadCommand.Retry(id))
    }

    // ---- settings & diagnostics ------------------------------------------

    /**
     * The grant is taken here rather than at the call site so that every path
     * into this setting ends up with a durable permission, which is what the
     * download service needs once the UI process is gone.
     */
    fun setDownloadFolder(treeUri: Uri, context: Context) {
        DownloadFolder.persist(context, treeUri)
        locator.settings.downloadFolder = treeUri.toString()
        refreshDownloadFolder()
    }

    fun resetDownloadFolder() {
        locator.settings.downloadFolder = null
        refreshDownloadFolder()
    }

    /**
     * Re-checks the picked folder. Run on every settings visit because the card
     * can be pulled while the app is running, which would leave a dead URI.
     */
    fun refreshDownloadFolder() {
        _ui.value = _ui.value.copy(
            downloadFolderLabel = locator.downloadFolderLabel(),
            downloadFolderUsable = locator.downloadFolderUsable(),
        )
    }

    fun setShowCovers(show: Boolean) {
        locator.settings.showCovers = show
        _ui.value = _ui.value.copy(showCovers = show)
    }

    fun setEinkMode(mode: EinkMode) {
        locator.settings.einkMode = mode
        applyEinkMode(mode)
    }

    fun fullRefresh() {
        val mode = _ui.value.einkMode
        // The panel service can publish after the app starts, so this doubles as
        // the manual re-probe from the diagnostics screen.
        locator.meink.init(force = true)
        locator.meink.flashFullRefresh(mode)
        syncEinkStatus()
    }

    fun onResume() {
        applyEinkMode(locator.settings.einkMode)
    }

    private fun applyEinkMode(mode: EinkMode) {
        if (mode == EinkMode.CLEAR) {
            // Never leave the panel in a non-readable initialisation pass.
            locator.meink.setMode(EinkMode.DEFAULT)
        } else {
            locator.meink.setMode(mode)
        }
        syncEinkStatus()
    }

    private fun syncEinkStatus() {
        val status = locator.meink.status.value
        _ui.value = _ui.value.copy(
            einkMode = locator.settings.einkMode,
            einkAvailable = status.serviceFound,
            einkError = status.lastError,
            einkTransacts = status.transactCount,
        )
    }

    fun navigate(route: Route) {
        _ui.value = _ui.value.copy(route = route)
        // A card can be inserted or pulled while the app is running, so the
        // list is only good for as long as it takes to reach the screen.
        if (route == Route.Settings) refreshDownloadFolder()
    }

    fun back(): Boolean = when (val route = _ui.value.route) {
        Route.Libraries -> false
        is Route.Items -> {
            backFromItems()
            true
        }

        Route.Downloaded -> {
            backFromDownloaded()
            true
        }

        is Route.Book -> {
            backFromBook()
            true
        }

        is Route.Player -> {
            backFromPlayer()
            true
        }

        Route.Settings, Route.Diagnostics -> {
            _ui.value = _ui.value.copy(route = Route.Libraries)
            true
        }

        Route.Login -> false
    }

    fun clearMessage() {
        _ui.value = _ui.value.copy(message = null)
    }
}

/**
 * Both tabs used to require `authorId`/`seriesId` and dropped everything else,
 * which on this server meant dropping all 200 books and rendering an empty
 * screen. The name is the only field that is reliably present, so it is what
 * the tabs group on, and the id is carried along when the server sent one.
 */
/**
 * The same author routinely appears as "Aaron Dembski Bowden" on one book and
 * "Aaron Dembski-Bowden" on another, which would otherwise be two tabs for one
 * person. Punctuation and spacing are dropped from the grouping key only; the
 * first spelling seen is what gets displayed.
 */
internal fun authorKey(name: String): String =
    name.lowercase().filter { it.isLetterOrDigit() }

/**
 * Server author rows into the list the Author tab renders.
 *
 * Authors with no books are dropped rather than shown with a zero: there is
 * nothing behind the row to drill into, so it was dead weight in the list. The
 * request sorts by numBooks so the count actually arrives, which is what makes
 * reading a zero as "no books" a fact rather than a guess.
 */
internal fun List<AuthorEntry>.toAuthorRows(): List<AuthorRow> =
    map {
        AuthorRow(
            name = it.name.trim(),
            bookCount = it.numBooks ?: 0,
            serverId = it.id,
            sortKey = it.nameIgnorePrefix,
        )
    }
        .filter { it.name.isNotEmpty() }
        .filter { it.bookCount > 0 }
        .distinctBy { it.serverId }

internal fun List<ItemEntity>.distinctAuthors(): List<AuthorRow> =
    mapNotNull { item ->
        val name = item.authorName.trim()
        if (name.isEmpty()) return@mapNotNull null
        AuthorRow(name, 1, item.authorId)
    }
        .groupBy { authorKey(it.name) }
        .map { (_, rows) ->
            AuthorRow(rows.first().name, rows.sumOf { it.bookCount }, rows.first().serverId)
        }
        .sortedWith { a, b -> a.compare(b) }

/**
 * Series names arrive pre-joined with the sequence, "The Horus Heresy #24", and
 * `seriesSequence` is null when the structured array is missing. Grouping on the
 * raw name would list all 24 books as 24 unrelated series, so the sequence is
 * split back off first.
 */
internal fun splitSeriesName(raw: String?): Pair<String, String?> {
    val name = raw?.trim().orEmpty()
    // A book in two series arrives joined, e.g.
    // "Fitz and the Fool #1, Realm of the Elderlings #14". The first component is
    // the series the book is read in, so that is what it is filed and sorted
    // under; taking the trailing "#" instead would leave the whole joined string
    // as a "series" and give every one of those books a row of its own.
    val primary = name.substringBefore(',').trim()
    val cut = primary.lastIndexOf(" #")
    if (cut <= 0) return primary to null
    val sequence = primary.substring(cut + 2).trim().removePrefix("#").trim()
    return primary.substring(0, cut).trim() to sequence.ifEmpty { null }
}

internal fun List<ItemEntity>.distinctSeries(): List<SeriesRow> =
    mapNotNull { item ->
        val (name, _) = splitSeriesName(item.seriesName)
        if (name.isEmpty()) return@mapNotNull null
        SeriesRow(name, 1, item.seriesId)
    }
        .groupBy { authorKey(it.name) }
        .map { (_, rows) ->
            SeriesRow(rows.first().name, rows.sumOf { it.bookCount }, rows.first().serverId)
        }
        .sortedWith { a, b -> Sorters.compareIgnoringArticle(a.name, b.name) }
