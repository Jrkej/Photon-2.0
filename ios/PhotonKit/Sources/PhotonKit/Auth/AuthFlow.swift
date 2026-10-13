import Foundation

/// Backend-mediated OAuth with PKCE (S256):
/// 1. a fresh verifier, kept in the Keychain for the duration of the attempt;
/// 2. `POST /v1/auth/oauth/start` with the challenge, returning the provider URL and `state`;
/// 3. the system browser (ASWebAuthenticationSession), back on the registered redirect;
/// 4. `POST /v1/auth/oauth/callback` with code, state, verifier and redirect URI;
/// 5. the token pair into the Keychain, the verifier deleted.
public final class AuthFlow: Sendable {
    struct PendingLogin: Codable, Equatable {
        var verifier: String
        var state: String
        var redirectURI: String
    }

    static let pendingKey = "photon.pending-login"

    private let api: APIClient
    private let secureStore: SecureStore
    private let authenticator: WebAuthenticator
    private let log = PhotonLog("auth")

    public init(api: APIClient, secureStore: SecureStore, authenticator: WebAuthenticator) {
        self.api = api
        self.secureStore = secureStore
        self.authenticator = authenticator
    }

    /// Runs the whole login. Returns the user id.
    public func login() async throws -> String {
        let config = api.config
        let pkce = PKCE.generate()
        let start = try await api.oauthStart(provider: config.authProvider, redirectURI: config.redirectURI,
                                             codeChallenge: pkce.challenge)
        guard let url = URL(string: start.authorizationURL), url.scheme == "https" || url.scheme == "http" else {
            throw PhotonError.invalidResponse("authorization_url")
        }
        let pending = PendingLogin(verifier: pkce.verifier, state: start.state, redirectURI: config.redirectURI)
        try secureStore.set(try JSONEncoder().encode(pending), for: Self.pendingKey)
        defer { try? secureStore.remove(Self.pendingKey) }

        let callbackURL = try await authenticator.authenticate(url: url, callback: config.authCallback)
        return try await finish(callbackURL: callbackURL)
    }

    /// Completes a login from the redirect URL. Also used when the Universal Link arrives
    /// through the app delegate instead of the browser session.
    public func finish(callbackURL: URL) async throws -> String {
        guard let data = try secureStore.data(for: Self.pendingKey),
              let pending = try? JSONDecoder().decode(PendingLogin.self, from: data) else {
            throw PhotonError.loginFailed("no login in progress")
        }
        let params = Self.queryParameters(callbackURL)
        if let error = params["error"] {
            try? secureStore.remove(Self.pendingKey)
            throw error == "access_denied" ? PhotonError.loginCancelled : PhotonError.loginFailed(error)
        }
        guard let code = params["code"], !code.isEmpty else { throw PhotonError.loginFailed("no code in redirect") }
        // The redirect must carry the state this attempt started with (login CSRF).
        guard params["state"] == pending.state else { throw PhotonError.loginFailed("state mismatch") }

        let pair = try await api.oauthCallback(code: code, state: pending.state, codeVerifier: pending.verifier,
                                               redirectURI: pending.redirectURI)
        try? secureStore.remove(Self.pendingKey)
        try await api.tokens.save(pair)
        log.info("logged in")
        return pair.userID
    }

    /// True for a URL that is this deployment's OAuth redirect.
    public func isAuthCallback(_ url: URL) -> Bool {
        guard let registered = URL(string: api.config.redirectURI) else { return false }
        return url.scheme == registered.scheme && url.host == registered.host && url.path == registered.path
    }

    static func queryParameters(_ url: URL) -> [String: String] {
        var out: [String: String] = [:]
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        for item in comps?.queryItems ?? [] { out[item.name] = item.value ?? "" }
        // Some providers return parameters in the fragment.
        if out["code"] == nil, let fragment = comps?.fragment,
           let f = URLComponents(string: "x:/?\(fragment)") {
            for item in f.queryItems ?? [] { out[item.name] = item.value ?? "" }
        }
        return out
    }
}
