package com.cuscus.wifiaudiostreaming

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

object DownloadLinks {
    const val GITHUB_RELEASES =
        "https://github.com/mu-23/WiFiAudioStreaming-Android/releases"
}

@Composable
fun UpdateAvailableDialog(
    current: String,
    latest: String,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ExpressiveVersionDialog(
        icon = Icons.Outlined.Update,
        accent = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.update_dialog_available_title),
        body = stringResource(R.string.update_dialog_available_body),
        fromVersion = current,
        toVersion = latest,
        confirmLabel = stringResource(R.string.update_dialog_github),
        confirmIcon = Icons.Outlined.Code,
        onConfirm = { onOpenUrl(DownloadLinks.GITHUB_RELEASES) },
        dismissLabel = stringResource(R.string.update_dialog_later),
        onDismiss = onDismiss
    )
}

@Composable
fun UpdateResultDialog(
    result: UpdateChecker.Result,
    onUpdate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    when (result) {
        is UpdateChecker.Result.Available -> ExpressiveVersionDialog(
            icon = Icons.Outlined.Update,
            accent = MaterialTheme.colorScheme.primary,
            title = stringResource(R.string.update_dialog_available_title),
            body = stringResource(R.string.update_dialog_available_body),
            fromVersion = result.current,
            toVersion = result.latest,
            confirmLabel = stringResource(R.string.update_dialog_github),
            confirmIcon = Icons.Outlined.Code,
            onConfirm = { onUpdate(DownloadLinks.GITHUB_RELEASES) },
            dismissLabel = stringResource(R.string.update_dialog_close),
            onDismiss = onDismiss
        )

        is UpdateChecker.Result.UpToDate -> ExpressiveVersionDialog(
            icon = Icons.Outlined.CheckCircle,
            accent = MaterialTheme.colorScheme.tertiary,
            title = stringResource(R.string.update_dialog_up_to_date_title),
            body = stringResource(R.string.update_dialog_up_to_date_body, result.current),
            fromVersion = null,
            toVersion = null,
            confirmLabel = null,
            dismissLabel = stringResource(R.string.update_dialog_close),
            onConfirm = null,
            onDismiss = onDismiss
        )

        is UpdateChecker.Result.Ahead -> VersionAheadDialog(
            current = result.current,
            latest = result.latest,
            onDismiss = onDismiss
        )

        is UpdateChecker.Result.Failed -> ExpressiveVersionDialog(
            icon = Icons.Outlined.CloudOff,
            accent = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.update_dialog_failed_title),
            body = stringResource(R.string.update_dialog_failed_body),
            fromVersion = null,
            toVersion = null,
            confirmLabel = null,
            dismissLabel = stringResource(R.string.update_dialog_close),
            onConfirm = null,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun VersionAheadDialog(
    current: String,
    latest: String,
    onDismiss: () -> Unit
) {
    ExpressiveVersionDialog(
        icon = Icons.Outlined.EmojiEvents,
        accent = MaterialTheme.colorScheme.tertiary,
        title = stringResource(R.string.update_dialog_ahead_title),
        body = stringResource(R.string.update_dialog_ahead_body),
        fromVersion = latest,
        toVersion = current,
        confirmLabel = null,
        dismissLabel = stringResource(R.string.update_dialog_close),
        onConfirm = null,
        onDismiss = onDismiss
    )
}
