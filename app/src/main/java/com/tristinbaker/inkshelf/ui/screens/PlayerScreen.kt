package com.tristinbaker.inkshelf.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.slider.SliderMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.ui.components.BackButton
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.playback.PlayerState
import com.tristinbaker.inkshelf.ui.components.CoverImage
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.SpeedDialog
import com.tristinbaker.inkshelf.ui.components.TransportControls
import com.tristinbaker.inkshelf.ui.components.chapterItems
import com.tristinbaker.inkshelf.ui.components.formatClock
import com.tristinbaker.inkshelf.ui.components.formatSpeed
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

private const val SKIP_MS = 30_000L
private const val PAGE_JUMP_STEP = 0

/** Larger than the 20sp body default, so the book reads as the headline. */
private val TITLE_SP = 24.sp

/** Deliberately below the body default: attribution, not the subject. */
private val AUTHOR_SP = 16.sp

private val CHAPTER_SP = 16.sp

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Playback controls, then the chapter list underneath.
 *
 * The chevrons skip chapters, the unit a listener actually navigates by; a
 * file split is an implementation detail, and on a single-file m4b a track skip
 * is a dead button. The ±30s buttons next to them cover fine scrubbing, and
 * stand in for a draggable scrubber: dragging across an e-ink panel forces a
 * full refresh per pixel of movement, and a 16-level greyscale thumb is hard
 * to grab precisely anyway.
 *
 * The page scrolls as one list rather than pinning the transport and scrolling
 * a chapter list in the space below it. Two scrollers on a panel this small
 * makes the inner one almost impossible to hit, and the listener is on this
 * page to drive playback, which stays at the top where they land.
 */
@Composable
fun PlayerScreen(
    state: PlayerState,
    covers: CoverStore? = null,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    var pickingSpeed by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBarMMD(
                title = { TextMMD(text = "Now Playing", maxLines = 1) },
                navigationIcon = { BackButton(onClick = onBack) },
            )

            LazyColumnMMD(
                modifier = Modifier.fillMaxSize(),
                scrollStep = PAGE_JUMP_STEP,
            ) {
                // Everything above the chapter list is one item exactly one
                // viewport tall, so at the top of the page the chapters start
                // just below the fold however long the title is, and one page
                // jump lands on them. The cover takes whatever height is left
                // after the text and controls.
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight()
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Cover, book, author, chapter. What is playing, biggest
                        // first, and centred because that is what the eye lands on
                        // when the screen lights up in the middle of listening.
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val itemId = state.itemId
                            if (itemId != null && covers != null) {
                                val side = min(maxWidth, maxHeight)
                                CoverImage(
                                    store = covers,
                                    itemId = itemId,
                                    width = side,
                                    height = side,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextMMD(
                                text = state.title.ifBlank { "Nothing playing" },
                                color = GrayRamp.g0,
                                fontSize = TITLE_SP,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            if (state.author.isNotBlank()) {
                                TextMMD(
                                    text = state.author,
                                    color = GrayRamp.g1,
                                    fontSize = AUTHOR_SP,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            // The service only republishes currentChapterIndex when
                            // the listener crosses a boundary, so this line does not
                            // force a redraw on every position tick.
                            val chapterIndex = state.currentChapterIndex
                            if (chapterIndex in state.chapters.indices) {
                                val chapter = state.chapters[chapterIndex]
                                val label = "Chapter ${chapterIndex + 1} of ${state.chapters.size}"
                                TextMMD(
                                    text = chapter.title?.takeIf { it.isNotBlank() }
                                        ?.let { "$label\n$it" } ?: label,
                                    color = GrayRamp.g1,
                                    fontSize = CHAPTER_SP,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            } else if (state.trackCount > 1) {
                                // No server chapters, so the file split is the only
                                // position cue left. Skipped for a single-file book,
                                // where "Track 1 of 1" is noise.
                                val trackNumber = (state.trackIndex + 1).coerceIn(1, state.trackCount)
                                TextMMD(
                                    text = "Track $trackNumber of ${state.trackCount}" +
                                        if (state.trackTitle.isBlank()) "" else "\n${state.trackTitle}",
                                    color = GrayRamp.g1,
                                    fontSize = CHAPTER_SP,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        if (state.durationMs > 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SliderMMD(
                                    value = state.progress,
                                    onValueChange = { onSeek((it * state.durationMs).toLong()) },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    TextMMD(
                                        text = formatClock(state.positionMs),
                                        color = GrayRamp.g1,
                                    )
                                    // Between the clocks rather than on the play
                                    // button, so the icon does not flip and back
                                    // while the buffer fills, and nothing shifts.
                                    if (state.buffering) {
                                        TextMMD(text = "Buffering…", color = GrayRamp.g1)
                                    }
                                    TextMMD(
                                        text = formatClock(state.durationMs),
                                        color = GrayRamp.g1,
                                    )
                                }
                            }
                        }

                        if (state.needsTranscode.isNotEmpty() || state.error != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (state.needsTranscode.isNotEmpty()) {
                                    TextMMD(
                                        text = "Some files need server transcoding and may not play.",
                                        color = GrayRamp.g1,
                                    )
                                }
                                state.error?.let { TextMMD(text = it, color = GrayRamp.g0) }
                            }
                        }

                        TransportControls(
                            playing = state.playing,
                            enabled = state.hasBook,
                            // Greys out on the last chapter. Checked against
                            // chapterCount rather than chapters.size because a
                            // book with no server chapters still navigates by
                            // synthetic five-minute markers.
                            nextEnabled = state.hasBook &&
                                state.currentChapterIndex + 1 < state.chapterCount,
                            onPrevious = onPrevious,
                            onSkipBack = onSkipBack,
                            onToggle = onToggle,
                            onSkipForward = onSkipForward,
                            onNext = onNext,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButtonMMD(
                                onClick = { pickingSpeed = true },
                                modifier = Modifier.weight(1f),
                                enabled = state.hasBook,
                            ) {
                                TextMMD(text = "Speed ${formatSpeed(state.speed)}", maxLines = 1)
                            }
                            OutlinedButtonMMD(
                                onClick = onStop,
                                modifier = Modifier.weight(1f),
                                enabled = state.hasBook,
                            ) {
                                TextMMD(text = "Stop", maxLines = 1)
                            }
                        }
                    }
                }

                // Under the controls, as a list: the row being played is marked, and
                // a tap is a seek because the book is already loaded here.
                chapterItems(
                    chapters = state.chapters,
                    currentIndex = state.currentChapterIndex,
                    onSelect = onSeek,
                )

                item { Gap(12) }
            }
        }

        if (pickingSpeed) {
            // Composed after the app-level handler, so Back closes the picker
            // instead of leaving the player.
            BackHandler { pickingSpeed = false }
            SpeedDialog(
                current = state.speed,
                onSelect = {
                    onSpeed(it)
                    pickingSpeed = false
                },
                onDismiss = { pickingSpeed = false },
            )
        }
    }
}