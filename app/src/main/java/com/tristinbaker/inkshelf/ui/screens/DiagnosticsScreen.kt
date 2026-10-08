package com.tristinbaker.inkshelf.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.tristinbaker.inkshelf.ui.components.InkLazyColumn
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.tristinbaker.inkshelf.ui.components.BackButton
import com.tristinbaker.inkshelf.core.eink.EinkMode
import com.tristinbaker.inkshelf.core.eink.MeinkBinder
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.components.SectionHeader
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

/**
 * Built specifically for the case where the e-ink work is unverified: there is
 * no public Mudita SDK, so the only way to know whether the hidden service
 * resolved is to print what happened and read it off the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    einkAvailable: Boolean,
    einkMode: EinkMode,
    einkError: String?,
    einkTransacts: Int,
    serverUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics
    val config = context.resources.configuration

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBarMMD(
            title = { TextMMD(text = "Diagnostics") },
            navigationIcon = { BackButton(onClick = onBack) },
        )

        InkLazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SectionHeader("E-ink service") }
            item { Field("Service found", if (einkAvailable) "yes" else "no") }
            item { Field("Service name", MeinkBinder.SERVICE_NAME) }
            item { Field("Interface descriptor", MeinkBinder.DESCRIPTOR) }
            item { Field("Transaction code", MeinkBinder.TRANSACTION_SET_DISPLAY_MODE.toString()) }
            item { Field("Applied mode", "${einkMode.label} (${einkMode.panelCode})") }
            item { Field("Transactions attempted", einkTransacts.toString()) }
            item {
                Field(
                    "Last error",
                    einkError ?: "none",
                )
            }

            item { Gap(10) }
            item { SectionHeader("Display") }
            item { Field("Width x height", "${density.widthPixels} x ${density.heightPixels}") }
            item { Field("Density dpi", density.densityDpi.toString()) }
            item { Field("Density", "${density.density}") }
            item { Field("Smallest width", "${config.smallestScreenWidthDp}dp") }
            item { Field("Screen size", config.screenLayout.toString()) }

            item { Gap(10) }
            item { SectionHeader("Platform") }
            item { Field("Android release", Build.VERSION.RELEASE) }
            item { Field("SDK level", Build.VERSION.SDK_INT.toString()) }
            item { Field("Device", "${Build.MANUFACTURER} ${Build.MODEL}") }
            item { Field("Board", Build.BOARD) }
            item { Field("Fingerprint", Build.FINGERPRINT) }

            item { Gap(10) }
            item { SectionHeader("Server") }
            item { Field("Base URL", serverUrl) }
            item { Gap(16) }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        TextMMD(text = label, color = GrayRamp.g1)
        TextMMD(text = value, color = GrayRamp.g0)
    }
}
