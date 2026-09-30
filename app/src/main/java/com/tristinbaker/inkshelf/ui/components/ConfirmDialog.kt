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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * A blocking yes/no for actions that destroy something the user spent time and
 * bandwidth on.
 *
 * Built from two sibling layers rather than a parent `Box` that carries the
 * dismiss handler. In the first version the dismiss `clickable` was on the Box
 * wrapping the panel, so it was an ancestor of every button and competed with
 * them: tapping "Delete" dismissed the dialog and never called `onConfirm`.
 * Here the scrim is a sibling drawn *first*, so it is behind the panel. Hit
 * testing picks the topmost node under the pointer, so a tap on the panel body
 * reaches no handler at all and a tap outside it reaches the scrim, and the
 * buttons, being leaves, always win.
 *
 * The scrim is a solid grey rather than a translucent black: on a 16-level
 * e-ink panel the alpha blend is not something to leave to chance when the
 * whole point of the layer is to separate the dialog from the page.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
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
                .background(GrayRamp.g4)
                .border(3.dp, GrayRamp.g0, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextMMD(text = title, color = GrayRamp.g0)
            TextMMD(text = body, color = GrayRamp.g1)
            Row2(
                dismissLabel = "Keep",
                confirmLabel = confirmLabel,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

/**
 * Dismiss is the safe default, so it is the one that looks safe: a white fill
 * with a black outline. Confirm is a solid black block.
 *
 * Drawn from `Box` rather than the MMD button components. Two MMD buttons side
 * by side both render as solid black fills on this display regardless of the
 * `colors` passed in, which left "Keep" and "Delete" looking identical, and that
 * is not an acceptable pair of choices to put in front of someone about to throw
 * away a download.
 */
@Composable
private fun Row2(
    dismissLabel: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DialogButton(
            label = dismissLabel,
            fill = GrayRamp.g4,
            content = GrayRamp.g0,
            outlined = true,
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
        )
        DialogButton(
            label = confirmLabel,
            fill = GrayRamp.g0,
            content = GrayRamp.g4,
            outlined = false,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DialogButton(
    label: String,
    fill: Color,
    content: Color,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .background(fill, shape)
            .then(if (outlined) Modifier.border(2.dp, GrayRamp.g0, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(text = label, color = content)
    }
}
