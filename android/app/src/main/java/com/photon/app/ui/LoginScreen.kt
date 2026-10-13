package com.photon.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.photon.app.BuildConfig
import com.photon.app.R
import com.photon.app.auth.AuthRules
import com.photon.app.auth.SignInMethod
import com.photon.app.auth.SignInProblem

/**
 * Sign-in: a short explainer and "Continue with Google" (Credential Manager, no browser). Debug
 * builds against a development server also get an in-app username sign-in.
 */
@Composable
fun LoginScreen(vm: AppViewModel, onGoogleSignIn: () -> Unit, onGitHubSignIn: () -> Unit) {
    val login by vm.login.collectAsStateWithLifecycle()
    val clientId = AuthRules.googleClientId(login.config)
    val gitHub = AuthRules.showGitHub(login.config)
    // Back from the browser without a redirect (tab closed): stop the spinner.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onLoginScreenResumed() }
    val retry: (() -> Unit)? = when (login.lastMethod) {
        SignInMethod.GOOGLE -> onGoogleSignIn.takeIf { clientId != null }
        SignInMethod.GITHUB -> onGitHubSignIn.takeIf { gitHub }
        else -> null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppBadge()
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.login_pitch),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            HowItWorks()
            Spacer(Modifier.height(32.dp))

            login.problem?.let { problem ->
                ProblemBanner(problem, onRetry = retry, onDismiss = vm::dismissSignInProblem)
                Spacer(Modifier.height(16.dp))
            }

            when {
                login.config == null && login.configFailed -> {
                    ErrorBanner(stringResource(R.string.login_config_failed), actionLabel = stringResource(R.string.retry), onAction = vm::loadAuthConfig)
                }
                login.config == null -> CircularProgressIndicator(Modifier.size(32.dp))
                clientId == null -> Text(
                    stringResource(R.string.login_google_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("google-unavailable"),
                )
                else -> GoogleSignInButton(
                    onClick = onGoogleSignIn,
                    enabled = !login.signingIn,
                    modifier = Modifier.fillMaxWidth().testTag("google-sign-in"),
                )
            }
            if (gitHub) {
                Spacer(Modifier.height(12.dp))
                GitHubSignInButton(
                    onClick = onGitHubSignIn,
                    enabled = !login.signingIn,
                    modifier = Modifier.fillMaxWidth().testTag("github-sign-in"),
                )
            }
            if (login.signingIn) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.login_signing_in), style = MaterialTheme.typography.bodySmall)
                }
            }

            if (AuthRules.showDevLogin(BuildConfig.DEBUG, login.config)) {
                Spacer(Modifier.height(32.dp))
                DevSignIn(enabled = !login.signingIn, onSignIn = vm::devSignIn)
            }
        }
    }
}

@Composable
private fun AppBadge() {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF1B1F3B)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(88.dp))
    }
}

@Composable
private fun HowItWorks() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Step(1, stringResource(R.string.login_step_1_title), stringResource(R.string.login_step_1_body))
            Step(2, stringResource(R.string.login_step_2_title), stringResource(R.string.login_step_2_body))
            Step(3, stringResource(R.string.login_step_3_title), stringResource(R.string.login_step_3_body))
        }
    }
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The standard "Continue with Google" button (Google identity branding): white or near-black
 * fill with a neutral outline, the multicolour G, the Medium-weight label, pill shape, 40 dp tall.
 */
@Composable
fun GoogleSignInButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val container = if (dark) Color(0xFF131314) else Color(0xFFFFFFFF)
    val outline = if (dark) Color(0xFF8E918F) else Color(0xFF747775)
    val label = if (dark) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, outline.copy(alpha = if (enabled) 1f else 0.38f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = label,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = label.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.height(48.dp),
    ) {
        Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.login_google), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * "Continue with GitHub" in GitHub's style: the dark button with the white Octocat mark. On a
 * dark background it gets a light outline so its edge stays visible.
 */
@Composable
fun GitHubSignInButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val container = Color(0xFF24292F)
    val outline = if (dark) Color(0xFF8B949E) else container
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        border = BorderStroke(1.dp, outline.copy(alpha = if (enabled) 1f else 0.38f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = container,
            contentColor = Color.White,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = Color.White.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.height(48.dp),
    ) {
        Image(painterResource(R.drawable.ic_github_mark), contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.login_github), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProblemBanner(problem: SignInProblem, onRetry: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val message = stringResource(
        when (problem) {
            SignInProblem.NO_GOOGLE_ACCOUNT -> R.string.signin_problem_no_account
            SignInProblem.GOOGLE_FAILED -> R.string.signin_problem_google
            SignInProblem.REJECTED -> R.string.signin_problem_rejected
            SignInProblem.NOT_CONFIGURED -> R.string.signin_problem_not_configured
            SignInProblem.GITHUB_FAILED -> R.string.signin_problem_github
            SignInProblem.GITHUB_NOT_CONFIGURED -> R.string.signin_problem_github_not_configured
            SignInProblem.LOGIN_EXPIRED -> R.string.signin_problem_expired
            SignInProblem.STATE_MISMATCH -> R.string.signin_problem_state
            SignInProblem.NO_BROWSER -> R.string.signin_problem_no_browser
            SignInProblem.DEV_LOGIN_OFF -> R.string.signin_problem_dev_off
            SignInProblem.BAD_USERNAME -> R.string.signin_problem_username
            SignInProblem.RATE_LIMITED -> R.string.signin_problem_rate_limited
            SignInProblem.OFFLINE -> R.string.signin_problem_offline
            SignInProblem.SERVER -> R.string.signin_problem_server
        },
    )
    if (problem == SignInProblem.NO_GOOGLE_ACCOUNT) {
        ErrorBanner(message, actionLabel = stringResource(R.string.login_add_account), onDismiss = onDismiss, onAction = {
            val addAccount = Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
            try {
                context.startActivity(addAccount)
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })
    } else {
        val retryable = problem !in setOf(
            SignInProblem.NOT_CONFIGURED,
            SignInProblem.GITHUB_NOT_CONFIGURED,
            SignInProblem.DEV_LOGIN_OFF,
            SignInProblem.BAD_USERNAME,
            SignInProblem.NO_BROWSER,
        )
        ErrorBanner(
            message,
            actionLabel = if (retryable && onRetry != null) stringResource(R.string.retry) else null,
            onAction = { onDismiss(); onRetry?.invoke() },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, actionLabel: String?, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Card(
        Modifier.fillMaxWidth().testTag("sign-in-error"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            Row(Modifier.align(Alignment.End)) {
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                }
                if (actionLabel != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

/** Debug builds against a development server only: sign in with a bare username, in the app. */
@Composable
private fun DevSignIn(enabled: Boolean, onSignIn: (String) -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    val valid = AuthRules.isValidUsername(username)
    OutlinedCard(Modifier.fillMaxWidth().testTag("dev-sign-in")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.login_dev_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.login_dev_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(40) },
                label = { Text(stringResource(R.string.login_dev_username)) },
                supportingText = { Text(stringResource(R.string.login_dev_username_hint)) },
                isError = username.isNotEmpty() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid && enabled) onSignIn(username) }),
                modifier = Modifier.fillMaxWidth().testTag("dev-username"),
            )
            FilledTonalButton(
                onClick = { onSignIn(username) },
                enabled = enabled && valid,
                modifier = Modifier.align(Alignment.End).testTag("dev-sign-in-button"),
            ) { Text(stringResource(R.string.login_dev_button)) }
        }
    }
}
