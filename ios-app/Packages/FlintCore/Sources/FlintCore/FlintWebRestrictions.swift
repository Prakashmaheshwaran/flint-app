import Foundation
#if canImport(ManagedSettings)
import ManagedSettings
#endif

// MARK: - Web filter mode

/// How Flint restricts Safari web content while a session is active. There is no iOS API to
/// disable Private Browsing on its own — Apple disables it (and locks "Clear History and Website
/// Data") as a *side effect* of any active web-content filter. So every non-off mode here is also
/// a Private-Browsing lockdown. We model that honestly rather than pretending it's independent.
public enum FlintWebFilter: String, Codable, CaseIterable, Sendable {
    /// Apple's automatic "Limit Adult Websites" filter, plus any manually-typed extra blocked /
    /// always-allowed domains. The everyday default.
    case limitAdult
    /// "Allowed Websites Only": Safari can reach *only* the user's allowed domains; everything
    /// else is blocked. The strictest mode.
    case allowedOnly

    /// Human-facing label for the picker.
    public var title: String {
        switch self {
        case .limitAdult: return "Limit adult websites"
        case .allowedOnly: return "Allowed sites only"
        }
    }

    /// One-line explanation of what the mode does.
    public var detail: String {
        switch self {
        case .limitAdult:
            return "Filters adult content and any sites you add below."
        case .allowedOnly:
            return "Blocks every site except the ones you allow below."
        }
    }
}

// MARK: - Configuration (pure data, App-Group persisted)

/// User configuration for Safari content restrictions + Private-Browsing lockdown (Opal features
/// 1.8 & 1.9 — both FREE in Flint). Pure `Foundation` so the host app, the system extensions, and
/// the unit tests all share one definition; the actual enforcement lives in
/// `FlintWebRestrictionStore` behind `canImport(ManagedSettings)`.
///
/// Each opted-in cause writes into its own shield store. Ending that cause clears its whole
/// store; edits reconcile only the web keys, preserving shields and the uninstall guard.
public struct FlintWebRestrictions: Codable, Equatable, Sendable {
    /// Apply during focus sessions (Block Now, Shortcuts/Pomodoro and Focus filters).
    public var enabled: Bool
    /// Independently opt schedules and Sleep into the same Safari filter. Off for older configs.
    public var applyDuringSchedules: Bool
    /// Which filtering mode to apply while enabled.
    public var filter: FlintWebFilter
    /// In `.limitAdult`: extra domains to always block ("Never Allow"). Ignored in `.allowedOnly`
    /// (where everything not allowed is already blocked).
    public var blockedDomains: [String]
    /// In `.limitAdult`: domains to always permit despite the filter ("Always Allow").
    /// In `.allowedOnly`: the entire set of sites Safari may reach.
    public var allowedDomains: [String]
    /// Also deny explicit music / podcast content (Apple Media content restriction) for the session.
    public var denyExplicitMedia: Bool

    public init(
        enabled: Bool = false,
        applyDuringSchedules: Bool = false,
        filter: FlintWebFilter = .limitAdult,
        blockedDomains: [String] = [],
        allowedDomains: [String] = [],
        denyExplicitMedia: Bool = false
    ) {
        self.enabled = enabled
        self.applyDuringSchedules = applyDuringSchedules
        self.filter = filter
        self.blockedDomains = blockedDomains
        self.allowedDomains = allowedDomains
        self.denyExplicitMedia = denyExplicitMedia
    }

    private enum CodingKeys: String, CodingKey {
        case enabled, applyDuringSchedules, filter, blockedDomains, allowedDomains, denyExplicitMedia
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try values.decode(Bool.self, forKey: .enabled)
        applyDuringSchedules = try values.decodeIfPresent(Bool.self, forKey: .applyDuringSchedules) ?? false
        filter = try values.decode(FlintWebFilter.self, forKey: .filter)
        blockedDomains = try values.decode([String].self, forKey: .blockedDomains)
        allowedDomains = try values.decode([String].self, forKey: .allowedDomains)
        denyExplicitMedia = try values.decode(Bool.self, forKey: .denyExplicitMedia)
    }

    /// Off by default — the user opts in. Adult filtering is the mode they get when they do.
    public static let `default` = FlintWebRestrictions()

    // MARK: Honest derived facts (used by the UI to tell the truth about side effects)

    /// Expected side effect while an opted-in cause's filter is active; not a live OS observation.
    public var disablesPrivateBrowsing: Bool { enabled || applyDuringSchedules }

    /// True when applying this config also locks "Clear History and Website Data" in Safari —
    /// iOS gates that button behind the same web-content restriction.
    public var locksHistoryClearing: Bool { enabled || applyDuringSchedules }

    // MARK: Normalized domain views (what actually gets enforced)

    /// `blockedDomains` cleaned + de-duplicated. Only meaningful in `.limitAdult`.
    public var normalizedBlockedDomains: [String] { Self.normalizedDomains(blockedDomains) }

    /// `allowedDomains` cleaned + de-duplicated.
    public var normalizedAllowedDomains: [String] { Self.normalizedDomains(allowedDomains) }

    public var validationMessage: String? {
        guard normalizedAllowedDomains.count <= FlintShieldLimits.tokenCap,
              normalizedBlockedDomains.count <= FlintShieldLimits.tokenCap else {
            return "Use at most \(FlintShieldLimits.tokenCap) sites in each website list. Remove sites before saving."
        }
        return nil
    }

    // MARK: Mutating helpers (used by the editor)

    /// Add a typed domain to the relevant list, normalizing first. Returns the normalized value
    /// that was stored, or nil if the input wasn't a valid host or was already present.
    @discardableResult
    public mutating func addBlockedDomain(_ raw: String) -> String? {
        guard let d = Self.normalizedDomain(raw), !normalizedBlockedDomains.contains(d) else { return nil }
        blockedDomains.append(d)
        return d
    }

    @discardableResult
    public mutating func addAllowedDomain(_ raw: String) -> String? {
        guard let d = Self.normalizedDomain(raw), !normalizedAllowedDomains.contains(d) else { return nil }
        allowedDomains.append(d)
        return d
    }

    // MARK: Domain normalization (pure + fully unit-testable)

    /// Reduce free-form user input ("https://www.Reddit.com/r/x?y=1") to a bare, lower-cased host
    /// ("reddit.com") suitable for `WebDomain(domain:)`. Returns nil if the result isn't a plausible
    /// hostname (no dot, bad characters, IP literal, empty label, numeric TLD, …).
    public static func normalizedDomain(_ raw: String) -> String? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !s.isEmpty else { return nil }

        if let scheme = s.range(of: "://") { s = String(s[scheme.upperBound...]) } // drop scheme
        if let slash = s.firstIndex(of: "/") { s = String(s[..<slash]) }           // drop path
        if let q = s.firstIndex(of: "?") { s = String(s[..<q]) }                   // drop bare query
        if let hash = s.firstIndex(of: "#") { s = String(s[..<hash]) }             // drop fragment
        if let at = s.lastIndex(of: "@") { s = String(s[s.index(after: at)...]) }  // drop userinfo
        if let colon = s.firstIndex(of: ":") { s = String(s[..<colon]) }           // drop port
        if s.hasPrefix("www.") { s = String(s.dropFirst(4)) }

        return isValidHost(s) ? s : nil
    }

    /// Normalize a list, dropping invalids and duplicates while preserving first-seen order.
    public static func normalizedDomains(_ list: [String]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for raw in list {
            guard let d = normalizedDomain(raw), seen.insert(d).inserted else { continue }
            out.append(d)
        }
        return out
    }

    /// RFC-ish hostname validity: ASCII labels of letters/digits/hyphen, at least one dot, an
    /// alphabetic TLD. Deliberately rejects IP literals and single-label hosts — Screen Time filters
    /// by registrable domain, not address.
    static func isValidHost(_ s: String) -> Bool {
        guard s.count <= 253, s.contains("."), !s.hasPrefix("."), !s.hasSuffix("."), !s.contains("..") else {
            return false
        }
        let labels = s.split(separator: ".", omittingEmptySubsequences: false)
        for label in labels {
            guard (1...63).contains(label.count), !label.hasPrefix("-"), !label.hasSuffix("-") else { return false }
            for ch in label where !(ch.isASCII && (ch.isLetter || ch.isNumber || ch == "-")) { return false }
        }
        guard let tld = labels.last, tld.count >= 2, tld.allSatisfy({ $0.isASCII && $0.isLetter }) else { return false }
        return true
    }
}

// MARK: - App Group persistence

extension FlintWebRestrictions {
    /// Namespaced under "flint.*" to match `FlintGroupStore`'s key convention. Lives outside
    /// `FlintGroupStore` itself because that file is a shared/locked scaffold file — same suite,
    /// same JSON, no edits to the hot file (mirrors how the Wave-1 stubs persist).
    private static let storageKey = "flint.webRestrictions"

    public static func load(from defaults: UserDefaults? = .flintGroup) -> FlintWebRestrictions {
        loadForEnforcement(from: defaults) ?? .default
    }

    /// Missing preferences mean the default opt-out. Unreadable saved data or unavailable
    /// shared storage are unknown intent: production callers must preserve last-good keys,
    /// rather than treating a failed decode as the user's instruction to disable protection.
    public static func loadForEnforcement(from defaults: UserDefaults? = .flintGroup) -> FlintWebRestrictions? {
        guard let defaults else { return nil }
        guard let stored = defaults.object(forKey: storageKey) else { return .default }
        guard let data = stored as? Data else { return nil }
        return try? JSONDecoder().decode(FlintWebRestrictions.self, from: data)
    }

    @discardableResult
    public func save(to defaults: UserDefaults? = .flintGroup) -> Bool {
        guard validationMessage == nil, let defaults, let data = try? JSONEncoder().encode(self) else { return false }
        defaults.set(data, forKey: Self.storageKey)
        return defaults.data(forKey: Self.storageKey) == data
    }
}

// MARK: - Enforcement (ManagedSettings)

#if canImport(ManagedSettings)
/// Writes only the web/media keys in the owning cause's store. Scope is decided by
/// `FlintWebRestrictionPolicy`; the session and schedule toggles are independent.
public final class FlintWebRestrictionStore {
    private let store: ManagedSettingsStore

    public init(named name: String) {
        self.store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
    }

    /// Enforce an already-authorized cause's configuration now.
    public func apply(_ restrictions: FlintWebRestrictions) {
        guard restrictions.validationMessage == nil else { return } // preserve last-good keys
        _ = FlintSessionController().withTransition {
            guard FlintPassRecoveryGate.read().permitsApplication else { return }
            store.webContent.blockedByFilter = Self.filterPolicy(for: restrictions)
            // nil = no assertion from this cause; another cause may still deny explicit content.
            store.media.denyExplicitContent = restrictions.denyExplicitMedia ? true : nil
        }
    }

    static func filterPolicy(for restrictions: FlintWebRestrictions) -> WebContentSettings.FilterPolicy {
        let allowed = Set(restrictions.normalizedAllowedDomains.map { WebDomain(domain: $0) })
        let blocked = Set(restrictions.normalizedBlockedDomains.map { WebDomain(domain: $0) })

        switch restrictions.filter {
        case .limitAdult:
            // Apple's auto adult filter, with the typed extras layered on. Setting any filter is
            // what disables Private Browsing + locks history clearing.
            return .auto(blocked, except: allowed)
        case .allowedOnly:
            return .all(except: allowed)
        }
    }

    /// Release this cause's web keys only. Other active causes may still restrict Safari.
    public func clear() {
        store.webContent.blockedByFilter = nil
        store.media.denyExplicitContent = nil
    }
}
#endif
