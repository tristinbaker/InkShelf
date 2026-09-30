package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.ui.theme.GrayRamp
import androidx.compose.runtime.Composable

/**
 * One tappable list row. Rows are separated with a full-strength rule rather
 * than a subtle background tint, because light greys render inconsistently on a
 * 16-level greyscale panel at 217 PPI.
 */
@Composable
fun InkRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(if (selected) GrayRamp.g3 else GrayRamp.g4)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Row(
                    modifier = Modifier.padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { leading() }
            }
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = GrayRamp.g0,
                )
                if (subtitle != null) {
                    TextMMD(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = GrayRamp.g1,
                    )
                }
            }
            if (trailing != null) {
                TextMMD(
                    text = trailing,
                    color = GrayRamp.g1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    HorizontalDividerMMD(color = GrayRamp.g2, thickness = 1.dp)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    TextMMD(
        text = text.uppercase(),
        color = GrayRamp.g0,
        modifier = modifier
            .fillMaxWidth()
            .background(GrayRamp.g3)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * The A-Z divider between two runs of a browse list.
 *
 * Same grey as [SectionHeader] so the two read as the same family of chrome,
 * but carrying a single large letter: at one glyph the bar is a position cue
 * rather than a label, and it needs to be findable at a glance while scrolling
 * rather than read. Black on `g3` keeps it dark enough to separate the two runs
 * on a panel that renders light greys unreliably.
 */
@Composable
fun LetterBar(letter: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(GrayRamp.g3),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(
            text = letter,
            color = GrayRamp.g0,
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
    HorizontalDividerMMD(color = GrayRamp.g0, thickness = 1.dp)
}

@Composable
fun Gap(height: Int) {
    Spacer(modifier = Modifier.height(height.dp))
}
