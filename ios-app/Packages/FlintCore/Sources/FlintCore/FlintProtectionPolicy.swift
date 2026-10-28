import Foundation

// ADR-012 (docs/architecture/protection-contract.md) as pure decisions. Every entry point —
// Today, Shortcuts/Siri, the Focus filter, rule editors, the shield action, the monitor — asks
// these functions at commit time; UI-disabled controls alone are never the gate (§2). Pure
// Foundation and generic over the target identity, so every matrix cell is unit-tested without
// tokens.
//
// Not decided here, by design: the §2 **OS** rows (uninstalling Flint, revoking Screen Time,
// opening recovery Settings, changing the clock or time zone). Flint never intercepts those
// (§7, §10); callers reconcile afterwards (§8, §9) instead of asking for permission.

// MARK: - Constants (§1, §4, §5, §6)

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
public struct FlintCauseState: Codable, Equatable, Sendable {
    public enum Kind: String, Codable, Sendable {
        case session          // manual / Block Now / Shortcut / Siri / Focus-filter session
        case pomodoro
        case recurringSchedule
        case oneShotSchedule
        case sleep
        case timeLimit
        case openLimit
    }

    /// `(causeId, occurrenceId)` identity and the policy revision the decision was made on (§1),
    /// so batch results, Harder counts and deferrals name exactly the occurrence they belong to
    /// and a stale decision can be rejected at commit.
    public var causeID: String
    public var occurrenceID: String
    public var revision: Int
    public var kind: Kind
    public var tier: BreakLevel
    /// An active commitment has started and not terminated — including during a break, a
    /// Pomodoro rest or a limit's unspent budget (§1). Not "a shield is visible".
    public var isActive: Bool
    /// The current occurrence's natural end. nil only for a legacy open-ended session (§12):
    /// new strict causes are always finite (§1).
    public var end: Date?
    /// Committed Harder weakening operations on this occurrence (§4's `n`).
    public var harderCount: Int
    /// The occurrence was already running under a pre-contract build (§12) and keeps the rights
    /// that build promised until it ends.
    public var legacyRights: Bool

    public init(
        causeID: String,
        occurrenceID: String? = nil,
        revision: Int = 0,
        kind: Kind,
        tier: BreakLevel,
        isActive: Bool,
        end: Date? = nil,
        harderCount: Int = 0,
        legacyRights: Bool = false
    ) {
        self.causeID = causeID
        self.occurrenceID = occurrenceID ?? causeID
        self.revision = revision
        self.kind = kind
        self.tier = tier
        self.isActive = isActive
        self.end = end
        self.harderCount = harderCount
        self.legacyRights = legacyRights
    }

    /// Rule-backed causes (templates with occurrences) versus transient sessions (§6 steps 1–2).
    public var isRuleBacked: Bool {
        switch kind {
        case .session, .pomodoro: return false
        case .recurringSchedule, .oneShotSchedule, .sleep, .timeLimit, .openLimit: return true
        }
    }
}

// MARK: - Actions and decisions (§2)

/// Weakening and exception requests against an existing cause. Starting a cause, accepting an
/// Open credit and spending the Emergency Pass have their own functions below, because their
/// inputs differ (the new cause, the other contributors, the pass timeline).
public enum FlintProtectionAction: String, Codable, Equatable, Sendable {
    /// Replace a running session with protection proven to dominate it (§3).
    case replaceDominating
    /// Replace a running session with weaker or incomparable protection.
    case replaceWeaker
    /// Stop / end the current occurrence early, from inside Flint.
    case stopEarly
    /// The cause's owning automation ended (e.g. its Focus turned off): an early-stop request
    /// from that owner, with the same tier rights as any other route (§1, PC-08).
    case ownerEnded
    /// Ordinary temporary break/access (300 s scoped grant).
    case ordinaryBreak
    /// Rename / presentation only.
    case editCosmetic
    /// Provably no target or time becomes allowed and no exit becomes easier.
    case editStrengthen
    /// Anything else, including any change that can't be proven to strengthen.
    case editWeaken
    case toggleOff
    case delete
}

public enum FlintProtectionDecision: Codable, Equatable, Sendable {
    /// Proceed. The contract's Allow cell always previews scope/deadline before persisting;
    /// `confirm` says whether an explicit confirmation step is also required.
    case allow(confirm: Bool)
    /// Harder friction: a persisted wait of `seconds` must mature, then an explicit confirm.
    /// Background routes report `confirmation_required` and never complete automatically.
    case wait(seconds: TimeInterval)
    /// Save for after the current occurrence ends; no immediate reduction.
    case deferUntil(Date)
    /// No mutation: show cause, tier, end, pass status and a safe route back.
    case deny(FlintDenialReason)
    /// Nothing to do ("Not running" / "No access is blocked").
    case notApplicable
}

public enum FlintDenialReason: String, Codable, Equatable, Sendable {
    /// Active Hardcore: ordinary early weakening is denied; expiry or a pass are the exits.
    case hardcoreCommitment = "hardcore_commitment"
    /// A replacement that would weaken an active Hardcore session; start it independently.
    case wouldWeakenHardcore = "would_weaken_hardcore"
    /// A legacy open-ended strict session: it ends only through the route its old build
    /// promised (its Focus turning off) or an Emergency Pass — never an endless "save for later".
    case legacyOpenEnded = "legacy_open_ended"
    /// The platform can't represent another cause; existing protection is untouched (§3).
    case capacityUnavailable = "capacity_unavailable"
    /// Another active cause would still block the target, so no credit is charged (§5).
    case otherCauseBlocks = "other_cause_blocks"
    /// The target's daily credits are used up.
    case noCreditsLeft = "no_credits_left"
    /// Corrupt/unknown counter state: never charge or grant (§8).
    case stateUnavailable = "state_unavailable"
}

public enum FlintProtectionPolicy {

    /// Harder delay for a cause that has already committed `count` weakening operations.
    public static func harderWait(forCount count: Int) -> TimeInterval {
        let waits = FlintProtectionConstants.harderWaits
        return waits[min(max(count, 0), waits.count - 1)]
    }

    /// The §2 decision for one action on one affected cause, by the cause's **current** tier.
    public static func decide(_ action: FlintProtectionAction, for cause: FlintCauseState) -> FlintProtectionDecision {
        guard cause.isActive else { return decideInactive(action) }
        if cause.legacyRights, let legacy = decideLegacy(action, for: cause) { return legacy }

        let weakening: FlintProtectionDecision
        switch cause.tier {
        case .easy:
            weakening = .allow(confirm: true)
        case .harder:
            weakening = .wait(seconds: harderWait(forCount: cause.harderCount))
        case .hardcore:
            weakening = .deny(.hardcoreCommitment)
        }

        switch action {
        case .replaceDominating:
            return .allow(confirm: true)
        case .editCosmetic:
            return .allow(confirm: false)
        case .editStrengthen:
            return .allow(confirm: true)
        case .replaceWeaker:
            return cause.tier == .hardcore ? .deny(.wouldWeakenHardcore) : weakening
        case .stopEarly, .ownerEnded, .ordinaryBreak:
            return weakening
        case .editWeaken, .toggleOff, .delete:
            guard cause.tier == .hardcore else { return weakening }
            // Future-only change, applied once this occurrence ends (§2). Without a finite end
            // there is no "after", so never offer a deferral that can't happen.
            guard let end = cause.end else { return .deny(.legacyOpenEnded) }
            return .deferUntil(end)
        }
    }

    private static func decideInactive(_ action: FlintProtectionAction) -> FlintProtectionDecision {
        switch action {
        case .replaceDominating, .replaceWeaker, .stopEarly, .ownerEnded, .ordinaryBreak:
            return .notApplicable
        case .editCosmetic, .editWeaken:
            return .allow(confirm: false)
        case .editStrengthen, .toggleOff, .delete:
            return .allow(confirm: true)
        }
    }

    /// §12: an occurrence already running under the old build keeps that build's promised
    /// rights until it ends. nil means "the current contract applies to this action".
    private static func decideLegacy(_ action: FlintProtectionAction, for cause: FlintCauseState) -> FlintProtectionDecision? {
        if cause.isRuleBacked {
            // Old builds let any rule be edited, switched off or deleted at every tier. Keep that
            // for the occurrence that is already running; new occurrences follow the contract.
            switch action {
            case .editWeaken, .toggleOff, .delete: return .allow(confirm: true)
            default: return nil
            }
        }
        guard cause.end == nil, cause.tier != .easy else { return nil }
        // Legacy open-ended strict session (an old Harder/Hardcore Focus-filter block): its Focus
        // turning off was its promised end. Old Harder also allowed an in-app stop after a wait,
        // which the current contract keeps; old Hardcore had no in-app exit but the pass.
        switch action {
        case .ownerEnded:
            return .allow(confirm: false)
        case .stopEarly, .replaceWeaker, .ordinaryBreak:
            return cause.tier == .hardcore ? .deny(.legacyOpenEnded) : nil
        default:
            return nil
        }
    }

    /// One operation touching several causes (§2 "a batch touches every affected cause
    /// atomically"). Precedence: any deny denies; else any defer defers the whole batch to the
    /// latest affected end — and the deferred batch is decided again when it applies, so a
    /// Harder cause still active then still gets its wait; else the longest wait (§4); else
    /// allow. Not-applicable causes don't contribute.
    public static func decide(_ action: FlintProtectionAction, for causes: [FlintCauseState]) -> FlintProtectionDecision {
        let decisions = causes.map { decide(action, for: $0) }.filter { $0 != .notApplicable }
        guard !decisions.isEmpty else { return .notApplicable }
        for decision in decisions { if case .deny = decision { return decision } }
        let defers = decisions.compactMap { decision -> Date? in
            if case let .deferUntil(end) = decision { return end }
            return nil
        }
        if let latest = defers.max() { return .deferUntil(latest) }
        let waits = decisions.compactMap { decision -> TimeInterval? in
            if case let .wait(seconds) = decision { return seconds }
            return nil
        }
        if let longest = waits.max() { return .wait(seconds: longest) }
        return .allow(confirm: decisions.contains(.allow(confirm: true)))
    }
}

// MARK: - Starting a cause (§2 row 1, §3 capacity)

extension FlintProtectionPolicy {

    /// Start a new cause beside the existing ones, retiring none (§2 "Start another cause").
    /// A new Hardcore cause requires confirmation of its finite end and rights, including from
    /// automation (§2 "Raising the tier requires explicit confirmation"). When the platform
    /// can't represent another cause the start is refused and existing protection stays (§3).
    public static func decideStart(newTier: BreakLevel, capacityAvailable: Bool) -> FlintProtectionDecision {
        guard capacityAvailable else { return .deny(.capacityUnavailable) }
        return .allow(confirm: newTier == .hardcore)
    }

    /// Commit-time replacement check: dominance is computed here from the snapshots, never
    /// trusted from the caller (§2, §3).
    public static func decideReplace<T>(
        _ replacement: FlintProtectionSnapshot<T>,
        over old: FlintProtectionSnapshot<T>,
        for cause: FlintCauseState
    ) -> FlintProtectionDecision {
        decide(dominates(replacement, over: old) ? .replaceDominating : .replaceWeaker, for: cause)
    }

    /// Commit-time edit check: the impact is computed here from the snapshots (§2).
    public static func decideEdit<T>(
        from old: FlintProtectionSnapshot<T>,
        to new: FlintProtectionSnapshot<T>,
        for cause: FlintCauseState
    ) -> FlintProtectionDecision {
        switch editImpact(from: old, to: new) {
        case .cosmetic: return decide(.editCosmetic, for: cause)
        case .strengthen: return decide(.editStrengthen, for: cause)
        case .weaken: return decide(.editWeaken, for: cause)
        }
    }
}

// MARK: - Open credits (§5)

extension FlintProtectionPolicy {

    /// Accept one Open credit for a target. Quota credits are part of the commitment at every
    /// tier (no Harder wait), but a credit is never charged while any other active cause would
    /// still block the target — including an unprovable category overlap, which callers report
    /// as an enforcing contributor (§5, PC-32, PC-33).
    ///
    /// - Parameters:
    ///   - creditsRemaining: nil when the counter state is unavailable/corrupt.
    ///   - otherEnforcing: every other active cause enforcing on this target right now.
    public static func decideOpenCredit(
        creditsRemaining: Int?,
        otherEnforcing: [FlintCauseState]
    ) -> FlintProtectionDecision {
        guard let creditsRemaining else { return .deny(.stateUnavailable) }
        guard otherEnforcing.allSatisfy({ !$0.isActive }) else { return .deny(.otherCauseBlocks) }
        guard creditsRemaining > 0 else { return .deny(.noCreditsLeft) }
        return .allow(confirm: true)
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
    /// The cause's daily budget or credit cap. nil means the cause kind has no budget at all —
    /// not "unlimited"; adding or removing one is a change of kind, never a proven strengthening.
    public var budget: Int?

    public init(mode: Mode, tier: BreakLevel, end: Date?, budget: Int? = nil) {
        self.mode = mode
        self.tier = tier
        self.end = end
        self.budget = budget
    }
}

public enum FlintEditImpact: String, Codable, Equatable, Sendable {
    case cosmetic
    case strengthen
    case weaken
}

extension FlintProtectionPolicy {

    /// True when `new` provably blocks at least everything `old` does: block set grows (or
    /// allow set shrinks) within the same mode, tier does not decrease, end does not move
    /// earlier, budget does not increase. Mode changes, opaque-category removals and budget
    /// kind changes are never provable (§2). An open-ended `new` is never "stronger" than a
    /// finite `old`: that would create an unbounded commitment, which §1 forbids.
    public static func isAtLeastAsStrong<T>(_ new: FlintProtectionSnapshot<T>, as old: FlintProtectionSnapshot<T>) -> Bool {
        guard rank(new.tier) >= rank(old.tier) else { return false }
        switch (old.end, new.end) {
        case (nil, nil): break
        case (nil, .some), (.some, nil): return false
        case let (.some(oldEnd), .some(newEnd)): if newEnd < oldEnd { return false }
        }
        switch (old.budget, new.budget) {
        case (nil, nil): break
        case (nil, .some), (.some, nil): return false
        case let (.some(oldBudget), .some(newBudget)): if newBudget > oldBudget { return false }
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

public enum FlintCauseIssue: String, Codable, Equatable, Sendable {
    /// Harder/Hardcore (and every Pomodoro) need a finite end; they are not permanent locks.
    case needsFiniteEnd = "needs_finite_end"
    /// Longer than 24 h for a session / 26 h for an occurrence.
    case tooLong = "too_long"
    /// Zero, negative or not a number.
    case nonPositive = "non_positive"
}

extension FlintProtectionPolicy {

    /// Validate a new session or Pomodoro parent. `duration == nil` means "until I stop", which
    /// only an Easy session may use — including a Focus filter's "while Focus is on" (§1).
    public static func issue(
        forNew kind: FlintCauseState.Kind,
        tier: BreakLevel,
        duration: TimeInterval?
    ) -> FlintCauseIssue? {
        guard let duration else {
            return kind == .session && tier == .easy ? nil : .needsFiniteEnd
        }
        guard duration.isFinite, duration > 0 else { return .nonPositive }
        if duration > FlintProtectionConstants.maximumSessionDuration { return .tooLong }
        return nil
    }

    /// Validate one materialized recurring/daily occurrence (start→end instants).
    public static func issue(forOccurrenceFrom start: Date, to end: Date) -> FlintCauseIssue? {
        let span = end.timeIntervalSince(start)
        guard span.isFinite, span > 0 else { return .nonPositive }
        if span > FlintProtectionConstants.maximumOccurrenceDuration { return .tooLong }
        return nil
    }
}

// MARK: - Emergency Pass eligibility (§6)

/// What the trusted elapsed timeline knows about the last committed pass use.
public enum FlintPassUsage: Codable, Equatable, Sendable {
    /// A validated install with no recorded use.
    case neverUsed
    /// Elapsed seconds since the committed use, on the anchored timeline (§9).
    case used(elapsed: TimeInterval)
    /// Corrupt, future, unsupported or unreadable state.
    case unknown
}

public enum FlintPassEligibility: Codable, Equatable, Sendable {
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
    /// Spending also needs at least one active personal commitment.
    public static func passEligibility(_ usage: FlintPassUsage, hasActiveCommitment: Bool) -> FlintPassEligibility {
        switch usage {
        case .unknown:
            return .unavailable
        case let .used(elapsed) where !elapsed.isFinite || elapsed < 0:
            return .unavailable
        case let .used(elapsed) where elapsed < FlintProtectionConstants.passPeriod:
            return .coolingDown(remaining: FlintProtectionConstants.passPeriod - elapsed)
        case .neverUsed, .used:
            return hasActiveCommitment ? .available : .nothingToRecover
        }
    }

    /// The same, deciding "something is active" from the causes themselves.
    public static func passEligibility(_ usage: FlintPassUsage, causes: [FlintCauseState]) -> FlintPassEligibility {
        passEligibility(usage, hasActiveCommitment: causes.contains { $0.isActive })
    }
}
