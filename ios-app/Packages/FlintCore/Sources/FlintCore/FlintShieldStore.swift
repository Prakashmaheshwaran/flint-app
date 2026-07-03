import Foundation
#if canImport(ManagedSettings)
import ManagedSettings
#endif

/// The ~50-token shield cap is real and **fails silently**: assigning more than this to a
/// shield property shields *nothing* with no error. Guard against it explicitly.
public enum FlintShieldLimits {
    public static let tokenCap = 50

    /// True if a selection of this size can be shielded by app/web token (vs. needing categories).
    public static func withinCap(_ count: Int) -> Bool { count <= tokenCap }
}

public enum FlintShieldError: Error, Equatable {
    case tooManyTokens(count: Int, cap: Int)
}

#if canImport(ManagedSettings)
/// Thin wrapper over a named `ManagedSettingsStore`. Shields are written from the
/// `DeviceActivityMonitor` extension (so blocking survives the app being closed); this wrapper
/// is shared so the app, monitor, and shield-action extensions speak the same store.
public final class FlintShieldStore {
    private let store: ManagedSettingsStore

    public init(named name: String = "flint") {
        self.store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
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
        store.shield.applications = applications.isEmpty ? nil : applications
        store.shield.applicationCategories = categories.isEmpty ? nil : .specific(categories)
        store.shield.webDomains = webDomains.isEmpty ? nil : webDomains
    }

    /// Allow-list ("brick phone"): shield ALL apps except the allowed applications.
    /// For `applicationCategories`, `.all(except:)` excludes specific `ApplicationToken`s
    /// (the apps to leave usable) — not category tokens.
    public func applyAllowList(allowedApplications: Set<ApplicationToken> = []) {
        store.shield.applicationCategories = .all(except: allowedApplications)
        store.shield.webDomainCategories = .all()
    }

    /// The Hardcore **uninstall guard**: while set, iOS blocks deleting apps (device-wide —
    /// Apple's setting isn't per-app), including Flint itself, which is otherwise the one-tap
    /// escape from a Hardcore block. `false` writes nil (this store's "don't assert"
    /// convention), so a non-Hardcore start can never leave a stale guard behind. Lives on
    /// this store so `clear()` / `clearAllSettings()` drops it together with the shield on
    /// every end path. See `FlintUninstallGuard` for the when.
    public func setDenyAppRemoval(_ deny: Bool) {
        store.application.denyAppRemoval = deny ? true : nil
    }

    public func clear() {
        store.clearAllSettings()
    }
}
#endif
