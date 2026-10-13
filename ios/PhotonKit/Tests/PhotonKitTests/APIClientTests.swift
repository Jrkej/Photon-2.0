import Foundation
import XCTest
@testable import PhotonKit

func tokenPair(_ access: String, _ refresh: String, expires: Int64 = 0) -> PbTokenPair {
    var p = PbTokenPair()
    p.accessToken = access
    p.refreshToken = refresh
    p.accessExpiresAtMs = expires
    p.userID = "me"
    return p
}

final class APIClientTests: XCTestCase {
    func testRequestShapeAndBaseURLs() async throws {
        let secure = MemorySecureStore()
        secure.seedTokens(access: "jwt")
        let transport = FakeTransport { request in
            if request.url.path == "/v1/sessions" {
                var info = PbSessionInfo()
                info.sessionID = "sid1"
                return pbResponse(info)
            }
            return HTTPResponse(status: 200, headers: ["Content-Type": "image/webp"], body: Data([1, 2, 3]))
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        let info = try await api.createSession(maxMembers: 4)
        XCTAssertEqual(info.sessionID, "sid1")
        let (data, mime) = try await api.download(sessionID: "sid1", sha256Hex: "ab", size: .thumb)
        XCTAssertEqual(data, Data([1, 2, 3]))
        XCTAssertEqual(mime, "image/webp")

        let create = transport.requests[0]
        XCTAssertEqual(create.method, "POST")
        XCTAssertEqual(create.url.absoluteString, "https://api.test/v1/sessions")
        XCTAssertEqual(create.headers["Content-Type"], "application/x-protobuf")
        XCTAssertEqual(create.headers["Authorization"], "Bearer jwt")
        XCTAssertNotNil(create.headers["X-Request-Id"])
        XCTAssertEqual(body(create, as: PbCreateSessionRequest.self).maxMembers, 4)
        XCTAssertFalse(create.preferHTTP3)

        let download = transport.requests[1]
        XCTAssertEqual(download.url.absoluteString, "https://img.test/v1/sessions/sid1/photos/ab?size=thumb")
        XCTAssertEqual(download.headers["Authorization"], "Bearer jwt")
        XCTAssertTrue(download.preferHTTP3)
    }

    func testErrorContractParsing() async throws {
        let secure = MemorySecureStore()
        secure.seedTokens()
        let transport = FakeTransport { request in
            switch request.url.path {
            case "/v1/photos/announce": return errorResponse(429, ErrorCode.quotaExceeded, retryable: true, retryAfter: 60)
            case "/v1/sessions/join": return errorResponse(404, ErrorCode.sessionNotFound)
            default: return HTTPResponse(status: 502, headers: ["Content-Type": "text/html", "Retry-After": "3"], body: Data("<html>".utf8))
            }
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        do {
            _ = try await api.announce(PbAnnounceRequest())
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error.apiError, APIError(status: 429, code: "QUOTA_EXCEEDED", retryable: true, retryAfterSeconds: 60))
            XCTAssertTrue(error.isTransient)
        }
        do {
            _ = try await api.join(.code("ABCD2345"))
            XCTFail("expected error")
        } catch {
            XCTAssertEqual(error.apiError?.code, "SESSION_NOT_FOUND")
            XCTAssertFalse(error.isTransient)
        }
        do {
            _ = try await api.snapshot(sessionID: "s")
            XCTFail("expected error")
        } catch {
            // A proxy page instead of an Error message: still classified, retryable for 5xx.
            XCTAssertEqual(error.apiError?.code, "HTTP_502")
            XCTAssertEqual(error.apiError?.retryAfterSeconds, 3)
            XCTAssertTrue(error.isTransient)
        }
    }

    func testCompleteOutcomes() async throws {
        let secure = MemorySecureStore()
        let transport = FakeTransport { request in
            if request.headers["Authorization"] == "Bearer pending" {
                return HTTPResponse(status: 202, headers: ["Retry-After": "2"])
            }
            if request.headers["Authorization"] == "Bearer missing" {
                return errorResponse(400, ErrorCode.invalidRequest, missing: [2, 5])
            }
            var r = PbCompleteResponse()
            r.state = "STORED"
            return pbResponse(r)
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        let pending = try await api.complete(uploadID: "u1", token: "pending", chunkCount: 3)
        XCTAssertEqual(pending, .pending(retryAfterSeconds: 2))
        let stored = try await api.complete(uploadID: "u1", token: "ok", chunkCount: 3)
        guard case .stored(let r) = stored else { return XCTFail() }
        XCTAssertEqual(r.state, "STORED")
        do {
            _ = try await api.complete(uploadID: "u1", token: "missing", chunkCount: 6)
            XCTFail("expected 400")
        } catch {
            XCTAssertEqual(error.apiError?.missingChunks, [2, 5])
        }
        XCTAssertEqual(transport.requests[0].url.absoluteString, "https://img.test/v1/uploads/u1/complete")
        XCTAssertEqual(body(transport.requests[0], as: PbCompleteRequest.self).chunkCount, 3)
    }

    /// Ten requests hit 401 TOKEN_EXPIRED at once: exactly one refresh, and every request is
    /// retried once with the new token.
    func testSingleFlightRefresh() async throws {
        let secure = MemorySecureStore()
        secure.seedTokens(access: "old", refresh: "r1")
        let refreshes = Counter()
        let transport = FakeTransport { request in
            if request.url.path == "/v1/auth/refresh" {
                refreshes.increment()
                XCTAssertEqual(body(request, as: PbRefreshRequest.self).refreshToken, "r1")
                XCTAssertNil(request.headers["Authorization"])
                try await Task.sleep(nanoseconds: 50_000_000)   // keep the refresh in flight
                return pbResponse(tokenPair("new", "r2"))
            }
            if request.headers["Authorization"] == "Bearer old" {
                return errorResponse(401, ErrorCode.tokenExpired)
            }
            return pbResponse(PbSnapshot())
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 0..<10 { group.addTask { _ = try await api.snapshot(sessionID: "s") } }
            try await group.waitForAll()
        }
        XCTAssertEqual(refreshes.value, 1)
        XCTAssertEqual(transport.requests.filter { $0.headers["Authorization"] == "Bearer new" }.count, 10)
        // The rotated pair is persisted in the Keychain.
        let stored = try JSONDecoder().decode(TokenManager.Stored.self, from: try secure.data(for: TokenManager.storeKey)!)
        XCTAssertEqual(stored.refresh, "r2")
    }

    func testRefusedRefreshRequiresLogin() async throws {
        let secure = MemorySecureStore()
        secure.seedTokens(access: "old", refresh: "revoked")
        let transport = FakeTransport { request in
            if request.url.path == "/v1/auth/refresh" { return errorResponse(401, ErrorCode.unauthenticated) }
            return errorResponse(401, ErrorCode.tokenExpired)
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        do {
            _ = try await api.snapshot(sessionID: "s")
            XCTFail("expected loginRequired")
        } catch {
            XCTAssertEqual(error as? PhotonError, .loginRequired)
        }
        XCTAssertNil(try secure.data(for: TokenManager.storeKey))
        let loggedIn = await api.tokens.isLoggedIn
        XCTAssertFalse(loggedIn)
    }

    func testOtherAuthErrorsAreNotRefreshed() async throws {
        let secure = MemorySecureStore()
        secure.seedTokens()
        let transport = FakeTransport { _ in errorResponse(403, ErrorCode.notAMember) }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        do { _ = try await api.snapshot(sessionID: "s") } catch {
            XCTAssertEqual(error.apiError?.code, ErrorCode.notAMember)
        }
        XCTAssertEqual(transport.requests.count, 1)
    }

    func testProactiveRefreshBeforeExpiry() async throws {
        let clock = TestClock()
        let secure = MemorySecureStore()
        secure.seedTokens(access: "old", refresh: "r1", expiresAtMs: clock.nowMs() + 10_000)
        let transport = FakeTransport { request in
            if request.url.path == "/v1/auth/refresh" {
                return pbResponse(tokenPair("new", "r2", expires: clock.nowMs() + 900_000))
            }
            return pbResponse(PbSnapshot())
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: clock)
        _ = try await api.snapshot(sessionID: "s")
        XCTAssertEqual(transport.requests.map(\.url.path), ["/v1/auth/refresh", "/v1/sessions/s/snapshot"])
        XCTAssertEqual(transport.requests[1].headers["Authorization"], "Bearer new")
    }
}

final class AuthFlowTests: XCTestCase {
    func testLoginWithPKCE() async throws {
        let secure = MemorySecureStore()
        let transport = FakeTransport { request in
            switch request.url.path {
            case "/v1/auth/oauth/start":
                let start = body(request, as: PbOAuthStartRequest.self)
                XCTAssertEqual(start.provider, "dev")
                XCTAssertEqual(start.redirectUri, "photon://auth/cb")
                XCTAssertEqual(start.codeChallenge.count, 43)
                var r = PbOAuthStartResponse()
                r.authorizationURL = "https://api.test/dev/authorize?state=st1"
                r.state = "st1"
                return pbResponse(r)
            case "/v1/auth/oauth/callback":
                var p = PbTokenPair()
                p.accessToken = "a"
                p.refreshToken = "r"
                p.userID = "u42"
                return pbResponse(p)
            default:
                return errorResponse(404, "NOT_ROUTED")
            }
        }
        let authenticator = FakeAuthenticator { _ in
            // The verifier is held in the Keychain while the browser is open.
            XCTAssertNotNil(try secure.data(for: AuthFlow.pendingKey))
            return URL(string: "photon://auth/cb?code=c1&state=st1")!
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        let flow = AuthFlow(api: api, secureStore: secure, authenticator: authenticator)
        let user = try await flow.login()
        XCTAssertEqual(user, "u42")

        XCTAssertEqual(authenticator.seen.first?.0.absoluteString, "https://api.test/dev/authorize?state=st1")
        XCTAssertEqual(authenticator.seen.first?.1, .customScheme("photon"))
        let start = body(transport.requests[0], as: PbOAuthStartRequest.self)
        let callback = body(transport.requests[1], as: PbOAuthCallbackRequest.self)
        XCTAssertEqual(callback.code, "c1")
        XCTAssertEqual(callback.state, "st1")
        XCTAssertEqual(callback.redirectUri, "photon://auth/cb")
        XCTAssertEqual(PKCE.challenge(for: callback.codeVerifier), start.codeChallenge)
        XCTAssertNil(try secure.data(for: AuthFlow.pendingKey), "verifier discarded after use")
        let loggedIn = await api.tokens.isLoggedIn
        XCTAssertTrue(loggedIn)
    }

    func testStateMismatchAndDenialFail() async throws {
        let secure = MemorySecureStore()
        let transport = FakeTransport { request in
            var r = PbOAuthStartResponse()
            r.authorizationURL = "https://api.test/authorize"
            r.state = "expected"
            return pbResponse(r)
        }
        let api = APIClient(config: testConfig, transport: transport, secureStore: secure, clock: TestClock())
        let forged = AuthFlow(api: api, secureStore: secure, authenticator: FakeAuthenticator { _ in
            URL(string: "photon://auth/cb?code=c&state=forged")!
        })
        do { _ = try await forged.login(); XCTFail() } catch {
            XCTAssertEqual(error as? PhotonError, .loginFailed("state mismatch"))
        }
        let denied = AuthFlow(api: api, secureStore: secure, authenticator: FakeAuthenticator { _ in
            URL(string: "photon://auth/cb?error=access_denied&state=expected")!
        })
        do { _ = try await denied.login(); XCTFail() } catch {
            XCTAssertEqual(error as? PhotonError, .loginCancelled)
        }
        XCTAssertFalse(transport.requests.contains { $0.url.path == "/v1/auth/oauth/callback" })
        XCTAssertNil(try secure.data(for: AuthFlow.pendingKey))
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    func increment() { lock.withLockValue { n += 1 } }
    var value: Int { lock.withLockValue { n } }
}
