import Foundation
import Darwin

/// Serializes host/extension read–check–arm–save operations. Easy mutations need no ledger,
/// but still need a cross-process revision check. Never falls back to an unlocked mutation.
enum FlintRuleStoreLock {
    private static let local = NSRecursiveLock()
    static func withLock<T>(directory: URL?, _ body: () -> T) -> T? {
        guard let directory else { return nil }
        local.lock(); defer { local.unlock() }
        let key = "FlintRuleStoreLock." + directory.path
        if Thread.current.threadDictionary[key] != nil { return body() }
        do { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
        catch { return nil }
        let fd = open(directory.appendingPathComponent("mutation.lock").path, O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        let deadline = ProcessInfo.processInfo.systemUptime + 2
        while flock(fd, LOCK_EX | LOCK_NB) != 0 {
            guard errno == EWOULDBLOCK || errno == EAGAIN,
                  ProcessInfo.processInfo.systemUptime < deadline else { return nil }
            usleep(10_000)
        }
        defer { flock(fd, LOCK_UN) }
        Thread.current.threadDictionary[key] = true
        defer { Thread.current.threadDictionary.removeObject(forKey: key) }
        return body()
    }
}
