package com.photon.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.photon.app.auth.LoginManager
import com.photon.app.core.Backoff
import com.photon.app.core.Clock
import com.photon.app.core.MediaCache
import com.photon.app.core.NetworkMonitor
import com.photon.app.core.Notifications
import com.photon.app.data.db.PhotonDatabase
import com.photon.app.data.prefs.EncryptedTokenStore
import com.photon.app.data.prefs.SettingsStore
import com.photon.app.events.EventConnection
import com.photon.app.events.EventSync
import com.photon.app.net.HttpClients
import com.photon.app.net.ImageApi
import com.photon.app.net.ImageTransport
import com.photon.app.net.LocalUrls
import com.photon.app.net.PhotonApi
import com.photon.app.net.RetryPolicy
import com.photon.app.net.TokenRefresher
import com.photon.app.net.TokenStore
import com.photon.app.push.PushRegistrar
import com.photon.app.receive.GallerySaver
import com.photon.app.receive.PhotoFetcher
import com.photon.app.receive.ReceivePipeline
import com.photon.app.session.SessionManager
import com.photon.app.sync.MediaScanner
import com.photon.app.sync.SyncService
import com.photon.app.sync.WorkScheduler
import com.photon.app.upload.ThroughputEstimator
import com.photon.app.upload.UploadPreparer
import com.photon.app.upload.Uploader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

class PhotonApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.onAppStart()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.onForeground()
            override fun onStop(owner: LifecycleOwner) = graph.onBackground()
        })
    }
}

val Context.graph: AppGraph get() = (applicationContext as PhotonApp).graph

/** Hand-wired dependencies. Everything is lazy; cycles are broken with provider lambdas. */
class AppGraph(private val app: Context) {
    /** Long-lived background work. A failure is logged and the job ends; it never takes the process down. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("PHOTON", "background job failed", e) },
    )
    val clock: Clock = Clock.SYSTEM

    val apiBase = BuildConfig.API_BASE.toHttpUrl()
    val imageBase = BuildConfig.IMAGE_BASE.toHttpUrl()

    val db: PhotonDatabase by lazy { PhotonDatabase.create(app) }
    val tokens: TokenStore by lazy { EncryptedTokenStore(app) }
    val settings by lazy { SettingsStore(app) }
    val cache by lazy { MediaCache(app) }
    val network by lazy { NetworkMonitor(app) }
    val scheduler by lazy { WorkScheduler(app) }
    val retryPolicy by lazy { RetryPolicy(Backoff()) }

    val refresher: TokenRefresher by lazy {
        TokenRefresher(tokens, refreshCall = { api.refresh(it) }, onSignedOut = { onSignedOut() }, clock = clock)
    }
    val http by lazy { HttpClients { refresher } }
    val api by lazy { PhotonApi(apiBase, http.api, http.auth) }
    val imageTransport by lazy { ImageTransport(http.image) }
    val imageApi by lazy { ImageApi(imageBase, { imageTransport.client }, BuildConfig.REWRITE_LOCAL_URLS) }

    val sessions: SessionManager by lazy {
        SessionManager(app, db, api, tokens, scheduler, cache, { eventSync }, { connection }, { pipeline }, clock)
    }
    val pipeline: ReceivePipeline by lazy { ReceivePipeline(db, scheduler, cache, { sessions }, clock) }
    val eventSync: EventSync by lazy { EventSync(db, api, pipeline) { sessions } }
    val connection: EventConnection by lazy {
        EventConnection(
            scope = appScope,
            db = db,
            client = http.webSocket,
            wsUrl = webSocketUrl(),
            eventSync = eventSync,
            pipeline = pipeline,
            refresher = refresher,
            tokens = tokens,
            sessions = { sessions },
            network = network,
            onLoginRequired = { onSignedOut() },
        )
    }
    val scanner by lazy { MediaScanner(app, db, clock) }
    val uploader by lazy {
        Uploader(
            context = app,
            db = db,
            api = api,
            imageApi = imageApi,
            settings = settings,
            preparer = UploadPreparer(app, cache),
            cache = cache,
            throughput = ThroughputEstimator(),
            sessions = { sessions },
            retry = retryPolicy,
            clock = clock,
        )
    }
    val fetcher by lazy { PhotoFetcher(db, imageApi, cache, GallerySaver(app), clock) }
    val login by lazy {
        LoginManager(api, tokens, BuildConfig.OAUTH_PROVIDER, BuildConfig.OAUTH_REDIRECT_URI) { url ->
            if (BuildConfig.REWRITE_LOCAL_URLS) LocalUrls.rebaseLocalhost(url, apiBase) else url
        }
    }
    val push by lazy { PushRegistrar(app, api, tokens) }

    /** `{API_BASE with ws/wss}/v1/ws`. */
    fun webSocketUrl(): String {
        val url = apiBase.newBuilder().addPathSegments("v1/ws").build().toString()
        return when {
            url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
            else -> "ws://" + url.removePrefix("http://")
        }
    }

    /** App start (7.6, 8.5): purge, catch up, reconcile, and try Cronet for the image client. */
    fun onAppStart() {
        appScope.launch {
            imageTransport.tryEnableCronet(app, imageBase)
        }
        appScope.launch {
            scheduler.enqueuePurgeNow()
            if (db.sessions().get() != null && tokens.signedIn.value) {
                scheduler.enqueueCatchUp()
                scheduler.enqueueReconcile()
                scheduler.enqueueUploads()
            }
        }
    }

    @Volatile
    private var inForeground = false

    fun onForeground() {
        inForeground = true
        appScope.launch {
            if (inForeground && db.sessions().get() != null && tokens.signedIn.value) {
                // Foreground: starting the service is allowed now even if it was refused before.
                SyncService.start(app)
                connection.acquire(FOREGROUND)
            }
        }
    }

    fun onBackground() {
        inForeground = false
        connection.release(FOREGROUND)
    }

    private fun onSignedOut() {
        connection.stopAll()
        SyncService.stop(app)
    }

    private companion object {
        const val FOREGROUND = "foreground"
    }
}
