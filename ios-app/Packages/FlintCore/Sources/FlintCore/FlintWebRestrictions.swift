import Foundation
import CryptoKit
#if canImport(ManagedSettings)
import ManagedSettings
#endif
#if canImport(DeviceActivity)
import DeviceActivity
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

public enum FlintWebRestrictionRead: Equatable, Sendable {
    case available(FlintWebRestrictions)
    case unavailable
    case busy
}

extension FlintWebRestrictions {
    /// Namespaced under "flint.*" to match `FlintGroupStore`'s key convention. Lives outside
    /// `FlintGroupStore` itself because that file is a shared/locked scaffold file — same suite,
    /// same JSON, no edits to the hot file (mirrors how the Wave-1 stubs persist).
    private static let storageKey = "flint.webRestrictions"
    static let initializedMarkerKey = storageKey + ".initialized.adr012"

    /// Production initialization shares the host/extension transition boundary with starts,
    /// edits and pass recovery. A concurrent first save cannot be overwritten by fresh defaults.
    public static func loadForEnforcement() -> FlintWebRestrictions? {
        guard case let .available(config) = readForEnforcement() else { return nil }
        return config
    }

    /// Enforcement keeps the ordinary transition wait; only display/editor reads are opportunistic.
    public static func readForEnforcement() -> FlintWebRestrictionRead {
        read(uiRead: false)
    }

    /// A busy monitor transition is retryable, distinct from unreadable saved intent. The file
    /// lock attempt is immediate; callers still run off-main because the process lock can wait.
    public static func readForUI() -> FlintWebRestrictionRead { read(uiRead: true) }

    private static func read(uiRead: Bool) -> FlintWebRestrictionRead {
        #if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
        return read(uiRead: uiRead, load: { loadForEnforcement(from: .flintGroup) }, transition: { timeout, body in
            FlintSessionController().withTransition(timeout: timeout) { body(); return true } ?? false
        })
        #else
        return loadForEnforcement(from: .flintGroup).map(FlintWebRestrictionRead.available) ?? .unavailable
        #endif
    }

    static func read(uiRead: Bool, load: @escaping () -> FlintWebRestrictions?,
                     transition: (TimeInterval?, () -> Void) -> Bool) -> FlintWebRestrictionRead {
        var result: FlintWebRestrictions?
        guard transition(uiRead ? 0 : nil, { result = load() }) else { return .busy }
        return result.map(FlintWebRestrictionRead.available) ?? .unavailable
    }

    /// Before this domain's marker, older builds persisted only explicit Safari saves. Other
    /// domains do not prove a web key ever existed: without native web restrictions, migrate
    /// that legacy opt-out once. After initialization, missing intent is unavailable.
    /// The optional native-store probe keeps isolated persistence tests independent of the OS.
    static func loadForEnforcement(from defaults: UserDefaults?,
                                  existingWebStores: (() -> Bool)? = nil) -> FlintWebRestrictions? {
        guard let defaults, defaults.synchronize() else { return nil }
        guard let stored = defaults.object(forKey: storageKey) else {
            guard defaults.object(forKey: initializedMarkerKey) == nil else { return nil }
            guard !hasExistingWebStores(in: defaults, probe: existingWebStores) else {
                // A positive or unknowable native owner stays unavailable even if a later
                // native read loses visibility (for example after authorization changes).
                _ = markInitialized(in: defaults)
                return nil
            }
            // A never-configured legacy install and pre-upgrade key loss without an applied
            // store are indistinguishable. Import the effective opt-out, never a native release.
            return Self.default.save(to: defaults) ? .default : nil
        }
        // Even malformed legacy bytes prove this domain existed. Retain the original bytes
        // and back-fill the marker before decoding; losing the key later cannot reset intent.
        guard markInitialized(in: defaults) else { return nil }
        guard let data = stored as? Data else { return nil }
        return try? JSONDecoder().decode(FlintWebRestrictions.self, from: data)
    }

    @discardableResult
    public func save(to defaults: UserDefaults? = .flintGroup) -> Bool {
        guard validationMessage == nil, let defaults, let data = try? JSONEncoder().encode(self) else { return false }
        let previous = defaults.object(forKey: Self.storageKey)
        let previousMarker = defaults.object(forKey: Self.initializedMarkerKey)
        var committed = false
        defer {
            if !committed {
                if let previous { defaults.set(previous, forKey: Self.storageKey) }
                else { defaults.removeObject(forKey: Self.storageKey) }
                if let previousMarker { defaults.set(previousMarker, forKey: Self.initializedMarkerKey) }
                else { defaults.removeObject(forKey: Self.initializedMarkerKey) }
                let flushed = defaults.synchronize()
                let restored = (defaults.object(forKey: Self.storageKey) as? NSObject) == (previous as? NSObject)
                    && (defaults.object(forKey: Self.initializedMarkerKey) as? NSObject) == (previousMarker as? NSObject)
                if !flushed || !restored {
                    // A failed rollback must not make this look like a fresh domain. Retain
                    // the restored cached bytes and latch loss detection until recovery.
                    if defaults.object(forKey: Self.initializedMarkerKey) == nil {
                        defaults.set(true, forKey: Self.initializedMarkerKey)
                    }
                    defaults.synchronize()
                }
            }
        }
        defaults.set(data, forKey: Self.storageKey)
        guard defaults.synchronize(), defaults.data(forKey: Self.storageKey) == data else { return false }
        guard Self.markInitialized(in: defaults) else { return false }
        committed = true
        return true
    }

    private static func markInitialized(in defaults: UserDefaults) -> Bool {
        if defaults.object(forKey: initializedMarkerKey) != nil { return true }
        defaults.set(true, forKey: initializedMarkerKey)
        return defaults.synchronize() && defaults.object(forKey: initializedMarkerKey) != nil
    }

    #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
    /// Called only after the editor's weakening gates, while its transition is held. Keep a
    /// durable, content-addressed copy of unavailable intent before an explicit repair.
    func saveRecoveringUnavailableIntent(to defaults: UserDefaults? = .flintGroup,
                                        recoveryDirectory: URL? = FlintRuleRecovery.directory) -> Bool {
        guard let defaults, defaults.synchronize() else { return false }
        if let object = defaults.object(forKey: Self.storageKey) {
            let config = (object as? Data).flatMap { try? JSONDecoder().decode(Self.self, from: $0) }
            if config == nil || config?.validationMessage != nil {
                let bytes: Data
                if let data = object as? Data { bytes = data }
                else {
                    guard let data = try? PropertyListSerialization.data(fromPropertyList: object,
                        format: .binary, options: 0) else { return false }
                    bytes = data
                }
                let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
                guard FlintRuleRecovery.retain(bytes, key: Self.storageKey + ".recovered." + digest,
                    defaults: defaults, directory: recoveryDirectory) else { return false }
            }
        }
        return save(to: defaults)
    }
    #endif

    private static func hasExistingWebStores(in defaults: UserDefaults, probe: (() -> Bool)?) -> Bool {
        #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
        // An unreadable rule domain cannot prove which owning stores need inspection. A
        // permissive raw-JSON parse would silently omit unknown or quarantined records.
        let domain = FlintRuleStoreKey.schedules
        let quarantine = domain.rawValue + ".quarantine.adr012"
        guard defaults.object(forKey: quarantine) == nil else { return true }
        let ruleEvidence = [domain.rawValue, domain.marker, domain.original].contains {
            defaults.object(forKey: $0) != nil
        }
        let rules: [FlintScheduleRule]
        if ruleEvidence {
            guard let saved = FlintDesiredRules.load(FlintScheduleRule.self, key: domain,
                from: defaults, migrating: false) else { return true }
            rules = saved
        } else { rules = [] }
        if let probe { return probe() }
        // Read the historical stores and stores identified by surviving native schedules.
        // Store enumeration is unavailable on the app's older supported iOS releases.
        let names = webStoreNames(rules: rules, activityNames: DeviceActivityCenter().activities.map(\.rawValue))
        return names.contains { name in
            let store = ManagedSettingsStore(named: .init(name))
            return store.webContent.blockedByFilter != nil || store.media.denyExplicitContent == true
        }
        #else
        return probe?() ?? false
        #endif
    }

    #if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
    static func webStoreNames(rules: [FlintScheduleRule], activityNames: [String]) -> Set<String> {
        var names: Set<String> = ["flint", "flint.web"]
        names.formUnion(rules.map(\.storeName))
        for activity in activityNames {
            if let id = FlintRuleActivity.ruleID(activity, prefix: FlintRuleStoreKey.schedules.prefix) {
                names.insert("flint." + id)
            }
        }
        return names
    }
    #endif
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
