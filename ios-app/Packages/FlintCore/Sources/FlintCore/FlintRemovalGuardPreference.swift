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

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decode(Int.self, forKey: .version)
        enabled = try values.decode(Bool.self, forKey: .enabled)
        disclosedAt = try values.decodeIfPresent(Date.self, forKey: .disclosedAt)
        disclosureVersion = try values.decodeIfPresent(Int.self, forKey: .disclosureVersion)
        guard isValid else { throw Error.invalid }
    }

    private var isValid: Bool {
        guard version == Self.currentVersion else { return false }
        if let disclosedAt {
            return disclosedAt.timeIntervalSince1970.isFinite &&
                disclosureVersion == Self.currentDisclosureVersion
        }
        return !enabled && disclosureVersion == nil
    }

    /// The production codec also serves fixtures; dates are absolute Unix seconds.
    public func encoded() throws -> Data {
        guard isValid else { throw Error.invalid }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> Self {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return try decoder.decode(Self.self, from: data)
    }

    public enum ReadResult: Equatable, Sendable {
        case value(FlintRemovalGuardPreference)
        case absent
        case unavailable

        public var captureForNewCommitment: Bool {
            guard case let .value(value) = self else { return false }
            return value.isValid && value.enabled
        }
    }

    /// Narrow injectable I/O for validated save + read-back. This is not a protection
    /// transaction or authority to release a running guard. Phase 2 serializes policy,
    /// queued changes and capture-at-start with its existing cross-process lock.
    public struct Store {
        private let read: () throws -> Data?
        private let write: (Data) throws -> Void

        public init(read: @escaping () throws -> Data?, write: @escaping (Data) throws -> Void) {
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
                return .value(try FlintRemovalGuardPreference.decode(data))
            } catch { return .unavailable }
        }

        public func save(_ preference: FlintRemovalGuardPreference) throws {
            let data = try preference.encoded()
            // Never repair an unreadable/newer record by overwriting it with defaults.
            guard load() != .unavailable else { throw Error.unavailable }
            try write(data)
            guard let stored = try read(), stored == data,
                  try FlintRemovalGuardPreference.decode(stored) == preference else {
                throw Error.readBackFailed
            }
        }
    }
}
