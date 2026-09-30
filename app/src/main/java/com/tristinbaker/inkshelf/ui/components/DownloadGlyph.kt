package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * The download affordance on a book.
 *
 * Three states, because the icon is the only control this has:
 *  - a download arrow, tappable to start or resume
 *  - a determinate ring, while bytes are moving
 *  - a tick, once every file is on the device
 *
 * Drawn rather than imported: `material-icons-core` is on the classpath
 * transitively but has no Download glyph, and the alternative is pulling in
 * `material-icons-extended` for one arrow. The ring is drawn here for the same
 * reason, so both states share a stroke weight.
 */
@Composable
fun DownloadGlyph(
    state: DownloadGlyphState,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(size)
            .background(GrayRamp.g4, shape)
            .border(2.dp, GrayRamp.g0, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val stroke = size.value * 0.11f
        when (state) {
            is DownloadGlyphState.Idle -> DownloadArrow(stroke)
            is DownloadGlyphState.Running -> ProgressRing(
                fraction = state.fraction,
                stroke = stroke,
            )
            DownloadGlyphState.Complete -> Tick(stroke)
        }
    }
}

sealed interface DownloadGlyphState {
    data object Idle : DownloadGlyphState
    data class Running(val fraction: Float) : DownloadGlyphState
    data object Complete : DownloadGlyphState
}

@Composable
private fun DownloadArrow(stroke: Float) {
    Canvas(modifier = Modifier.size(IconFraction)) {
        val w = this.size.width
        val h = this.size.height
        val ink = GrayRamp.g0
        fun x(f: Float) = w * f
        fun y(f: Float) = h * f

        // Stem, then two barbs angled up and out to form the head. The head
        // has to clear the tray: overlapping them turns the whole lower half
        // into a solid block, which is not a download arrow.
        drawLine(ink, Offset(x(0.5f), y(0.07f)), Offset(x(0.5f), y(0.52f)), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ink, Offset(x(0.5f), y(0.52f)), Offset(x(0.18f), y(0.28f)), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ink, Offset(x(0.5f), y(0.52f)), Offset(x(0.82f), y(0.28f)), strokeWidth = stroke, cap = StrokeCap.Round)

        // Tray the arrow drops into, with a short down-tick at each end.
        drawLine(ink, Offset(x(0.06f), y(0.80f)), Offset(x(0.94f), y(0.80f)), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ink, Offset(x(0.06f), y(0.80f)), Offset(x(0.06f), y(0.93f)), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(ink, Offset(x(0.94f), y(0.80f)), Offset(x(0.94f), y(0.93f)), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun Tick(stroke: Float) {
    Canvas(modifier = Modifier.size(IconFraction)) {
        val w = this.size.width
        val h = this.size.height
        val ink = GrayRamp.g0
        drawLine(
            ink,
            Offset(w * 0.12f, h * 0.54f),
            Offset(w * 0.40f, h * 0.82f),
            strokeWidth = stroke * 1.15f,
        )
        drawLine(
            ink,
            Offset(w * 0.40f, h * 0.82f),
            Offset(w * 0.90f, h * 0.18f),
            strokeWidth = stroke * 1.15f,
        )
    }
}

@Composable
private fun ProgressRing(fraction: Float, stroke: Float) {
    Canvas(modifier = Modifier.size(IconFraction)) {
        val d = this.size.minDimension
        val topLeft = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f)
        val arcSize = Size(d, d)
        drawArc(
            color = GrayRamp.g3,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = GrayRamp.g0,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke),
        )
    }
}

/** The glyph inside the button, as a share of the button's edge. */
private val IconFraction = 26.dp
