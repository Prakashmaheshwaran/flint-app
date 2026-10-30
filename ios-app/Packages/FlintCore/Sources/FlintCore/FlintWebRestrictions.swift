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

public struct FlintWebRestrictionSettingsRead: Equatable, Sendable {
    public let preferences: FlintWebRestrictionRead
    /// nil means the read was busy, so the UI retains its last known recovery state.
    public let needsChildRecovery: Bool?
}

extension FlintWebRestrictions {
    /// Namespaced under "flint.*" to match `FlintGroupStore`'s key convention. Lives outside
    /// `FlintGroupStore` itself because that file is a shared/locked scaffold file — same suite,
    /// same JSON, no edits to the hot file (mirrors how the Wave-1 stubs persist).
    static let storageKey = "flint.webRestrictions"
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

    public static func readForSettingsUI() -> FlintWebRestrictionSettingsRead {
        #if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
        var result = FlintWebRestrictionSettingsRead(preferences: .busy, needsChildRecovery: nil)
        let preferences = read(uiRead: true, environment: .live, transition: { timeout, body in
            FlintSessionController().withTransition(timeout: timeout) {
                body()
                result = .init(preferences: result.preferences, needsChildRecovery: FlintWebRestrictionChildren.live.needsRecovery)
                return true
            } ?? false
        })
        return .init(preferences: preferences, needsChildRecovery: preferences == .busy ? nil : result.needsChildRecovery)
        #else
        return .init(preferences: readForUI(), needsChildRecovery: false)
        #endif
    }

    private static func read(uiRead: Bool) -> FlintWebRestrictionRead {
        #if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
        return read(uiRead: uiRead, environment: .live, transition: { timeout, body in
            FlintSessionController().withTransition(timeout: timeout) { body(); return true } ?? false
        })
        #else
        return loadForEnforcement(from: .flintGroup).map(FlintWebRestrictionRead.available) ?? .unavailable
        #endif
    }

    #if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
    static func read(uiRead: Bool, environment: FlintRuleReloadEnvironment,
                     existingWebStores: (() -> Bool)? = nil,
                     transition: (TimeInterval?, () -> Void) -> Bool) -> FlintWebRestrictionRead {
        // Initialize rule domains before our first web marker or session lock can become
        // installation evidence. Domain-specific legacy migration remains the rule owner's.
        switch FlintRuleStoreLock.initializationReadiness(environment: environment, timeout: uiRead ? 0 : 2) {
        case .ready: break
        case .contended: return .busy
        case .failed: return .unavailable
        }
        return read(uiRead: uiRead, load: {
            loadForEnforcement(from: environment.defaults, existingWebStores: existingWebStores)
        }, transition: transition)
    }
    #endif

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
    public func save() -> Bool {
        #if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
        guard validationMessage == nil,
              FlintRuleStoreLock.initializationReadiness(environment: .live) == .ready else { return false }
        return FlintSessionController().withTransition {
            guard let group = FlintGroupStore(), let snapshot = FlintWebRestrictionEditor.Snapshot.observing(
                session: group.loadActiveSession(), recordPresent: group.hasActiveSessionRecord(),
                rules: FlintDesiredRules.load(FlintScheduleRule.self, key: .schedules, from: .flintGroup, migrating: false),
                ledgerState: try? FlintProtectionLedger.appGroup()?.peek().get(), clock: .system),
                snapshot.rules.allSatisfy(FlintWebRestrictionChildren.knows) else { return false }
            let children = FlintWebRestrictionChildren.live
            // Freeze old effective intent before replacing future preferences. Unavailable
            // established children remain unavailable, with their bytes preserved by the write.
            if children.read() != .unavailable,
               !children.capture(FlintWebRestrictionChildren.causes(snapshot, clock: .system),
                                 global: Self.loadForEnforcement(from: .flintGroup)) { return false }
            return save(to: .flintGroup)
        } ?? false
        #else
        return save(to: .flintGroup)
        #endif
    }

    @discardableResult
    func save(to defaults: UserDefaults?) -> Bool {
        guard validationMessage == nil else { return false }
        return Self.writeStorage(to: defaults, config: self)
    }

    /// Global edits merge into the freshly read root. In particular they never erase a child
    /// captured after the caller loaded its preference draft. All production callers hold the
    /// shared transition; child/global editor changes use this same atomic blob replacement.
    static func writeStorage(to defaults: UserDefaults?, config: Self?, children: Data? = nil) -> Bool {
        guard let defaults, config != nil || children != nil, config?.validationMessage == nil else { return false }
        let childMarker = "flint.webRestrictions.children.initialized.adr012"
        let keys = [storageKey, initializedMarkerKey, childMarker]
        var previous = keys.map { defaults.object(forKey: $0) }
        var committed = false
        defer {
            if !committed {
                for (key, object) in zip(keys, previous) {
                    if let object { defaults.set(object, forKey: key) }
                    else { defaults.removeObject(forKey: key) }
                }
                let flushed = defaults.synchronize()
                let restored = zip(keys, previous).allSatisfy {
                    (defaults.object(forKey: $0.0) as? NSObject) == ($0.1 as? NSObject)
                }
                if !flushed || !restored {
                    // A failed rollback must not make this look like a fresh domain. Retain
                    // the restored cached bytes and latch loss detection until recovery.
                    if defaults.object(forKey: Self.initializedMarkerKey) == nil {
                        defaults.set(true, forKey: Self.initializedMarkerKey)
                    }
                    if children != nil, defaults.object(forKey: childMarker) == nil {
                        defaults.set(true, forKey: childMarker)
                    }
                    defaults.synchronize()
                }
            }
        }
        guard defaults.synchronize() else { return false }
        // Snapshot after synchronization, while the transition excludes other writers.
        previous = keys.map { defaults.object(forKey: $0) }
        let stored = defaults.object(forKey: storageKey)
        var root = (stored as? Data).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
        if root.isEmpty, let stored {
            let bytes = (stored as? Data) ?? (try? PropertyListSerialization.data(fromPropertyList: stored, format: .binary, options: 0))
            guard let bytes else { return false }
            root["_flintWebRetainedUnavailable"] = bytes.base64EncodedString()
        }
        if let config {
            guard let encoded = try? JSONEncoder().encode(config),
                  let fields = (try? JSONSerialization.jsonObject(with: encoded)) as? [String: Any] else { return false }
            root.merge(fields) { _, new in new }
        }
        if let children {
            root["_flintWebChildrenSchema"] = 1
            root["_flintWebChildren"] = children.base64EncodedString()
        }
        guard let data = try? JSONSerialization.data(withJSONObject: root, options: [.sortedKeys]) else { return false }
        defaults.set(data, forKey: Self.storageKey)
        guard defaults.synchronize(), defaults.data(forKey: Self.storageKey) == data else { return false }
        guard Self.markInitialized(in: defaults) else { return false }
        if children != nil, defaults.object(forKey: childMarker) == nil {
            defaults.set(true, forKey: childMarker)
            guard defaults.synchronize(), defaults.object(forKey: childMarker) != nil else { return false }
        }
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
        guard Self.retainUnavailableIntent(to: defaults, recoveryDirectory: recoveryDirectory) else { return false }
        return save(to: defaults)
    }

    static func retainUnavailableIntent(to defaults: UserDefaults?, recoveryDirectory: URL?, force: Bool = false) -> Bool {
        guard let defaults, defaults.synchronize() else { return false }
        if let object = defaults.object(forKey: Self.storageKey) {
            let config = (object as? Data).flatMap { try? JSONDecoder().decode(Self.self, from: $0) }
            if force || config == nil || config?.validationMessage != nil {
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
        return true
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
