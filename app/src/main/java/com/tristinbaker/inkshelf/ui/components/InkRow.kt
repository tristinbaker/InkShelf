package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.ui.theme.GrayRamp
import androidx.compose.runtime.Composable

/**
 * One tappable list row. Rows are separated with a full-strength rule rather
 * than a subtle background tint, because light greys render inconsistently on a
 * 16-level greyscale panel at 217 PPI. For the same reason a [selected] row is
 * inverted to white on black rather than tinted grey: the panel dithered the
 * tint into a speckle that swallowed the text on it. Inverted, the secondary
 * text goes to white too, since a grey on black dithers the same way.
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
    val ink = if (selected) GrayRamp.g4 else GrayRamp.g0
    val secondaryInk = if (selected) GrayRamp.g4 else GrayRamp.g1
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .background(if (selected) GrayRamp.g0 else GrayRamp.g4)
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
                    color = ink,
                )
                if (subtitle != null) {
                    TextMMD(
                        text = subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = secondaryInk,
                    )
                }
            }
            if (trailing != null) {
                TextMMD(
                    text = trailing,
                    color = secondaryInk,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    HorizontalDividerMMD(color = GrayRamp.g2, thickness = 1.dp)
}

/**
 * A labelled break between groups of rows: bold black type over a 2.dp black
 * rule, on the page's own white. It used to be a solid black bar with white
 * type, which read as crisp but heavy; slabs of black every few rows pulled the
 * eye away from the rows themselves. The rule keeps the header unmistakably
 * stronger than the 1.dp `g2` row dividers without filling anything in.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        TextMMD(
            text = text.uppercase(),
            color = GrayRamp.g0,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        )
        HorizontalDividerMMD(color = GrayRamp.g0, thickness = 2.dp)
    }
}

/**
 * The A-Z divider between two runs of a browse list.
 *
 * Drawn like [SectionHeader] so the two read as the same family of chrome, but
 * carrying a single larger letter at the left edge, the way an index or a
 * contacts list does: at one glyph it is a position cue rather than a label,
 * and it needs to be findable at a glance while scrolling rather than read.
 * Pure black on white throughout; the panel dithers greys behind text.
 */
@Composable
fun LetterBar(letter: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        TextMMD(
            text = letter,
            color = GrayRamp.g0,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
        )
        HorizontalDividerMMD(color = GrayRamp.g0, thickness = 2.dp)
    }
}

@Composable
fun Gap(height: Int) {
    Spacer(modifier = Modifier.height(height.dp))
}
