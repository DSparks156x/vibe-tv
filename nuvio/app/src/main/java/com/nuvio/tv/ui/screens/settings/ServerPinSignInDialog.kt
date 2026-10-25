@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** Vibe fork: sign-in for providers that approve a code on another device (Plex: plex.tv/link). */
@Composable
internal fun ServerPinSignInDialog(
    provider: ServerProvider,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.pinSignIn.collectAsStateWithLifecycle()
    val start = { viewModel.startPinSignIn(provider, onConnected) }

    DisposableEffect(provider) {
        start()
        onDispose { viewModel.cancelPinSignIn() }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.servers_sign_in_title, provider.displayName),
        subtitle = stringResource(R.string.vibe_servers_pin_subtitle),
        width = 640.dp
    ) {
        val pin = state.pin
        if (pin != null && state.choices.isEmpty() && !state.expired) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val qr = remember(pin.approveUrl) { QrCodeGenerator.generate(pin.approveUrl, size = 360).asImageBitmap() }
                Image(
                    bitmap = qr,
                    contentDescription = stringResource(R.string.cd_qr_login),
                    modifier = Modifier.size(180.dp),
                    contentScale = ContentScale.Fit
                )
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    Text(
                        text = stringResource(R.string.vibe_servers_pin_instructions, pin.linkUrl.removePrefix("https://")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = pin.code,
                        style = MaterialTheme.typography.displaySmall,
                        color = NuvioTheme.colors.TextPrimary
                    )
                }
            }
        }
        if (state.choices.isNotEmpty() && !state.connecting) {
            Text(
                text = stringResource(R.string.vibe_servers_pin_choose),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            state.choices.forEach { choice ->
                SettingsDialogActionRow(horizontalAlignment = Alignment.Start) {
                    SettingsDialogActionButton(
                        text = if (choice.owned) choice.name else stringResource(R.string.vibe_servers_pin_shared, choice.name),
                        onClick = { viewModel.choosePinServer(provider, choice, onConnected) }
                    )
                }
            }
        }
        val error = state.error
        val status = when {
            error != null -> error.pinMessage()
            state.expired -> stringResource(R.string.vibe_servers_pin_expired)
            state.connecting || pin == null -> stringResource(R.string.servers_connecting)
            state.choices.isEmpty() -> stringResource(R.string.vibe_servers_pin_waiting)
            else -> null
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            if (state.expired || error != null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.vibe_servers_pin_new_code),
                    onClick = start,
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun ServerFailure.pinMessage(): String = when (this) {
    ServerFailure.NOT_FOUND -> stringResource(R.string.vibe_servers_pin_no_servers)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.AUTH_REQUIRED,
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_auth)
    ServerFailure.UNSUPPORTED,
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(R.string.servers_error_failed)
}
