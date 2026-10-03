package com.tristinbaker.inkshelf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.ui.components.BackButton
import com.tristinbaker.inkshelf.core.eink.EinkMode
import com.tristinbaker.inkshelf.core.storage.DownloadFolder
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.InkRow
import com.tristinbaker.inkshelf.ui.components.SectionHeader
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

private const val PAGE_JUMP_STEP = 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    einkMode: EinkMode,
    einkAvailable: Boolean,
    einkError: String?,
    downloadFolderLabel: String,
    downloadFolderUsable: Boolean,
    serverUrl: String,
    username: String,
    showCovers: Boolean,
    onEinkMode: (EinkMode) -> Unit,
    onShowCovers: (Boolean) -> Unit,
    onPickDownloadFolder: () -> Unit,
    onResetDownloadFolder: () -> Unit,
    onFullRefresh: () -> Unit,
    onDiagnostics: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = { TextMMD(text = "Settings") },
            navigationIcon = { BackButton(onClick = onBack) },
        )

        LazyColumnMMD(modifier = Modifier.fillMaxSize(), scrollStep = PAGE_JUMP_STEP) {
            item { SectionHeader("Display") }

            item {
                TextMMD(
                    text = if (einkAvailable) {
                        "Meink service found. Choose how the panel renders."
                    } else {
                        "No e-ink service on this device. The app will still run, but " +
                            "the panel stays in whatever mode the system chose."
                    },
                    color = GrayRamp.g1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            items(EinkMode.readable.size) { index ->
                val mode = EinkMode.readable[index]
                InkRow(
                    title = "${mode.label} (${mode.panelCode})",
                    subtitle = mode.description,
                    selected = mode == einkMode,
                    onClick = { onEinkMode(mode) },
                )
            }

            item {
                Gap(8)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ButtonMMD(
                        onClick = onFullRefresh,
                        enabled = einkAvailable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextMMD(text = "Full refresh (clear ghosting)")
                    }
                    if (einkError != null) {
                        TextMMD(text = einkError, color = GrayRamp.g1)
                    }
                }
            }

            item {
                InkRow(
                    title = "Cover thumbnails",
                    subtitle = "Artwork beside titles in lists. Turning it off " +
                        "makes scrolling refresh faster.",
                    trailing = if (showCovers) "On" else "Off",
                    selected = showCovers,
                    onClick = { onShowCovers(!showCovers) },
                )
            }

            item { Gap(12) }
            item { SectionHeader("Downloads") }

            item {
                TextMMD(
                    text = "Pick any folder to download books into. An SD card shows " +
                        "up in the folder browser alongside internal storage.",
                    color = GrayRamp.g1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            item { InkRow(title = "Download location", subtitle = downloadFolderLabel) }

            item {
                Gap(8)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ButtonMMD(onClick = onPickDownloadFolder, modifier = Modifier.fillMaxWidth()) {
                        TextMMD(text = "Choose folder")
                    }
                    if (downloadFolderLabel != DownloadFolder.DEFAULT_LABEL) {
                        ButtonMMD(
                            onClick = onResetDownloadFolder,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            TextMMD(text = "Use the default location")
                        }
                    }
                }
            }

            if (!downloadFolderUsable) {
                item {
                    TextMMD(
                        text = "That folder is not available right now. If it was on " +
                            "an SD card, check the card is inserted. Downloads will " +
                            "go to the default location until it comes back.",
                        color = GrayRamp.g1,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            item {
                TextMMD(
                    text = "Books already downloaded stay where they are.",
                    color = GrayRamp.g1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            item {
                Gap(12)
                SectionHeader("Account")
                InkRow(title = "Server", subtitle = serverUrl)
                InkRow(title = "Signed in as", subtitle = username)
            }

            item {
                Gap(8)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ButtonMMD(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        TextMMD(text = "Diagnostics")
                    }
                    ButtonMMD(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                        TextMMD(text = "Sign out")
                    }
                }
                Gap(12)
            }
        }
    }
}
