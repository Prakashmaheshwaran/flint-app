import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import CryptoKit
import Darwin

/// Recovery bytes live outside preferences, which have a per-domain capacity limit.
/// Files are immutable and retained indefinitely; normal rule/device cleanup never removes them.
enum FlintRuleRecovery {
    static var directory: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID)?
            .appendingPathComponent("FlintRules/Recovery", isDirectory: true)
    }

    private struct Reference: Codable {
        let flintRuleRecoveryFile: Int
        let sha256: String
        let byteCount: Int
    }

    static func read(_ object: Any, key: String, directory: URL?) -> Data? {
        if let bytes = object as? Data { return bytes } // existing inline backups stay readable
        guard let fields = object as? [String: Any], fields["flintRuleRecoveryFile"] != nil else {
            return try? PropertyListSerialization.data(fromPropertyList: object, format: .binary, options: 0)
        }
        guard Set(fields.keys) == ["flintRuleRecoveryFile", "sha256", "byteCount"],
              let encoded = try? JSONSerialization.data(withJSONObject: fields),
              let reference = try? JSONDecoder().decode(Reference.self, from: encoded),
              reference.flintRuleRecoveryFile == 1, reference.byteCount >= 0,
              reference.sha256.count == 64, reference.sha256.allSatisfy({ "0123456789abcdef".contains($0) }),
              let directory,
              let bytes = try? Data(contentsOf: file(key, digest: reference.sha256, in: directory)),
              bytes.count == reference.byteCount, digest(bytes) == reference.sha256 else { return nil }
        return bytes
    }

    static func available(_ key: String, defaults: UserDefaults, directory: URL?) -> Bool {
        guard let object = defaults.object(forKey: key) else { return true }
        return read(object, key: key, directory: directory) != nil
    }

    @discardableResult
    static func retain(_ object: Any, key: String, defaults: UserDefaults, directory: URL?) -> Bool {
        if let existing = defaults.object(forKey: key) {
            return read(existing, key: key, directory: directory) != nil
        }
        guard let bytes = object as? Data
                ?? (try? PropertyListSerialization.data(fromPropertyList: object, format: .binary, options: 0)),
              let directory else { return false }
        let hash = digest(bytes)
        let destination = file(key, digest: hash, in: directory)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            if FileManager.default.fileExists(atPath: destination.path) {
                guard try Data(contentsOf: destination) == bytes else { return false }
            } else {
                let temporary = directory.appendingPathComponent(".\(UUID().uuidString).tmp")
                defer { try? FileManager.default.removeItem(at: temporary) }
                guard FileManager.default.createFile(atPath: temporary.path, contents: nil,
                    attributes: [.posixPermissions: 0o600, .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else { return false }
                let handle = try FileHandle(forWritingTo: temporary)
                do {
                    try handle.write(contentsOf: bytes); try handle.synchronize(); try handle.close()
                } catch { try? handle.close(); throw error }
                guard rename(temporary.path, destination.path) == 0 else { return false }
            }
            // Also sync an orphan from a crash between rename and marker before adopting it.
            let descriptor = open(directory.path, O_RDONLY)
            guard descriptor >= 0 else { return false }
            defer { close(descriptor) }
            guard fsync(descriptor) == 0, try Data(contentsOf: destination) == bytes else { return false }
            let reference = Reference(flintRuleRecoveryFile: 1, sha256: hash, byteCount: bytes.count)
            let fields = try JSONSerialization.jsonObject(with: JSONEncoder().encode(reference))
            defaults.set(fields, forKey: key)
            guard defaults.synchronize(), let stored = defaults.object(forKey: key),
                  read(stored, key: key, directory: directory) == bytes else {
                defaults.removeObject(forKey: key); defaults.synchronize()
                return false
            }
            return true
        } catch { return false }
    }

    private static func file(_ key: String, digest: String, in directory: URL) -> URL {
        directory.appendingPathComponent("\(self.digest(Data(key.utf8))).\(digest).raw")
    }
    private static func digest(_ bytes: Data) -> String {
        SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    }
}
#endif
