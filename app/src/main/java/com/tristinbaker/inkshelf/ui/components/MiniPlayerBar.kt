package com.tristinbaker.inkshelf.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.slider.SliderMMD
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.R
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.playback.PlayerState
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * Transport bar pinned to the bottom of every screen while a book is loaded.
 *
 * Playback runs in the foreground now, so the app can be in the background and
 * still be playing when the listener comes back to it. This is how they get at
 * that book without hunting for it: artwork and title on the left to say what it
 * is, the transport in the middle, and a seek bar underneath for where they are.
 *
 * The artwork and title are one target that opens the player. The controls do
 * their own thing rather than also navigating, because a tap that both seeks and
 * changes screen is the one that cannot be predicted.
 */
@Composable
fun MiniPlayerBar(
    state: PlayerState,
    covers: CoverStore?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemId = state.itemId

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(GrayRamp.g3),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Weighted, so the title takes whatever the controls do not and the
            // controls stay put as titles of different lengths come through.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sized either way so the controls do not shift sideways when the
                // artwork arrives, which would reflow the whole bar.
                if (covers != null && itemId != null) {
                    CoverImage(
                        store = covers,
                        itemId = itemId,
                        width = ART_SIZE,
                        height = ART_SIZE,
                    )
                } else {
                    Box(modifier = Modifier.size(ART_SIZE))
                }

                TextMMD(
                    text = state.title.ifBlank { "Nothing playing" },
                    color = GrayRamp.g0,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            OutlinedButtonMMD(onClick = onSkipBack) {
                ControlIcon(R.drawable.ic_replay30)
            }
            ButtonMMD(
                onClick = onToggle,
                enabled = state.hasBook,
            ) {
                ControlIcon(
                    if (state.buffering) R.drawable.ic_pause
                    else if (state.playing) R.drawable.ic_pause
                    else R.drawable.ic_play,
                )
            }
            OutlinedButtonMMD(onClick = onSkipForward) {
                ControlIcon(R.drawable.ic_forward30)
            }
        }

        // Under the controls rather than above: it reads as measuring the
        // transport sitting on top of it.
        //
        // Hidden until the book has a length. One being opened has none yet, and
        // a bar pinned at zero that cannot move is worse than no bar.
        if (state.durationMs > 0) {
            SliderMMD(
                value = state.progress,
                onValueChange = { onSeek((it * state.durationMs).toLong()) },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SEEK_BAR_HEIGHT)
                    .padding(horizontal = 10.dp),
            )
        }
    }
}

/**
 * Drawn in the surrounding control's content colour rather than a fixed one, so
 * it stays legible inside both the filled play button and the outlined skips.
 */
@Composable
private fun ControlIcon(@DrawableRes icon: Int) {
    Image(
        painter = painterResource(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(LocalContentColor.current),
        modifier = Modifier.size(CONTROL_ICON_SIZE),
    )
}

private val ART_SIZE = 44.dp

/**
 * SliderMMD draws a thumb that has to stay big enough to grab, so it is not
 * something to shrink further than this to save height.
 */
private val SEEK_BAR_HEIGHT = 40.dp

private val CONTROL_ICON_SIZE = 22.dp
