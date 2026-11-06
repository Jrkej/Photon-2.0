package com.photon.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photon.app.R
import com.photon.app.core.MediaAccess
import com.photon.app.core.MediaPermissions
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.ReceiveState
import com.photon.app.data.db.ReceivedPhoto
import com.photon.app.data.db.SessionState
import com.photon.app.data.db.UploadState
import com.photon.app.events.ConnectionState
import com.photon.app.upload.Uploader

@Composable
fun HomeScreen(vm: AppViewModel, onScan: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Spacer(Modifier.height(24.dp)) }
        item { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold) }
        item {
            Button(onClick = vm::requestCreate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("create")) {
                Text(stringResource(R.string.create_session))
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(64) },
                        label = { Text(stringResource(R.string.join_code_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("code"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.requestJoinByText(code) }, enabled = code.isNotBlank() && !ui.busy) {
                            Text(stringResource(R.string.join_session))
                        }
                        OutlinedButton(onClick = onScan, enabled = !ui.busy) { Text(stringResource(R.string.scan_qr)) }
                    }
                }
            }
        }
        item { SettingsCard(vm) }
        item {
            TextButton(onClick = vm::signOut) { Text(stringResource(R.string.sign_out)) }
        }
    }
}

@Composable
fun SessionScreen(vm: AppViewModel, session: SessionState) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val received by vm.received.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val keys by vm.keyStatus.collectAsStateWithLifecycle()

    var recallTarget by remember { mutableStateOf<String?>(null) }
    var reportTarget by remember { mutableStateOf<String?>(null) }
    var confirmEnd by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item { SyncCard(session, connection, enabled = !ui.busy, onPausedChange = vm::setPaused) }
        if (keys?.holdsCurrent == false) item { KeyWaitCard() }
        item { BatteryOptimizationCard() }
        item { PermissionCards(vm) }
        item { InviteCard(session, onRotate = vm::rotateInvites, busy = ui.busy) }

        item { SectionTitle(stringResource(R.string.received_title)) }
        if (received.isEmpty()) {
            item { Text(stringResource(R.string.no_photos_yet), style = MaterialTheme.typography.bodyMedium) }
        }
        items(received.chunked(3)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { photo ->
                    ReceivedTile(
                        photo = photo,
                        canRecall = session.isHost,
                        onRecall = { recallTarget = photo.sha256 },
                        onReport = { reportTarget = photo.sha256 },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        item { SectionTitle(stringResource(R.string.mine_title)) }
        if (mine.isEmpty()) {
            item { Text(stringResource(R.string.no_photos_yet), style = MaterialTheme.typography.bodyMedium) }
        }
        items(mine, key = { it.mediaId }) { photo ->
            MineRow(photo, onRecall = { photo.sha256?.let { recallTarget = it } })
        }

        item { SectionTitle(stringResource(R.string.members_title, members.size)) }
        items(members, key = { it }) { member ->
            MemberRow(member, session, vm.selfUserId, onKick = { vm.kick(member) })
        }

        item { SettingsCard(vm) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (session.isHost) {
                    OutlinedButton(onClick = { confirmEnd = true }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.end_session))
                    }
                }
                Button(onClick = vm::leave, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().testTag("leave")) {
                    Text(stringResource(R.string.leave_session))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    recallTarget?.let { sha ->
        RecallDialog(onConfirm = { vm.recall(sha); recallTarget = null }, onDismiss = { recallTarget = null })
    }
    reportTarget?.let { sha ->
        ReportDialog(onSend = { vm.report(sha, it); reportTarget = null }, onDismiss = { reportTarget = null })
    }
    if (confirmEnd) {
        ConfirmDialog(
            title = stringResource(R.string.end_session),
            text = stringResource(R.string.session_ended_message),
            confirm = stringResource(R.string.end_session),
            onConfirm = { vm.endForEveryone(); confirmEnd = false },
            onDismiss = { confirmEnd = false },
        )
    }
}

/** The persistent sync indicator and the pause switch (7.4, 7.11). */
@Composable
private fun SyncCard(session: SessionState, connection: ConnectionState, enabled: Boolean, onPausedChange: (Boolean) -> Unit) {
    val on = !session.paused
    Card(Modifier.fillMaxWidth().testTag("sync-indicator")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(if (on) Color(0xFF2EAD5B) else MaterialTheme.colorScheme.outline),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (on) R.string.sync_on else R.string.sync_paused),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(connectionLabel(connection), style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(
                    checked = on,
                    enabled = enabled,
                    onCheckedChange = { onPausedChange(!it) },
                    modifier = Modifier.testTag("pause-switch"),
                )
                Text(stringResource(R.string.sync_pause_switch), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * e2ee.md 5.3: a newcomer can't send or open photos until some member's app has sealed the session key for
 * it, which happens automatically when one of them is online.
 */
@Composable
private fun KeyWaitCard() {
    Card(
        Modifier.fillMaxWidth().testTag("key-wait"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.key_wait_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.key_wait_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun connectionLabel(state: ConnectionState) = when (state) {
    ConnectionState.LIVE -> "Live"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.OFFLINE -> "Offline, retrying"
    ConnectionState.IDLE -> "Not connected"
}

@Composable
private fun InviteCard(session: SessionState, onRotate: () -> Unit, busy: Boolean) {
    val context = LocalContext.current
    val link = session.qrPayload.ifEmpty { session.inviteUrl }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.session_code, session.joinCode),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            if (link.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                QrImage(link, Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.inviteUrl.ifEmpty { link })
                        context.startActivity(Intent.createChooser(send, null))
                    }) { Text("Share link") }
                    if (session.isHost) {
                        TextButton(onClick = onRotate, enabled = !busy) { Text(stringResource(R.string.rotate_invites)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceivedTile(photo: ReceivedPhoto, canRecall: Boolean, onRecall: () -> Unit, onReport: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        FileImage(photo.previewPath ?: photo.thumbPath, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
        Text(
            when (photo.state) {
                ReceiveState.SAVED -> "Saved"
                ReceiveState.PREVIEW -> "Preview"
                ReceiveState.FAILED -> "Couldn't be verified"
                else -> if (photo.thumbPath == null) "Loading…" else "Thumbnail"
            },
            style = MaterialTheme.typography.labelSmall,
        )
        Row {
            if (canRecall) TextButton(onClick = onRecall) { Text(stringResource(R.string.recall)) }
            TextButton(onClick = onReport) { Text(stringResource(R.string.report)) }
        }
    }
}

@Composable
private fun MineRow(photo: PendingPhoto, onRecall: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MediaImage(photo.uri, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(uploadLabel(photo), style = MaterialTheme.typography.bodyMedium)
            photo.lastError?.takeIf { photo.state == UploadState.FAILED || photo.state == UploadState.SKIPPED }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (photo.state == UploadState.SYNCED && photo.sha256 != null) {
            TextButton(onClick = onRecall) { Text(stringResource(R.string.recall)) }
        }
    }
}

private fun uploadLabel(photo: PendingPhoto) = when {
    !photo.isFinal && photo.lastError == Uploader.KEY_WAIT -> "Waiting for the session key"
    else -> uploadStateLabel(photo)
}

private fun uploadStateLabel(photo: PendingPhoto) = when (photo.state) {
    UploadState.PENDING, UploadState.HASHED -> "Queued"
    UploadState.UPLOADING -> "Uploading"
    UploadState.WAITING -> "Someone else is uploading it"
    UploadState.SYNCED -> "Shared"
    UploadState.SKIPPED -> "Not shared"
    UploadState.FAILED -> "Failed"
    else -> photo.state
}

@Composable
private fun MemberRow(memberId: String, session: SessionState, self: String?, onKick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(memberId.take(12), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        if (memberId == session.hostId) Text(stringResource(R.string.host_badge), style = MaterialTheme.typography.labelSmall)
        if (memberId == self) {
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.you_badge), style = MaterialTheme.typography.labelSmall)
        }
        if (session.isHost && memberId != self) TextButton(onClick = onKick) { Text(stringResource(R.string.kick)) }
    }
    HorizontalDivider()
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** GPS stripping (on by default) and originals on Wi-Fi or while charging (7.11). */
@Composable
fun SettingsCard(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Without the permission Android keeps redacting location, so sharing it can't be on.
        if (!granted) vm.setStripGps(true)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
            SettingRow(
                title = stringResource(R.string.setting_strip_gps),
                detail = stringResource(R.string.setting_strip_gps_detail),
                checked = settings.stripGps,
                tag = "strip-gps",
                onChange = { strip ->
                    vm.setStripGps(strip)
                    if (!strip && Build.VERSION.SDK_INT >= 29) locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                },
            )
            SettingRow(
                title = stringResource(R.string.setting_originals_wifi),
                detail = stringResource(R.string.setting_originals_wifi_detail),
                checked = settings.originalsWifiOrCharging,
                tag = "originals-wifi",
                onChange = vm::setOriginalsWifiOrCharging,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, detail: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * Gallery and notification permissions. Android 14 partial access hides new photos, so it gets
 * an explanation and a way to grant full access.
 */
@Composable
fun PermissionCards(
    vm: AppViewModel,
    accessProvider: (android.content.Context) -> MediaAccess = MediaPermissions::access,
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(accessProvider(context)) }
    var notificationsAllowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || MediaPermissions.isGranted(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = accessProvider(context)
        if (now != access && now == MediaAccess.FULL) vm.onMediaAccessChanged()
        access = now
        notificationsAllowed = Build.VERSION.SDK_INT < 33 || MediaPermissions.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = accessProvider(context)
        if (access == MediaAccess.FULL) vm.onMediaAccessChanged()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (access != MediaAccess.FULL) {
            Card(Modifier.fillMaxWidth().testTag("media-access-prompt")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.perm_media_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.perm_media_body), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { mediaLauncher.launch(MediaPermissions.toRequest()) }) {
                            Text(stringResource(R.string.perm_media_grant))
                        }
                        if (access == MediaAccess.PARTIAL) {
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                                )
                            }) { Text(stringResource(R.string.perm_open_settings)) }
                        }
                    }
                }
            }
        }
        if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.perm_notifications_body), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text(stringResource(R.string.perm_notifications_grant))
                    }
                }
            }
        }
    }
}

/**
 * Motorola and other vendors stop background work aggressively. Continuous sync is the app's
 * core function, so it asks to be exempt from battery optimisation; the exemption also lets the
 * background triggers restart SyncService after the process was killed.
 */
@SuppressLint("BatteryLife")
@Composable
fun BatteryOptimizationCard() {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = isIgnoringBatteryOptimizations(context) }
    if (exempt) return
    Card(
        Modifier.fillMaxWidth().testTag("battery-optimization"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.battery_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                try {
                    context.startActivity(request)
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }) { Text(stringResource(R.string.battery_allow)) }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true
