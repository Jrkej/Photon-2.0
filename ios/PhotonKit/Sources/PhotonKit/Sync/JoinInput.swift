import Foundation

/// What a user typed, scanned or tapped to join: a join code or an invite link.
public enum JoinInput: Sendable, Equatable {
    case code(String)
    case inviteToken(String)

    /// Crockford base32, the alphabet of join codes (no I, L, O, U).
    static let codeAlphabet = Set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    /// Accepts an invite link `https://<inviteHost>/j/<token>` or an 8-character code.
    /// Codes are normalised the Crockford way: case, spaces and dashes are ignored, and
    /// O is read as 0, I and L as 1.
    public static func parse(_ raw: String, inviteHost: String) -> JoinInput? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: text), url.scheme != nil {
            return inviteToken(from: url, inviteHost: inviteHost).map(JoinInput.inviteToken)
        }
        var code = ""
        for ch in text.uppercased() {
            switch ch {
            case " ", "-": continue
            case "O": code.append("0")
            case "I", "L": code.append("1")
            default: code.append(ch)
            }
        }
        guard code.count == 8, code.allSatisfy({ codeAlphabet.contains($0) }) else { return nil }
        return .code(code)
    }

    /// The token of an invite link (the Universal Link the QR code encodes), or nil.
    public static func inviteToken(from url: URL, inviteHost: String) -> String? {
        guard url.scheme == "https", url.host?.lowercased() == inviteHost.lowercased() else { return nil }
        let parts = url.path.split(separator: "/").map(String.init)
        guard parts.count == 2, parts[0] == "j" else { return nil }
        let token = parts[1]
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard (16...128).contains(token.count), token.allSatisfy({ allowed.contains($0) }) else { return nil }
        return token
    }

    var request: PbJoinRequest.OneOf_By {
        switch self {
        case .code(let c): return .code(c)
        case .inviteToken(let t): return .inviteToken(t)
        }
    }
}
