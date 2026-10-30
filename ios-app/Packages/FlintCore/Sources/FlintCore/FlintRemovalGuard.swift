import Foundation

/// Pure ownership decisions; the reconciler alone owns ManagedSettings writes.
/// The adapter must supply the complete owner inventory, not only a callback's
/// owner. nil inventory and any unreadable owner return keep, even beside an eligible
/// owner: incomplete state authorizes neither a new denial nor a release (ADR-012 §8).
public enum FlintRemovalGuard {
    public enum Decision: Equatable, Sendable { case deny, release, keep }

    public static func decide(owners: [FlintRemovalGuardOwner]?, clock: FlintClock) -> Decision {
        guard let owners, owners.allSatisfy(isReadable) else { return .keep }
        let now = FlintLedgerInstant(clock: clock)
        var deny = false
        for owner in owners {
            guard let cause = owner.cause, cause.isActive, cause.tier == .hardcore,
                  owner.removalGuard == true, owner.passRecovered == false,
                  owner.registration == .observed, cause.end != nil else { continue }
            switch remaining(owner, at: now) {
            case .unavailable: return .keep
            case .uncertain: return .keep
            case let .known(seconds): deny = deny || seconds > 0
            }
        }
        return deny ? .deny : .release
    }

    /// Plans a preference change; it does not save, queue, or mutate captured opt-ins.
    /// Phase 2 must recheck at commit under the shared transition lock and persist a
    /// deferred off separately, applying it before accepting the next occurrence.
    /// Registration loss does not erase an active commitment's weakening rights.
    public static func decidePreferenceChange(to enabled: Bool, owners: [FlintRemovalGuardOwner]?,
                                              clock: FlintClock) -> FlintProtectionDecision {
        if enabled { return .allow(confirm: true) } // New captures only; disclosure still required.
        guard let owners, owners.allSatisfy(isReadable) else { return .deny(.stateUnavailable) }
        let now = FlintLedgerInstant(clock: clock)
        var affected: [FlintCauseState] = []
        for owner in owners {
            guard var cause = owner.cause, cause.isActive, owner.removalGuard == true,
                  owner.passRecovered == false, cause.end != nil else { continue }
            guard case let .known(seconds) = remaining(owner, at: now) else {
                return .deny(.stateUnavailable)
            }
            guard seconds > 0 else { continue }
            // A wall-clock jump must not shorten the deferral. Rebase its display date
            // from the same logical deadline used by the guard decision.
            cause.end = now.wall.addingTimeInterval(seconds)
            guard cause.end?.timeIntervalSince1970.isFinite == true else { return .deny(.stateUnavailable) }
            // Legacy template edit rights do not authorize weakening a captured guard.
            // Migration still leaves captured opt-in nil, so this cannot tighten old causes.
            cause.legacyRights = false
            affected.append(cause)
        }
        return affected.isEmpty ? .allow(confirm: true) : FlintProtectionPolicy.decide(.toggleOff, for: affected)
    }

    private static func isReadable(_ owner: FlintRemovalGuardOwner) -> Bool {
        guard let cause = owner.cause, !cause.causeID.isEmpty, !cause.occurrenceID.isEmpty,
              cause.revision >= 0, owner.registration != .unavailable,
              owner.passRecovered != nil else { return false }
        if let end = cause.end, !end.timeIntervalSince1970.isFinite { return false }
        if let end = owner.logicalEnd {
            guard end.wall.timeIntervalSince1970.isFinite, end.monotonic.isFinite,
                  end.monotonic >= 0 else { return false }
        }
        return true
    }

    private enum Remaining { case known(TimeInterval), uncertain, unavailable }

    private static func remaining(_ owner: FlintRemovalGuardOwner, at now: FlintLedgerInstant) -> Remaining {
        guard owner.timeKnown else { return .uncertain }
        // Claiming known finite time without a deadline is an unreadable snapshot.
        guard let end = owner.logicalEnd else { return .unavailable }
        guard now.wall.timeIntervalSince1970.isFinite, now.monotonic.isFinite,
              now.monotonic >= 0 else { return .unavailable }
        guard let boot = now.bootID, !boot.isEmpty, boot == end.bootID else { return .uncertain }
        let seconds = max(0, end.monotonic - now.monotonic)
        guard seconds <= FlintProtectionConstants.maximumOccurrenceDuration else { return .unavailable }
        return .known(seconds)
    }
}
