import Foundation

// ADR-012 §8: protection state that the host app, the DeviceActivityMonitor extension and the
// ShieldAction extension all mutate needs one crash-safe, cross-process transaction boundary —
// not independent App Group UserDefaults read/write pairs. This is that boundary: one versioned
// JSON document in the App Group container, mutated only under an exclusive `flock`, written
// with temp-file + fsync + rename, and never silently replaced with defaults when it can't be
// read (§8 "unavailable", not "empty").

// MARK: - Errors

public enum FlintLedgerError: Error, Equatable, Sendable {
    /// The state can't be trusted: corrupt, newer schema, missing after initialization, or IO.
    /// Callers must deny new releases/credits/passes/weakening — never substitute defaults.
    case unavailable(String)
    /// Another process held the lock past the timeout. Retry later; nothing was changed.
    case busy
}

// MARK: - Time stamps (§9 anchors)

/// One observation on both clocks, so later reads can measure elapsed time on the monotonic
/// timeline when the boot matches, and fall back conservatively when it doesn't.
public struct FlintLedgerInstant: Codable, Equatable, Sendable {
    public var wall: Date
    public var monotonic: TimeInterval
    public var bootID: String?

    public init(wall: Date, monotonic: TimeInterval, bootID: String?) {
        self.wall = wall
        self.monotonic = monotonic
        self.bootID = bootID
    }

    public init(clock: FlintClock) {
        self.init(wall: clock.now(), monotonic: clock.monotonic(), bootID: clock.bootSessionID())
    }

    /// Elapsed seconds from `self` to `now` when both are provably on the same boot; nil when
    /// the boot changed or can't be shown to match (callers then apply §9's reboot rules).
    public func elapsed(until now: FlintLedgerInstant) -> TimeInterval? {
        if let bootID, let other = now.bootID {
            guard bootID == other else { return nil }
        } else {
            return nil
        }
        guard now.monotonic >= monotonic else { return nil }
        return now.monotonic - monotonic
    }
}

// MARK: - State

public struct FlintLedgerState: Codable, Equatable, Sendable {
    public static let currentVersion = 1
    /// Bounded idempotency memory (§8 "query by operation ID").
    public static let operationMemory = 200

    public var version: Int
    public var initializedAt: Date
    /// Durable one-time migration markers (§12).
    public var migrations: Set<String>
    public var pass: Pass
    /// Harder friction per cause occurrence id (§4).
    public var harder: [String: Harder]
    /// Recent committed operations, newest last.
    public var operations: [Operation]

    public init(initializedAt: Date) {
        version = Self.currentVersion
        self.initializedAt = initializedAt
        migrations = []
        pass = Pass()
        harder = [:]
        operations = []
    }

    // MARK: Emergency Pass (§6)

    public struct Pass: Codable, Equatable, Sendable {
        public enum Usage: String, Codable, Sendable {
            /// A validated install with no committed use.
            case neverUsed
            /// Used; cooldown is tracked by `cooldownRemaining` at `checkpoint`.
            case used
            /// Imported state couldn't be validated: no pass until repaired (§12).
            case unknown
        }

        public var usage: Usage
        /// Remaining cooldown, in elapsed seconds, as of `checkpoint`.
        public var cooldownRemaining: TimeInterval
        public var checkpoint: FlintLedgerInstant?
        /// The last committed consumption's operation id (idempotent retries).
        public var lastOperationID: String?
        /// Global recovery remaining, in elapsed seconds, as of `checkpoint`.
        public var recoveryRemaining: TimeInterval

        public init() {
            usage = .neverUsed
            cooldownRemaining = 0
            checkpoint = nil
            lastOperationID = nil
            recoveryRemaining = 0
        }
    }

    // MARK: Harder friction (§4)

    public struct Harder: Codable, Equatable, Sendable {
        /// Committed weakening operations on this occurrence (`n`).
        public var committedCount: Int
        public var pending: PendingWait?

        public init(committedCount: Int = 0, pending: PendingWait? = nil) {
            self.committedCount = committedCount
            self.pending = pending
        }
    }

    public struct PendingWait: Codable, Equatable, Sendable {
        public var operationID: String
        /// e.g. "stop", "break", "edit", "off", "delete", "replace".
        public var kind: String
        /// Exact revisions / target scope the wait was requested for; a different operation or
        /// changed policy needs a new wait (§4).
        public var scope: [String]
        public var requestedAt: FlintLedgerInstant
        public var wait: TimeInterval

        public init(operationID: String, kind: String, scope: [String], requestedAt: FlintLedgerInstant, wait: TimeInterval) {
            self.operationID = operationID
            self.kind = kind
            self.scope = scope
            self.requestedAt = requestedAt
            self.wait = wait
        }
    }

    public struct Operation: Codable, Equatable, Sendable {
        public var id: String
        public var kind: String
        public var result: String
        public var at: Date

        public init(id: String, kind: String, result: String, at: Date) {
            self.id = id
            self.kind = kind
            self.result = result
            self.at = at
        }
    }

    public mutating func remember(_ operation: Operation) {
        operations.removeAll { $0.id == operation.id }
        operations.append(operation)
        if operations.count > Self.operationMemory {
            operations.removeFirst(operations.count - Self.operationMemory)
        }
    }

    public func operation(_ id: String) -> Operation? {
        operations.last { $0.id == id }
    }
}

// MARK: - Ledger

public final class FlintProtectionLedger {
    public static let fileName = "ledger.json"
    static let lockName = "ledger.lock"
    static let quarantineName = "quarantine"
    static let quarantineLimit = 3

    private let directory: URL
    private let initializedMarker: () -> Date?
    private let setInitializedMarker: (Date) -> Void
    private let clock: FlintClock
    private let lockTimeout: TimeInterval

    /// - Parameters:
    ///   - initializedMarker / setInitializedMarker: a durable marker *outside* the ledger file,
    ///     so a deleted or truncated file after first initialization reads as unavailable rather
    ///     than as a fresh install (§8).
    public init(
        directory: URL,
        clock: FlintClock,
        lockTimeout: TimeInterval = 2,
        initializedMarker: @escaping () -> Date?,
        setInitializedMarker: @escaping (Date) -> Void
    ) {
        self.directory = directory
        self.clock = clock
        self.lockTimeout = lockTimeout
        self.initializedMarker = initializedMarker
        self.setInitializedMarker = setInitializedMarker
    }

    /// The production ledger in the App Group container; nil without the entitlement.
    public static func appGroup(clock: FlintClock = .system) -> FlintProtectionLedger? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID),
            let defaults = UserDefaults.flintGroup else { return nil }
        let key = "flint.ledger.initializedAt"
        return FlintProtectionLedger(
            directory: container.appendingPathComponent("FlintLedger", isDirectory: true),
            clock: clock,
            initializedMarker: {
                let value = defaults.double(forKey: key)
                return value > 0 ? Date(timeIntervalSince1970: value) : nil
            },
            setInitializedMarker: { defaults.set($0.timeIntervalSince1970, forKey: key) }
        )
    }

    var fileURL: URL { directory.appendingPathComponent(Self.fileName) }

    // MARK: Read / transact

    /// A consistent snapshot, taken under the lock. Initializes a brand-new install.
    public func read() -> Result<FlintLedgerState, FlintLedgerError> {
        transaction { $0 }
    }

    /// Run `body` against the current state under an exclusive cross-process lock and persist
    /// the result atomically. If `body` throws, nothing is written and the error is returned
    /// as `.unavailable`. Never nest transactions on one thread (it would self-deadlock across
    /// file descriptors); a nested call fails instead.
    public func transaction<T>(_ body: (inout FlintLedgerState) throws -> T) -> Result<T, FlintLedgerError> {
        let nestingKey = "FlintProtectionLedger.inTransaction"
        if Thread.current.threadDictionary[nestingKey] != nil {
            return .failure(.unavailable("nested ledger transaction"))
        }
        Thread.current.threadDictionary[nestingKey] = true
        defer { Thread.current.threadDictionary.removeObject(forKey: nestingKey) }

        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            return .failure(.unavailable("cannot create ledger directory: \(error)"))
        }
        guard let lock = acquireLock() else { return .failure(.busy) }
        defer { releaseLock(lock) }

        var state: FlintLedgerState
        switch load() {
        case let .success(loaded): state = loaded
        case let .failure(error): return .failure(error)
        }
        let before = state
        let result: T
        do {
            result = try body(&state)
        } catch {
            return .failure(.unavailable("transaction aborted: \(error)"))
        }
        if state != before || !FileManager.default.fileExists(atPath: fileURL.path) {
            if let failure = write(state) { return .failure(failure) }
        }
        if initializedMarker() == nil { setInitializedMarker(state.initializedAt) }
        return .success(result)
    }

    // MARK: Load / validate

    private func load() -> Result<FlintLedgerState, FlintLedgerError> {
        let manager = FileManager.default
        guard manager.fileExists(atPath: fileURL.path) else {
            if initializedMarker() != nil {
                return .failure(.unavailable("ledger missing after initialization"))
            }
            return .success(FlintLedgerState(initializedAt: clock.now()))
        }
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch {
            return .failure(.unavailable("cannot read ledger: \(error)"))
        }
        // Peek at the version first so a newer schema is reported as such, not as corruption.
        if let header = try? JSONDecoder().decode(Header.self, from: data),
           header.version > FlintLedgerState.currentVersion {
            return .failure(.unavailable("ledger schema \(header.version) is newer than this build"))
        }
        do {
            return .success(try Self.decoder.decode(FlintLedgerState.self, from: data))
        } catch {
            quarantine(data)
            return .failure(.unavailable("ledger is corrupt"))
        }
    }

    private struct Header: Decodable { let version: Int }

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return decoder
    }()

    // MARK: Write (temp + fsync + rename)

    private func write(_ state: FlintLedgerState) -> FlintLedgerError? {
        let data: Data
        do {
            data = try Self.encoder.encode(state)
        } catch {
            return .unavailable("cannot encode ledger: \(error)")
        }
        let temp = directory.appendingPathComponent(".\(Self.fileName).\(UUID().uuidString)")
        let manager = FileManager.default
        // Readable by the extensions after first unlock; never left world-readable.
        let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        guard manager.createFile(atPath: temp.path, contents: nil, attributes: attributes),
              let handle = try? FileHandle(forWritingTo: temp) else {
            return .unavailable("cannot create ledger temp file")
        }
        do {
            try handle.write(contentsOf: data)
            try handle.synchronize()
            try handle.close()
        } catch {
            try? handle.close()
            try? manager.removeItem(at: temp)
            return .unavailable("cannot write ledger: \(error)")
        }
        guard rename(temp.path, fileURL.path) == 0 else {
            try? manager.removeItem(at: temp)
            return .unavailable("cannot replace ledger (errno \(errno))")
        }
        return nil
    }

    // MARK: Quarantine (keep the original bytes for recovery)

    private func quarantine(_ data: Data) {
        let folder = directory.appendingPathComponent(Self.quarantineName, isDirectory: true)
        let manager = FileManager.default
        try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = "\(Int64(clock.now().timeIntervalSince1970 * 1000)).json"
        let target = folder.appendingPathComponent(name)
        if !manager.fileExists(atPath: target.path) {
            try? data.write(to: target)
        }
        let existing = ((try? manager.contentsOfDirectory(atPath: folder.path)) ?? []).sorted()
        for stale in existing.dropLast(Self.quarantineLimit) {
            try? manager.removeItem(at: folder.appendingPathComponent(stale))
        }
    }

    // MARK: Lock

    private func acquireLock() -> Int32? {
        let path = directory.appendingPathComponent(Self.lockName).path
        let fd = open(path, O_CREAT | O_RDWR, 0o600)
        guard fd >= 0 else { return nil }
        let deadline = Date().addingTimeInterval(lockTimeout)
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            guard errno == EWOULDBLOCK || errno == EINTR, Date() < deadline else {
                close(fd)
                return nil
            }
            usleep(5_000)
        }
        return fd
    }

    private func releaseLock(_ fd: Int32) {
        flock(fd, LOCK_UN)
        close(fd)
    }
}
