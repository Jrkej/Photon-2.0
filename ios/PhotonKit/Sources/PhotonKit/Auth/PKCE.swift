import Foundation

/// RFC 7636 PKCE with S256. The verifier is 32 random bytes as base64url without padding
/// (43 characters); the challenge is base64url(SHA-256(verifier)), also 43 characters.
public struct PKCE: Sendable, Equatable {
    public let verifier: String
    public let challenge: String

    public init(verifier: String) {
        self.verifier = verifier
        self.challenge = PKCE.challenge(for: verifier)
    }

    public static func generate() -> PKCE {
        var rng = SystemRandomNumberGenerator()   // CSPRNG on every platform
        return PKCE(verifier: randomToken(byteCount: 32, using: &rng))
    }

    public static func challenge(for verifier: String) -> String {
        SHA256Hasher.hash(Data(verifier.utf8)).base64URLEncodedString
    }

    static func randomToken<R: RandomNumberGenerator>(byteCount: Int, using rng: inout R) -> String {
        Data((0..<byteCount).map { _ in UInt8.random(in: .min ... .max, using: &rng) }).base64URLEncodedString
    }
}
