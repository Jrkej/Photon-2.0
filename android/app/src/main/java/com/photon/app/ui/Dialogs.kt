package com.photon.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.photon.app.R
import com.photon.proto.SessionInfo

/** Shown before every join and create: photos taken during the session are shared (7.4, 8.3). */
@Composable
fun ConsentDialog(creating: Boolean, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("consent"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.consent_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.consent_body))
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(if (creating) R.string.consent_accept_create else R.string.consent_accept))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Says plainly what recall can and cannot do (7.11). */
@Composable
fun RecallDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("recall"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recall_title)) },
        text = { Text(stringResource(R.string.recall_body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.recall_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ReportDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.report_title)) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(500) },
                label = { Text(stringResource(R.string.report_hint)) },
                minLines = 2,
            )
        },
        confirmButton = {
            TextButton(enabled = reason.isNotBlank(), onClick = { onSend(reason.trim()) }) {
                Text(stringResource(R.string.report_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The server already has the user in another session (409 ALREADY_IN_SESSION on create or join,
 * or found at start-up). Offers to open it here or leave it.
 */
@Composable
fun SessionConflictDialog(
    info: SessionInfo,
    selfUserId: String?,
    retrying: Boolean,
    onOpen: () -> Unit,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isHost = info.role == "host" || (!selfUserId.isNullOrEmpty() && info.host_id == selfUserId)
    AlertDialog(
        modifier = Modifier.testTag("session-conflict"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (retrying) R.string.conflict_title_retry else R.string.conflict_title_found)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.conflict_body,
                        info.join_code.ifEmpty { info.session_id.take(8) },
                        stringResource(if (isHost) R.string.conflict_role_host else R.string.conflict_role_member),
                    ) + " " + pluralStringResource(R.plurals.conflict_members, info.member_ids.size, info.member_ids.size),
                )
                Text(stringResource(R.string.conflict_open_note))
                if (retrying) Text(stringResource(R.string.conflict_leave_note))
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text(stringResource(R.string.conflict_open)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onLeave) {
                    Text(stringResource(if (retrying) R.string.conflict_leave_and_continue else R.string.conflict_leave))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
