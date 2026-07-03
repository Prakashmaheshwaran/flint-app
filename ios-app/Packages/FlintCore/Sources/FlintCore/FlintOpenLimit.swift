import Foundation
import CryptoKit
#if canImport(FamilyControls)
import FamilyControls
#endif
#if canImport(ManagedSettings)
import ManagedSettings
#endif

/// Per-key daily "open" counts with a midnight reset — the data behind **Open Limits**
/// (launch-count caps).
///
/// **iOS platform reality:** Screen Time has no native launch-count event (`DeviceActivityEvent`
/// thresholds are usage *time* only). So Open Limits is driven by the `ShieldAction` extension:
/// the app stays shielded, and each "Use app" tap increments the count here; once the cap is hit,
/// the shield stops letting the app through. Keyed by a stable-ish token hash (tokens can rotate;
/// the count resets daily, which bounds the impact).
public struct FlintOpenLimitState: Codable, Equatable {
    public var dayStart: Date
    public var counts: [String: Int]

    public init(dayStart: Date = Date(), counts: [String: Int] = [:]) {
        self.dayStart = dayStart
        self.counts = counts
    }
}

public enum FlintOpenLimit {
    /// Register one open for [key], resetting first if it's a new day. Returns the updated state
    /// and the new count.
    public static func registerOpen(
        _ key: String,
        in state: FlintOpenLimitState,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> (state: FlintOpenLimitState, count: Int) {
        var updated = state
        if !calendar.isDate(updated.dayStart, inSameDayAs: now) {
            updated = FlintOpenLimitState(dayStart: now, counts: [:])
        }
        let count = (updated.counts[key] ?? 0) + 1
        updated.counts[key] = count
        return (updated, count)
    }

    /// Today's open count for [key] (0 if the stored day is stale).
    public static func count(
        _ key: String,
        in state: FlintOpenLimitState,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> Int {
        guard calendar.isDate(state.dayStart, inSameDayAs: now) else { return 0 }
        return state.counts[key] ?? 0
    }
}

// MARK: - Open verdicts (pure)

/// The outcome of asking to spend one open against a daily cap. Pure data — decidable and
/// testable without any Screen Time framework.
public enum FlintOpenVerdict: Equatable {
    /// The open was granted and counted: `count` is today's total including this open,
    /// `remaining` how many grants are left after it.
    case granted(count: Int, remaining: Int)
    /// The daily cap is spent (or the cap is non-positive) — keep the shield up.
    case exhausted(count: Int)
}

extension FlintOpenLimit {

    /// Ask to spend one open for [key] against a cap of [opensAllowed]. Grants (and records the
    /// open) only while today's tally is below the cap; a non-positive cap never grants. On
    /// `.exhausted` the state comes back unchanged — nothing to persist. Composed from the two
    /// tested primitives above (`count` to decide, `registerOpen` to record).
    public static func requestOpen(
        _ key: String,
        opensAllowed: Int,
        in state: FlintOpenLimitState,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> (state: FlintOpenLimitState, verdict: FlintOpenVerdict) {
        let used = count(key, in: state, now: now, calendar: calendar)
        guard opensAllowed > 0, used < opensAllowed else {
            return (state, .exhausted(count: used))
        }
        let result = registerOpen(key, in: state, now: now, calendar: calendar)
        return (result.state, .granted(
            count: result.count,
            remaining: max(0, opensAllowed - result.count)
        ))
    }

    /// Opens left for [key] today under [opensAllowed] (0 if spent or the cap is non-positive).
    public static func remainingOpens(
        _ key: String,
        opensAllowed: Int,
        in state: FlintOpenLimitState,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> Int {
        max(0, opensAllowed - count(key, in: state, now: now, calendar: calendar))
    }
}

// MARK: - Stable counter keys from opaque tokens

#if canImport(ManagedSettings)
extension FlintOpenLimit {

    /// Stable counter key for an application token. (Never `hashValue` — Swift hashing is
    /// process-seeded, and this key must agree across the host app and every extension.)
    public static func key(for token: ApplicationToken) -> String { stableKey(token) }

    /// Stable counter key for a web-domain token.
    public static func key(for token: WebDomainToken) -> String { stableKey(token) }

    /// SHA-256 (lowercase hex, mirrors `FlintPIN.hash`) over the token's canonical JSON —
    /// wrapped in a single-element array so the encoder always has a valid top level, with
    /// `.sortedKeys` for deterministic bytes. Tokens *can* rotate (FB14082790): a rotated token
    /// simply starts a fresh counter, and the daily reset bounds the impact.
    private static func stableKey<T: Encodable>(_ token: T) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode([token]) else { return "flint.openLimit.unencodable" }
        return SHA256.hash(data: data)
            .map { String(format: "%02x", $0) }
            .joined()
    }
}
#endif

// MARK: - Open-limit rules

#if canImport(FamilyControls)
/// A daily **Open Limit**: the selected apps/sites stay shielded, and the user gets
/// `opensAllowed` intentional pass-throughs per day, spent from the shield's action button.
/// Mirrors `FlintLimitRule` (same fields, its own `ManagedSettingsStore`) so open limits stack
/// cleanly with Sessions / Schedules / Time Limits.
///
/// Category tokens can be *selected* but are not enforced per-open: Apple's tokens are opaque,
/// so a shield tap on an app inside a category can't be matched back to that category's counter
/// (FB14237883). Open Limits are an app/site-token feature, matching the product spec
/// ("specific apps/websites").
public struct FlintOpenLimitRule: Codable, Identifiable, Equatable {
    public var id: String
    public var name: String
    /// Intentional opens allowed per day before the shield stops letting the user through.
    public var opensAllowed: Int
    public var selection: FamilyActivitySelection
    public var breakLevel: BreakLevel
    public var enabled: Bool

    public init(
        id: String = String(UUID().uuidString.prefix(8)),
        name: String,
        opensAllowed: Int,
        selection: FamilyActivitySelection = FamilyActivitySelection(),
        breakLevel: BreakLevel = .easy,
        enabled: Bool = true
    ) {
        self.id = id
        self.name = name
        self.opensAllowed = opensAllowed
        self.selection = selection
        self.breakLevel = breakLevel
        self.enabled = enabled
    }

    /// The `ManagedSettingsStore` this limit shields into ("O" for open limit — cf. "flintL." for
    /// time limits and "flint." for schedule rules).
    public var storeName: String { "flintO.\(id)" }
}

private enum FlintOpenLimitStoreKey {
    static let rules = "flint.openLimit.rules"
}

extension FlintOpenLimitRule {
    /// All saved rules, from the shared App Group. Same persistence pattern as the Wave-1 stubs
    /// in `FlintModel` (`FlintGroupStore` is a locked shared file, so this vertical owns its own
    /// "flint.*" key against the same suite).
    public static func loadAll(from defaults: UserDefaults? = .flintGroup) -> [FlintOpenLimitRule] {
        guard let data = defaults?.data(forKey: FlintOpenLimitStoreKey.rules),
              let rules = try? JSONDecoder().decode([FlintOpenLimitRule].self, from: data) else {
            return []
        }
        return rules
    }

    public static func saveAll(_ rules: [FlintOpenLimitRule], to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(rules), forKey: FlintOpenLimitStoreKey.rules)
    }
}
#endif

// MARK: - ShieldAction enforcement

#if canImport(FamilyControls) && canImport(ManagedSettings)
/// The token→context lookup + enforcement behind the `ShieldAction` extension. A tapped token is
/// matched against the saved `FlintOpenLimitRule`s (Apple's shield callback can't tell an
/// open-limit token from a hard-block one — FB14237883 — so Flint looks the context up itself);
/// a grant releases the token from the rule's own store, and an exhausted cap keeps/re-applies
/// the shield instead.
///
/// **Who re-arms after a grant:** the extension can't schedule its own re-shield (no
/// DeviceActivity here — the monitor is a separate vertical), so a granted token stays released
/// until the host app calls `applyShield(for:)` again (on launch, rule edits, and at the day
/// boundary). The daily counter itself resets automatically via `FlintOpenLimitState.dayStart`.
public enum FlintOpenLimitEnforcer {

    /// What the shield's action button should do for a tapped token.
    public enum Outcome: Equatable {
        /// One open spent & recorded; the token was released from every covering open-limit
        /// store. `remaining` grants are left today.
        case granted(remaining: Int)
        /// Today's opens are spent — the shield stays (token re-applied defensively).
        case exhausted
        /// Another blocking layer (Session / Schedule / Time Limit) also shields this token, so
        /// a release wouldn't let the user in — nothing is spent, the shield stays.
        case hardBlocked
        /// No enabled open-limit rule covers this token — a plain hard block ("Stay focused").
        case notOpenLimited
    }

    // MARK: Shield-tap entry points (called by the ShieldAction extension)

    public static func handleOpenRequest(application token: ApplicationToken) -> Outcome {
        handleOpenRequest(
            key: FlintOpenLimit.key(for: token),
            rules: coveringRules { $0.selection.applicationTokens.contains(token) },
            isHardBlocked: isHardBlocked(application: token),
            release: { store in
                var apps = store.shield.applications ?? []
                apps.remove(token)
                store.shield.applications = apps.isEmpty ? nil : apps
            },
            reshield: { store in
                var apps = store.shield.applications ?? []
                apps.insert(token)
                store.shield.applications = apps
            }
        )
    }

    public static func handleOpenRequest(webDomain token: WebDomainToken) -> Outcome {
        handleOpenRequest(
            key: FlintOpenLimit.key(for: token),
            rules: coveringRules { $0.selection.webDomainTokens.contains(token) },
            isHardBlocked: isHardBlocked(webDomain: token),
            release: { store in
                var domains = store.shield.webDomains ?? []
                domains.remove(token)
                store.shield.webDomains = domains.isEmpty ? nil : domains
            },
            reshield: { store in
                var domains = store.shield.webDomains ?? []
                domains.insert(token)
                store.shield.webDomains = domains
            }
        )
    }

    // MARK: Host-app seam (navigation/UI wiring belongs to the integrator, not this vertical)

    /// Shield a rule's full selection into its own store. Call when a rule is created or
    /// enabled, on app launch, and at the day boundary — that last call re-arms tokens released
    /// by yesterday's grants. App & web-domain tokens only (see the category note on
    /// `FlintOpenLimitRule`); selections near the ~50-token cap should be validated with
    /// `FlintShieldLimits` at creation time.
    public static func applyShield(for rule: FlintOpenLimitRule) {
        let store = ManagedSettingsStore(named: ManagedSettingsStore.Name(rule.storeName))
        let selection = rule.selection
        store.shield.applications =
            selection.applicationTokens.isEmpty ? nil : selection.applicationTokens
        store.shield.webDomains =
            selection.webDomainTokens.isEmpty ? nil : selection.webDomainTokens
    }

    /// Clear a rule's store (rule deleted or disabled).
    public static func clearShield(for rule: FlintOpenLimitRule) {
        ManagedSettingsStore(named: ManagedSettingsStore.Name(rule.storeName)).clearAllSettings()
    }

    // MARK: Internals

    private static func coveringRules(
        _ matches: (FlintOpenLimitRule) -> Bool
    ) -> [FlintOpenLimitRule] {
        FlintOpenLimitRule.loadAll().filter { $0.enabled && matches($0) }
    }

    /// The shared decision path. When several rules cover one token the strictest cap wins, and
    /// one tap spends one open from the single shared daily counter. The state is persisted
    /// *before* the stores are touched: if the extension dies mid-way we may under-grant, never
    /// over-grant.
    private static func handleOpenRequest(
        key: String,
        rules: [FlintOpenLimitRule],
        isHardBlocked: @autoclosure () -> Bool,
        release: (ManagedSettingsStore) -> Void,
        reshield: (ManagedSettingsStore) -> Void
    ) -> Outcome {
        guard !rules.isEmpty else { return .notOpenLimited }
        guard !isHardBlocked() else { return .hardBlocked }
        // No App Group = no counter; fail CLOSED (keep the shield) rather than grant unlimited
        // opens.
        guard let group = FlintGroupStore() else { return .exhausted }

        let cap = rules.map(\.opensAllowed).min() ?? 0
        let result = FlintOpenLimit.requestOpen(key, opensAllowed: cap, in: group.loadOpenLimitState())
        switch result.verdict {
        case .granted(count: _, remaining: let remaining):
            group.saveOpenLimitState(result.state)
            for rule in rules {
                release(ManagedSettingsStore(named: ManagedSettingsStore.Name(rule.storeName)))
            }
            return .granted(remaining: remaining)
        case .exhausted:
            // Keep/re-apply: make sure the token is (still) shielded by every covering rule.
            for rule in rules {
                reshield(ManagedSettingsStore(named: ManagedSettingsStore.Name(rule.storeName)))
            }
            return .exhausted
        }
    }

    /// True if a non-open-limit layer also shields this token right now. Purely a UX-honesty
    /// check (don't charge an open that can't get the user in) — safety never depends on it,
    /// because a grant only ever touches "flintO.*" stores, so every other layer keeps blocking
    /// regardless. Category-membership blocks can't be detected (opaque tokens); allow-list
    /// (`.all(except:)`) blocks can.
    private static func isHardBlocked(application token: ApplicationToken) -> Bool {
        for store in otherBlockingStores() {
            if store.shield.applications?.contains(token) == true { return true }
            if case .all(except: let allowed)? = store.shield.applicationCategories,
               !allowed.contains(token) {
                return true // allow-list ("brick phone") mode blocks everything not excepted
            }
        }
        return false
    }

    private static func isHardBlocked(webDomain token: WebDomainToken) -> Bool {
        for store in otherBlockingStores() {
            if store.shield.webDomains?.contains(token) == true { return true }
            if case .all(except: let allowed)? = store.shield.webDomainCategories,
               !allowed.contains(token) {
                return true
            }
        }
        return false
    }

    /// Every store the *other* blocking layers shield into: the manual-session store plus one
    /// per schedule rule and one per time limit (see `FlintMonitor` and the controllers).
    private static func otherBlockingStores() -> [ManagedSettingsStore] {
        var names = ["flint"]
        if let group = FlintGroupStore() {
            names += group.loadRules().map(\.storeName)
            names += group.loadLimits().map(\.storeName)
        }
        return names.map { ManagedSettingsStore(named: ManagedSettingsStore.Name($0)) }
    }
}
#endif
