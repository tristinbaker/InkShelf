package com.tristinbaker.inkshelf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.ui.components.BackButton
import com.tristinbaker.inkshelf.data.BookMetadataEntity
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.formatDuration
import com.tristinbaker.inkshelf.ui.components.InkRow
import com.tristinbaker.inkshelf.ui.components.SectionHeader
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

private const val PAGE_JUMP_STEP = 0

/**
 * Books whose files are on the device, listed from the local cache so this screen
 * works with the radio off. It is the only way into a downloaded book offline,
 * because every other book list is fetched from the server.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedScreen(
    books: List<BookMetadataEntity>,
    onBack: () -> Unit,
    onOpen: (BookMetadataEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = { TextMMD(text = "Downloaded", maxLines = 1) },
            navigationIcon = { BackButton(onClick = onBack) },
        )

        if (books.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextMMD(text = "No books on this device yet.", color = GrayRamp.g1)
            }
            return@Column
        }

        LazyColumnMMD(
            modifier = Modifier.fillMaxSize(),
            scrollStep = PAGE_JUMP_STEP,
        ) {
            item { SectionHeader("On this device") }
            items(books.size) { index ->
                val book = books[index]
                InkRow(
                    title = book.title,
                    subtitle = listOfNotNull(
                        book.authorName?.takeIf { it.isNotBlank() },
                        book.seriesName?.let {
                            it + book.seriesSequence?.let { s -> " #$s" }.orEmpty()
                        },
                    ).joinToString(" · "),
                    trailing = if (book.durationSeconds > 0) {
                        formatDuration(book.durationSeconds)
                    } else {
                        null
                    },
                    onClick = { onOpen(book) },
                )
            }
            item { Gap(16) }
        }
    }
}
