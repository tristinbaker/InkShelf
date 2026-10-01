package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.ui.theme.GrayRamp
import java.util.Locale

/** Quarter steps across the range people actually listen at. */
val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private const val SPEEDS_PER_ROW = 3

/** "1×", "1.25×", "1.5×": no trailing zeros, which is how speeds are spoken. */
fun formatSpeed(speed: Float): String =
    String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"

/**
 * Picks a playback speed in one tap.
 *
 * Replaces a button that cycled through the list, which meant tapping past
 * every speed in between (and a full panel refresh per tap) to get back from
 * 2× to 1×. Every option is on screen at once and the current one is the
 * solid block.
 *
 * Layered the same way as [ConfirmDialog]: the dismissing scrim is a sibling
 * behind the panel, not its parent, so it never competes with the options.
 */
@Composable
fun SpeedDialog(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GrayRamp.g2)
                .clickable(onClick = onDismiss),
        )

        val shape = RoundedCornerShape(6.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .background(GrayRamp.g4, shape)
                .border(3.dp, GrayRamp.g0, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextMMD(text = "Playback speed", color = GrayRamp.g0)
            PLAYBACK_SPEEDS.chunked(SPEEDS_PER_ROW).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { speed ->
                        SpeedOption(
                            speed = speed,
                            selected = speed == current,
                            onClick = { onSelect(speed) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedOption(
    speed: Float,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .background(if (selected) GrayRamp.g0 else GrayRamp.g4, shape)
            .border(2.dp, GrayRamp.g0, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(
            text = formatSpeed(speed),
            color = if (selected) GrayRamp.g4 else GrayRamp.g0,
        )
    }
}
