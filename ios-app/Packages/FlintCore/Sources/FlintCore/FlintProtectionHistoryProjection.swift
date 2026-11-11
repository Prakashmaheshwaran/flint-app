import Foundation

/// Projects the protection ledger's committed break and pass intents into focus history
/// (ADR-012 §11, `docs/architecture/focus-history.md`). The ledger is the outbox. An ordinary
/// break intent is acknowledged only after history's atomic ingress returns a positive
/// receipt. Every row has a stable replay ID, so a crash between the history write and the
/// acknowledgement replays into the same row. Any process may drain, including the monitor
/// extension while the host is dead; the host projects what is left on its next history
/// refresh. History stays local. Target scopes, tokens and rule identifiers never leave the
/// ledger: a row carries only session/grant/operation IDs, times, kind and reason.
public enum FlintProtectionHistoryProjection {
    /// History receipt for one intent and one frozen focus session. True only after durable ingress.
    public typealias BreakAppend = (FlintOrdinaryGrantState.EventIntent, String) -> Bool

    /// The production receipt: the App Group history inbox, usable from the host and extensions.
    public static let liveBreakAppend: BreakAppend = { intent, session in
        FlintHistoryStore.shared.append(FlintHistoryEvent(ordinaryBreak: intent, session: session))
    }

    /// `break_ended.reason`. The first four use the wire values Android writes; reboot and
    /// carrier failure are iOS-only machine codes. Aggregation does not interpret the reason.
    public static func wireReason(_ reason: FlintBreakEndReason) -> String {
        switch reason {
        case .deadline: return "expired"
        case .resumeNow: return "resumed"
        case .supersession, .newCommitment: return "superseded"
        case .pass: return "recovery"
        case .reboot: return "reboot"
        case .carrierFailed: return "carrier_failed"
        }
    }

    /// Delivers every unacknowledged break intent, then acknowledges the delivered rows by
    /// stable ID in one ledger transaction. Appends happen outside the ledger lock. Two
    /// concurrent drainers can both append a row; history keeps the first copy of an ID.
    /// A rejected append stays pending for a later drain. Corrupt retained outbox bytes are
    /// never emitted or discarded here. Returns false only when the ledger cannot be read or
    /// the acknowledgement cannot commit. Never call this inside a ledger transaction.
    @discardableResult
    public static func drainOrdinaryBreaks(ledger: FlintProtectionLedger, append: BreakAppend) -> Bool {
        guard case let .success(state) = ledger.peek() else { return false }
        let pending = state.ordinaryGrants?.pendingHistory() ?? []
        var delivered = Set<String>()
        for (intent, session) in pending where append(intent, session) {
            delivered.insert(intent.stableID(for: session))
        }
        guard !delivered.isEmpty else { return true }
        return (try? ledger.transaction { state in
            guard var grants = state.ordinaryGrants else { return }
            try grants.acknowledgeHistory(delivered)
            state.ordinaryGrants = grants
        }.get()) != nil
    }

    /// Host-only: re-publishes the last committed Emergency Pass if history lacks it. The
    /// enforcement owner acknowledges its own `eventPending` flag whether or not history
    /// accepted the row, so a failed write must never block the next pass. This replay
    /// recovers that row instead. The affected session's `ended_early(emergency_pass)` is
    /// replayed only when the session authority durably records that exact terminal. A
    /// terminal is never inferred from the pass scope alone. Returns the number of rows published.
    @discardableResult
    static func replayCommittedPass(_ state: FlintLedgerState, snapshot: FlintHistorySnapshot,
                                    now: Date, timeZone: TimeZone = .current,
                                    append: (FlintHistoryEvent) -> Bool) -> Int {
        guard state.hasValidPassRecovery, let recovery = state.emergencyRecovery,
              let historyStart = snapshot.historyStart else { return 0 }
        let acceptedAt = recovery.acceptedAt.wall
        // Older than history (cleared or created later) or retention: never resurrect it.
        guard acceptedAt >= historyStart, now.timeIntervalSince(acceptedAt) < 399 * 86_400 else { return 0 }
        let observed = now == acceptedAt ? nil : now
        var rows = [FlintHistoryEvent(.annotated(.globalPassUsed(operationID: recovery.operationID,
            sessions: recovery.scope.sessions, recoveryEnd: recovery.recoveryEnd, at: acceptedAt),
            origin: "app_ui", reason: "emergency_pass", observedAt: observed), timeZone: timeZone)]
        if !recovery.releasePending, let starts = state.sessionAuthority?.acceptedStarts {
            for session in recovery.scope.sessions {
                guard let proof = starts.last(where: { $0.sessionID == session.id }),
                      proof.outcome == .stopped, proof.endRoute == FlintSessionEndRoute.emergencyPass.rawValue,
                      let endedAt = proof.endedAt else { continue }
                rows.append(FlintHistoryEvent(.annotated(.ended(session, route: .emergencyPass, at: endedAt),
                    origin: nil, reason: "emergency_pass", observedAt: observed), timeZone: timeZone))
            }
        }
        let retained = Set(snapshot.events.map(\.id))
        return rows.filter { !retained.contains($0.id) }.reduce(0) { count, row in
            var replay = row; replay.inferred = true
            return append(replay) ? count + 1 : count
        }
    }

    /// The host history refresh step: drain break intents left by any process, then replay a
    /// missing pass row. Publishes only to the inbox; the caller's next read drains it.
    public static func project(ledger: FlintProtectionLedger?, store: FlintHistoryStore,
                               snapshot: FlintHistorySnapshot, now: Date = Date()) {
        guard let ledger else { return }
        drainOrdinaryBreaks(ledger: ledger) { store.append(FlintHistoryEvent(ordinaryBreak: $0, session: $1)) }
        guard case let .success(state) = ledger.peek() else { return }
        replayCommittedPass(state, snapshot: snapshot, now: now, append: store.append)
    }

    /// Production host projection, from the App Group ledger.
    public static func projectLive(store: FlintHistoryStore, snapshot: FlintHistorySnapshot) {
        project(ledger: FlintProtectionLedger.appGroup(), store: store, snapshot: snapshot)
    }
}

extension FlintHistoryEvent {
    /// One frozen focus session's row for an ordinary break boundary. The logical time is the
    /// owner's accepted/ended instant; a later observation is kept separately. A reboot or late
    /// deadline end is a reconciliation (`inferred`); uncertainty stays explicit.
    public init(ordinaryBreak intent: FlintOrdinaryGrantState.EventIntent, session: String,
                timeZone: TimeZone = .current) {
        let started = intent.kind == .started
        let late = intent.observedAt.timeIntervalSince(intent.at) > 2
        let inferred = !started && (intent.reason == .reboot || (intent.reason == .deadline && late))
        self.init(type: started ? .breakStarted : .breakEnded, session: session, at: intent.at,
                  id: intent.stableID(for: session), timeZone: timeZone, inferred: inferred ? true : nil,
                  breakEnd: started ? intent.breakEnd : nil,
                  reason: started ? nil : intent.reason.map(FlintProtectionHistoryProjection.wireReason),
                  observedAt: FlintHistoryEvent.milliseconds(intent.observedAt) == FlintHistoryEvent.milliseconds(intent.at)
                    ? nil : intent.observedAt,
                  kind: intent.breakKind, grant: intent.grantID, timeConfidence: intent.timeConfidence)
    }
}

extension FlintOrdinaryGrantState {
    /// Unacknowledged (intent, session) pairs, live records first, then the archived outbox.
    /// Undecodable outbox bytes are skipped (retained in place, never authorization).
    func pendingHistory() -> [(EventIntent, String)] {
        let decoder = FlintProtectionLedger.makeDecoder()
        let archived = eventOutbox.compactMap { try? decoder.decode(EventIntent.self, from: $0) }
        return (records.flatMap(\.events) + archived).flatMap { intent in
            intent.focusSessionIDs.subtracting(intent.acknowledgedSessionIDs).sorted().map { (intent, $0) }
        }
    }

    /// Marks exactly the delivered stable rows acknowledged. A fully acknowledged outbox
    /// intent is removed; corrupt and partly delivered ones stay. Then the usual bounded
    /// audit trim and archive of applied records run, as for any ordinary-grant write.
    mutating func acknowledgeHistory(_ delivered: Set<String>) throws {
        func acknowledge(_ intent: inout EventIntent) {
            for session in intent.focusSessionIDs where delivered.contains(intent.stableID(for: session)) {
                intent.acknowledgedSessionIDs.insert(session)
            }
        }
        for i in records.indices { for j in records[i].events.indices { acknowledge(&records[i].events[j]) } }
        let decoder = FlintProtectionLedger.makeDecoder(), encoder = FlintProtectionLedger.makeEncoder()
        var retained: [Data] = []
        for raw in eventOutbox {
            guard var intent = try? decoder.decode(EventIntent.self, from: raw) else { retained.append(raw); continue }
            let before = intent
            acknowledge(&intent)
            if intent.isAcknowledged { continue }
            retained.append(intent == before ? raw : try encoder.encode(intent))
        }
        eventOutbox = retained
        trimAudit()
        try archiveFinished()
    }
}
