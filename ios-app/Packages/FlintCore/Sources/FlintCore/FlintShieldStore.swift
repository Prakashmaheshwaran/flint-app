import Foundation
#if canImport(ManagedSettings)
import ManagedSettings
import FamilyControls
#endif

/// The ~50-token shield cap is real and **fails silently**: assigning more than this to a
/// shield property shields *nothing* with no error. Guard against it explicitly.
public enum FlintShieldLimits {
    public static let tokenCap = 50

    /// True if a selection of this size can be shielded by app/web token (vs. needing categories).
    public static func withinCap(_ count: Int) -> Bool { count <= tokenCap }
}

public enum FlintShieldError: Error, Equatable, LocalizedError {
    case tooManyTokens(count: Int, cap: Int)
    case recoverySuspended
    case protectionStateUnavailable
    case transitionBusy

    public var errorDescription: String? {
        switch self {
        case let .tooManyTokens(count, cap): return "Too many targets (\(count)); the system limit is \(cap)."
        case .recoverySuspended: return "Resume protection first"
        case .protectionStateUnavailable: return "Protection state unavailable. No new shield was applied."
        case .transitionBusy: return "Protection is busy. Try again."
        }
    }
}

#if canImport(ManagedSettings)
/// Thin wrapper over a named `ManagedSettingsStore`. Shields are written from the
/// `DeviceActivityMonitor` extension (so blocking survives the app being closed); this wrapper
/// is shared so the app, monitor, and shield-action extensions speak the same store.
public final class FlintShieldStore {
    private let store: ManagedSettingsStore
    private let name: String

    public init(named name: String = "flint") {
        self.store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
        self.name = name
    }

    /// Apply a block-list shield. Throws if a token set exceeds the silent cap.
    public func applyBlockList(
        applications: Set<ApplicationToken>,
        categories: Set<ActivityCategoryToken> = [],
        webDomains: Set<WebDomainToken> = []
    ) throws {
        guard FlintShieldLimits.withinCap(applications.count) else {
            throw FlintShieldError.tooManyTokens(count: applications.count, cap: FlintShieldLimits.tokenCap)
        }
        guard FlintShieldLimits.withinCap(webDomains.count) else {
            throw FlintShieldError.tooManyTokens(count: webDomains.count, cap: FlintShieldLimits.tokenCap)
        }
        guard let _ = try FlintSessionController().withTransition({
            switch FlintPassRecoveryGate.read() {
            case .enforcing: break
            case .suspended: throw FlintShieldError.recoverySuspended
            case .unavailable: throw FlintShieldError.protectionStateUnavailable
            }
            var selection = FamilyActivitySelection()
            selection.applicationTokens = applications; selection.categoryTokens = categories; selection.webDomainTokens = webDomains
            let effective = FlintOrdinaryBreaks.filteredSelection(selection, storeName: name)
            store.shield.applications = effective.applicationTokens.isEmpty ? nil : effective.applicationTokens
            store.shield.applicationCategories = effective.categoryTokens.isEmpty ? nil : .specific(effective.categoryTokens)
            store.shield.webDomains = effective.webDomainTokens.isEmpty ? nil : effective.webDomainTokens
        }) else { throw FlintShieldError.transitionBusy }
    }

    /// Allow-list ("brick phone"): shield ALL apps except the allowed applications.
    /// For `applicationCategories`, `.all(except:)` excludes specific `ApplicationToken`s
    /// (the apps to leave usable) — not category tokens.
    public func applyAllowList(allowedApplications: Set<ApplicationToken> = []) {
        _ = FlintSessionController().withTransition {
            guard FlintPassRecoveryGate.read().permitsApplication else { return }
            var selection = FamilyActivitySelection(); selection.applicationTokens = allowedApplications
            let effective = FlintOrdinaryBreaks.filteredSelection(selection, storeName: name, allowList: true)
            store.shield.applicationCategories = .all(except: effective.applicationTokens)
            store.shield.webDomainCategories = .all()
        }
    }

    /// Clear all session-owned keys, including historical allow-list and Safari settings.
    /// The old denyAppRemoval value is retired ONLY by the validated derived migration;
    /// a whole-store clear here would release it even when another owner's read failed.
    public func clear() {
        store.shield.applications = nil
        store.shield.applicationCategories = nil
        store.shield.webDomains = nil
        store.shield.webDomainCategories = nil
        store.webContent.blockedByFilter = nil
        store.media.denyExplicitContent = nil
    }
}
#endif
