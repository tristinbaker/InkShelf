package com.tristinbaker.inkshelf.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.mudita.mmd.eInkColorScheme

/**
 * MMD's own [eInkColorScheme] is strictly 1-bit: every slot is black or white.
 * The Kompakt panel is a 16-level greyscale, so we widen the scheme with a
 * four-step ramp to carry secondary text and dividers without resorting to
 * dithered greys that the panel has to simulate at 1 bpp anyway.
 */
object GrayRamp {
    val g0 = Color(0xFF000000)
    val g1 = Color(0xFF3F3F3F)
    val g2 = Color(0xFF7F7F7F)
    val g3 = Color(0xFFBFBFBF)
    val g4 = Color(0xFFFFFFFF)

    val list: List<Color> = listOf(g0, g1, g2, g3, g4)
}

fun inkShelfColorScheme(): ColorScheme = eInkColorScheme.copy(
    onSurfaceVariant = GrayRamp.g1,
    surfaceVariant = GrayRamp.g4,
    outline = GrayRamp.g0,
    outlineVariant = GrayRamp.g2,
    surfaceContainerLow = GrayRamp.g4,
)
