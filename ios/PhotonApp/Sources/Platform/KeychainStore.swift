import Foundation
import PhotonKit
import Security

/// `SecureStore` in the Keychain: the token pair and the in-flight PKCE verifier.
/// AfterFirstUnlockThisDeviceOnly: readable by a background wake on a locked phone (to
/// refresh during a push catch-up), never synced to iCloud or restored to another device.
final class KeychainStore: SecureStore, @unchecked Sendable {
    private let service: String

    init(service: String = "com.photon.app") {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: key]
    }

    func data(for key: String) throws -> Data? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        switch status {
        case errSecSuccess: return result as? Data
        case errSecItemNotFound: return nil
        default: throw KeychainError(status: status)
        }
    }

    func set(_ data: Data, for key: String) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query(key) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(key)
            add.merge(attributes) { _, new in new }
            let addStatus = SecItemAdd(add as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw KeychainError(status: addStatus) }
        } else if status != errSecSuccess {
            throw KeychainError(status: status)
        }
    }

    func remove(_ key: String) throws {
        let status = SecItemDelete(query(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError(status: status) }
    }
}

struct KeychainError: Error, CustomStringConvertible {
    let status: OSStatus
    var description: String { "Keychain error \(status)" }
}
