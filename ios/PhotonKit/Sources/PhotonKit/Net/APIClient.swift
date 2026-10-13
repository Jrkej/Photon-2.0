import Foundation
import SwiftProtobuf

public let protobufContentType = "application/x-protobuf"

/// Every PHOTON HTTP call. Bodies are protobuf; errors become `PhotonError.api` with the
/// contract's `Error` fields. Calls made with the user's JWT refresh once on 401
/// (single-flight through `TokenManager`) and are retried once.
public final class APIClient: Sendable {
    public enum Host: Sendable { case api, image }
    public enum Auth: Sendable {
        case none
        /// The user's access JWT.
        case user
        /// An upload token from announce (image server only).
        case bearer(String)
    }

    public let config: PhotonConfig
    public let tokens: TokenManager
    let transport: HTTPTransport

    public init(config: PhotonConfig, transport: HTTPTransport, secureStore: SecureStore, clock: PhotonClock = SystemClock()) {
        self.config = config
        self.transport = transport
        self.tokens = TokenManager(store: secureStore, clock: clock) { [transport, config] refreshToken in
            var req = PbRefreshRequest()
            req.refreshToken = refreshToken
            let request = try APIClient.makeRequest(config: config, method: "POST", host: .api, path: "v1/auth/refresh",
                                                    query: [], body: .data(try req.serializedBytes()),
                                                    contentType: protobufContentType, bearer: nil)
            let response = try await transport.send(request)
            guard response.isSuccess else { throw PhotonError.api(APIError(response: response)) }
            return try PbTokenPair(serializedBytes: response.body)
        }
    }

    // MARK: - Core

    static func makeRequest(config: PhotonConfig, method: String, host: Host, path: String, query: [URLQueryItem],
                            body: HTTPBody, contentType: String?, bearer: String?,
                            extraHeaders: [String: String] = [:], timeout: TimeInterval = 30) throws -> HTTPRequest {
        let base = host == .api ? config.apiBase : config.imageBase
        guard var c = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw PhotonError.invalidResponse("bad URL for \(path)")
        }
        if !query.isEmpty { c.queryItems = query }
        guard let url = c.url else { throw PhotonError.invalidResponse("bad URL for \(path)") }
        var headers = extraHeaders
        headers["Accept"] = headers["Accept"] ?? protobufContentType
        headers["X-Request-Id"] = UUID().uuidString.lowercased()
        if let contentType, body != .none { headers["Content-Type"] = contentType }
        if let bearer { headers["Authorization"] = "Bearer \(bearer)" }
        return HTTPRequest(method: method, url: url, headers: headers, body: body, timeout: timeout,
                           preferHTTP3: host == .image)
    }

    /// Sends a request and returns a 2xx response, or throws `PhotonError.api`.
    public func send(_ method: String, _ host: Host, _ path: String, query: [URLQueryItem] = [],
                     body: HTTPBody = .none, contentType: String? = protobufContentType, auth: Auth,
                     extraHeaders: [String: String] = [:], timeout: TimeInterval = 30) async throws -> HTTPResponse {
        func build(_ bearer: String?) throws -> HTTPRequest {
            try Self.makeRequest(config: config, method: method, host: host, path: path, query: query, body: body,
                                 contentType: contentType, bearer: bearer, extraHeaders: extraHeaders, timeout: timeout)
        }
        switch auth {
        case .none:
            return try check(try await transport.send(try build(nil)))
        case .bearer(let token):
            return try check(try await transport.send(try build(token)))
        case .user:
            let token = try await tokens.validAccessToken()
            let first = try await transport.send(try build(token))
            guard first.status == 401 else { return try check(first) }
            let err = APIError(response: first)
            guard err.is(ErrorCode.tokenExpired) || err.is(ErrorCode.unauthenticated) || err.code.hasPrefix("HTTP_") else {
                throw PhotonError.api(err)
            }
            let fresh = try await tokens.refresh(rejected: token)
            return try check(try await transport.send(try build(fresh)))
        }
    }

    private func check(_ response: HTTPResponse) throws -> HTTPResponse {
        guard response.isSuccess else { throw PhotonError.api(APIError(response: response)) }
        return response
    }

    func call<Req: SwiftProtobuf.Message, Res: SwiftProtobuf.Message>(
        _ method: String, _ path: String, _ request: Req?, auth: Auth = .user, as: Res.Type
    ) async throws -> Res {
        let body: HTTPBody = try request.map { .data(try $0.serializedBytes()) } ?? .none
        let response = try await send(method, .api, path, body: body, auth: auth)
        do {
            return try Res(serializedBytes: response.body)
        } catch {
            throw PhotonError.invalidResponse("cannot decode \(Res.protoMessageName)")
        }
    }

    func callNoResult<Req: SwiftProtobuf.Message>(_ method: String, _ path: String, _ request: Req?) async throws {
        let body: HTTPBody = try request.map { .data(try $0.serializedBytes()) } ?? .none
        _ = try await send(method, .api, path, body: body, auth: .user)
    }

    // MARK: - Auth

    public func oauthStart(provider: String, redirectURI: String, codeChallenge: String) async throws -> PbOAuthStartResponse {
        var r = PbOAuthStartRequest()
        r.provider = provider
        r.redirectUri = redirectURI
        r.codeChallenge = codeChallenge
        return try await call("POST", "v1/auth/oauth/start", r, auth: .none, as: PbOAuthStartResponse.self)
    }

    public func oauthCallback(code: String, state: String, codeVerifier: String, redirectURI: String) async throws -> PbTokenPair {
        var r = PbOAuthCallbackRequest()
        r.code = code
        r.state = state
        r.codeVerifier = codeVerifier
        r.redirectUri = redirectURI
        return try await call("POST", "v1/auth/oauth/callback", r, auth: .none, as: PbTokenPair.self)
    }

    // MARK: - Devices

    /// `PUT /v1/devices/current` with platform "ios" and the APNs token (hex).
    public func registerDevice(pushToken: String) async throws {
        var r = PbDeviceRegistration()
        r.platform = "ios"
        r.pushToken = pushToken
        try await callNoResult("PUT", "v1/devices/current", r)
    }

    // MARK: - Sessions

    public func createSession(maxMembers: UInt32) async throws -> PbSessionInfo {
        var r = PbCreateSessionRequest()
        r.maxMembers = maxMembers
        return try await call("POST", "v1/sessions", r, as: PbSessionInfo.self)
    }

    public func join(_ by: PbJoinRequest.OneOf_By) async throws -> PbSessionInfo {
        var r = PbJoinRequest()
        r.by = by
        return try await call("POST", "v1/sessions/join", r, as: PbSessionInfo.self)
    }

    public func leave(sessionID: String) async throws {
        var r = PbSessionRef()
        r.sessionID = sessionID
        try await callNoResult("POST", "v1/sessions/leave", r)
    }

    public func endSession(sessionID: String) async throws {
        try await callNoResult("POST", "v1/sessions/\(sessionID)/end", Optional<PbSessionRef>.none)
    }

    public func kick(sessionID: String, memberID: String) async throws {
        var r = PbKickRequest()
        r.sessionID = sessionID
        r.memberID = memberID
        try await callNoResult("POST", "v1/sessions/\(sessionID)/kick", r)
    }

    /// New join code and invite link; the old ones stop working. Returns the new
    /// `SessionInfo` when the server sends one.
    public func rotateInvites(sessionID: String) async throws -> PbSessionInfo? {
        let response = try await send("POST", .api, "v1/sessions/\(sessionID)/invites/rotate", auth: .user)
        guard !response.body.isEmpty else { return nil }
        return try? PbSessionInfo(serializedBytes: response.body)
    }

    /// `GET /v1/sessions/current`: the session the server has this user in, or nil
    /// (404 SESSION_NOT_FOUND). Lets a client refused with ALREADY_IN_SESSION find it.
    public func currentSession() async throws -> PbSessionInfo? {
        do {
            return try await call("GET", "v1/sessions/current", Optional<PbSessionRef>.none, as: PbSessionInfo.self)
        } catch let error where error.apiError?.status == 404 {
            return nil
        }
    }

    /// `GET /v1/sessions/{sid}`: role, host, members and invite for a session.
    public func sessionInfo(sessionID: String) async throws -> PbSessionInfo {
        try await call("GET", "v1/sessions/\(sessionID)", Optional<PbSessionRef>.none, as: PbSessionInfo.self)
    }

    public func snapshot(sessionID: String) async throws -> PbSnapshot {
        try await call("GET", "v1/sessions/\(sessionID)/snapshot", Optional<PbSessionRef>.none, as: PbSnapshot.self)
    }

    public func events(sessionID: String, since: String, limit: Int) async throws -> PbEventBatch {
        let response = try await send("GET", .api, "v1/sessions/\(sessionID)/events",
                                      query: [URLQueryItem(name: "since", value: since),
                                              URLQueryItem(name: "limit", value: String(limit))],
                                      auth: .user)
        return try PbEventBatch(serializedBytes: response.body)
    }

    // MARK: - Photos

    public func announce(_ request: PbAnnounceRequest) async throws -> PbAnnounceResponse {
        try await call("POST", "v1/photos/announce", request, as: PbAnnounceResponse.self)
    }

    public func recall(sessionID: String, sha256Hex: String) async throws {
        try await callNoResult("POST", "v1/sessions/\(sessionID)/photos/\(sha256Hex)/recall", Optional<PbSessionRef>.none)
    }

    public func report(sessionID: String, sha256Hex: String, reason: String) async throws {
        var r = PbReportPhotoRequest()
        r.reason = String(reason.prefix(500))
        try await callNoResult("POST", "v1/sessions/\(sessionID)/photos/\(sha256Hex)/report", r)
    }

    // MARK: - Uploads (image server, upload token)

    public func uploadPath(_ uploadID: String) -> String { "v1/uploads/\(uploadID)" }

    public func uploadStatus(uploadID: String, token: String) async throws -> PbUploadStatus {
        let response = try await send("GET", .image, uploadPath(uploadID), auth: .bearer(token))
        return try PbUploadStatus(serializedBytes: response.body)
    }

    public enum CompleteOutcome: Sendable, Equatable {
        /// 200: verified, stored and accepted by the orchestrator.
        case stored(PbCompleteResponse)
        /// 202: still verifying or reporting; ask again after the delay.
        case pending(retryAfterSeconds: Double)
    }

    /// `POST /v1/uploads/{id}/complete`. 400 (missing chunks), 409, 410, 422 and 503 throw
    /// `PhotonError.api`.
    public func complete(uploadID: String, token: String, chunkCount: Int) async throws -> CompleteOutcome {
        var r = PbCompleteRequest()
        r.chunkCount = UInt32(chunkCount)
        let response = try await send("POST", .image, "\(uploadPath(uploadID))/complete",
                                      body: .data(try r.serializedBytes()), auth: .bearer(token), timeout: 60)
        if response.status == 202 {
            return .pending(retryAfterSeconds: response.retryAfterSeconds ?? 2)
        }
        let result = (try? PbCompleteResponse(serializedBytes: response.body)) ?? PbCompleteResponse()
        return .stored(result)
    }

    // MARK: - Downloads (image server, user JWT)

    public enum PhotoSize: String, Sendable { case thumb, preview, full }

    /// `GET /v1/sessions/{sid}/photos/{hash}?size=`. Returns the bytes and their media type.
    public func download(sessionID: String, sha256Hex: String, size: PhotoSize) async throws -> (data: Data, mime: String) {
        let response = try await send("GET", .image, "v1/sessions/\(sessionID)/photos/\(sha256Hex)",
                                      query: [URLQueryItem(name: "size", value: size.rawValue)],
                                      auth: .user, extraHeaders: ["Accept": "image/*"],
                                      timeout: size == .full ? 120 : 30)
        let mime = (response.header("Content-Type") ?? "application/octet-stream")
            .split(separator: ";").first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? "application/octet-stream"
        return (response.body, mime)
    }
}
