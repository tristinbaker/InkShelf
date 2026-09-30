package com.tristinbaker.inkshelf.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.tristinbaker.inkshelf.core.abs.Chapter
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * Chapter rows for the foot of a page, appended to that page's own lazy list
 * rather than wrapped in a nested one. Two vertical scrollers on a 480x600
 * e-ink panel is a fight nobody wins, and the outer list already knows how to
 * scroll the whole page.
 *
 * @param currentIndex row to mark as the one playing, which turns the list into
 *  a position indicator on the play page.
 * @param onSelect start position in book time. The caller decides what that
 *  means: a seek while the book is already loaded, or a play-at-chapter from
 *  the book page where nothing is loaded yet.
 */
fun LazyListScope.chapterItems(
    chapters: List<Chapter>,
    currentIndex: Int = -1,
    onSelect: (Long) -> Unit,
) {
    item(key = "chapters-header") {
        SectionHeader("Chapters", modifier = Modifier.padding(top = 8.dp))
    }

    if (chapters.isEmpty()) {
        item(key = "chapters-empty") {
            // Said out loud rather than left out: the skip buttons move in
            // synthetic steps for a book like this, and a listener who wonders
            // why deserves an answer.
            TextMMD(
                text = "This book has no chapter markers.",
                color = GrayRamp.g1,
                maxLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        return
    }

    items(count = chapters.size, key = { "chapter-$it" }) { index ->
        val chapter = chapters[index]
        val startMs = (chapter.start * 1000).toLong()
        // The server has been observed to hand back unordered starts after a
        // re-scan, so the number shown is the row position in the list the
        // reader is looking at, not the server's own id.
        val title = "${index + 1}. " +
            (chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}")
        InkRow(
            title = title,
            trailing = formatClock(startMs),
            selected = index == currentIndex,
            onClick = { onSelect(startMs) },
        )
    }
}
