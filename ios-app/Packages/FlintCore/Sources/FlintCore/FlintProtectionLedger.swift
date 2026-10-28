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
    /// A transaction was started inside another one on the same thread — a programming error.
    case nested
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

    /// Elapsed seconds from `self` to `now` for **cooldowns and waits** — never more than may
    /// have passed — or nil after a reboot (callers then apply §9's reboot rules).
    ///
    /// The monotonic delta is the measure: within a boot it is the true elapsed time and
    /// immune to wall-clock changes; under a reboot the OS didn't report (no boot id, but the
    /// new uptime already exceeds the anchor) it can only under-count. A monotonic reading below
    /// the anchor, or differing known boot ids, is a reboot.
    public func elapsed(until now: FlintLedgerInstant) -> TimeInterval? {
        guard isSameBootCandidate(now) else { return nil }
        return now.monotonic - monotonic
    }

    /// Elapsed seconds for **release intervals** (pass recovery, access grants) — never less
    /// than may have passed, so a release can only end early, not late — or nil when that can't
    /// be shown (the release then ends conservatively, §9). With matching known boot ids the
    /// monotonic delta is exact. Without a boot id an undetected reboot under-counts monotonic
    /// time and a wall clock moved back under-counts wall time; the larger delta is safe unless
    /// both happened, so a wall clock that fell behind the monotonic delta (rollback evidence)
    /// yields nil.
    public func elapsedForRelease(until now: FlintLedgerInstant) -> TimeInterval? {
        guard isSameBootCandidate(now) else { return nil }
        let monotonicDelta = now.monotonic - monotonic
        if bootID != nil, now.bootID != nil { return monotonicDelta }
        let wallDelta = now.wall.timeIntervalSince(wall)
        if wallDelta < monotonicDelta - Self.rollbackTolerance { return nil }
        return max(monotonicDelta, wallDelta)
    }

    /// Wall-vs-monotonic disagreement treated as a clock change (ADR-012 §9's threshold).
    static let rollbackTolerance: TimeInterval = 5

    private func isSameBootCandidate(_ now: FlintLedgerInstant) -> Bool {
        guard now.monotonic >= monotonic else { return false }
        if let bootID, let other = now.bootID { return bootID == other }
        return true
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
            /// The one-time import of the pre-ledger pass state hasn't run yet: no pass can be
            /// offered until it has (§12 — an established install must never get a fresh pass).
            case pendingImport
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
            usage = .pendingImport
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
    private let migrate: (inout FlintLedgerState, FlintLedgerInstant) -> Void

    /// - Parameters:
    ///   - initializedMarker / setInitializedMarker: a durable marker *outside* the ledger file,
    ///     so a deleted or truncated file after first initialization reads as unavailable rather
    ///     than as a fresh install (§8). Invariant: the marker is written only after the file is
    ///     durable, so "marker present" implies the file once existed.
    ///   - migrate: one-time imports, run inside every transaction before the body (each is
    ///     guarded by its own durable marker, so this is idempotent).
    public init(
        directory: URL,
        clock: FlintClock,
        lockTimeout: TimeInterval = 2,
        initializedMarker: @escaping () -> Date?,
        setInitializedMarker: @escaping (Date) -> Void,
        migrate: @escaping (inout FlintLedgerState, FlintLedgerInstant) -> Void = { _, _ in }
    ) {
        self.directory = directory
        self.clock = clock
        self.lockTimeout = lockTimeout
        self.initializedMarker = initializedMarker
        self.setInitializedMarker = setInitializedMarker
        self.migrate = migrate
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
            setInitializedMarker: { defaults.set($0.timeIntervalSince1970, forKey: key) },
            // Required for a usable pass: without it a new ledger stays `pendingImport` (closed).
            migrate: { state, now in
                guard !state.migrations.contains(FlintLedgerState.legacyPassMigration) else { return }
                // The App Group suite opened, so absence of the legacy key is the old "never
                // used" state. `UserDefaults` has no per-key failure channel: an unreadable
                // container is caught earlier (no suite / no ledger directory), never here.
                let raw = defaults.object(forKey: FlintLedgerState.legacyPassKey)
                state.importLegacyPass(raw.map(FlintLegacyValue.value) ?? .absent, at: now)
            }
        )
    }

    var fileURL: URL { directory.appendingPathComponent(Self.fileName) }

    // MARK: Read / transact

    /// A consistent snapshot, taken under the lock. Initializes a brand-new install.
    public func read() -> Result<FlintLedgerState, FlintLedgerError> {
        transaction { $0 }
    }

    /// Reads the last committed document without locking, migrating, checkpointing or writing.
    /// Atomic replacement gives readers a complete committed version, including while a
    /// transaction is open. A missing ledger is unavailable, even on a new install: only a
    /// transaction may initialize it. Corrupt bytes are left untouched, without quarantine.
    public func peek() -> Result<FlintLedgerState, FlintLedgerError> {
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            return .failure(.unavailable(initializedMarker() != nil
                ? "ledger missing after initialization" : "ledger is not initialized"))
        } catch {
            return .failure(.unavailable("cannot read ledger: \(error)"))
        }
        return Self.decode(data)
    }

    /// Run `body` against the current state under an exclusive cross-process lock and persist
    /// the result atomically. If `body` throws, nothing is written and the error is returned
    /// as `.unavailable`. Never nest transactions on one thread (it would self-deadlock across
    /// file descriptors); a nested call fails instead.
    public func transaction<T>(_ body: (inout FlintLedgerState) throws -> T) -> Result<T, FlintLedgerError> {
        let nestingKey = "FlintProtectionLedger.inTransaction:" + directory.path
        if Thread.current.threadDictionary[nestingKey] != nil { return .failure(.nested) }
        Thread.current.threadDictionary[nestingKey] = true
        defer { Thread.current.threadDictionary.removeObject(forKey: nestingKey) }

        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            return .failure(.unavailable("cannot create ledger directory: \(error)"))
        }
        let lock: Int32
        switch acquireLock() {
        case let .success(fd): lock = fd
        case let .failure(error): return .failure(error)
        }
        defer { releaseLock(lock) }

        var state: FlintLedgerState
        let existed: Bool
        switch load() {
        case let .success(loaded): (state, existed) = loaded
        case let .failure(error): return .failure(error)
        }
        let before = state
        migrate(&state, FlintLedgerInstant(clock: clock))
        let result: T
        do {
            result = try body(&state)
        } catch {
            return .failure(.unavailable("transaction aborted: \(error)"))
        }
        if state != before || !existed {
            if let failure = write(state) { return .failure(failure) }
        }
        if initializedMarker() == nil { setInitializedMarker(state.initializedAt) }
        return .success(result)
    }

    // MARK: Load / validate

    /// The state and whether the file existed. Only a genuinely absent file (ENOENT) may start
    /// a fresh install; an unreadable one (e.g. locked before first unlock) is unavailable.
    private func load() -> Result<(FlintLedgerState, Bool), FlintLedgerError> {
        let data: Data
        do {
            data = try Data(contentsOf: fileURL)
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            if initializedMarker() != nil {
                return .failure(.unavailable("ledger missing after initialization"))
            }
            return .success((FlintLedgerState(initializedAt: clock.now()), false))
        } catch {
            return .failure(.unavailable("cannot read ledger: \(error)"))
        }
        return Self.decode(data, onCorrupt: { self.quarantine(data) }).map { ($0, true) }
    }

    /// Shared validation; only transactional reads supply a quarantine effect.
    private static func decode(_ data: Data, onCorrupt: () -> Void = {}) -> Result<FlintLedgerState, FlintLedgerError> {
        // Peek at the version first so a newer schema is reported as such, not as corruption.
        if let header = try? JSONDecoder().decode(Header.self, from: data),
           header.version > FlintLedgerState.currentVersion {
            return .failure(.unavailable("ledger schema \(header.version) is newer than this build"))
        }
        do {
            return .success(try makeDecoder().decode(FlintLedgerState.self, from: data))
        } catch {
            onCorrupt()
            return .failure(.unavailable("ledger is corrupt"))
        }
    }

    private struct Header: Decodable { let version: Int }

    // Per-call coders: transactions run concurrently on different threads.
    static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        return decoder
    }

    // MARK: Write (temp + fsync + rename)

    private func write(_ state: FlintLedgerState) -> FlintLedgerError? {
        let data: Data
        do {
            data = try Self.makeEncoder().encode(state)
        } catch {
            return .unavailable("cannot encode ledger: \(error)")
        }
        let temp = directory.appendingPathComponent(".\(Self.fileName).\(UUID().uuidString)")
        let manager = FileManager.default
        // Readable by the extensions after first unlock; never left world-readable.
        let attributes: [FileAttributeKey: Any] = [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        guard manager.createFile(atPath: temp.path, contents: nil, attributes: attributes) else {
            return .unavailable("cannot create ledger temp file")
        }
        guard let handle = try? FileHandle(forWritingTo: temp) else {
            try? manager.removeItem(at: temp)
            return .unavailable("cannot open ledger temp file")
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
            let code = errno
            try? manager.removeItem(at: temp)
            return .unavailable("cannot replace ledger (errno \(code))")
        }
        // Make the rename itself durable before anything (the init marker) relies on it.
        let directoryFD = open(directory.path, O_RDONLY)
        if directoryFD >= 0 {
            fsync(directoryFD)
            close(directoryFD)
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
            manager.createFile(atPath: target.path, contents: data,
                               attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        let existing = ((try? manager.contentsOfDirectory(atPath: folder.path)) ?? []).sorted()
        for stale in existing.dropLast(Self.quarantineLimit) {
            try? manager.removeItem(at: folder.appendingPathComponent(stale))
        }
    }

    // MARK: Lock

    /// An exclusive lock on a dedicated file that is never deleted. Only a deadline expiry is
    /// `.busy`; a lock that can't be opened or taken at all is `.unavailable` (a permanent fault,
    /// not contention). The deadline runs on monotonic time, immune to clock changes.
    private func acquireLock() -> Result<Int32, FlintLedgerError> {
        let path = directory.appendingPathComponent(Self.lockName).path
        var fd: Int32
        repeat { fd = open(path, O_CREAT | O_RDWR, 0o600) } while fd < 0 && errno == EINTR
        guard fd >= 0 else { return .failure(.unavailable("cannot open ledger lock (errno \(errno))")) }
        let deadline = clock.monotonic() + lockTimeout
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            let code = errno
            guard code == EWOULDBLOCK || code == EINTR else {
                close(fd)
                return .failure(.unavailable("cannot lock ledger (errno \(code))"))
            }
            guard clock.monotonic() < deadline else {
                close(fd)
                return .failure(.busy)
            }
            usleep(5_000)
        }
        return .success(fd)
    }

    private func releaseLock(_ fd: Int32) {
        flock(fd, LOCK_UN)
        close(fd)
    }
}
