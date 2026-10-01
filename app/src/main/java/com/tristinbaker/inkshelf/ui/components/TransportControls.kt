package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * The player's transport row: previous chapter, back 30s, play/pause,
 * forward 30s, next chapter.
 *
 * Icons rather than labels, because five text buttons do not fit across a
 * 354dp-wide panel without truncating to "Pre" and "Nex". Drawn for the same
 * reason as [DownloadGlyph]: `material-icons-core` has no pause or skip-30
 * glyph, and `material-icons-extended` is a large dependency for five shapes.
 *
 * Play/pause is the one solid control, so it is the one the eye finds first.
 * The rest are bare glyphs with no outline: a row of five boxes is what made
 * the old layout read as a grid of form buttons.
 */
@Composable
fun TransportControls(
    playing: Boolean,
    enabled: Boolean,
    nextEnabled: Boolean,
    onPrevious: () -> Unit,
    onSkipBack: () -> Unit,
    onToggle: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(TransportGlyph.Previous, "Previous chapter", enabled, onPrevious)
        TransportButton(TransportGlyph.Rewind, "Back 30 seconds", enabled, onSkipBack)
        TransportButton(
            glyph = if (playing) TransportGlyph.Pause else TransportGlyph.Play,
            label = if (playing) "Pause" else "Play",
            enabled = enabled,
            onClick = onToggle,
            primary = true,
        )
        TransportButton(TransportGlyph.FastForward, "Forward 30 seconds", enabled, onSkipForward)
        TransportButton(TransportGlyph.Next, "Next chapter", nextEnabled, onNext)
    }
}

private enum class TransportGlyph { Previous, Rewind, Play, Pause, FastForward, Next }

/** Comfortably over the 48dp touch minimum; the panel has room for it. */
private val SIDE_BUTTON = 52.dp
private val PRIMARY_BUTTON = 60.dp

/** The skip distance, printed inside the round arrows. */
private const val SKIP_LABEL = "30"
private val SKIP_LABEL_SP = 13.sp

@Composable
private fun TransportButton(
    glyph: TransportGlyph,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val size: Dp = if (primary) PRIMARY_BUTTON else SIDE_BUTTON
    // Disabled is a mid grey rather than an alpha fade, for the same reason the
    // dialog scrim is solid: blending is left to the panel's 16 levels.
    val ink = if (enabled) GrayRamp.g0 else GrayRamp.g3
    Box(
        modifier = Modifier
            .size(size)
            .then(if (primary) Modifier.background(ink, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val round = glyph == TransportGlyph.Rewind || glyph == TransportGlyph.FastForward
        // The round arrows need room inside the ring for the label, so they
        // fill more of the button than the solid glyphs do.
        Canvas(modifier = Modifier.size(size * if (round) 0.72f else 0.5f)) {
            drawGlyph(glyph, if (primary) GrayRamp.g4 else ink)
        }
        if (round) {
            TextMMD(text = SKIP_LABEL, color = ink, fontSize = SKIP_LABEL_SP, maxLines = 1)
        }
    }
}

private fun DrawScope.drawGlyph(glyph: TransportGlyph, ink: Color) {
    val w = size.width
    val h = size.height
    fun p(x: Float, y: Float) = Offset(w * x, h * y)
    fun triangle(a: Offset, b: Offset, c: Offset) = drawPath(
        Path().apply {
            moveTo(a.x, a.y)
            lineTo(b.x, b.y)
            lineTo(c.x, c.y)
            close()
        },
        ink,
    )
    fun chevron(tipX: Float, armX: Float) = drawPath(
        Path().apply {
            moveTo(w * armX, h * 0.12f)
            lineTo(w * tipX, h * 0.5f)
            lineTo(w * armX, h * 0.88f)
        },
        ink,
        style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    when (glyph) {
        TransportGlyph.Previous -> chevron(tipX = 0.3f, armX = 0.66f)
        TransportGlyph.Next -> chevron(tipX = 0.7f, armX = 0.34f)
        TransportGlyph.Rewind -> roundArrow(ink, clockwise = false)
        TransportGlyph.FastForward -> roundArrow(ink, clockwise = true)
        // Nudged right of centre: a triangle's visual weight sits towards its
        // base, so a geometrically centred one looks off-centre in the circle.
        TransportGlyph.Play -> triangle(p(0.2f, 0.06f), p(0.2f, 0.94f), p(0.95f, 0.5f))
        TransportGlyph.Pause -> {
            val bar = w * 0.26f
            drawRect(ink, topLeft = p(0.12f, 0.08f), size = Size(bar, h * 0.84f))
            drawRect(ink, topLeft = p(0.62f, 0.08f), size = Size(bar, h * 0.84f))
        }
    }
}

/**
 * A ring open just to one side of twelve o'clock, with the arrowhead at the top
 * pointing the way time moves: left (anticlockwise) to go back, right to go
 * forward. The same shape every audiobook and podcast app uses for a skip.
 */
private fun DrawScope.roundArrow(ink: Color, clockwise: Boolean) {
    val stroke = size.minDimension * 0.09f
    val head = size.minDimension * 0.17f
    // Inset so the arrowhead, which sits on the ring, is not clipped at the top.
    val radius = size.minDimension / 2f - head
    val centre = Offset(size.width / 2f, size.height / 2f)
    val top = Offset(centre.x, centre.y - radius)

    // Angles run clockwise from three o'clock, so -90 is twelve o'clock and a
    // 300 degree sweep leaves a 60 degree gap on the side the arrow points to.
    drawArc(
        color = ink,
        startAngle = -90f,
        sweepAngle = if (clockwise) -300f else 300f,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Butt),
    )

    val dir = if (clockwise) 1f else -1f
    drawPath(
        Path().apply {
            moveTo(top.x + dir * head, top.y)
            lineTo(top.x - dir * head * 0.4f, top.y - head)
            lineTo(top.x - dir * head * 0.4f, top.y + head)
            close()
        },
        ink,
    )
}
