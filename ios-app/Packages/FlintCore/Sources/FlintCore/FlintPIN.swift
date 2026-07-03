import Foundation
import CryptoKit

/// App-open PIN (part of the anti-bypass suite). Stores only a salted SHA-256 hash — never the
/// PIN itself. Note: the *strong* anti-uninstall guarantee on iOS is the **system Screen Time
/// passcode** (surface that in onboarding); this app PIN just gates opening Flint.
public enum FlintPIN {

    public static func isSet(_ store: FlintGroupStore?) -> Bool {
        store?.pinRecord() != nil
    }

    public static func set(_ pin: String, _ store: FlintGroupStore?) {
        guard let store, !pin.isEmpty else { return }
        let salt = UUID().uuidString
        store.setPINRecord(hash: hash(pin, salt: salt), salt: salt)
    }

    public static func verify(_ pin: String, _ store: FlintGroupStore?) -> Bool {
        guard let store, let record = store.pinRecord() else { return false }
        return hash(pin, salt: record.salt) == record.hash
    }

    public static func clear(_ store: FlintGroupStore?) {
        store?.clearPINRecord()
    }

    /// Salted SHA-256, lowercase hex. Pure & deterministic for testing.
    public static func hash(_ pin: String, salt: String) -> String {
        SHA256.hash(data: Data((pin + salt).utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
