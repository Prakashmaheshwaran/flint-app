import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// Preference decisions share the exact authority and inventory used by enforcement.
public struct FlintRemovalGuardSettings {
    public static let disclosure = "This asks Screen Time to restrict app deletion device-wide, including apps other than Flint. Screen Time permission can still be revoked in iOS Settings. The guard ends when the finite Hardcore block ends, and the Emergency Pass releases it. Recovery is in Flint → You → Emergency Pass, or iOS Settings → Screen Time. Availability and behavior vary by iOS version; Flint cannot promise that every version honors the restriction."

    public struct Status {
        public init() {}
        public var enabled = false
        public var label = "Off"
        public var pendingOff = false
        public var unavailable = false
    }
    public enum Change: Equatable { case saved, deferred(until: Date), unavailable, disclosureRequired }
    private let environment: FlintRemovalGuardReconciler.Environment
    public init() { environment = .live }
    init(environment: FlintRemovalGuardReconciler.Environment) { self.environment = environment }

    /// Called only at serialized acceptance. Unreadable intent is off for NEW captures.
    static func capture(defaults: UserDefaults? = .flintGroup) -> Bool {
        guard let defaults else { return false }
        return FlintRemovalGuardPreference.Store(defaults: defaults).load().captureForNewCommitment
    }

    public func status() -> Status {
        let report = FlintRemovalGuardReconciler(environment: environment).reconcile()
        var result = Status()
        let acquired = environment.withAuthority {
            guard let store = environment.preference else { result.unavailable = true; result.label = "Protection state unavailable"; return }
            switch store.load() {
            case .absent: break
            case .unavailable: result.unavailable = true; result.label = "Protection state unavailable"
            case let .value(value, _):
                result.enabled = value.enabled
                result.pendingOff = value.deferredOffUntil != nil
                if value.enabled { result.label = "Requested" }
            }
            if !environment.isAuthorized() {
                result.label = "Unavailable (Screen Time not authorized)"
            } else if report.decision == .keep || (report.applied && !report.migrated) {
                result.unavailable = true
                result.label = "Protection state unavailable"
            } else if report.applied, report.decision == .deny, let end = report.until {
                result.label = "On until " + FlintEmergencyPassStatus.dateText(end)
            }
        }
        if !acquired { result.unavailable = true; result.label = "Protection state unavailable" }
        return result
    }

    public func request(enabled: Bool, disclosureAccepted: Bool = false) -> Change {
        if enabled && !disclosureAccepted { return .disclosureRequired }
        var result: Change = .unavailable
        _ = environment.withAuthority {
            guard let store = environment.preference else { return }
            let old: FlintRemovalGuardPreference
            switch store.load() {
            case .absent: old = .init()
            case let .value(value, _): old = value
            case .unavailable: return
            }
            let clock = FlintLedgerInstant(clock: environment.clock).clock
            let decision = FlintRemovalGuard.decidePreferenceChange(to: enabled,
                owners: environment.inventory(clock), clock: clock)
            let next: FlintRemovalGuardPreference
            switch decision {
            case .allow:
                next = enabled ? .init(enabled: true, disclosedAt: clock.now(),
                    disclosureVersion: FlintRemovalGuardPreference.currentDisclosureVersion)
                    : .init(enabled: false, disclosedAt: old.disclosedAt, disclosureVersion: old.disclosureVersion)
                result = .saved
            case let .deferUntil(end):
                // Consent must already exist before a captured Hardcore guard can defer off.
                guard old.enabled, old.disclosedAt != nil else { return }
                next = .init(enabled: true, disclosedAt: old.disclosedAt,
                    disclosureVersion: old.disclosureVersion, deferredOffUntil: end)
                result = .deferred(until: end)
            default: return
            }
            do { try store.save(next) } catch { result = .unavailable }
        }
        if result != .unavailable { _ = FlintRemovalGuardReconciler(environment: environment).reconcile() }
        return result
    }
}
#endif
