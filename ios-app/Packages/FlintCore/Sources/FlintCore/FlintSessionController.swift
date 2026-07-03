import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

public enum FlintSessionError: Error, LocalizedError {
    case appGroupUnavailable
    case emptySelection
    case tooManyTokens(count: Int, cap: Int)

    public var errorDescription: String? {
        switch self {
        case .appGroupUnavailable: return "Flint's shared storage is unavailable (App Group misconfigured)."
        case .emptySelection: return "Pick at least one app or website to block first."
        case let .tooManyTokens(count, cap): return "Too many items selected (\(count)). The system limit is \(cap) — use a category instead."
        }
    }
}

/// Orchestrates a "Block Now" session end-to-end: persist the selection, apply shields
/// immediately (instant feedback), persist the `FlintActiveSession`, and register a
/// `DeviceActivity` schedule so the monitor extension clears the shield at the end time even if
/// the app is closed/killed.
public final class FlintSessionController {

    public static let monitorName = "flint.session"

    private let shield = FlintShieldStore(named: "flint")

    public init() {}

    public var activeSession: FlintActiveSession? {
        FlintGroupStore()?.loadActiveSession()
    }

    /// Start an immediate block. `duration <= 0` means open-ended (until manually stopped).
    @discardableResult
    public func startBlockNow(
        selection: FamilyActivitySelection,
        duration: TimeInterval,
        breakLevel: BreakLevel,
        name: String = "Focus"
    ) throws -> FlintActiveSession {
        guard let group = FlintGroupStore() else { throw FlintSessionError.appGroupUnavailable }

        let appCount = selection.applicationTokens.count
        let webCount = selection.webDomainTokens.count
        let catCount = selection.categoryTokens.count
        guard appCount + webCount + catCount > 0 else { throw FlintSessionError.emptySelection }
        if appCount > FlintShieldLimits.tokenCap {
            throw FlintSessionError.tooManyTokens(count: appCount, cap: FlintShieldLimits.tokenCap)
        }

        group.saveSelection(selection)

        // Apply immediately so the user sees the block take effect now (on device).
        try shield.applyBlockList(
            applications: selection.applicationTokens,
            categories: selection.categoryTokens,
            webDomains: selection.webDomainTokens
        )

        // Uninstall guard: a Hardcore block also denies app removal (deleting Flint is
        // otherwise the one-tap escape). Same store as the shield, so every teardown path —
        // stop(), the Emergency Pass, the monitor's intervalDidEnd — drops both together via
        // clearAllSettings(). Non-Hardcore starts assert nil, so replacing a Hardcore session
        // can't leave the guard dangling.
        shield.setDenyAppRemoval(breakLevel == .hardcore)

        let now = Date()
        let endsAt = duration > 0 ? now.addingTimeInterval(duration) : nil
        let session = FlintActiveSession(
            name: name,
            startedAt: now,
            endsAt: endsAt,
            breakLevel: breakLevel,
            monitorName: Self.monitorName
        )
        group.saveActiveSession(session)

        // Register an auto-clear schedule so enforcement ends on time without the app running.
        if let endsAt {
            let calendar = Calendar.current
            let startComps = calendar.dateComponents([.hour, .minute, .second], from: now)
            let endComps = calendar.dateComponents([.hour, .minute, .second], from: endsAt)
            let schedule = DeviceActivitySchedule(
                intervalStart: startComps,
                intervalEnd: endComps,
                repeats: false
            )
            // Best-effort: the immediate shield is already applied; this owns the clean teardown.
            try? FlintScheduling.startMonitoring(Self.monitorName, during: schedule)
        }

        return session
    }

    /// Whether the active session is allowed to be stopped right now.
    /// Hardcore can't be stopped early (until it expires, or — later — an Emergency Pass is spent).
    public func canStop(_ session: FlintActiveSession) -> Bool {
        switch session.breakLevel {
        case .easy, .harder:
            return true
        case .hardcore:
            return session.isExpired
        }
    }

    /// Tear down the active session: clear shields — which also drops the Hardcore uninstall
    /// guard living on the same store — stop monitoring, forget the session.
    public func stop() {
        shield.clear()
        if let session = FlintGroupStore()?.loadActiveSession() {
            FlintScheduling.stopMonitoring([session.monitorName])
        }
        FlintGroupStore()?.clearActiveSession()
    }

    /// Called when a timed session reaches its end (or the app notices it expired): clean up.
    public func finishIfExpired() {
        guard let session = activeSession, session.isExpired else { return }
        stop()
    }

    /// Launch-time reconciliation for the uninstall guard: finish an expired session first
    /// (full teardown), then make `denyAppRemoval` match reality — re-asserted while a
    /// Hardcore session is still running, dropped otherwise. Belt-and-braces for the one
    /// end path the app can't see happen: the monitor's `intervalDidEnd` failing to fire
    /// (e.g. the device was powered off across the boundary).
    public func reconcileUninstallGuard() {
        finishIfExpired()
        shield.setDenyAppRemoval(FlintUninstallGuard.shouldDeny(for: activeSession))
    }

    // MARK: Emergency pass (free, one per week)

    public func emergencyPassAvailable(now: Date = Date()) -> Bool {
        FlintEmergencyPass.isAvailable(lastUsed: FlintGroupStore()?.emergencyPassLastUsed(), now: now)
    }

    /// Spend the weekly Emergency Pass to end a running Hardcore session early. Returns false if
    /// there's no eligible Hardcore session or the pass isn't available yet.
    @discardableResult
    public func useEmergencyPass() -> Bool {
        guard let session = activeSession, session.breakLevel == .hardcore, !session.isExpired else {
            return false
        }
        guard emergencyPassAvailable() else { return false }
        FlintGroupStore()?.recordEmergencyPassUse()
        stop()
        return true
    }
}
#endif
