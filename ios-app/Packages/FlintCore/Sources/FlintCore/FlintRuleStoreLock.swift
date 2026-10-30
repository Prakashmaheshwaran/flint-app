import Foundation
import Darwin
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)

/// Serializes host/extension read–check–arm–save operations. Easy mutations need no ledger,
/// but still need a cross-process revision check. Never falls back to an unlocked mutation.
enum FlintRuleStoreLock {
    private static let local = NSRecursiveLock()
    private final class AfterUnlock: NSObject { var actions: [() -> Void] = [] }
    static func withLock<T>(environment: FlintRuleReloadEnvironment, _ body: () -> T) -> T? {
        guard let directory = environment.lockDirectory else { return nil }
        let key = "FlintRuleStoreLock." + directory.path
        if Thread.current.threadDictionary[key] != nil { return body() }
        guard prepareInitialization(environment: environment) else { return nil }
        let afterKey = key + ".afterUnlock"
        let after = AfterUnlock()
        Thread.current.threadDictionary[afterKey] = after
        var result: T?
        let acquired = environment.withTransitionBoundary({ result = domainLock(directory: directory, body) })
        Thread.current.threadDictionary.removeObject(forKey: afterKey)
        after.actions.forEach { $0() }
        return acquired ? result : nil
    }

    @discardableResult
    static func prepareInitialization(environment: FlintRuleReloadEnvironment,
                                      timeout: TimeInterval = Thread.isMainThread ? 0 : 2) -> Bool {
        guard let defaults = environment.defaults else { return false }
        switch FlintRuleInitialization.receipt(in: defaults) {
        case .complete: return true
        case .invalid: return false
        case .absent:
            // Each domain has its own evidence. A sibling's presence cannot skip another
            // domain's one-time upgrade initialization.
            if FlintRuleStoreKey.allCases.allSatisfy({ defaults.object(forKey: $0.marker) != nil
                || (defaults.object(forKey: $0.rawValue) != nil && defaults.object(forKey: $0.ownership) != nil) }) { return true }
        case .preparing: break
        }
        // Initialization takes only the domain lock, never a ledger/session lock. Taking it
        // before withTransitionBoundary prevents first-launch ledger creation from becoming
        // false legacy evidence. Upgrade evidence uses only read-only ledger peek, no lock.
        return domainLock(directory: environment.lockDirectory, timeout: timeout) {
            FlintRuleInitialization.prepare(environment: environment)
        } ?? false
    }

    static func afterUnlock(environment: FlintRuleReloadEnvironment, _ action: @escaping () -> Void) {
        let key = "FlintRuleStoreLock." + (environment.lockDirectory?.path ?? "") + ".afterUnlock"
        if let pending = Thread.current.threadDictionary[key] as? AfterUnlock { pending.actions.append(action) }
        else { action() }
    }

    private static func domainLock<T>(directory: URL?, timeout: TimeInterval = 2, _ body: () -> T) -> T? {
        guard let directory else { return nil }
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        while !local.try() {
            guard ProcessInfo.processInfo.systemUptime < deadline else { return nil }
            usleep(10_000)
        }
        defer { local.unlock() }
        let key = "FlintRuleStoreLock." + directory.path
        if Thread.current.threadDictionary[key] != nil { return body() }
        do { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
        catch { return nil }
        let fd = open(directory.appendingPathComponent("mutation.lock").path, O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
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
#endif
