package com.photon.app.net

import com.photon.proto.AnnounceRequest
import com.photon.proto.AuthConfig
import com.photon.proto.DevLoginRequest
import com.photon.proto.GoogleSignInRequest
import com.photon.proto.NonceResponse
import com.photon.proto.AnnounceResponse
import com.photon.proto.CreateSessionRequest
import com.photon.proto.DeviceRegistration
import com.photon.proto.EventBatch
import com.photon.proto.JoinRequest
import com.photon.proto.KeyState
import com.photon.proto.KeyUpload
import com.photon.proto.KeyUploadResult
import com.photon.proto.KickRequest
import com.photon.proto.OAuthCallbackRequest
import com.photon.proto.OAuthStartRequest
import com.photon.proto.OAuthStartResponse
import com.photon.proto.RefreshRequest
import com.photon.proto.ReportPhotoRequest
import com.photon.proto.SessionInfo
import com.photon.proto.SessionRef
import com.photon.proto.Snapshot
import com.photon.proto.TokenPair
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

/**
 * The orchestrator and Auth (API_BASE). [client] carries the access token and refreshes it;
 * [authClient] is used for the auth endpoints themselves and has its own dispatcher, so a
 * refresh can never queue behind the requests that are waiting for it.
 */
class PhotonApi(
    private val base: HttpUrl,
    private val client: OkHttpClient,
    private val authClient: OkHttpClient,
) {
    // ---- auth

    suspend fun oauthStart(req: OAuthStartRequest): OAuthStartResponse =
        authClient.callProto(post("v1/auth/oauth/start", req.toProtoBody()), OAuthStartResponse.ADAPTER)

    suspend fun oauthCallback(req: OAuthCallbackRequest): TokenPair =
        authClient.callProto(post("v1/auth/oauth/callback", req.toProtoBody()), TokenPair.ADAPTER)

    suspend fun refresh(refreshToken: String): TokenPair =
        authClient.callProto(post("v1/auth/refresh", RefreshRequest(refresh_token = refreshToken).toProtoBody()), TokenPair.ADAPTER)

    /** `GET /v1/auth/config`: which sign-in methods the server offers. */
    suspend fun authConfig(): AuthConfig =
        authClient.callProto(get(url("v1/auth/config")), AuthConfig.ADAPTER)

    /** `POST /v1/auth/nonce`: single use, 10 minutes; the Google ID token must carry it. */
    suspend fun authNonce(): NonceResponse =
        authClient.callProto(post("v1/auth/nonce", emptyProtoBody()), NonceResponse.ADAPTER)

    suspend fun googleSignIn(req: GoogleSignInRequest): TokenPair =
        authClient.callProto(post("v1/auth/google", req.toProtoBody()), TokenPair.ADAPTER)

    /** Development servers only (404 elsewhere). */
    suspend fun devLogin(req: DevLoginRequest): TokenPair =
        authClient.callProto(post("v1/auth/dev/login", req.toProtoBody()), TokenPair.ADAPTER)

    suspend fun registerDevice(reg: DeviceRegistration) {
        client.callUnit(Request.Builder().url(url("v1/devices/current")).put(reg.toProtoBody()).build())
    }

    // ---- sessions

    suspend fun createSession(req: CreateSessionRequest): SessionInfo =
        client.callProto(post("v1/sessions", req.toProtoBody()), SessionInfo.ADAPTER)

    suspend fun join(req: JoinRequest): SessionInfo =
        client.callProto(post("v1/sessions/join", req.toProtoBody()), SessionInfo.ADAPTER)

    suspend fun leave(sessionId: String) {
        client.callUnit(post("v1/sessions/leave", SessionRef(session_id = sessionId).toProtoBody()))
    }

    suspend fun end(sessionId: String) {
        client.callUnit(post(url("v1/sessions", sessionId, "end"), emptyProtoBody()))
    }

    suspend fun kick(sessionId: String, memberId: String) {
        val body = KickRequest(session_id = sessionId, member_id = memberId).toProtoBody()
        client.callUnit(post(url("v1/sessions", sessionId, "kick"), body))
    }

    suspend fun rotateInvites(sessionId: String): SessionInfo =
        client.callProto(post(url("v1/sessions", sessionId, "invites", "rotate"), emptyProtoBody()), SessionInfo.ADAPTER)

    /**
     * `GET /v1/sessions/current`: the caller's live session on the server, or null when there is
     * none (`404 SESSION_NOT_FOUND`).
     */
    suspend fun currentSession(): SessionInfo? = try {
        client.callProto(get(url("v1/sessions/current")), SessionInfo.ADAPTER)
    } catch (e: ApiException) {
        if (e.isCode(ErrorCodes.SESSION_NOT_FOUND)) null else throw e
    }

    suspend fun snapshot(sessionId: String): Snapshot =
        client.callProto(get(url("v1/sessions", sessionId, "snapshot")), Snapshot.ADAPTER)

    suspend fun events(sessionId: String, since: String, limit: Int = EVENTS_PAGE): EventBatch {
        val u = url("v1/sessions", sessionId, "events").newBuilder()
            .addQueryParameter("since", since)
            .addQueryParameter("limit", limit.toString())
            .build()
        return client.callProto(get(u), EventBatch.ADAPTER)
    }

    // ---- end-to-end keys (e2ee.md 5)

    /** `GET /v1/sessions/{sid}/keys`: the current epoch, every member's public key, and this user's sealed keys. */
    suspend fun keys(sessionId: String): KeyState =
        client.callProto(get(url("v1/sessions", sessionId, "keys")), KeyState.ADAPTER)

    /** `POST /v1/sessions/{sid}/keys`: sealed keys for members who lack an epoch, or a new epoch (rotate). */
    suspend fun uploadKeys(sessionId: String, upload: KeyUpload): KeyUploadResult =
        client.callProto(post(url("v1/sessions", sessionId, "keys"), upload.toProtoBody()), KeyUploadResult.ADAPTER)

    // ---- photos

    suspend fun announce(req: AnnounceRequest): AnnounceResponse =
        client.callProto(post("v1/photos/announce", req.toProtoBody()), AnnounceResponse.ADAPTER)

    suspend fun recall(sessionId: String, sha256Hex: String) {
        client.callUnit(post(url("v1/sessions", sessionId, "photos", sha256Hex, "recall"), emptyProtoBody()))
    }

    suspend fun report(sessionId: String, sha256Hex: String, reason: String) {
        val body = ReportPhotoRequest(reason = reason.take(500)).toProtoBody()
        client.callUnit(post(url("v1/sessions", sessionId, "photos", sha256Hex, "report"), body))
    }

    // ---- helpers

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun url(prefix: String, vararg segments: String): HttpUrl {
        val b = base.newBuilder().addPathSegments(prefix)
        segments.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private fun post(path: String, body: RequestBody) = post(url(path), body)

    private fun post(url: HttpUrl, body: RequestBody): Request =
        Request.Builder().url(url).post(body).header("Accept", PROTOBUF.toString()).build()

    private fun get(url: HttpUrl): Request =
        Request.Builder().url(url).get().header("Accept", PROTOBUF.toString()).build()

    companion object {
        const val EVENTS_PAGE = 500
    }
}
