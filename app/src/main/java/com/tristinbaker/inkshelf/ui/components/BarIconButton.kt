package com.tristinbaker.inkshelf.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonDefaultsMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.tristinbaker.inkshelf.R

/** The top bar's way out, on every screen that has one. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BarIconButton(icon = R.drawable.ic_back, label = "Back", onClick = onClick, modifier = modifier)
}

/** Opens the downloaded books from the library's top bar. */
@Composable
fun DownloadsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    BarIconButton(icon = R.drawable.ic_download, label = "Downloads", onClick = onClick, modifier = modifier)
}

/**
 * A filled MMD button holding a glyph instead of a word, so it keeps the same
 * height and black fill as the text buttons beside it. The label is still
 * announced, it just is not drawn.
 */
@Composable
private fun BarIconButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonMMD(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = label },
        contentPadding = BarIconPadding,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(LocalContentColor.current),
            modifier = Modifier.size(BAR_ICON_SIZE),
        )
    }
}

private val BAR_ICON_SIZE = 26.dp

private val BarIconPadding = PaddingValues(
    horizontal = 14.dp,
    vertical = ButtonDefaultsMMD.buttonVerticalPadding,
)
