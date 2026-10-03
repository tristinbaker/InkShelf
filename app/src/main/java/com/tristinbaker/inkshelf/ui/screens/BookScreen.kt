package com.tristinbaker.inkshelf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.progress_indicator.LinearProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.ui.components.BackButton
import com.tristinbaker.inkshelf.core.abs.Chapter
import com.tristinbaker.inkshelf.download.DownloadSummary
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.ui.components.ConfirmDialog
import com.tristinbaker.inkshelf.ui.components.CoverImage
import com.tristinbaker.inkshelf.ui.components.DownloadGlyph
import com.tristinbaker.inkshelf.ui.components.DownloadGlyphState
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.InkRow
import com.tristinbaker.inkshelf.ui.components.SectionHeader
import com.tristinbaker.inkshelf.ui.components.chapterItems
import com.tristinbaker.inkshelf.ui.components.formatClock
import com.tristinbaker.inkshelf.ui.components.formatDuration
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

private const val PAGE_JUMP_STEP = 0

/**
 * Square, to match the artwork rather than crop it. Big enough to read the
 * cover, small enough that the fact list below stays reachable without a long
 * scroll on a 480x600 panel.
 */
private val GLYPH_SIZE = 58.dp

private val DETAIL_COVER_WIDTH = 220.dp
private val DETAIL_COVER_HEIGHT = 220.dp

/** Per-book actions: play, resume, download. */
data class BookActions(
    val resumeLabel: String?,
    val download: DownloadSummary?,
    val onPlay: () -> Unit,
    val onDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    title: String,
    author: String,
    seriesLine: String?,
    narrator: String?,
    durationSeconds: Double,
    trackCount: Int?,
    download: DownloadSummary?,
    itemId: String,
    covers: CoverStore? = null,
    chapters: List<Chapter> = emptyList(),
    actions: BookActions,
    onPlayChapter: (Long) -> Unit = {},
    onBack: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }

    // The dialog is the *last* child so it draws over the page. Emitted before
    // the Column it sat underneath it: the page painted on top, the dialog was
    // invisible, and its buttons never received a tap.
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = { TextMMD(text = title, maxLines = 1) },
            navigationIcon = { BackButton(onClick = onBack) },
        )

        LazyColumnMMD(
            modifier = Modifier.fillMaxSize(),
            scrollStep = PAGE_JUMP_STEP,
        ) {
            if (covers != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CoverImage(
                            store = covers,
                            itemId = itemId,
                            width = DETAIL_COVER_WIDTH,
                            height = DETAIL_COVER_HEIGHT,
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextMMD(text = title, color = GrayRamp.g0)
                    if (author.isNotBlank()) {
                        TextMMD(text = author, color = GrayRamp.g1)
                    }
                    if (!seriesLine.isNullOrBlank()) {
                        TextMMD(text = seriesLine, color = GrayRamp.g1)
                    }
                    if (!narrator.isNullOrBlank()) {
                        TextMMD(text = "Read by $narrator", color = GrayRamp.g1)
                    }
                    val facts = buildList {
                        if (durationSeconds > 0) add(formatDuration(durationSeconds))
                        if (trackCount != null && trackCount > 0) {
                            add(if (trackCount == 1) "1 file" else "$trackCount files")
                        }
                    }.joinToString(" · ")
                    if (facts.isNotBlank()) {
                        TextMMD(text = facts, color = GrayRamp.g1)
                    }
                    if (download != null && download.isActive) {
                        // isActive, not isRunning: a book can be partly fetched
                        // with every remaining file still queued, and gating on
                        // isRunning made the bar vanish exactly when the user
                        // most wants to know a download is under way.
                        // Percent and megabytes, not a file count. A single-file
                        // book reported "0 of 1" for the whole download, which
                        // reads as nothing happening while the bar above it fills.
                        val percent = (download.progress * 100).toInt()
                        TextMMD(
                            text = if (download.isRunning) {
                                "Downloading $percent% · " +
                                    "${download.megabytesWritten} of " +
                                    "${download.megabytesTotal} MB"
                            } else {
                                "Queued $percent% · ${download.doneTracks} of " +
                                    "${download.totalTracks} files"
                            },
                            color = GrayRamp.g0,
                        )
                        LinearProgressIndicatorMMD(progress = { download.progress })
                        // Spelled out as well as on the ring: a ring reads as a
                        // progress readout, not as something to tap to stop it.
                        OutlinedButtonMMD(
                            onClick = { confirmingCancel = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextMMD(text = "Cancel download")
                        }
                    }
                }
                    Box(
                        modifier = Modifier.padding(top = 2.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        DownloadGlyph(
                            state = when {
                                download == null -> DownloadGlyphState.Idle
                                download.isComplete -> DownloadGlyphState.Complete
                                download.isActive -> DownloadGlyphState.Running(download.progress)
                                else -> DownloadGlyphState.Idle
                            },
                            size = GLYPH_SIZE,
                            onClick = {
                                when {
                                    download?.isComplete == true -> confirmingDelete = true
                                    // Tapping the ring used to re-queue the book,
                                    // which did nothing visible; stopping it is
                                    // the only useful thing a tap there can mean.
                                    download?.isActive == true -> confirmingCancel = true
                                    else -> actions.onDownload()
                                }
                            },
                        )
                    }
                }
            }

            item { SectionHeader("Listen") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ButtonMMD(onClick = actions.onPlay, modifier = Modifier.fillMaxWidth()) {
                        TextMMD(text = actions.resumeLabel ?: "Play")
                    }
                }
            }

            // Under everything else, as a list rather than a row that opens
            // another screen: the chapters belong to this book, so they read
            // here with the rest of it.
            chapterItems(chapters, onSelect = onPlayChapter)
            item { Gap(16) }
        }
    }

        if (confirmingDelete) {
            ConfirmDialog(
                title = "Delete download?",
                body = "\"$title\" will be removed from this device. " +
                    "You can download it again later.",
                confirmLabel = "Delete",
                onConfirm = {
                    confirmingDelete = false
                    actions.onCancelDownload()
                },
                onDismiss = { confirmingDelete = false },
            )
        }

        if (confirmingCancel) {
            ConfirmDialog(
                title = "Cancel download?",
                body = "The download of \"$title\" will stop and anything " +
                    "already saved will be deleted.",
                confirmLabel = "Stop",
                onConfirm = {
                    confirmingCancel = false
                    actions.onCancelDownload()
                },
                onDismiss = { confirmingCancel = false },
            )
        }
    }
}
