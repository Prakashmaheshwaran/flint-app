import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls

/// All methods run inside FlintSessionTransitionLock. Ledger transactions are nested only
/// inside that transition, never inside another ledger transaction. UserDefaults is the session
/// projection; this ledger generation is the authority for acceptance and termination.
final class FlintSessionPersistence {
    let env: FlintSessionEnvironment
    init(environment: FlintSessionEnvironment) { env = environment }

    struct Resolution {
        var available: Bool
        var session: FlintActiveSession? = nil
        var timeVerdict: FlintTimeIntegrity.Verdict = .unverifiable
        var releaseAlreadyObserved = false
    }

    func unavailable() -> Resolution {
        FlintSessionHealth.update(in: env.healthDefaults()) {
            $0.lastArmFailure = .init(sessionName: "Focus session",
                reason: "the running session's generation or record can't be read", at: env.clock.now())
        }
        return Resolution(available: false)
    }

    /// The ordinary pass preparation reads the complete scope. Finish an already accepted
    /// installation first, so its intentional projection gap cannot deadlock that scope read.
    /// No new commitment or pass authority is created here.
    func drainAcceptedInstallation() {
        guard let ledger = env.ledger(), case let .success(state) = ledger.peek(),
              let authority = state.sessionAuthority, authority.isValid, !authority.provesIdle,
              let generation = authority.latest else { return }
        guard let store = env.store(), store.isProtectionStateReadable() else { _ = unavailable(); return }
        let raw = store.loadActiveSession()
        if generation.phase == .applied {
            if raw == nil { _ = unavailable() }
            return
        }
        let status = state.pass.status(at: .init(clock: env.clock), hasActiveCommitment: true)
        let projectionMatches = raw.map {
            generation.matches($0) || ($0.id == generation.previousSession?.id && $0.monitorName == generation.previousMonitorName)
        } ?? !store.hasActiveSessionRecord()
        guard env.recoveryGate().permitsApplication, state.hasValidPassRecovery,
              status.eligibility != .unavailable, !status.isRecovering,
              state.emergencyRecovery?.needsApplication(isRecovering: false) != true,
              projectionMatches,
              finishInstallation(generation, in: store) else { _ = unavailable(); return }
    }

    func authority(in store: FlintSessionStateStoring) -> FlintSessionAuthority? {
        guard let ledger = env.ledger() else { return nil }
        let raw = store.loadActiveSession()
        if raw == nil && store.hasActiveSessionRecord() { return nil }
        let result = ledger.transaction { state -> FlintSessionAuthority? in
            var authority = state.sessionAuthority
            if authority == nil && (state.version >= 3 || state.sessionAuthorityVersion != nil) { return nil }
            // Shipped legacy ledgers had no generation field. Upgrade a positively readable
            // legacy absence once; after this migration only the latest tombstone proves idle.
            if authority == nil, store.isProtectionStateReadable() {
                authority = FlintSessionAuthority(initializedAt: state.initializedAt)
                if let raw { authority?.adopt(raw) }
            } else if authority?.latest == nil, let raw {
                authority?.adopt(raw)
            }
            guard let authority, authority.isValid else { return nil }
            state.sessionAuthority = authority
            state.sessionAuthorityVersion = 1
            return authority
        }
        guard store.isProtectionStateReadable() else { return nil }
        return (try? result.get()) ?? nil
    }

    func resolve(in store: FlintSessionStateStoring?) -> Resolution {
        guard let store, let authority = authority(in: store) else { return unavailable() }
        let raw = store.loadActiveSession()
        if raw == nil && store.hasActiveSessionRecord() { return unavailable() }
        if let generation = authority.latest, generation.phase == .installing, !authority.provesIdle {
            guard raw == nil || generation.matches(raw!)
                || (raw?.id == generation.previousSession?.id && raw?.monitorName == generation.previousMonitorName),
                  finishInstallation(generation, in: store) else { return unavailable() }
            return resolve(in: store)
        }
        if authority.provesIdle {
            if let session = authority.latest?.session, !emitStartIfPending(session) { return unavailable() }
            if let raw {
                guard authority.latest?.matches(raw) == true else { return unavailable() }
                // A crash after the terminal commit can leave the old projection/resources.
                if raw.naturalEndObservedAt == nil { env.shield.clear() }
                store.clearActiveSession(); store.clearSessionSelection()
                env.registrar.stopMonitoring([raw.monitorName])
            }
            return Resolution(available: true,
                releaseAlreadyObserved: raw != nil || authority.latest?.session.naturalEndObservedAt != nil)
        }
        guard let raw, let observed = authority.observedSession(matching: raw, clock: env.clock) else {
            return unavailable()
        }
        let verdict = FlintTimeIntegrity.verdict(for: observed, clock: env.clock)
        let session = observed.checkpointed(clock: env.clock)
        guard let ledger = env.ledger(), case .success = ledger.transaction({ state in
            guard var current = state.sessionAuthority else { throw FlintSessionAuthorityError.generationMismatch }
            try current.checkpoint(session)
            state.sessionAuthority = current
        }) else { return unavailable() }
        if raw != session { store.saveActiveSession(session) }
        guard store.loadActiveSession() == session else { return unavailable() }
        guard emitStartIfPending(session) else { return unavailable() }
        if let observedEnd = session.naturalEndObservedAt {
            guard session.isLogicallyExpired(clock: env.clock),
                  terminate(session, outcome: .unconfirmed, route: "natural_end_unconfirmed", at: observedEnd) != nil else {
                return unavailable()
            }
            store.clearActiveSession(); store.clearSessionSelection()
            env.registrar.stopMonitoring([session.monitorName])
            return Resolution(available: true, timeVerdict: verdict, releaseAlreadyObserved: true)
        }
        return Resolution(available: true, session: session, timeVerdict: verdict)
    }

    /// Completion of one already-accepted desired state, including a crash before its session
    /// save. An applied generation never enters this recovery on an unexplained missing key.
    func finishInstallation(_ generation: FlintSessionAuthority.Generation,
                            in store: FlintSessionStateStoring) -> Bool {
        guard env.recoveryGate().permitsApplication else { return false }
        let session = generation.session.checkpointed(clock: env.clock)
        let selection = generation.selection.flatMap { try? JSONDecoder().decode(FamilyActivitySelection.self, from: $0) }
        if generation.selection != nil && selection == nil { return false }
        if session.isLogicallyExpired(clock: env.clock) {
            // Acceptance alone is not evidence that protection applied for the interval.
            // Retire this exact desired generation as unconfirmed, never a fabricated start
            // or completion. Its projection lets ordinary tombstone recovery release once.
            store.saveActiveSession(session)
            guard store.loadActiveSession() == session,
                  terminate(session, outcome: .unconfirmed, route: "start_expired_unapplied", at: env.clock.now()) != nil else { return false }
            if let previous = generation.previousMonitorName, previous != session.monitorName {
                env.registrar.stopMonitoring([previous])
            }
            FlintSessionHealth.update(in: env.healthDefaults()) {
                $0.lastArmFailure = .init(sessionName: session.name,
                    reason: "the accepted start expired before protection was applied", at: env.clock.now())
            }
            return true
        }
        if !session.isLogicallyExpired(clock: env.clock), let end = session.logicalEnd(clock: env.clock),
           !env.registrar.registeredActivityNames().contains(session.monitorName) {
            let plan = FlintSessionAutoClearPlan.make(start: env.clock.now(), end: end,
                calendar: env.clock.calendar(), strategy: .endWarning)
            do { try env.registrar.startMonitoring(session.monitorName, plan: plan) } catch { return false }
        }
        if let selection { store.saveSessionSelection(selection, sessionID: session.id) }
        store.saveActiveSession(session)
        guard store.loadActiveSession() == session else { return false }
        if !session.isLogicallyExpired(clock: env.clock) {
            do { if let selection { try env.shield.applyBlockList(selection) } } catch { return false }
            env.webRestrictions.reconcile(session, clock: env.clock)
            env.shield.setDenyAppRemoval(FlintUninstallGuard.shouldDeny(for: session, clock: env.clock))
        }
        guard markApplied(session) else { return false }
        if let previous = generation.previousMonitorName, previous != session.monitorName {
            env.registrar.stopMonitoring([previous])
        }
        return emitStartIfPending(session)
    }

    /// Keep the start pending until synchronous lifecycle delivery returns. The flag repairs a
    /// crash after application but before delivery; it does not claim sink-level exactly-once IO.
    func emitStartIfPending(_ session: FlintActiveSession) -> Bool {
        guard let ledger = env.ledger(), case let .success(state) = ledger.peek(),
              let generation = state.sessionAuthority?.latest, generation.matches(session) else { return false }
        guard generation.phase == .applied, generation.emitsStart == true, generation.startEventEmitted != true else { return true }
        FlintSessionLifecycle.didStart(generation.session, replacing: generation.previousSession,
            source: generation.source, at: generation.session.startedAt)
        guard case .success = ledger.transaction({ state in
            guard state.sessionAuthority?.latest?.matches(session) == true else {
                throw FlintSessionAuthorityError.generationMismatch
            }
            state.sessionAuthority?.latest?.startEventEmitted = true
        }) else { return false }
        return true
    }

    func markApplied(_ session: FlintActiveSession) -> Bool {
        guard let ledger = env.ledger(), case .success = ledger.transaction({ state in
            guard var authority = state.sessionAuthority else { throw FlintSessionAuthorityError.generationMismatch }
            try authority.checkpoint(session)
            try authority.markApplied(session)
            state.sessionAuthority = authority
        }) else { return false }
        return true
    }

    func terminate(_ session: FlintActiveSession, outcome: FlintAcceptedStartResult.Outcome,
                   route: String, at date: Date) -> Bool? {
        guard let ledger = env.ledger(), case let .success(changed) = ledger.transaction({ state -> Bool in
            guard var authority = state.sessionAuthority else { throw FlintSessionAuthorityError.generationMismatch }
            if !authority.provesIdle { try authority.checkpoint(session) }
            let changed = try authority.terminate(session, outcome: outcome, route: route, at: date)
            state.sessionAuthority = authority
            state.resetHarder(cause: session.id)
            return changed
        }) else { _ = unavailable(); return nil }
        return changed
    }
}
#endif
