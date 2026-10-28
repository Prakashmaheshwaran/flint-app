import Foundation
import CryptoKit
#if canImport(FamilyControls)
import FamilyControls
#endif
#if canImport(ManagedSettings)
import ManagedSettings
#endif

/// Legacy counter payload, retained only for the one-time ledger import. Live Open credits
/// use FlintOpenCreditState and the cross-process protection ledger.
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
    /// `.sortedKeys` for deterministic bytes. A rotated token requires conservative reselection
    /// and cannot mint a fresh mid-period pool (ADR-012 PC-38).
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

    /// The all-day `DeviceActivityName` whose day boundary re-arms this rule's shield (cf.
    /// `FlintLimitRule.activityName`). Registered by `FlintOpenLimitsController`; the monitor
    /// extension re-applies the shield when it fires.
    public var activityName: String { "flint.openLimit.\(id)" }
}

private enum FlintOpenLimitStoreKey {
    static let rules = FlintRuleStoreKey.openLimits.rawValue
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

    @discardableResult
    public static func saveAll(_ rules: [FlintOpenLimitRule], to defaults: UserDefaults? = .flintGroup) -> Bool {
        guard let defaults, let data = try? JSONEncoder().encode(rules) else { return false }
        defaults.set(data, forKey: FlintOpenLimitStoreKey.rules)
        return true
    }
}
#endif

// MARK: - ShieldAction enforcement

#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
/// Compatibility surface for shield configuration; all acceptance uses the atomic ledger.
public enum FlintOpenLimitEnforcer {
    public enum Outcome: Equatable {
        case granted(remaining: Int)
        case exhausted
        case hardBlocked
        case notOpenLimited
        case unavailable
    }

    public static func handleOpenRequest(application token: ApplicationToken) -> Outcome {
        outcome(FlintOpenCredit.accept(for: token))
    }
    public static func handleOpenRequest(webDomain token: WebDomainToken) -> Outcome {
        outcome(FlintOpenCredit.accept(for: token))
    }
    public static func remainingOpens(application token: ApplicationToken) -> Int? {
        remaining(FlintOpenCredit.preview(for: token))
    }
    public static func remainingOpens(webDomain token: WebDomainToken) -> Int? {
        remaining(FlintOpenCredit.preview(for: token))
    }
    public static func applyShield(for rule: FlintOpenLimitRule) {
        FlintOpenCredit.applyShield(for: rule)
    }
    public static func clearShield(for rule: FlintOpenLimitRule) {
        ManagedSettingsStore(named: .init(rule.storeName)).clearAllSettings()
    }
    private static func remaining(_ preview: FlintOpenCreditPreview) -> Int? {
        guard preview.blockedBy == nil || preview.blockedBy == .exhausted else { return nil }
        return preview.remaining
    }
    private static func outcome(_ result: Result<FlintOpenCreditReceipt, FlintOpenCreditRefusal>) -> Outcome {
        switch result {
        case let .success(receipt): return .granted(remaining: receipt.remaining)
        case .failure(.denied(.exhausted)): return .exhausted
        case .failure(.denied(.notOpenLimited)): return .notOpenLimited
        case .failure(.denied(.otherContributor)), .failure(.denied(.categoryOverlap)): return .hardBlocked
        case .failure: return .unavailable
        }
    }
}
#endif
