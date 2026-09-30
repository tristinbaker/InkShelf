package com.tristinbaker.inkshelf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tristinbaker.inkshelf.ui.AppViewModel
import com.tristinbaker.inkshelf.ui.Route
import com.tristinbaker.inkshelf.ui.screens.BookActions
import com.tristinbaker.inkshelf.ui.screens.BookScreen
import com.tristinbaker.inkshelf.ui.screens.BrowseScreen
import com.tristinbaker.inkshelf.ui.screens.DiagnosticsScreen
import com.tristinbaker.inkshelf.ui.screens.DownloadedScreen
import com.tristinbaker.inkshelf.ui.screens.ItemsScreen
import com.tristinbaker.inkshelf.ui.screens.LoginScreen
import com.tristinbaker.inkshelf.ui.screens.PlayerScreen
import com.tristinbaker.inkshelf.ui.components.MiniPlayerBar
import com.tristinbaker.inkshelf.ui.components.formatClock
import com.tristinbaker.inkshelf.ui.screens.SettingsScreen
import com.tristinbaker.inkshelf.playback.PlayCommand
import com.tristinbaker.inkshelf.ui.theme.InkShelfTheme
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

@Composable
fun InkShelfApp(
    locator: ServiceLocator,
    pickDownloadFolder: () -> Unit,
) {
    val viewModel: AppViewModel = viewModel(factory = AppViewModelFactory(locator))
    val state by viewModel.ui.collectAsStateWithLifecycle()

    InkShelfTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = GrayRamp.g4,
        ) {
            if (state.booting) return@Surface

            // Owned here, above the route switch, because drilling into an
            // author or series swaps BrowseScreen out of the composition
            // entirely. A state remembered inside BrowseScreen would be thrown
            // away and the list would jump back to the top on the way back.
            //
            // Keyed per tab and library so each one keeps its own place: backing
            // out of a drill-down returns to the same row, and switching tabs
            // starts that tab where you left it rather than inheriting another
            // tab's offset.
            val browseScroll = remember { mutableStateMapOf<String, LazyListState>() }
            val scrollKey = "${state.selectedLibrary?.id}|${state.browse.order}"
            val browseListState = browseScroll.getOrPut(scrollKey) { LazyListState(0, 0) }

            BackHandler(enabled = state.route != Route.Libraries && state.route != Route.Login) {
                viewModel.back()
            }

            // The route takes the space above the transport bar rather than
            // running under it, so the last row of a list is never hidden behind
            // the controls.
            Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
            when (val route = state.route) {
                Route.Login -> LoginScreen(
                    busy = state.busy,
                    message = state.message,
                    supportsLocalLogin = state.serverSupportsLocalLogin,
                    probe = state.tlsProbe,
                    initialUrl = state.session?.serverUrl ?: "",
                    onCheckServer = viewModel::checkServer,
                    onLogin = viewModel::login,
                )

                Route.Libraries -> BrowseScreen(
                    libraries = state.libraries,
                    selectedLibrary = state.selectedLibrary,
                    browse = state.browse,
                    onSelectLibrary = viewModel::selectLibrary,
                    onOrderChange = viewModel::setOrder,
                    onToggleDirection = viewModel::toggleDirection,
                    onOpenSeries = viewModel::openSeries,
                    onOpenAuthor = viewModel::openAuthor,
                    onOpenItem = viewModel::openBook,
                    onOpenSettings = { viewModel.navigate(Route.Settings) },
                    onRefresh = viewModel::refreshItems,
                    downloadedCount = state.downloaded.size,
                    covers = locator.covers,
                    showCovers = state.showCovers,
                    listState = browseListState,
                    onOpenDownloaded = viewModel::openDownloaded,
                )

                is Route.Items -> ItemsScreen(
                    title = route.title,
                    items = state.browse.items,
                    loading = state.browse.loading,
                    error = state.browse.error,
                    onBack = viewModel::backFromItems,
                    onRefresh = viewModel::refreshItems,
                    covers = locator.covers,
                    showCovers = state.showCovers,
                    onOpen = viewModel::openBook,
                )

                Route.Downloaded -> DownloadedScreen(
                    books = state.downloaded,
                    onBack = viewModel::backFromDownloaded,
                    onOpen = { viewModel.openDownloadedBook(it.itemId) },
                )

                is Route.Book -> {
                    val book = state.book
                    val metadata = book?.media?.metadata
                    val series = book?.media?.metadata?.series?.firstOrNull()
                    BookScreen(
                        title = metadata?.title.orEmpty(),
                        author = metadata?.authorName.orEmpty(),
                        seriesLine = series?.let {
                            it.name + it.sequence?.let { s -> " #$s" }.orEmpty()
                        },
                        narrator = metadata?.narratorName,
                        durationSeconds = book?.media?.duration ?: 0.0,
                        trackCount = book?.media?.numTracks ?: book?.media?.tracks?.size,
                        download = state.downloads[route.itemId],
                        itemId = route.itemId,
                        covers = locator.covers,
                        chapters = book?.media?.chapters.orEmpty(),
                        actions = BookActions(
                            resumeLabel = book?.userMediaProgress?.currentTime
                                ?.takeIf { it > 0 }
                                ?.let { "Resume · ${formatClock((it * 1000).toLong())}" },
                            download = state.downloads[route.itemId],
                            onPlay = viewModel::playBook,
                            onDownload = viewModel::downloadBook,
                            onCancelDownload = viewModel::removeDownload,
                        ),
                        onPlayChapter = viewModel::playChapterAt,
                        onBack = viewModel::backFromBook,
                    )
                }

                Route.Player -> PlayerScreen(
                    state = state.player,
                    covers = locator.covers,
                    onToggle = { viewModel.sendPlay(PlayCommand.Toggle) },
                    onPrevious = { viewModel.sendPlay(PlayCommand.Previous) },
                    onNext = { viewModel.sendPlay(PlayCommand.Next) },
                    onSkipForward = viewModel::skipForward,
                    onSkipBack = viewModel::skipBack,
                    onSeek = { viewModel.sendPlay(PlayCommand.SeekTo(it)) },
                    onSpeed = { viewModel.sendPlay(PlayCommand.SetSpeed(it)) },
                    onStop = { viewModel.sendPlay(PlayCommand.Stop) },
                    onBack = viewModel::backFromPlayer,
                )

                Route.Settings -> SettingsScreen(
                    einkMode = state.einkMode,
                    einkAvailable = state.einkAvailable,
                    einkError = state.einkError,
                    downloadFolderLabel = state.downloadFolderLabel,
                    downloadFolderUsable = state.downloadFolderUsable,
                    serverUrl = state.session?.serverUrl ?: "",
                    username = state.session?.username ?: "",
                    showCovers = state.showCovers,
                    onEinkMode = viewModel::setEinkMode,
                    onShowCovers = viewModel::setShowCovers,
                    onPickDownloadFolder = pickDownloadFolder,
                    onResetDownloadFolder = viewModel::resetDownloadFolder,
                    onFullRefresh = viewModel::fullRefresh,
                    onDiagnostics = { viewModel.navigate(Route.Diagnostics) },
                    onSignOut = viewModel::signOut,
                    onBack = { viewModel.navigate(Route.Libraries) },
                )

                Route.Diagnostics -> DiagnosticsScreen(
                    einkAvailable = state.einkAvailable,
                    einkMode = state.einkMode,
                    einkError = state.einkError,
                    einkTransacts = state.einkTransacts,
                    serverUrl = state.session?.serverUrl ?: "",
                    onBack = { viewModel.navigate(Route.Settings) },
                )
            }
            }

            // Playback outlives the app being backgrounded, so whatever screen
            // the listener lands back on has to offer the way back into the book.
            // Hidden on the player itself, where it would only repeat the
            // controls already on screen, and before sign-in, where there is
            // nothing loaded to control.
            if (state.player.hasBook &&
                state.route != Route.Player &&
                state.route != Route.Login
            ) {
                MiniPlayerBar(
                    state = state.player,
                    covers = locator.covers,
                    onOpen = viewModel::openPlayer,
                    onToggle = { viewModel.sendPlay(PlayCommand.Toggle) },
                    onSkipBack = viewModel::skipBack,
                    onSkipForward = viewModel::skipForward,
                    onSeek = { viewModel.sendPlay(PlayCommand.SeekTo(it)) },
                )
            }
            }
        }
    }
}
