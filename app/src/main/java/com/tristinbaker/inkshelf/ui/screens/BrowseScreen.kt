package com.tristinbaker.inkshelf.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonDefaultsMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.tristinbaker.inkshelf.ui.components.InkLazyColumn
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.core.abs.BrowseOrder
import com.tristinbaker.inkshelf.data.ItemEntity
import com.tristinbaker.inkshelf.data.LibraryEntity
import com.tristinbaker.inkshelf.data.Sorters
import com.tristinbaker.inkshelf.ui.AuthorRow
import com.tristinbaker.inkshelf.ui.BrowseListEntry
import com.tristinbaker.inkshelf.ui.BrowseState
import com.tristinbaker.inkshelf.ui.headerIndices
import com.tristinbaker.inkshelf.ui.letterSegments
import com.tristinbaker.inkshelf.ui.SeriesRow
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.ui.components.CoverImage
import com.tristinbaker.inkshelf.ui.components.CoverThumbHeight
import com.tristinbaker.inkshelf.ui.components.CoverThumbWidth
import com.tristinbaker.inkshelf.ui.components.DownloadsButton
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.InkRow
import com.tristinbaker.inkshelf.ui.components.LetterBar
import com.tristinbaker.inkshelf.ui.components.SectionHeader
import com.tristinbaker.inkshelf.ui.components.formatDuration
import com.tristinbaker.inkshelf.ui.theme.GrayRamp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    libraries: List<LibraryEntity>,
    selectedLibrary: LibraryEntity?,
    browse: BrowseState,
    onSelectLibrary: (LibraryEntity) -> Unit,
    onOrderChange: (BrowseOrder) -> Unit,
    onToggleDirection: () -> Unit,
    onOpenSeries: (SeriesRow) -> Unit,
    onOpenAuthor: (AuthorRow) -> Unit,
    onOpenItem: (ItemEntity) -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    downloadedCount: Int = 0,
    covers: CoverStore? = null,
    showCovers: Boolean = true,
    listState: LazyListState,
    onOpenDownloaded: () -> Unit = {},
) {
    // Deliberately no scrollToItem here. Drilling into an author or series swaps
    // this screen out of the composition, so returning re-creates it and any
    // effect launched in it runs again from the top, wiping the saved position.
    // The caller owns one state per tab and hands back the matching one.
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = { TextMMD(text = selectedLibrary?.name ?: "InkShelf") },
            actions = {
                if (downloadedCount > 0) {
                    DownloadsButton(
                        onClick = onOpenDownloaded,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                ButtonMMD(onClick = onOpenSettings) { TextMMD(text = "Menu") }
            },
        )

        OrderBar(
            order = browse.order,
            descending = browse.descending,
            onOrderChange = onOrderChange,
            onToggleDirection = onToggleDirection,
        )

        if (browse.filterLabel != null) return@Column

        LibraryBar(
            libraries = libraries,
            selected = selectedLibrary,
            onSelect = onSelectLibrary,
        )

        if (browse.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicatorMMD()
            }
            return@Column
        }

        if (browse.error != null && !browse.fromCache) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextMMD(text = browse.error, color = GrayRamp.g0)
                Gap(4)
                ButtonMMD(onClick = onRefresh) { TextMMD(text = "Try again") }
                // Retrying will not help with the radio off, but books that are
                // already on the device still work, so the way in has to be here.
                if (downloadedCount > 0) {
                    ButtonMMD(onClick = onOpenDownloaded) { TextMMD(text = "Downloaded ($downloadedCount)") }
                }
            }
            return@Column
        }

        if (browse.fromCache) {
            TextMMD(
                text = browse.error ?: "Showing saved copy",
                color = GrayRamp.g1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        // Built ahead of the list rather than inside it so the scrollbar can be
        // told where the letter bars are: a long press on its arrows jumps
        // between them.
        val segments = remember(browse.order, browse.items, browse.authors, browse.series) {
            when (browse.order) {
                BrowseOrder.TITLE -> letterSegments(browse.items) { Sorters.titleLetterBucket(it.title) }
                BrowseOrder.AUTHOR -> letterSegments(browse.authors) { Sorters.lastNameLetterBucket(it.name) }
                BrowseOrder.SERIES -> letterSegments(browse.series) { Sorters.nameLetterBucket(it.name) }
            }
        }
        // Series opens with its count header, one item ahead of the first bar.
        val leadingItems = if (browse.order == BrowseOrder.SERIES) 1 else 0

        InkLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            sectionStarts = remember(segments, leadingItems) { segments.headerIndices(leadingItems) },
        ) {
            when (browse.order) {
                BrowseOrder.TITLE -> {
                    segments.forEach { segment ->
                        when (segment) {
                            is BrowseListEntry.Header ->
                                item(key = "h${segment.letter}") { LetterBar(segment.letter) }

                            is BrowseListEntry.Row -> {
                                val item = browse.items[segment.index]
                                item(key = "i${item.id}") {
                                    InkRow(
                                        title = item.title,
                                        subtitle = listOfNotNull(
                                            item.authorName.takeIf { it.isNotBlank() },
                                            item.seriesName,
                                        ).joinToString(" · "),
                                        trailing = formatDuration(item.durationSeconds),
                                        leading = covers?.takeIf { showCovers }?.let { store ->
                                            {
                                                CoverImage(
                                                    store = store,
                                                    itemId = item.id,
                                                    width = CoverThumbWidth,
                                                    height = CoverThumbHeight,
                                                )
                                            }
                                        },
                                        onClick = { onOpenItem(item) },
                                    )
                                }
                            }
                        }
                    }
                }

                BrowseOrder.AUTHOR -> {
                    segments.forEach { segment ->
                        when (segment) {
                            is BrowseListEntry.Header ->
                                item(key = "h${segment.letter}") { LetterBar(segment.letter) }

                            is BrowseListEntry.Row -> {
                                val author = browse.authors[segment.index]
                                item(key = "a${author.name}") {
                                    InkRow(
                                        title = author.displayName,
                                        trailing = "${author.bookCount}",
                                        onClick = { onOpenAuthor(author) },
                                    )
                                }
                            }
                        }
                    }
                }

                BrowseOrder.SERIES -> {
                    item { SectionHeader("${browse.series.size} series") }
                    segments.forEach { segment ->
                        when (segment) {
                            is BrowseListEntry.Header ->
                                item(key = "h${segment.letter}") { LetterBar(segment.letter) }

                            is BrowseListEntry.Row -> {
                                val series = browse.series[segment.index]
                                item(key = "s${series.name}") {
                                    InkRow(
                                        title = series.name,
                                        trailing = "${series.bookCount}",
                                        onClick = { onOpenSeries(series) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Gap(12) }
        }
    }
}

/**
 * Colours for a bar button.
 *
 * ButtonMMD fills its container black, so the dark-on-light ramp that InkRow
 * uses for text is unreadable inside one: `g1` rendered as grey on black and the
 * selected `g0` as black on black. Bar labels therefore take the component's
 * own white, and the selected item inverts to a white fill with black text.
 */
@Composable
/** The selected bar item keeps MMD's solid black fill; the rest go white. */
private fun barColors(selected: Boolean): ButtonColors =
    if (selected) {
        ButtonDefaultsMMD.buttonColors()
    } else {
        ButtonDefaultsMMD.buttonColors().copy(
            containerColor = GrayRamp.g4,
            contentColor = GrayRamp.g0,
        )
    }

/**
 * A white bar item sits on the same white page as its neighbours, so without
 * an outline it would read as loose text rather than a button. 2.dp of black is
 * the width MMD already uses for its own outlined buttons.
 */
private fun barBorder(selected: Boolean): BorderStroke? =
    if (selected) null else BorderStroke(2.dp, GrayRamp.g0)

/**
 * MMD's default horizontal padding is sized for a lone button. Four of them
 * across a 354dp panel left "Author" about 60dp for its text, so it wrapped to
 * "Autho / r". The label only needs a hairline of clearance from the outline.
 */
private val OrderBarPadding = PaddingValues(
    horizontal = 4.dp,
    vertical = ButtonDefaultsMMD.buttonVerticalPadding,
)

@Composable
private fun OrderBar(
    order: BrowseOrder,
    descending: Boolean,
    onOrderChange: (BrowseOrder) -> Unit,
    onToggleDirection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BrowseOrder.entries.forEach { candidate ->
            ButtonMMD(
                onClick = { onOrderChange(candidate) },
                modifier = Modifier.weight(1f),
                colors = barColors(selected = candidate == order),
                border = barBorder(selected = candidate == order),
                contentPadding = OrderBarPadding,
            ) {
                TextMMD(text = candidate.label, maxLines = 1)
            }
        }
        ButtonMMD(
            onClick = onToggleDirection,
            contentPadding = PaddingValues(
                horizontal = 10.dp,
                vertical = ButtonDefaultsMMD.buttonVerticalPadding,
            ),
        ) {
            TextMMD(text = if (descending) "Z-A" else "A-Z", maxLines = 1)
        }
    }
}

@Composable
private fun LibraryBar(
    libraries: List<LibraryEntity>,
    selected: LibraryEntity?,
    onSelect: (LibraryEntity) -> Unit,
) {
    if (libraries.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        libraries.forEach { library ->
            ButtonMMD(
                onClick = { onSelect(library) },
                colors = barColors(selected = library.id == selected?.id),
                border = barBorder(selected = library.id == selected?.id),
            ) {
                TextMMD(
                    text = library.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}