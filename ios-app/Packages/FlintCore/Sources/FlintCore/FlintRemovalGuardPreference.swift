import Foundation

/// R-08 phase 1: preference storage only, not wired into Settings or enforcement.
/// The off fallback is ONLY for new captures. Existing owners use their frozen optional
/// removalGuard field, never a fresh preference read (ADR-012 §10/12, PC-79).
public struct FlintRemovalGuardPreference: Codable, Equatable, Sendable {
    public static let key = "flint.removalGuard.preference"
    public static let currentVersion = 1
    public static let currentDisclosureVersion = 1

    public let version: Int
    public let enabled: Bool
    public let disclosedAt: Date?
    public let disclosureVersion: Int?

    public init(enabled: Bool = false, disclosedAt: Date? = nil, disclosureVersion: Int? = nil) {
        version = Self.currentVersion
        self.enabled = enabled
        self.disclosedAt = disclosedAt
        self.disclosureVersion = disclosureVersion
    }

    public enum Error: Swift.Error, Equatable { case invalid, unavailable, readBackFailed }
    private enum CodingKeys: String, CodingKey { case version, enabled, disclosedAt, disclosureVersion }
    private static let disclosureContext = CodingUserInfoKey(rawValue: "FlintRemovalGuardPreference.disclosureVersion")!

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decode(Int.self, forKey: .version)
        enabled = try values.decode(Bool.self, forKey: .enabled)
        let date = try values.decodeIfPresent(Date.self, forKey: .disclosedAt)
        let disclosure = try values.decodeIfPresent(Int.self, forKey: .disclosureVersion)
        let current = decoder.userInfo[Self.disclosureContext] as? Int ?? Self.currentDisclosureVersion
        // Future disclosure semantics are unknown even on an off record; preserve them.
        if let disclosure, disclosure < 0 || disclosure > current { throw Error.invalid }
        // Old off records with incomplete metadata are readable off, not a permanent
        // storage error. Preserve bytes on load; an explicit save writes canonical JSON.
        disclosedAt = !enabled && (date == nil || disclosure == nil) ? nil : date
        disclosureVersion = !enabled && (date == nil || disclosure == nil) ? nil : disclosure
        guard isValid(currentDisclosureVersion: current) else { throw Error.invalid }
    }

    private func isValid(currentDisclosureVersion: Int) -> Bool {
        guard version == Self.currentVersion, currentDisclosureVersion > 0 else { return false }
        if let disclosedAt {
            guard let disclosureVersion else { return false }
            return disclosedAt.timeIntervalSince1970.isFinite && disclosureVersion >= 0 &&
                disclosureVersion <= currentDisclosureVersion
        }
        return !enabled && disclosureVersion == nil
    }

    /// The production codec also serves fixtures; dates are absolute Unix seconds.
    public func encoded(currentDisclosureVersion: Int = Self.currentDisclosureVersion) throws -> Data {
        guard isValid(currentDisclosureVersion: currentDisclosureVersion),
              !enabled || disclosureVersion == currentDisclosureVersion else { throw Error.invalid }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data, currentDisclosureVersion: Int = Self.currentDisclosureVersion) throws -> Self {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        decoder.userInfo[disclosureContext] = currentDisclosureVersion
        return try decoder.decode(Self.self, from: data)
    }

    public enum ReadResult: Equatable, Sendable {
        /// Older disclosure is readable stale consent, off for new captures until accepted
        /// again. The context is injectable so a version bump is covered before shipping.
        case value(FlintRemovalGuardPreference, currentDisclosureVersion: Int = FlintRemovalGuardPreference.currentDisclosureVersion)
        case absent
        case unavailable

        public var captureForNewCommitment: Bool {
            guard case let .value(value, current) = self else { return false }
            return value.isValid(currentDisclosureVersion: current) && value.enabled && value.disclosureVersion == current
        }
    }

    /// Narrow injectable I/O for validated save + read-back. This is not a protection
    /// transaction or authority to release a running guard. Read-back proves logical
    /// correctness, not cross-process durability. Phase 2's shared lock/transaction is
    /// the durability boundary for policy, queued changes and capture-at-start.
    public struct Store {
        private let read: () throws -> Data?
        private let write: (Data) throws -> Void
        private let currentDisclosureVersion: Int

        public init(currentDisclosureVersion: Int = FlintRemovalGuardPreference.currentDisclosureVersion,
                    read: @escaping () throws -> Data?, write: @escaping (Data) throws -> Void) {
            self.currentDisclosureVersion = currentDisclosureVersion
            self.read = read
            self.write = write
        }

        public init?(suiteName: String = FlintGroupStore.appGroupID) {
            guard let defaults = UserDefaults(suiteName: suiteName) else { return nil }
            self.init(read: {
                guard defaults.synchronize() else { throw Error.unavailable }
                guard let object = defaults.object(forKey: FlintRemovalGuardPreference.key) else { return nil }
                guard let data = object as? Data else { throw Error.unavailable }
                return data
            }, write: { data in
                defaults.set(data, forKey: FlintRemovalGuardPreference.key)
                guard defaults.synchronize() else { throw Error.unavailable }
            })
        }

        public func load() -> ReadResult {
            do {
                guard let data = try read() else { return .absent }
                return .value(try FlintRemovalGuardPreference.decode(data, currentDisclosureVersion: currentDisclosureVersion),
                              currentDisclosureVersion: currentDisclosureVersion)
            } catch { return .unavailable }
        }

        public func save(_ preference: FlintRemovalGuardPreference) throws {
            let data = try preference.encoded(currentDisclosureVersion: currentDisclosureVersion)
            // Never repair an unreadable/newer record by overwriting it with defaults.
            guard load() != .unavailable else { throw Error.unavailable }
            try write(data)
            guard let stored = try read(), stored == data,
                  try FlintRemovalGuardPreference.decode(stored, currentDisclosureVersion: currentDisclosureVersion) == preference else {
                throw Error.readBackFailed
            }
        }
    }
}
