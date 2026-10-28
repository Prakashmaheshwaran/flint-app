import Foundation
import CryptoKit
import Darwin

public enum FlintHistoryAvailability: Equatable, Sendable {
    case unavailable, degraded, empty, value
}

public struct FlintHistorySnapshot: Sendable {
    public var events: [FlintHistoryEvent]
    public var historyStart: Date?
    public var availability: FlintHistoryAvailability

    public init(events: [FlintHistoryEvent], historyStart: Date?, availability: FlintHistoryAvailability) {
        self.events = events; self.historyStart = historyStart; self.availability = availability
    }
}

/// Synchronous disk operations belong on a background queue. Every operation uses a stable,
/// separate advisory-lock file, including atomic replacement of the log and metadata.
/// Lifecycle callers use FlintHistorySink, which never waits for these operations.
public final class FlintHistoryStore: @unchecked Sendable {
    public static let softLimit = 1_048_576
    public static let hardLimit = 5 * 1_048_576
    public static let shared = FlintHistoryStore(directory: FileManager.default
        .containerURL(forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID)?
        .appendingPathComponent("FlintHistory", isDirectory: true))

    public let directory: URL?
    private let clock: () -> Date
    private let calendar: Calendar
    private let mutex = NSLock()
    private let fm = FileManager.default

    private struct Metadata: Codable {
        var v = 1
        var createdAt: Int64
        var clearedAt: Int64?
        var lastCompacted: Int64?
        var degraded = false
        var lostAllReadableEvents: Bool?
        // A durable clear intent closes the crash window between replacing two files.
        var pendingClear: Bool?
        var keepSession: String?
        var historyStart: Date { FlintHistoryEvent.date(max(createdAt, clearedAt ?? createdAt)) }

        init(createdAt: Int64, degraded: Bool = false) {
            self.createdAt = createdAt; self.degraded = degraded
        }
        private enum CodingKeys: String, CodingKey {
            case v, createdAt, clearedAt, lastCompacted, degraded, pendingClear, keepSession, lostAllReadableEvents
        }
        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            v = try c.decode(Int.self, forKey: .v)
            createdAt = try c.decode(Int64.self, forKey: .createdAt)
            clearedAt = try c.decodeIfPresent(Int64.self, forKey: .clearedAt)
            lastCompacted = try c.decodeIfPresent(Int64.self, forKey: .lastCompacted)
            degraded = try c.decodeIfPresent(Bool.self, forKey: .degraded) ?? false
            pendingClear = try c.decodeIfPresent(Bool.self, forKey: .pendingClear)
            keepSession = try c.decodeIfPresent(String.self, forKey: .keepSession)
            lostAllReadableEvents = try c.decodeIfPresent(Bool.self, forKey: .lostAllReadableEvents)
        }
    }
    private struct Line {
        let event: FlintHistoryEvent?
        let raw: Data // Preserve newer fields/types/versions byte-for-byte on compaction.
        let session: String
        let at: Date?
    }
    private enum StoreError: Error { case unavailable, io, metadata, cap }

    public init(directory: URL?, clock: @escaping () -> Date = Date.init, calendar: Calendar = .current) {
        self.directory = directory; self.clock = clock; self.calendar = calendar
    }

    /// Append-only path, including in extensions: reads metadata and at most the last byte
    /// of the log, never scans history or compacts. The host performs maintenance on refresh.
    @discardableResult public func append(_ event: FlintHistoryEvent) -> Bool {
        do {
            return try locked {
                var metadata = try loadMetadata(recoverByReadingLog: false)
                // A host interrupted while clearing: reject append rather than resurrect old history.
                guard metadata.pendingClear != true else { throw StoreError.unavailable }
                let data = try encodedLine(event)
                let url = path("events.jsonl")
                let fd = open(url.path, O_CREAT | O_RDWR | O_APPEND, S_IRUSR | S_IWUSR)
                guard fd >= 0 else { throw StoreError.io }
                defer { close(fd) }
                try protect(url)
                let size = lseek(fd, 0, SEEK_END)
                guard size >= 0, size + Int64(data.count) + 2 <= Int64(Self.hardLimit) else {
                    metadata.degraded = true; try saveMetadata(metadata)
                    throw StoreError.cap
                }
                if size > 0 {
                    var tail: UInt8 = 0
                    guard pread(fd, &tail, 1, size - 1) == 1 else { throw StoreError.io }
                    if tail != 10 {
                        // Keep a torn record invalid even if it happened to end after a JSON object.
                        try writeOnce(Data([0, 10]), fd: fd)
                    }
                }
                try writeOnce(data, fd: fd)
                guard fsync(fd) == 0 else { throw StoreError.io }
                return true
            }
        } catch { return false }
    }

    public func read() -> FlintHistorySnapshot {
        do {
            return try locked {
                var metadata = try loadMetadata()
                try finishPendingClear(&metadata)
                let (lines, bad) = try readLines()
                if bad {
                    metadata.degraded = true
                    metadata.lostAllReadableEvents = lines.compactMap(\.event).isEmpty
                    try saveMetadata(metadata)
                }
                return snapshot(lines, metadata: metadata, unreadable: bad && lines.isEmpty)
            }
        } catch { return .init(events: [], historyStart: nil, availability: .unavailable) }
    }

    /// Host-only maintenance. Runs once per local day or whenever the log exceeds 1 MiB.
    @discardableResult public func compactIfNeeded() -> Bool {
        do {
            return try locked {
                var metadata = try loadMetadata()
                try finishPendingClear(&metadata)
                let now = clock()
                let bytes = (try? fm.attributesOfItem(atPath: path("events.jsonl").path)[.size] as? NSNumber)?.intValue ?? 0
                if let last = metadata.lastCompacted,
                   calendar.isDate(FlintHistoryEvent.date(last), inSameDayAs: now), bytes <= Self.softLimit {
                    return true
                }
                let (lines, bad) = try readLines()
                metadata.degraded = metadata.degraded || bad
                if bad { metadata.lostAllReadableEvents = lines.compactMap(\.event).isEmpty }
                let cutoff = calendar.date(byAdding: .day, value: -400, to: now) ?? now
                let grouped = Dictionary(grouping: lines, by: \.session)
                var retained = Set(grouped.filter { _, records in
                    records.contains { $0.at.map { $0 >= cutoff } ?? true }
                }.keys)
                var sizes = [String: Int]()
                for (id, group) in grouped { sizes[id] = group.reduce(0) { $0 + $1.raw.count + 1 } }
                var total = retained.reduce(0) { $0 + (sizes[$1] ?? 0) }
                let oldest = retained.sorted {
                    let lhs = grouped[$0]!.compactMap(\.at).max() ?? .distantFuture
                    let rhs = grouped[$1]!.compactMap(\.at).max() ?? .distantFuture
                    return lhs == rhs ? $0 < $1 : lhs < rhs
                }
                for id in oldest where total > Self.hardLimit {
                    retained.remove(id); total -= sizes[id] ?? 0
                }
                try replaceLog(lines.filter { retained.contains($0.session) })
                metadata.lastCompacted = FlintHistoryEvent.milliseconds(now)
                try saveMetadata(metadata)
                return true
            }
        } catch { return false }
    }

    @discardableResult public func clear(keepingRunningSession sessionID: String?) -> Bool {
        do {
            return try locked {
                var metadata = try loadMetadata()
                metadata.clearedAt = FlintHistoryEvent.milliseconds(clock())
                metadata.pendingClear = true; metadata.keepSession = sessionID
                try saveMetadata(metadata)
                try finishPendingClear(&metadata)
                return true
            }
        } catch { return false }
    }

    private func snapshot(_ lines: [Line], metadata: Metadata, unreadable: Bool) -> FlintHistorySnapshot {
        let events = lines.compactMap(\.event)
        let unavailable = unreadable || (metadata.lostAllReadableEvents == true && events.isEmpty)
        let availability: FlintHistoryAvailability = unavailable ? .unavailable : metadata.degraded ? .degraded :
            events.contains { $0.isSupported && $0.type == .started } ? .value : .empty
        return .init(events: events, historyStart: metadata.historyStart, availability: availability)
    }

    private func locked<T>(_ body: () throws -> T) throws -> T {
        // Neither another instance/process nor this instance can hold callers indefinitely.
        let deadline = ProcessInfo.processInfo.systemUptime + 0.2
        while !mutex.try() {
            guard ProcessInfo.processInfo.systemUptime < deadline else { throw StoreError.unavailable }
            usleep(1_000)
        }
        defer { mutex.unlock() }
        try checkCancellation()
        guard let directory else { throw StoreError.unavailable }
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        try protect(directory)
        let lockURL = path("history.lock")
        let fd = open(lockURL.path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw StoreError.io }
        defer { close(fd) }
        try protect(lockURL)
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            guard errno == EWOULDBLOCK || errno == EAGAIN,
                  ProcessInfo.processInfo.systemUptime < deadline else { throw StoreError.io }
            try checkCancellation()
            usleep(1_000)
        }
        defer { flock(fd, LOCK_UN) }
        try checkCancellation()
        return try body()
    }

    private func path(_ name: String) -> URL { directory!.appendingPathComponent(name) }
    private func protect(_ url: URL) throws {
        #if os(iOS)
        try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                             ofItemAtPath: url.path)
        #endif
    }
    private func loadMetadata(recoverByReadingLog: Bool = true) throws -> Metadata {
        let url = path("metadata.json")
        if fm.fileExists(atPath: url.path) {
            let data = try Data(contentsOf: url)
            if let metadata = try? JSONDecoder().decode(Metadata.self, from: data), metadata.v == 1 {
                return metadata
            }
            try quarantine(data, kind: "metadata")
        }
        let size = (try? fm.attributesOfItem(atPath: path("events.jsonl").path)[.size] as? NSNumber)?.intValue ?? 0
        let damaged = size > 0 || fm.fileExists(atPath: url.path)
        let earliest = recoverByReadingLog && size > 0 ? try readLines().0.compactMap(\.at).min() : nil
        // Extensions never scan history, even during metadata recovery. A conservative now
        // boundary is used until host recovery; a degraded state makes the damage explicit.
        let metadata = Metadata(createdAt: FlintHistoryEvent.milliseconds(earliest ?? clock()), degraded: damaged)
        try saveMetadata(metadata)
        return metadata
    }
    private func saveMetadata(_ metadata: Metadata) throws {
        try atomicWrite(JSONEncoder().encode(metadata), to: path("metadata.json"))
    }
    private func encodedLine(_ event: FlintHistoryEvent) throws -> Data {
        var data = try JSONEncoder().encode(event)
        if event.type == .started, event.plannedEnd == nil,
           var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            object["plannedEnd"] = NSNull()
            data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        }
        data.append(10); return data
    }
    private func readLines() throws -> ([Line], Bool) {
        let url = path("events.jsonl")
        guard fm.fileExists(atPath: url.path) else { return ([], false) }
        let data: Data
        do { data = try Data(contentsOf: url) }
        catch {
            // Only replace an unreadable log if an exact quarantine copy can be preserved.
            let folder = path("quarantine")
            try fm.createDirectory(at: folder, withIntermediateDirectories: true)
            let backup = folder.appendingPathComponent("\(FlintHistoryEvent.milliseconds(clock()))-unreadable.jsonl")
            try fm.copyItem(at: url, to: backup)
            try protect(backup)
            try atomicWrite(Data(), to: url)
            return ([], true)
        }
        var lines: [Line] = []; var bad = false
        var start = data.startIndex
        for end in data.indices where data[end] == 10 {
            try checkCancellation()
            let raw = data.subdata(in: start..<end)
            if let object = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any] {
                let event = try? JSONDecoder().decode(FlintHistoryEvent.self, from: raw)
                let known = event?.isSupported == true
                let future = (object["v"] as? Int ?? 0) > 1
                let unknownType: Bool
                if let type = object["type"] as? String {
                    let supported = [FlintHistoryEventType.started, .completed, .endedEarly, .superseded,
                                     .breakStarted, .breakEnded, .passUsed, .failedToArm, .openIntercepted]
                    unknownType = !supported.contains { $0.wireName == type }
                } else { unknownType = false }
                if known || future || unknownType {
                    let identity = object["session"] as? String ?? "opaque:\(SHA256.hash(data: raw).description)"
                    let at = (object["at"] as? Int64).map(FlintHistoryEvent.date)
                    lines.append(Line(event: known ? event : nil, raw: raw, session: identity, at: at))
                } else { bad = true }
            } else { bad = true }
            start = data.index(after: end)
        }
        if start < data.endIndex { bad = true }
        if bad { try quarantine(data) }
        return (lines, bad)
    }
    private func quarantine(_ data: Data, kind: String = "events") throws {
        let folder = path("quarantine")
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        try protect(folder)
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let existing = try fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
        if !existing.contains(where: { $0.lastPathComponent.contains(digest) }) {
            let url = folder.appendingPathComponent("\(FlintHistoryEvent.milliseconds(clock()))-\(kind)-\(digest).jsonl")
            try atomicWrite(data, to: url)
        }
        let copies = try fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.creationDateKey])
            .sorted { ((try? $0.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast) <
                      ((try? $1.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? .distantPast) }
        for old in copies.prefix(max(0, copies.count - 3)) { try fm.removeItem(at: old) }
    }
    private func finishPendingClear(_ metadata: inout Metadata) throws {
        guard metadata.pendingClear == true else { return }
        let (lines, _) = try readLines()
        try replaceLog(lines.filter { $0.session == metadata.keepSession })
        metadata.pendingClear = nil; metadata.keepSession = nil
        metadata.degraded = false; metadata.lostAllReadableEvents = nil; metadata.lastCompacted = nil
        try saveMetadata(metadata)
    }
    private func replaceLog(_ lines: [Line]) throws {
        var data = Data()
        for line in lines { try checkCancellation(); data.append(line.raw); data.append(10) }
        try atomicWrite(data, to: path("events.jsonl"))
    }
    private func atomicWrite(_ data: Data, to url: URL) throws {
        try checkCancellation()
        let temp = url.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).tmp")
        defer { try? fm.removeItem(at: temp) }
        #if os(iOS)
        try data.write(to: temp, options: .completeFileProtectionUntilFirstUserAuthentication)
        #else
        try data.write(to: temp)
        #endif
        try protect(temp)
        let fd = open(temp.path, O_RDONLY)
        guard fd >= 0 else { throw StoreError.io }
        let result = fsync(fd); close(fd)
        guard result == 0, rename(temp.path, url.path) == 0 else { throw StoreError.io }
    }
    private func writeOnce(_ data: Data, fd: Int32) throws {
        try checkCancellation()
        let written = data.withUnsafeBytes { Darwin.write(fd, $0.baseAddress, $0.count) }
        guard written == data.count else { throw StoreError.io }
    }

    private func checkCancellation() throws {
        if let activity = Thread.current.threadDictionary[FlintHistoryActivity.key] as? FlintHistoryActivity,
           activity.expired { throw StoreError.unavailable }
    }
}

private final class FlintHistoryActivity: @unchecked Sendable {
    static let key = "app.flint.history.activity"
    private let lock = NSLock()
    private var cancelled = false
    private var began = false
    var expired: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
    func begin() -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled else { return false }
        began = true; return true
    }
    /// Only release the serial queue here if work has not started. Otherwise the running
    /// block cooperatively releases its lock and signals from its defer.
    func cancelBeforeStart() -> Bool {
        lock.lock(); defer { lock.unlock() }
        cancelled = true; return !began
    }
}

/// A single ordered queue per process ensures a quick start/end pair retains emission order.
public final class FlintHistorySink: FlintSessionEventSink, @unchecked Sendable {
    public static let shared = FlintHistorySink(store: .shared)
    public let store: FlintHistoryStore
    private let isExtension: Bool
    private let queue = DispatchQueue(label: "app.flint.history", qos: .utility)

    public init(store: FlintHistoryStore, isExtension: Bool = Bundle.main.bundlePath.hasSuffix(".appex")) {
        self.store = store; self.isExtension = isExtension
    }
    public func handle(_ event: FlintSessionLifecycleEvent) {
        if isExtension {
            // The callback is the extension's execution lifetime. A bounded append must finish
            // before it returns; background queue work could otherwise vanish on termination.
            store.append(FlintHistoryEvent(event))
        } else {
            perform { $0.append(FlintHistoryEvent(event)) }
        }
    }
    /// UI refreshes enqueue after emissions, without blocking the caller or holding a file lock.
    public func perform(_ operation: @escaping (FlintHistoryStore) -> Void) {
        queue.async { [store] in
            #if os(iOS)
            let activity = FlintHistoryActivity()
            let done = DispatchSemaphore(value: 0)
            ProcessInfo.processInfo.performExpiringActivity(withReason: "Save local focus history") { expired in
                if expired { if activity.cancelBeforeStart() { done.signal() }; return }
                guard activity.begin() else { return }
                Thread.current.threadDictionary[FlintHistoryActivity.key] = activity
                defer {
                    Thread.current.threadDictionary.removeObject(forKey: FlintHistoryActivity.key)
                    done.signal()
                }
                operation(store)
            }
            // Wait on the worker queue only, with no file lock held. Keeps host emissions ordered.
            done.wait()
            #else
            operation(store)
            #endif
        }
    }
}
