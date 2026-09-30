package com.tristinbaker.inkshelf.ui.theme

import androidx.compose.runtime.Composable
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.eInkTypography

/**
 * MMD handles the e-ink specifics for us: it nulls out
 * `LocalRippleConfiguration` so there is no ripple animation, and its bundled
 * Lato scale is tuned for slow greyscale refreshes.
 */
@Composable
fun InkShelfTheme(content: @Composable () -> Unit) {
    ThemeMMD(
        colorScheme = inkShelfColorScheme(),
        typography = eInkTypography,
        content = content,
    )
}
