package com.photon.app.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photon.app.BuildConfig
import com.photon.app.R
import com.photon.app.auth.AuthRules
import com.photon.app.auth.GoogleTokenResult
import com.photon.app.auth.BrowserLoginTracker
import com.photon.app.auth.OAuthRedirect
import com.photon.app.auth.SignInMethod
import com.photon.app.auth.SignInOutcome
import com.photon.app.auth.SignInProblem
import com.photon.app.data.db.PendingPhoto
import com.photon.app.data.db.ReceivedPhoto
import com.photon.app.data.db.SessionState
import com.photon.app.events.ConnectionState
import com.photon.app.graph
import com.photon.app.net.ApiException
import com.photon.app.net.ErrorCodes
import com.photon.app.session.EndReason
import com.photon.app.session.InviteLinks
import com.photon.app.session.JoinConflict
import com.photon.app.session.JoinTarget
import com.photon.app.session.SessionRules
import com.photon.proto.AuthConfig
import com.photon.proto.SessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface Consent {
    data object Create : Consent
    data class Join(val target: JoinTarget) : Consent
}

/**
 * The server has this user in [info] already. [retry] is the create or join that was refused
 * with ALREADY_IN_SESSION, or null when found at start-up (local state lost).
 */
data class SessionConflict(val info: SessionInfo, val retry: Consent?)

/** The sign-in screen. [config] says which methods the server offers. */
data class LoginState(
    val config: AuthConfig? = null,
    val loadingConfig: Boolean = false,
    val configFailed: Boolean = false,
    val signingIn: Boolean = false,
    val problem: SignInProblem? = null,
    /** The method behind [problem], so "Try again" repeats it. */
    val lastMethod: SignInMethod? = null,
)

data class UiState(
    val busy: Boolean = false,
    val consent: Consent? = null,
    val conflict: SessionConflict? = null,
    /** An invite opened while signed out, kept until login finishes. */
    val pendingInvite: JoinTarget? = null,
)

private const val GITHUB = "github"

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val app = application

    private val state = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = state.asStateFlow()

    private val loginFlow = MutableStateFlow(LoginState())
    private val browserLogin = BrowserLoginTracker()
    val login: StateFlow<LoginState> = loginFlow.asStateFlow()

    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    val signedIn: StateFlow<Boolean> = graph.tokens.signedIn
    val selfUserId: String? get() = graph.tokens.userId

    val session: StateFlow<SessionState?> = graph.sessions.session
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val members: StateFlow<List<String>> = session
        .flatMapLatest { s -> s?.let { graph.db.sessions().observeMembers(it.sessionId) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val received: StateFlow<List<ReceivedPhoto>> = session
        .flatMapLatest { s -> s?.let { graph.db.received().observeVisible(it.sessionId) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mine: StateFlow<List<PendingPhoto>> = session
        .flatMapLatest { s -> s?.let { graph.db.pending().observeForSession(it.sessionId) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connection: StateFlow<ConnectionState> = graph.connection.state

    data class Settings(val stripGps: Boolean = true, val originalsWifiOrCharging: Boolean = false)

    val settings: StateFlow<Settings> = combine(graph.settings.stripGps, graph.settings.originalsWifiOrCharging) { a, b ->
        Settings(a, b)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    init {
        viewModelScope.launch {
            signedIn.collect { if (!it && loginFlow.value.config == null) loadAuthConfig() }
        }
        viewModelScope.launch {
            var previous: SessionState? = null
            graph.sessions.session.collect { current ->
                if (SessionRules.becameHost(previous, current)) messageChannel.trySend(app.getString(R.string.now_host_message))
                previous = current
            }
        }
        viewModelScope.launch {
            if (signedIn.value && graph.sessions.current() == null) checkServerSession()
        }
        viewModelScope.launch {
            graph.sessions.ended.collect { reason ->
                val text = when (reason) {
                    EndReason.ENDED -> R.string.session_ended_message
                    EndReason.KICKED -> R.string.session_kicked_message
                    EndReason.LEFT -> R.string.session_left_message
                    EndReason.SIGNED_OUT -> null
                }
                text?.let { messageChannel.trySend(app.getString(it)) }
            }
        }
    }

    // ---- links

    /** A deep link: the OAuth redirect or an invite. */
    fun onLink(uri: Uri) {
        if (graph.browserOAuth.isRedirect(uri.toString())) {
            finishBrowserLogin(uri)
            return
        }
        val target = InviteLinks.parse(uri.toString(), BuildConfig.APP_LINK_HOST) ?: return
        if (!signedIn.value) {
            state.update { it.copy(pendingInvite = target) }
        } else {
            requestJoin(target)
        }
    }

    // ---- login

    // ---- sign-in (Credential Manager, or a username on dev servers)

    fun loadAuthConfig() {
        if (loginFlow.value.loadingConfig) return
        loginFlow.update { it.copy(loadingConfig = true, configFailed = false) }
        viewModelScope.launch {
            try {
                val config = graph.auth.config()
                loginFlow.update { it.copy(config = config, loadingConfig = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginFlow.update { it.copy(loadingConfig = false, configFailed = true) }
            }
        }
    }

    /** [idToken] runs the account picker; it needs the activity, so the screen provides it. */
    fun signInWithGoogle(idToken: suspend (clientId: String, nonce: String) -> GoogleTokenResult) {
        val clientId = AuthRules.googleClientId(loginFlow.value.config) ?: return
        runSignIn(SignInMethod.GOOGLE) { graph.auth.signInWithGoogle(clientId, idToken) }
    }

    fun devSignIn(username: String) = runSignIn(SignInMethod.DEV) { graph.auth.devLogin(username) }

    /**
     * GitHub, through the browser: [openBrowser] shows the provider page in a Custom Tab. The
     * login finishes in [onLink] when the redirect comes back, possibly in a new process.
     */
    fun signInWithGitHub(openBrowser: (Uri) -> Unit) {
        if (loginFlow.value.signingIn) return
        loginFlow.update { it.copy(signingIn = true, problem = null, lastMethod = SignInMethod.GITHUB) }
        viewModelScope.launch {
            try {
                val url = graph.browserOAuth.start(GITHUB)
                browserLogin.launched()
                openBrowser(Uri.parse(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                browserLogin.redirectArrived()
                loginFlow.update { it.copy(signingIn = false, problem = SignInProblem.NO_BROWSER) }
            } catch (e: Exception) {
                val problem = if (e is ApiException || e is IOException) AuthRules.problemFor(e, SignInMethod.GITHUB) else SignInProblem.GITHUB_FAILED
                loginFlow.update { it.copy(signingIn = false, problem = problem) }
            }
        }
    }

    /** The sign-in screen resumed. Without a redirect, the user closed the browser: stop loading. */
    fun onLoginScreenResumed() {
        if (browserLogin.resumedWithoutRedirect()) loginFlow.update { it.copy(signingIn = false) }
    }

    /** Redirects being finished; the server's return page can deliver the same one twice. */
    private var redirectsInFlight = 0

    private fun finishBrowserLogin(uri: Uri) {
        browserLogin.redirectArrived()
        redirectsInFlight++
        loginFlow.update { it.copy(signingIn = true, lastMethod = SignInMethod.GITHUB) }
        viewModelScope.launch {
            try {
                when (val outcome = graph.browserOAuth.finish(OAuthRedirect.parse(uri.toString()))) {
                    SignInOutcome.SignedIn -> afterSignIn()
                    SignInOutcome.Cancelled, SignInOutcome.Ignored -> Unit
                    is SignInOutcome.Failed -> loginFlow.update { it.copy(problem = outcome.problem) }
                }
            } finally {
                if (--redirectsInFlight == 0) loginFlow.update { it.copy(signingIn = false) }
            }
        }
    }

    fun dismissSignInProblem() = loginFlow.update { it.copy(problem = null) }

    private fun runSignIn(method: SignInMethod, block: suspend () -> SignInOutcome) {
        if (loginFlow.value.signingIn) return
        loginFlow.update { it.copy(signingIn = true, problem = null, lastMethod = method) }
        viewModelScope.launch {
            try {
                when (val outcome = block()) {
                    SignInOutcome.SignedIn -> afterSignIn()
                    SignInOutcome.Cancelled, SignInOutcome.Ignored -> Unit
                    is SignInOutcome.Failed -> loginFlow.update { it.copy(problem = outcome.problem) }
                }
            } finally {
                loginFlow.update { it.copy(signingIn = false) }
            }
        }
    }

    private suspend fun afterSignIn() {
        graph.sessions.onSignedIn()
        if (graph.sessions.current() == null) checkServerSession()
        state.value.pendingInvite?.let { target ->
            state.update { it.copy(pendingInvite = null) }
            requestJoin(target)
        }
    }

    fun signOut() = launchBusy { graph.sessions.signOut() }

    // ---- create / join

    fun requestCreate() = state.update { it.copy(consent = Consent.Create) }

    fun requestJoinByText(text: String) {
        val target = InviteLinks.parse(text, BuildConfig.APP_LINK_HOST)
        if (target == null) {
            messageChannel.trySend(app.getString(R.string.invalid_invite))
        } else {
            requestJoin(target)
        }
    }

    fun requestJoin(target: JoinTarget) = state.update { it.copy(consent = Consent.Join(target)) }

    fun dismissConsent() = state.update { it.copy(consent = null) }

    /** Consent given: only now is the join (or create) call made (7.4). */
    fun confirmConsent() {
        val consent = state.value.consent ?: return
        state.update { it.copy(consent = null) }
        launchBusy { runConsented(consent) }
    }

    /** Create or join; on ALREADY_IN_SESSION, show which session the user is in. */
    private suspend fun runConsented(consent: Consent) {
        val attempt = JoinConflict.attempt(
            action = {
                when (consent) {
                    Consent.Create -> graph.sessions.create()
                    is Consent.Join -> graph.sessions.join(consent.target)
                }
            },
            current = { graph.sessions.serverSession() },
        )
        if (attempt is JoinConflict.Attempt.InOther) {
            state.update { it.copy(conflict = SessionConflict(attempt.info, consent)) }
        }
    }

    /** Start-up and after login: the server may know a session this device lost. */
    private suspend fun checkServerSession() {
        val info = try {
            graph.sessions.serverSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // offline or signed out: nothing to offer now
        } ?: return
        if (graph.sessions.current() == null) state.update { it.copy(conflict = SessionConflict(info, retry = null)) }
    }

    /** Open the session the server has the user in, on this device. */
    fun openConflictSession() {
        val conflict = state.value.conflict ?: return
        state.update { it.copy(conflict = null) }
        launchBusy { graph.sessions.open(conflict.info) }
    }

    /** Leave the other session, then retry the refused create or join. */
    fun leaveConflictSession() {
        val conflict = state.value.conflict ?: return
        state.update { it.copy(conflict = null) }
        launchBusy {
            graph.sessions.leaveRemote(conflict.info.session_id)
            conflict.retry?.let { runConsented(it) }
        }
    }

    fun dismissConflict() = state.update { it.copy(conflict = null) }

    // ---- in a session

    fun setPaused(paused: Boolean) = launchBusy { graph.sessions.setPaused(paused) }
    fun leave() = launchBusy { graph.sessions.leave() }
    fun endForEveryone() = launchBusy { graph.sessions.endForEveryone() }
    fun kick(memberId: String) = launchBusy { graph.sessions.kick(memberId) }
    fun rotateInvites() = launchBusy { graph.sessions.rotateInvites() }
    fun recall(sha256: String) = launchBusy { graph.sessions.recall(sha256) }

    fun report(sha256: String, reason: String) = launchBusy {
        graph.sessions.report(sha256, reason)
        messageChannel.trySend(app.getString(R.string.report_sent))
    }

    /** Gallery access was granted (or widened): look for photos the observer couldn't see. */
    fun onMediaAccessChanged() {
        if (session.value != null) graph.scheduler.enqueueReconcile()
    }

    fun setStripGps(value: Boolean) = viewModelScope.launch { graph.settings.setStripGps(value) }

    fun setOriginalsWifiOrCharging(value: Boolean) = viewModelScope.launch { graph.settings.setOriginalsWifiOrCharging(value) }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            state.update { it.copy(busy = true) }
            try {
                block()
            } catch (e: ApiException) {
                messageChannel.trySend(describe(e))
            } catch (e: IOException) {
                messageChannel.trySend(app.getString(R.string.error_generic, e.message ?: "network error"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No browser for Custom Tabs, a session that ended mid-join, and the like.
                messageChannel.trySend(app.getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    private fun describe(e: ApiException): String = when (e.code) {
        ErrorCodes.SESSION_NOT_FOUND -> "No session with that code or invite. It may have expired."
        ErrorCodes.ALREADY_IN_SESSION -> "You're already in another session. Leave it first."
        ErrorCodes.SESSION_ENDED -> app.getString(R.string.session_ended_message)
        ErrorCodes.RATE_LIMITED -> "Too many attempts. Try again in a minute."
        ErrorCodes.FORBIDDEN -> e.serverMessage.ifEmpty { "Not allowed." }
        else -> app.getString(R.string.error_generic, e.serverMessage.ifEmpty { e.code })
    }
}
