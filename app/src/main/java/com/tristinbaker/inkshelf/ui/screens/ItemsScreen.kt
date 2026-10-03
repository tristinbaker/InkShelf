package com.tristinbaker.inkshelf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.ui.components.BackButton
import com.tristinbaker.inkshelf.data.ItemEntity
import com.tristinbaker.inkshelf.cover.CoverStore
import com.tristinbaker.inkshelf.ui.components.CoverImage
import com.tristinbaker.inkshelf.ui.components.CoverThumbHeight
import com.tristinbaker.inkshelf.ui.components.CoverThumbWidth
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.formatDuration
import com.tristinbaker.inkshelf.ui.components.InkRow
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

private const val PAGE_JUMP_STEP = 0

/** Books inside a series or by an author. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    title: String,
    items: List<ItemEntity>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    covers: CoverStore? = null,
    showCovers: Boolean = true,
    onOpen: (ItemEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = {
                TextMMD(text = title, maxLines = 1)
            },
            navigationIcon = {
                BackButton(onClick = onBack)
            },
        )

        if (loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicatorMMD()
            }
            return@Column
        }

        if (error != null) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextMMD(text = error, color = GrayRamp.g0)
                Gap(4)
                ButtonMMD(onClick = onRefresh) { TextMMD(text = "Try again") }
            }
            return@Column
        }

        if (items.isEmpty()) {
            TextMMD(text = "Nothing here yet", color = GrayRamp.g1, modifier = Modifier.padding(16.dp))
            return@Column
        }

        LazyColumnMMD(
            modifier = Modifier.fillMaxSize(),
            scrollStep = PAGE_JUMP_STEP,
        ) {
            items(items.size) { index ->
                val item = items[index]
                InkRow(
                    title = item.title,
                    subtitle = listOfNotNull(
                        item.authorName.takeIf { it.isNotBlank() },
                        item.seriesSequence?.let { "Book $it" },
                        item.narratorName,
                    ).joinToString(" · "),
                    trailing = formatDuration(item.durationSeconds),
                    leading = covers?.takeIf { showCovers }?.let { store ->
                        {
                            CoverImage(
                                store = store,
                                itemId = item.id,
                                width = CoverThumbWidth,
                                height = CoverThumbHeight,
                            )
                        }
                    },
                    onClick = { onOpen(item) },
                )
            }
            item { Gap(12) }
        }
    }
}
