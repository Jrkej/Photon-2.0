import AuthenticationServices
import PhotonKit
import UIKit

/// `WebAuthenticator` over ASWebAuthenticationSession: the system browser, never a web
/// view. The redirect is the Universal Link https://photon.example/auth/cb in production
/// (an https callback, iOS 17.4+, which needs the webcredentials associated domain) and
/// photon://auth/cb with the dev provider.
@MainActor
final class WebAuthSession: NSObject, WebAuthenticator, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authenticate(url: URL, callback: AuthCallback) async throws -> URL {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<URL, Error>) in
            // The handler touches nothing but the continuation, so it is safe on any thread.
            let completion: ASWebAuthenticationSession.CompletionHandler = { url, error in
                if let url {
                    cont.resume(returning: url)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    cont.resume(throwing: PhotonError.loginCancelled)
                } else {
                    cont.resume(throwing: PhotonError.loginFailed(error?.localizedDescription ?? "unknown error"))
                }
            }
            let matcher: ASWebAuthenticationSession.Callback
            switch callback {
            case .customScheme(let scheme): matcher = .customScheme(scheme)
            case .https(let host, let path): matcher = .https(host: host, path: path)
            }
            let session = ASWebAuthenticationSession(url: url, callback: matcher, completionHandler: completion)
            session.presentationContextProvider = self
            // Share the browser's cookies so an existing Google sign-in is reused.
            session.prefersEphemeralWebBrowserSession = false
            self.session = session          // kept alive until the next login replaces it
            if !session.start() {
                cont.resume(throwing: PhotonError.loginFailed("could not open the browser"))
            }
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            if let key = scenes.flatMap(\.windows).first(where: \.isKeyWindow) { return key }
            if let scene = scenes.first { return ASPresentationAnchor(windowScene: scene) }
            return ASPresentationAnchor()
        }
    }
}
