package com.tristinbaker.inkshelf.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * A cover at a fixed size, or a neutral tile while it loads or if there is
 * none.
 *
 * The tile is always the same size as the image, so a row never reflows when
 * its cover arrives. On this panel a row moving as covers stream in means a
 * full refresh and a re-read of the list, which is the expensive case.
 *
 * Drawn unsmoothed: covers are 16-level greyscale here, and bilinear filtering
 * only softens the type on them.
 */
@Composable
fun CoverImage(
    store: CoverStore,
    itemId: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { width.roundToPx() }
    var bitmap by remember(itemId, px) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(itemId, px) {
        bitmap = store.load(itemId, px)
    }

    Box(
        modifier = modifier
            .size(width, height)
            .background(GrayRamp.g3),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(width, height),
            )
        }
    }
}

/**
 * Square, because the artwork in this library is square. Sizing the tile to a
 * conventional 1:1.5 book portrait crops a square cover to its middle third,
 * which is how the Warbreaker artwork lost its title. `ContentScale.Crop` still
 * guards a stray non-square cover by centring it rather than letterboxing.
 */
val CoverThumbWidth: Dp = 80.dp
val CoverThumbHeight: Dp = 80.dp
