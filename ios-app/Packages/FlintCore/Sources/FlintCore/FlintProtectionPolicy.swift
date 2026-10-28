import Foundation

// ADR-012 (docs/architecture/protection-contract.md) as pure decisions. Every entry point —
// Today, Shortcuts/Siri, the Focus filter, rule editors, the shield action — asks these
// functions at commit time; UI-disabled controls alone are never the gate (§2). Pure Foundation
// and generic over the target identity, so every matrix cell is unit-tested without tokens.

// MARK: - Constants (§4, §1, §6)

public enum FlintProtectionConstants {
    /// Ordinary scoped break and one Open credit (§4, §5).
    public static let breakDuration: TimeInterval = 300
    public static let openCreditDuration: TimeInterval = 300
    /// Harder friction, by count of committed weakening operations on the cause (§4).
    public static let harderWaits: [TimeInterval] = [60, 300, 900]
    /// New finite sessions and Pomodoro parents (§1).
    public static let maximumSessionDuration: TimeInterval = 24 * 3600
    /// One recurring/daily occurrence, allowing a 25-hour DST day (§1).
    public static let maximumOccurrenceDuration: TimeInterval = 26 * 3600
    /// Emergency Pass renewal and global recovery (§6).
    public static let passPeriod: TimeInterval = 604_800
    public static let passRecovery: TimeInterval = 3600
}

// MARK: - Cause state

/// What the matrix needs to know about one affected cause occurrence (§1, §2).
public struct FlintCauseState: Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case session          // manual / Block Now / Shortcut / Siri / Focus-filter session
        case pomodoro
        case recurringSchedule
        case oneShotSchedule
        case sleep
        case timeLimit
        case openLimit
    }

    public var kind: Kind
    public var tier: BreakLevel
    /// An active commitment has started and not terminated — including during a break, a
    /// Pomodoro rest or a limit's unspent budget (§1). Not "a shield is visible".
    public var isActive: Bool
    /// The current occurrence's natural end; nil only for legacy open-ended causes.
    public var end: Date?
    /// Committed Harder weakening operations on this occurrence (§4's `n`).
    public var harderCount: Int
    /// Rights migrated from a pre-contract occurrence (§12), kept until that occurrence ends.
    /// The matrix itself is route-independent; route adapters (e.g. Focus-off of a legacy
    /// open-ended Focus session) consult this to honor an exit the old build promised.
    public var legacyRights: Bool

    public init(
        kind: Kind,
        tier: BreakLevel,
        isActive: Bool,
        end: Date? = nil,
        harderCount: Int = 0,
        legacyRights: Bool = false
    ) {
        self.kind = kind
        self.tier = tier
        self.isActive = isActive
        self.end = end
        self.harderCount = harderCount
        self.legacyRights = legacyRights
    }

    /// Rule-backed causes (templates with occurrences) versus transient sessions (§6 step 1–2).
    public var isRuleBacked: Bool {
        switch kind {
        case .session, .pomodoro: return false
        case .recurringSchedule, .oneShotSchedule, .sleep, .timeLimit, .openLimit: return true
        }
    }
}

// MARK: - Actions and decisions (§2)

public enum FlintProtectionAction: Equatable, Sendable {
    /// Start another cause without retiring any existing one.
    case startAdditive
    /// Replace a running session; `dominating` per `FlintProtectionPolicy.dominates` (§3).
    case replace(dominating: Bool)
    /// Stop / end the current occurrence early.
    case stopEarly
    /// Ordinary temporary break/access (300 s scoped grant).
    case ordinaryBreak
    /// Accept an available Open credit.
    case acceptOpenCredit
    case edit(FlintEditImpact)
    case toggleOff
    case delete
}

/// What an edit does to protection (§2 "Strengthen requires a proof…").
public enum FlintEditImpact: Equatable, Sendable {
    /// Rename / presentation only — no change to occurrence identity, counters or waits.
    case cosmetic
    /// Provably no target or time becomes allowed and no exit becomes easier.
    case strengthen
    /// Anything else, including any change that cannot be proven to strengthen.
    case weaken
}

public enum FlintProtectionDecision: Equatable, Sendable {
    /// Preview, (confirm,) persist, then show the actual result.
    case allow(confirm: Bool)
    /// Harder friction: a persisted wait of `seconds` must mature, then an explicit confirm.
    case wait(seconds: TimeInterval)
    /// Save for after the current occurrence ends (`until`); no immediate reduction.
    case deferUntil(Date?)
    /// No mutation: show cause, tier, end, pass status and a safe route back.
    case deny(FlintDenialReason)
    /// Nothing to do ("Not running" / "No access is blocked").
    case notApplicable
}

public enum FlintDenialReason: String, Equatable, Sendable {
    /// Active Hardcore: ordinary early weakening is denied; expiry or a pass are the exits.
    case hardcoreCommitment = "hardcore_commitment"
    /// A replacement that would weaken an active Hardcore session; start it independently.
    case wouldWeakenHardcore = "would_weaken_hardcore"
}

public enum FlintProtectionPolicy {

    /// Harder delay for a cause that has already committed `count` weakening operations.
    public static func harderWait(forCount count: Int) -> TimeInterval {
        let waits = FlintProtectionConstants.harderWaits
        return waits[min(max(count, 0), waits.count - 1)]
    }

    /// The §2 decision for one action on one affected cause, by the cause's **current** tier.
    public static func decide(_ action: FlintProtectionAction, for cause: FlintCauseState) -> FlintProtectionDecision {
        guard cause.isActive else { return decideInactive(action, for: cause) }

        switch action {
        case .startAdditive:
            return .allow(confirm: cause.tier == .hardcore)
        case let .replace(dominating):
            if dominating { return .allow(confirm: true) }
            switch cause.tier {
            case .easy: return .allow(confirm: true)
            case .harder: return .wait(seconds: harderWait(forCount: cause.harderCount))
            case .hardcore: return .deny(.wouldWeakenHardcore)
            }
        case .stopEarly, .ordinaryBreak:
            switch cause.tier {
            case .easy: return .allow(confirm: true)
            case .harder: return .wait(seconds: harderWait(forCount: cause.harderCount))
            case .hardcore: return .deny(.hardcoreCommitment)
            }
        case .acceptOpenCredit:
            // Quota credits are part of the original commitment at every tier; no Harder wait.
            return cause.kind == .openLimit ? .allow(confirm: true) : .notApplicable
        case let .edit(impact):
            switch impact {
            case .cosmetic, .strengthen: return .allow(confirm: impact == .strengthen)
            case .weaken:
                switch cause.tier {
                case .easy: return .allow(confirm: true)
                case .harder: return .wait(seconds: harderWait(forCount: cause.harderCount))
                case .hardcore: return .deferUntil(cause.end)
                }
            }
        case .toggleOff, .delete:
            switch cause.tier {
            case .easy: return .allow(confirm: true)
            case .harder: return .wait(seconds: harderWait(forCount: cause.harderCount))
            case .hardcore: return .deferUntil(cause.end)
            }
        }
    }

    private static func decideInactive(_ action: FlintProtectionAction, for cause: FlintCauseState) -> FlintProtectionDecision {
        switch action {
        case .startAdditive:
            return .allow(confirm: cause.tier == .hardcore) // finite-end disclosure for Hardcore
        case .replace, .stopEarly, .ordinaryBreak, .acceptOpenCredit:
            return .notApplicable
        case let .edit(impact):
            return .allow(confirm: impact == .strengthen)
        case .toggleOff, .delete:
            return .allow(confirm: true)
        }
    }

    /// One operation touching several causes (§2 "A batch touches every affected cause
    /// atomically"): any deny denies; otherwise any defer defers to the latest end; otherwise
    /// the longest wait applies; otherwise allow. Not-applicable causes don't contribute.
    public static func decide(_ action: FlintProtectionAction, for causes: [FlintCauseState]) -> FlintProtectionDecision {
        let decisions = causes.map { decide(action, for: $0) }.filter { $0 != .notApplicable }
        guard !decisions.isEmpty else { return .notApplicable }
        if let deny = decisions.first(where: { if case .deny = $0 { return true }; return false }) { return deny }
        let defers = decisions.compactMap { decision -> Date?? in
            if case let .deferUntil(end) = decision { return .some(end) }
            return nil
        }
        if !defers.isEmpty {
            // An open-ended (nil) occurrence end dominates any finite one.
            if defers.contains(where: { $0 == nil }) { return .deferUntil(nil) }
            return .deferUntil(defers.compactMap { $0 }.max())
        }
        let waits = decisions.compactMap { decision -> TimeInterval? in
            if case let .wait(seconds) = decision { return seconds }
            return nil
        }
        if let longest = waits.max() { return .wait(seconds: longest) }
        return .allow(confirm: decisions.contains(.allow(confirm: true)))
    }
}

// MARK: - Targets, dominance and edit classification (§2, §3)

/// A cause's protection as comparable sets. `Target` is an opaque, stable identity (e.g. an
/// encoded token); categories are compared as opaque identities and never inferred.
public struct FlintProtectionSnapshot<Target: Hashable>: Equatable {
    public enum Mode: Equatable {
        /// Block exactly these.
        case blockList(applications: Set<Target>, categories: Set<Target>, webDomains: Set<Target>)
        /// Block everything except these applications.
        case allowList(allowedApplications: Set<Target>)
    }

    public var mode: Mode
    public var tier: BreakLevel
    /// nil = open-ended (legacy only).
    public var end: Date?
    /// Daily budget / credits, when the cause has one (Time/Open Limits).
    public var budget: Int?

    public init(mode: Mode, tier: BreakLevel, end: Date?, budget: Int? = nil) {
        self.mode = mode
        self.tier = tier
        self.end = end
        self.budget = budget
    }
}

extension FlintProtectionPolicy {

    /// True when `new` provably blocks at least everything `old` does: block set grows (or
    /// allow set shrinks) within the same mode, tier does not decrease, end does not move
    /// earlier, budget does not increase. Mode changes and opaque-category changes are never
    /// provable (§2), so they are not "at least as strong".
    public static func isAtLeastAsStrong<T>(_ new: FlintProtectionSnapshot<T>, as old: FlintProtectionSnapshot<T>) -> Bool {
        guard rank(new.tier) >= rank(old.tier) else { return false }
        switch (old.end, new.end) {
        case (nil, .some): return false          // finite never covers open-ended
        case let (.some(oldEnd), .some(newEnd)) where newEnd < oldEnd: return false
        default: break
        }
        switch (old.budget, new.budget) {
        case let (.some(oldBudget), .some(newBudget)) where newBudget > oldBudget: return false
        case (.some, nil): return false          // removing a limit is weakening
        default: break
        }
        switch (old.mode, new.mode) {
        case let (.blockList(oa, oc, ow), .blockList(na, nc, nw)):
            return na.isSuperset(of: oa) && nc.isSuperset(of: oc) && nw.isSuperset(of: ow)
        case let (.allowList(oldAllowed), .allowList(newAllowed)):
            return newAllowed.isSubset(of: oldAllowed)
        default:
            return false
        }
    }

    /// A replacement dominates only if it preserves every old target's protection, at least
    /// the old tier, throughout the old remaining interval (§3).
    public static func dominates<T>(_ replacement: FlintProtectionSnapshot<T>, over old: FlintProtectionSnapshot<T>) -> Bool {
        isAtLeastAsStrong(replacement, as: old)
    }

    /// Classify an edit of an occurrence's protection (§2). Equal protection is cosmetic.
    public static func editImpact<T>(from old: FlintProtectionSnapshot<T>, to new: FlintProtectionSnapshot<T>) -> FlintEditImpact {
        if new == old { return .cosmetic }
        return isAtLeastAsStrong(new, as: old) ? .strengthen : .weaken
    }

    static func rank(_ tier: BreakLevel) -> Int {
        switch tier {
        case .easy: return 0
        case .harder: return 1
        case .hardcore: return 2
        }
    }
}

// MARK: - New-cause validation (§1)

public enum FlintCauseIssue: String, Equatable, Sendable {
    /// Harder/Hardcore need a finite end; they are not permanent locks.
    case strictNeedsFiniteEnd = "strict_needs_finite_end"
    /// Longer than 24 h for a session / 26 h for an occurrence.
    case tooLong = "too_long"
    case nonPositive = "non_positive"
}

extension FlintProtectionPolicy {

    /// Validate a new session or Pomodoro parent. `duration == nil` means "until I stop",
    /// which only Easy may use — including a Focus filter's "while Focus is on" (§1).
    public static func issue(forNewSession tier: BreakLevel, duration: TimeInterval?) -> FlintCauseIssue? {
        guard let duration else { return tier == .easy ? nil : .strictNeedsFiniteEnd }
        if duration <= 0 { return .nonPositive }
        if duration > FlintProtectionConstants.maximumSessionDuration { return .tooLong }
        return nil
    }

    /// Validate one materialized recurring/daily occurrence (start→end instants).
    public static func issue(forOccurrenceFrom start: Date, to end: Date) -> FlintCauseIssue? {
        let span = end.timeIntervalSince(start)
        if span <= 0 { return .nonPositive }
        if span > FlintProtectionConstants.maximumOccurrenceDuration { return .tooLong }
        return nil
    }
}

// MARK: - Emergency Pass eligibility (§6)

/// What the trusted elapsed timeline knows about the last committed pass use.
public enum FlintPassUsage: Equatable, Sendable {
    /// A validated install with no recorded use.
    case neverUsed
    /// Elapsed seconds since the committed use, on the anchored timeline (§9).
    case used(elapsed: TimeInterval)
    /// Corrupt, future, unsupported or unreadable state.
    case unknown
}

public enum FlintPassEligibility: Equatable, Sendable {
    case available
    /// Next eligibility on the trusted elapsed timeline.
    case coolingDown(remaining: TimeInterval)
    /// Nothing personal is active, so there is nothing to recover from.
    case nothingToRecover
    /// State is corrupt/future/unknown: never manufacture a pass (§8).
    case unavailable
}

extension FlintProtectionPolicy {

    /// One pass per rolling 604,800 s after committed use — no week boundary, no bank (§6).
    public static func passEligibility(_ usage: FlintPassUsage, hasActiveCommitment: Bool) -> FlintPassEligibility {
        switch usage {
        case .unknown:
            return .unavailable
        case let .used(elapsed) where elapsed < 0:
            return .unavailable
        case let .used(elapsed) where elapsed < FlintProtectionConstants.passPeriod:
            return .coolingDown(remaining: FlintProtectionConstants.passPeriod - elapsed)
        case .neverUsed, .used:
            return hasActiveCommitment ? .available : .nothingToRecover
        }
    }
}
