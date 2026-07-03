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
/// Lifecycle: the user edits this in Settings (it persists to the shared App Group). Whatever
/// starts a session applies it via `FlintWebRestrictionStore().applySaved()` and clears it on
/// session end via `.clear()` — the same "apply on start / clear on stop" shape the shield uses.
///
/// **Beating Opal:** Opal can't let you type a custom domain on iOS (it relies on Screen Time's
/// frequently-used-site list). Flint can: `ManagedSettings`'s `WebDomain(domain:)` is built from a
/// plain string, so `blockedDomains` / `allowedDomains` accept anything the user types.
public struct FlintWebRestrictions: Codable, Equatable, Sendable {
    /// Master switch. When false, `FlintWebRestrictionStore` leaves Safari untouched.
    public var enabled: Bool
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
        filter: FlintWebFilter = .limitAdult,
        blockedDomains: [String] = [],
        allowedDomains: [String] = [],
        denyExplicitMedia: Bool = false
    ) {
        self.enabled = enabled
        self.filter = filter
        self.blockedDomains = blockedDomains
        self.allowedDomains = allowedDomains
        self.denyExplicitMedia = denyExplicitMedia
    }

    /// Off by default — the user opts in. Adult filtering is the mode they get when they do.
    public static let `default` = FlintWebRestrictions()

    // MARK: Honest derived facts (used by the UI to tell the truth about side effects)

    /// True when applying this config disables Safari Private Browsing. On iOS that's exactly
    /// "a web filter is active", i.e. enabled with any mode.
    public var disablesPrivateBrowsing: Bool { enabled }

    /// True when applying this config also locks "Clear History and Website Data" in Safari —
    /// iOS gates that button behind the same web-content restriction.
    public var locksHistoryClearing: Bool { enabled }

    // MARK: Normalized domain views (what actually gets enforced)

    /// `blockedDomains` cleaned + de-duplicated. Only meaningful in `.limitAdult`.
    public var normalizedBlockedDomains: [String] { Self.normalizedDomains(blockedDomains) }

    /// `allowedDomains` cleaned + de-duplicated.
    public var normalizedAllowedDomains: [String] { Self.normalizedDomains(allowedDomains) }

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
        guard let data = defaults?.data(forKey: storageKey),
              let config = try? JSONDecoder().decode(FlintWebRestrictions.self, from: data) else {
            return .default
        }
        return config
    }

    public func save(to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: Self.storageKey)
    }
}

// MARK: - Enforcement (ManagedSettings)

#if canImport(ManagedSettings)
/// Applies `FlintWebRestrictions` to Safari via a dedicated `ManagedSettingsStore`. Kept on its own
/// named store ("flint.web") so it composes with — but never disturbs — the app/website *shield*
/// tokens that `FlintShieldStore` writes to "flint". Both stores' settings union at the OS level.
///
/// Call `applySaved()` when a session begins and `clear()` when it ends. (The integrator wires this
/// into the session lifecycle; this type deliberately owns no session state of its own.)
public final class FlintWebRestrictionStore {
    public static let storeName = "flint.web"

    private let store: ManagedSettingsStore

    public init(named name: String = FlintWebRestrictionStore.storeName) {
        self.store = ManagedSettingsStore(named: ManagedSettingsStore.Name(name))
    }

    /// Enforce a configuration now. A disabled config is equivalent to `clear()`.
    public func apply(_ restrictions: FlintWebRestrictions) {
        guard restrictions.enabled else { clear(); return }

        let allowed = Set(restrictions.normalizedAllowedDomains.map { WebDomain(domain: $0) })
        let blocked = Set(restrictions.normalizedBlockedDomains.map { WebDomain(domain: $0) })

        switch restrictions.filter {
        case .limitAdult:
            // Apple's auto adult filter, with the typed extras layered on. Setting any filter is
            // what disables Private Browsing + locks history clearing.
            store.webContent.blockedByFilter = .auto(blocked, except: allowed)
        case .allowedOnly:
            store.webContent.blockedByFilter = .specific(allowed)
        }

        // `denyExplicitContent` is optional: nil = "don't assert", so leave it unset when off.
        store.media.denyExplicitContent = restrictions.denyExplicitMedia ? true : nil
    }

    /// Load the user's saved configuration and enforce it. The convenience a session-start hook calls.
    public func applySaved(from defaults: UserDefaults? = .flintGroup) {
        apply(FlintWebRestrictions.load(from: defaults))
    }

    /// Lift the restrictions — re-enables Private Browsing and unlocks history clearing. Surgical:
    /// only resets the keys this store sets, leaving any unrelated settings alone.
    public func clear() {
        store.webContent.blockedByFilter = nil
        store.media.denyExplicitContent = nil
    }
}
#endif
