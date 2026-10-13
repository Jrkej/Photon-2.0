package com.photon.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.photon.app.R
import com.photon.app.ui.theme.PhotonTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity still carries its original intent; handle a link only once.
        if (savedInstanceState == null) handleLink(intent)

        setContent {
            PhotonTheme {
                val scanPrompt = stringResource(R.string.scan_prompt)
                val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
                    result.contents?.let(vm::requestJoinByText)
                }
                PhotonRoot(
                    vm = vm,
                    onOpenUrl = ::openInCustomTab,
                    onScan = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(scanPrompt)
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let(vm::onLink)
    }

    /** OAuth runs in the system browser through Custom Tabs, never a WebView (7.3). */
    private fun openInCustomTab(uri: Uri) {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri)
    }
}

@Composable
fun PhotonRoot(vm: AppViewModel, onOpenUrl: (Uri) -> Unit, onScan: () -> Unit) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val current = session
            when {
                !signedIn -> LoginScreen(vm, onOpenUrl)
                current != null -> SessionScreen(vm, current)
                else -> HomeScreen(vm, onScan)
            }
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }

    ui.conflict?.let { conflict ->
        SessionConflictDialog(
            info = conflict.info,
            selfUserId = vm.selfUserId,
            retrying = conflict.retry != null,
            onOpen = vm::openConflictSession,
            onLeave = vm::leaveConflictSession,
            onDismiss = vm::dismissConflict,
        )
    }

    ui.consent?.let { consent ->
        ConsentDialog(
            creating = consent is Consent.Create,
            onAccept = vm::confirmConsent,
            onDismiss = vm::dismissConsent,
        )
    }
}
