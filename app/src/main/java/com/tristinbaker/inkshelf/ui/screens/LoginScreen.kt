package com.tristinbaker.inkshelf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.progress_indicator.CircularProgressIndicatorMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.tristinbaker.inkshelf.core.net.TlsProbe
import com.tristinbaker.inkshelf.ui.components.Gap
import com.tristinbaker.inkshelf.ui.theme.GrayRamp

@Composable
fun LoginScreen(
    busy: Boolean,
    message: String?,
    supportsLocalLogin: Boolean,
    probe: TlsProbe?,
    initialUrl: String,
    onCheckServer: (String) -> Unit,
    onLogin: (url: String, user: String, password: String, trustCert: Boolean) -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var trustCert by remember { mutableStateOf(false) }

    // Default the trust toggle on as soon as we know the cert is not trusted,
    // so the common self-hosted case is one tap.
    if (probe is TlsProbe.SelfSigned && !trustCert) trustCert = true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextMMD(text = "InkShelf", color = GrayRamp.g0)
        TextMMD(
            text = "Connect to your Audiobookshelf server",
            color = GrayRamp.g1,
        )

        Gap(8)

        TextFieldMMD(
            value = url,
            onValueChange = { url = it },
            label = { TextMMD(text = "Server address") },
            placeholder = { TextMMD(text = "https://books.example.com") },
            singleLine = true,
        )
        TextMMD(
            text = "https only. Plain http is not accepted.",
            color = GrayRamp.g1,
        )

        Gap(4)

        ButtonMMD(
            onClick = { onCheckServer(url) },
            enabled = !busy && url.isNotBlank(),
        ) {
            TextMMD(text = "Check server")
        }

        Gap(4)

        when (val p = probe) {
            is TlsProbe.TrustedSystem -> TrustNote("Certificate is trusted by this device.")
            is TlsProbe.SelfSigned -> TrustNote(
                "Untrusted certificate for ${p.subject}. " +
                    "Its fingerprint will be pinned to this device.",
            )

            is TlsProbe.Failed -> TrustNote("Could not inspect the certificate: ${p.reason}")
            null -> Unit
        }

        if (probe is TlsProbe.SelfSigned) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SwitchMMD(checked = trustCert, onCheckedChange = { trustCert = it })
                TextMMD(
                    text = "Trust and pin this certificate",
                    color = GrayRamp.g0,
                )
            }
        }

        Gap(4)

        if (!supportsLocalLogin) {
            TextMMD(
                text = "This server only accepts SSO sign-in, so a username and " +
                    "password will not work here.",
                color = GrayRamp.g0,
            )
        } else {
            TextFieldMMD(
                value = username,
                onValueChange = { username = it },
                label = { TextMMD(text = "Username") },
                singleLine = true,
            )
            TextFieldMMD(
                value = password,
                onValueChange = { password = it },
                label = { TextMMD(text = "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        Gap(4)

        if (busy) {
            CircularProgressIndicatorMMD()
        } else {
            ButtonMMD(
                onClick = { onLogin(url, username, password, trustCert) },
                enabled = supportsLocalLogin &&
                    url.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(text = "Sign in")
            }
        }

        if (message != null) {
            Gap(4)
            TextMMD(text = message, color = GrayRamp.g0)
        }

        Gap(8)
        TextMMD(
            text = "Audiobookshelf limits sign-in attempts to 40 per 10 minutes " +
                "per IP address, and successful attempts count towards that.",
            color = GrayRamp.g1,
        )
    }
}

@Composable
private fun TrustNote(text: String) {
    TextMMD(text = text, color = GrayRamp.g1)
}
