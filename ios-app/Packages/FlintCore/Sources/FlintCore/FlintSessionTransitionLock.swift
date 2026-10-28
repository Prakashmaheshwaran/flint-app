import Foundation

/// A cross-process exclusive lock for session transitions (ADR-012 §8; red-team R-20). The host
/// and the DeviceActivityMonitor extension both start, end and re-arm the one session; a
/// process-local lock can't stop an old registration's callback in the extension from clearing a
/// replacement the host persisted a moment earlier. Every transition holds this lock and re-reads
/// the record inside it.
///
/// A dedicated file, not the ledger's lock: Harder-gate ledger transactions run inside a session
/// transition, and the ledger refuses nesting. Lock order is always session, then ledger.
public final class FlintSessionTransitionLock {
    private let url: URL
    private let timeout: TimeInterval

    public init(directory: URL, timeout: TimeInterval = 2) {
        url = directory.appendingPathComponent("session.lock")
        self.timeout = timeout
    }

    /// The App Group lock; nil when the container is unavailable.
    public static func appGroup() -> FlintSessionTransitionLock? {
        guard let container = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: FlintGroupStore.appGroupID) else { return nil }
        return FlintSessionTransitionLock(directory: container.appendingPathComponent("FlintLedger", isDirectory: true))
    }

    /// Acquire the lock, waiting up to the timeout; nil when it can't be had. Release the
    /// returned hold with `end()` (idempotent).
    public func acquire() -> Hold? {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let fd = open(url.path, O_RDWR | O_CREAT | O_CLOEXEC, 0o600)
        guard fd >= 0 else { return nil }
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            guard errno == EWOULDBLOCK || errno == EINTR,
                  ProcessInfo.processInfo.systemUptime < deadline else { close(fd); return nil }
            usleep(10_000)
        }
        return Hold(fd: fd)
    }

    public final class Hold {
        private var fd: Int32?
        init(fd: Int32?) { self.fd = fd }
        /// A hold that owns nothing (a nested transition in the same process).
        static var nested: Hold { Hold(fd: nil) }
        public func end() {
            guard let fd else { return }
            flock(fd, LOCK_UN)
            close(fd)
            self.fd = nil
        }
        deinit { end() }
    }
}
