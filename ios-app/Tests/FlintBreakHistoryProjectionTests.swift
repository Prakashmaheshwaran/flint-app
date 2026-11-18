import XCTest
@testable import FlintCore

/// F05 iOS: durable typed break/pass history (ADR-012 §11, focus-history.md). Every case is a
/// FIXTURE: the protection ledger and history store are real files in a temporary directory,
/// while Screen Time registration, shields and the session owner are fakes. "Monitor" and
/// "app" are separate store instances on one directory, not separate OS processes. Nothing
/// here proves physical-device callback delivery.
final class FlintBreakHistoryProjectionTests: XCTestCase {
    private typealias Target = FlintBreakScope.Target
    private let token = Target(kind: .application, token: Data("fixture-app-token".utf8))
    private var directory: URL!
    private var marker: Date?
    private var wall = Date(timeIntervalSince1970: 1_800_000_000) // the break request, t0 + 600
    private var mono: Double = 1000
    private var boot: String? = "boot-1"
    private var carriers: Set<String> = []
    private var passSessions: [FlintActiveSession] = []
    private var t0: Date { Date(timeIntervalSince1970: 1_800_000_000 - 600) }
    private var utc: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("flint-break-history-\(UUID().uuidString)")
        marker = nil; wall = Date(timeIntervalSince1970: 1_800_000_000); mono = 1000; boot = "boot-1"
        carriers = []; passSessions = [session]
    }
    override func tearDown() { try? FileManager.default.removeItem(at: directory); super.tearDown() }

    // MARK: Fixture harness

    private var clock: FlintClock {
        .init(now: { self.wall }, monotonic: { self.mono }, bootSessionID: { self.boot }, calendar: { self.utc })
    }
    private var ledger: FlintProtectionLedger {
        .init(directory: directory.appendingPathComponent("ledger"), clock: clock,
              initializedMarker: { self.marker }, setInitializedMarker: { self.marker = $0 },
              migrate: { $0.importLegacyPass(.absent, at: $1) })
    }
    private var historyDirectory: URL { directory.appendingPathComponent("history") }
    /// The host app's store. Only it drains the inbox into the log.
    private func host(at now: Date? = nil) -> FlintHistoryStore {
        .init(directory: historyDirectory, clock: { now ?? self.wall }, calendar: utc)
    }
    /// An extension's store on the same App Group directory: ingress only.
    private func extensionStore() -> FlintHistoryStore {
        .init(directory: historyDirectory, clock: { self.wall }, calendar: utc, isHost: false)
    }
    private var session: FlintActiveSession {
        .init(id: "session-a", name: "Focus", startedAt: t0, endsAt: t0.addingTimeInterval(3600),
              breakLevel: .easy, monitorName: "flint.session.fixture")
    }
    private func context(_ state: FlintLedgerState) -> FlintBreakContext {
        .init(owners: [.init(.init(cause: .init(causeID: session.id, occurrenceID: "occurrence-a", revision: 0,
                kind: .session, tier: .easy, isActive: true),
            selection: .blockList(.init(targets: [token])),
            logicalEnd: .init(wall: wall.addingTimeInterval(10_000 - mono), monotonic: 10_000, bootID: boot),
            timeKnown: true, harder: state.harder[session.id] ?? .init()), storeName: "flint")])
    }
    private func service(history: FlintProtectionHistoryProjection.BreakAppend? = nil) -> FlintOrdinaryBreakService {
        .init(ledger: ledger, clock: clock, transition: { body in body(); return true },
              context: { state, _ in self.context(state) },
              register: { record, _ in self.carriers.insert(record.activityName) },
              registered: { self.carriers.contains($0) }, stop: { self.carriers.remove($0) },
              apply: { _, _, _ in }, history: history)
    }
    private func appending(to store: FlintHistoryStore) -> FlintProtectionHistoryProjection.BreakAppend {
        { store.append(FlintHistoryEvent(ordinaryBreak: $0, session: $1)) }
    }
    private func acceptBreak(_ service: FlintOrdinaryBreakService) throws -> FlintOrdinaryGrantState.Record {
        guard case let .confirmation(request) = service.request(scope: .init(targets: [token])),
              case let .active(record, _) = service.confirm(request) else {
            XCTFail("Fixture Easy break was not accepted"); throw FlintOrdinaryBreakError.unavailable
        }
        return record
    }
    private func advance(_ seconds: Double) { mono += seconds; wall.addTimeInterval(seconds) }
    private func recordSessionStart(_ store: FlintHistoryStore) {
        XCTAssertTrue(store.append(FlintHistoryEvent(.started(session, source: .manual, at: t0))))
    }
    private func recordCompletion(_ store: FlintHistoryStore) {
        XCTAssertTrue(store.append(FlintHistoryEvent(.ended(session, route: .completed, at: t0.addingTimeInterval(3600)))))
    }
    private func pending() throws -> [(FlintOrdinaryGrantState.EventIntent, String)] {
        try ledger.peek().get().ordinaryGrants?.pendingHistory() ?? []
    }
    private func summarize(_ snapshot: FlintHistorySnapshot, at now: Date) -> FlintHistorySummary {
        FlintHistoryAggregator.summarize(snapshot, activeSessionID: nil, now: now, calendar: utc)
    }
    private func breakRows(_ snapshot: FlintHistorySnapshot) -> [FlintHistoryEvent] {
        snapshot.events.filter { $0.type == .breakStarted || $0.type == .breakEnded }
    }
    private func inboxCount() -> Int {
        ((try? FileManager.default.contentsOfDirectory(atPath: historyDirectory.appendingPathComponent("inbox").path)) ?? [])
            .filter { $0.hasSuffix(".json") }.count
    }

    // MARK: Typed rows

    func testBreakBoundariesBecomeTypedRowsWithStableIDsAndNoTargets() throws {
        let app = extensionStore()
        let breaks = service(history: appending(to: app))
        let record = try acceptBreak(breaks)
        advance(120)
        XCTAssertTrue(breaks.resumeNow(grantID: record.grant.id))
        XCTAssertTrue(try pending().isEmpty, "both boundaries were delivered and acknowledged")

        let snapshot = host().read()
        let rows = breakRows(snapshot)
        XCTAssertEqual(rows.map(\.type), [.breakStarted, .breakEnded])
        let (opened, closed) = (rows[0], rows[1])
        XCTAssertEqual(opened.id, record.grant.id + ".break_started.session-a")
        XCTAssertEqual(closed.id, record.grant.id + ".break_ended.session-a")
        for row in rows {
            XCTAssertEqual(row.session, session.id)
            XCTAssertEqual(row.grant, record.grant.id)
            XCTAssertEqual(row.kind, "ordinary")
            XCTAssertEqual(row.timeConfidence, .certain)
            XCTAssertNil(row.inferred)
            XCTAssertTrue(row.isSupported)
        }
        XCTAssertEqual(opened.date, wall.addingTimeInterval(-120))
        XCTAssertEqual(opened.breakEnd, FlintHistoryEvent.milliseconds(record.grant.deadline.wall))
        XCTAssertNil(opened.reason)
        XCTAssertEqual(closed.date, wall)
        XCTAssertEqual(closed.reason, "resumed")
        XCTAssertNil(closed.breakEnd)

        // Privacy: the frozen target scope stays in the ledger. No token bytes, store or rule name.
        let raw = try String(contentsOf: historyDirectory.appendingPathComponent("events.jsonl"), encoding: .utf8)
        XCTAssertFalse(raw.contains(token.token.base64EncodedString()))
        XCTAssertFalse(raw.contains("fixture-app-token"))
        XCTAssertFalse(raw.contains("\"flint\""))
        let allowed: Set<String> = ["v", "id", "type", "session", "at", "tz", "inferred", "breakEnd", "reason",
                                    "observedAt", "kind", "grant", "timeConfidence"]
        for line in raw.split(separator: "\n") {
            let keys = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any]).keys
            XCTAssertTrue(Set(keys).isSubset(of: allowed), "unexpected history fields: \(keys.sorted())")
        }
    }

    func testEndReasonsUseSharedWireValuesAndIOSOnlyCodes() {
        let expected: [FlintBreakEndReason: String] = [
            .deadline: "expired", .resumeNow: "resumed", .supersession: "superseded",
            .newCommitment: "superseded", .pass: "recovery", .reboot: "reboot", .carrierFailed: "carrier_failed"
        ]
        for (reason, wire) in expected { XCTAssertEqual(FlintProtectionHistoryProjection.wireReason(reason), wire) }
    }

    // MARK: Host dead, extension writes, host projects

    func testMonitorDeadlineWritesHistoryWhileHostIsDeadAndTodaySubtractsTheBreak() throws {
        // The app accepts the break; the host process then dies. The monitor's warning
        // callback at the logical deadline ends it and writes history through its own ingress.
        recordSessionStart(extensionStore())
        let record = try acceptBreak(service(history: appending(to: extensionStore())))
        advance(300)
        let monitor = service(history: appending(to: extensionStore()))
        XCTAssertTrue(monitor.reconcile(activity: record.activityName, callback: .warning))
        XCTAssertTrue(try pending().isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: historyDirectory.appendingPathComponent("events.jsonl").path),
                       "no host has drained yet: the rows are in the App Group inbox")
        recordCompletion(extensionStore())

        // Next host launch.
        let now = t0.addingTimeInterval(4000)
        let snapshot = FlintHistoryReconciler.reconcile(store: host(at: now), activeSession: nil, now: now, calendar: utc)
        let ended = try XCTUnwrap(breakRows(snapshot).first { $0.type == .breakEnded })
        XCTAssertEqual(ended.reason, "expired")
        XCTAssertEqual(ended.date, record.grant.deadline.wall)
        let summary = summarize(snapshot, at: now)
        XCTAssertEqual(summary.sessions.first?.outcome, .completed)
        XCTAssertEqual(summary.sessions.first?.focusedSeconds ?? -1, 3300, accuracy: 0.001)
        XCTAssertEqual(summary.days.last?.focusedSeconds ?? -1, 3300, accuracy: 0.001)
        XCTAssertEqual(summary.days.last?.completed, 1)
    }

    func testLateDeadlineReconciliationKeepsLogicalTimeAndIsMarkedInferred() throws {
        let record = try acceptBreak(service())
        advance(900) // warning missed; a later callback recovers the deadline
        XCTAssertTrue(service(history: appending(to: extensionStore())).reconcile(activity: record.activityName, callback: .end))
        let ended = try XCTUnwrap(breakRows(host().read()).first { $0.type == .breakEnded })
        XCTAssertEqual(ended.date, record.grant.deadline.wall)
        XCTAssertEqual(ended.observedAt, FlintHistoryEvent.milliseconds(wall))
        XCTAssertEqual(ended.inferred, true)
        XCTAssertEqual(ended.timeConfidence, .certain)
    }

    // MARK: Crashes and replay

    func testCrashBetweenLedgerCommitAndProjectionIsProjectedOnNextHostRefresh() throws {
        // A process that dies after the ledger commit never reaches its projection step.
        let breaks = service(history: nil)
        let record = try acceptBreak(breaks)
        advance(60)
        XCTAssertTrue(breaks.resumeNow(grantID: record.grant.id))
        XCTAssertEqual(try pending().count, 2)
        recordSessionStart(host())

        let now = t0.addingTimeInterval(4000)
        let refresh = { (store: FlintHistoryStore) in
            FlintHistoryReconciler.reconcile(store: store, activeSession: nil, now: now, calendar: self.utc) {
                FlintProtectionHistoryProjection.project(ledger: self.ledger, store: $0, snapshot: $1, now: now)
            }
        }
        let first = refresh(host(at: now))
        XCTAssertEqual(breakRows(first).map(\.type), [.breakStarted, .breakEnded])
        XCTAssertTrue(try pending().isEmpty)
        let again = refresh(host(at: now))
        XCTAssertEqual(again.events.map(\.id), first.events.map(\.id), "a second launch publishes nothing new")
        XCTAssertEqual(inboxCount(), 0)
    }

    func testCrashAfterHistoryAppendBeforeAcknowledgementReplaysIntoTheSameRow() throws {
        let breaks = service()
        let record = try acceptBreak(breaks)
        let app = extensionStore()
        // Append commits, then the process dies before its acknowledgement transaction.
        XCTAssertTrue(breaks.drainEvents { intent, session in
            XCTAssertTrue(app.append(FlintHistoryEvent(ordinaryBreak: intent, session: session))); return false
        })
        XCTAssertEqual(try pending().count, 1)
        XCTAssertTrue(breaks.drainEvents(append: appending(to: app)))
        XCTAssertTrue(try pending().isEmpty)
        XCTAssertTrue(breaks.drainEvents { _, _ in XCTFail("acknowledged row replayed"); return true })
        let rows = breakRows(host().read())
        XCTAssertEqual(rows.map(\.id), [record.grant.id + ".break_started.session-a"], "two deliveries, one row")
    }

    func testConcurrentDrainersInDifferentProcessesConvergeOnOneRowPerBoundary() throws {
        let breaks = service()
        let record = try acceptBreak(breaks)
        advance(30)
        XCTAssertTrue(breaks.resumeNow(grantID: record.grant.id))
        let monitor = extensionStore(), app = extensionStore()
        var raced = false
        // The app's drain is interrupted after its first append by a complete monitor drain.
        XCTAssertTrue(FlintProtectionHistoryProjection.drainOrdinaryBreaks(ledger: ledger) { intent, session in
            let ok = app.append(FlintHistoryEvent(ordinaryBreak: intent, session: session))
            if !raced {
                raced = true
                XCTAssertTrue(FlintProtectionHistoryProjection.drainOrdinaryBreaks(ledger: self.ledger,
                                                                                    append: self.appending(to: monitor)))
            }
            return ok
        })
        XCTAssertTrue(try pending().isEmpty)
        XCTAssertEqual(inboxCount(), 4, "both drainers published; history keeps one copy per ID")
        let rows = breakRows(host().read())
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(Set(rows.map(\.id)).count, 2)
    }

    func testCrossProcessDeliveryOrderDoesNotChangePairingOrTotals() throws {
        let breaks = service()
        let record = try acceptBreak(breaks)
        advance(240)
        XCTAssertTrue(breaks.resumeNow(grantID: record.grant.id))
        let intents = try pending()
        let started = try XCTUnwrap(intents.first { $0.0.kind == .started })
        let ended = try XCTUnwrap(intents.first { $0.0.kind == .ended })
        // The monitor publishes the end before the app publishes the start.
        recordSessionStart(extensionStore())
        XCTAssertTrue(extensionStore().append(FlintHistoryEvent(ordinaryBreak: ended.0, session: ended.1)))
        recordCompletion(extensionStore())
        XCTAssertTrue(extensionStore().append(FlintHistoryEvent(ordinaryBreak: started.0, session: started.1)))
        let now = t0.addingTimeInterval(4000)
        let summary = summarize(host(at: now).read(), at: now)
        XCTAssertEqual(summary.sessions.first?.focusedSeconds ?? -1, 3600 - 240, accuracy: 0.001)
        XCTAssertEqual(summary.days.last?.focusedSeconds ?? -1, 3600 - 240, accuracy: 0.001)
    }

    func testUnavailableHistoryNeverBlocksTheBreakAndKeepsItsIntent() throws {
        let broken = FlintHistoryStore(directory: nil, isHost: false)
        let breaks = service(history: appending(to: broken))
        let record = try acceptBreak(breaks) // enforcement result is unchanged by history
        advance(30)
        XCTAssertTrue(breaks.resumeNow(grantID: record.grant.id))
        XCTAssertEqual(try pending().count, 2, "a refused receipt keeps the intent in the ledger")
        FlintProtectionHistoryProjection.project(ledger: ledger, store: host(), snapshot: host().read())
        XCTAssertEqual(breakRows(host().read()).count, 2)
        XCTAssertTrue(try pending().isEmpty)
    }

    // MARK: Conservative uncertainty

    func testRebootEndIsUncertainAndCannotRestoreFocusTime() throws {
        recordSessionStart(extensionStore())
        _ = try acceptBreak(service(history: appending(to: extensionStore())))
        boot = "boot-2"; mono = 40; wall.addTimeInterval(1000) // reboot; the real end is unknown
        XCTAssertTrue(service(history: appending(to: extensionStore())).reconcile())
        recordCompletion(extensionStore())
        let now = t0.addingTimeInterval(4000)
        let snapshot = host(at: now).read()
        let ended = try XCTUnwrap(breakRows(snapshot).first { $0.type == .breakEnded })
        XCTAssertEqual(ended.reason, "reboot")
        XCTAssertEqual(ended.inferred, true)
        XCTAssertEqual(ended.timeConfidence, .uncertain(reason: "reboot_or_unknown_boot"))
        let summary = summarize(snapshot, at: now)
        XCTAssertLessThanOrEqual(summary.sessions.first?.focusedSeconds ?? .infinity, 3300)
        XCTAssertLessThanOrEqual(summary.days.last?.focusedSeconds ?? .infinity, 3300)
    }

    func testMissingBreakAndSessionEndsNeverInventCompletionOrStreak() throws {
        recordSessionStart(extensionStore())
        _ = try acceptBreak(service(history: appending(to: extensionStore())))
        // The break never ends in history and the session has no terminal (host-dead gap).
        let now = t0.addingTimeInterval(7200)
        let summary = summarize(host(at: now).read(), at: now)
        XCTAssertEqual(summary.sessions.first?.outcome, .unconfirmed)
        XCTAssertEqual(summary.days.last?.completed, 0)
        XCTAssertEqual(summary.days.last?.unconfirmed, 1)
        XCTAssertEqual(summary.streak, 0)
        XCTAssertLessThanOrEqual(summary.days.last?.focusedSeconds ?? .infinity, 600,
                                 "only the prefix evidenced by the break start may count")
    }

    // MARK: Emergency Pass

    private func passController(emit: @escaping (FlintPassRecovery) -> Void) -> FlintEmergencyPassController {
        .init(environment: .init(ledger: { self.ledger }, clock: clock, scope: { _, _ in
            .init(revision: "fixture-scope", sessions: self.passSessions, rules: [], stores: ["flint"])
        }, registerCarrier: { _, _, _ in }, terminate: { sessions, at in
            // Fake session owner: commits the terminal proof the real owner writes (R-20/R-25).
            try self.ledger.transaction { state in
                for ended in sessions {
                    state.sessionAuthority?.acceptedStarts.append(.init(operationID: "start-" + ended.id, session: ended,
                        outcome: .stopped, acceptedAt: .init(wall: ended.startedAt, monotonic: 1, bootID: self.boot),
                        fingerprint: "fixture", expectedRevision: nil, endedAt: at,
                        endRoute: FlintSessionEndRoute.emergencyPass.rawValue))
                }
            }.get()
            self.passSessions = []
        }, release: { _ in }, resume: { _, _ in }, emit: emit))
    }
    private func usePass(emit: @escaping (FlintPassRecovery) -> Void = { _ in }) throws -> FlintPassRecovery {
        let controller = passController(emit: emit)
        let preview = try controller.preview().get()
        _ = try controller.use(previewRevision: preview.revision, operationID: "pass-fixture").get()
        let state = try ledger.peek().get()
        let recovery = try XCTUnwrap(state.emergencyRecovery)
        XCTAssertFalse(recovery.eventPending, "the owner acknowledges its own event flag")
        XCTAssertFalse(recovery.releasePending)
        return recovery
    }

    func testLostLivePassRowIsReplayedOnceWithBreakAndSessionEndAndTodayIsHonest() throws {
        recordSessionStart(host(at: t0)) // history exists before the pass
        let record = try acceptBreak(service(history: appending(to: extensionStore())))
        advance(100)
        // The live pass emission failed to reach history (emit fakes a refused receipt).
        let recovery = try usePass()
        XCTAssertEqual(try pending().first?.0.reason, .pass, "the pass ended the live break in its transaction")

        let now = t0.addingTimeInterval(5000)
        let refresh = {
            FlintHistoryReconciler.reconcile(store: self.host(at: now), activeSession: nil, now: now, calendar: self.utc) {
                FlintProtectionHistoryProjection.project(ledger: self.ledger, store: $0, snapshot: $1, now: now)
            }
        }
        let snapshot = refresh()
        let pass = try XCTUnwrap(snapshot.events.first { $0.type == .passUsed })
        XCTAssertEqual(pass.id, recovery.operationID)
        XCTAssertEqual(pass.sessions, [session.id])
        XCTAssertEqual(pass.recoveryEnd, FlintHistoryEvent.milliseconds(recovery.recoveryEnd))
        XCTAssertEqual(pass.inferred, true)
        let terminal = try XCTUnwrap(snapshot.events.first { $0.type == .endedEarly })
        XCTAssertEqual(terminal.route, "emergency_pass")
        XCTAssertEqual(terminal.id, FlintHistoryEvent(.ended(session, route: .emergencyPass, at: recovery.acceptedAt.wall)).id,
                       "the replay reuses the live lifecycle identity")
        let ended = try XCTUnwrap(breakRows(snapshot).first { $0.type == .breakEnded })
        XCTAssertEqual(ended.reason, "recovery")
        XCTAssertEqual(ended.grant, record.grant.id)
        XCTAssertEqual(ended.date, recovery.acceptedAt.wall)

        let summary = summarize(snapshot, at: now)
        XCTAssertEqual(summary.sessions.first?.outcome, .endedEarly)
        // Focus from t0 to the pass (700 s), minus the 100 s break the pass ended.
        XCTAssertEqual(summary.sessions.first?.focusedSeconds ?? -1, 600, accuracy: 0.001)
        XCTAssertEqual(summary.days.last?.focusedSeconds ?? -1, 600, accuracy: 0.001)
        XCTAssertEqual(summary.days.last?.endedEarly, 1)
        XCTAssertEqual(summary.days.last?.completed, 0)
        XCTAssertEqual(summary.streak, 0)

        XCTAssertEqual(refresh().events.map(\.id), snapshot.events.map(\.id), "replay is idempotent across launches")
        XCTAssertEqual(inboxCount(), 0)
    }

    func testLivePassRowSuppressesReplay() throws {
        recordSessionStart(host(at: t0))
        advance(100)
        let app = extensionStore()
        let recovery = try usePass { committed in
            XCTAssertTrue(app.append(FlintHistoryEvent(.annotated(.globalPassUsed(operationID: committed.operationID,
                sessions: committed.scope.sessions, recoveryEnd: committed.recoveryEnd, at: committed.acceptedAt.wall),
                origin: "app_ui", reason: "emergency_pass", observedAt: nil))))
        }
        XCTAssertTrue(app.append(FlintHistoryEvent(.ended(session, route: .emergencyPass, at: recovery.acceptedAt.wall))))
        let snapshot = host().read()
        XCTAssertEqual(FlintProtectionHistoryProjection.replayCommittedPass(try ledger.peek().get(), snapshot: snapshot,
            now: wall, append: { _ in XCTFail("live rows were already retained"); return true }), 0)
    }

    func testPassReplayNeverInventsATerminalWithoutOwnerProof() throws {
        recordSessionStart(host(at: t0))
        advance(100)
        _ = try usePass()
        _ = try ledger.transaction { state in state.sessionAuthority?.acceptedStarts = [] }.get() // proof aged out
        var replayed: [FlintHistoryEvent] = []
        let count = FlintProtectionHistoryProjection.replayCommittedPass(try ledger.peek().get(), snapshot: host().read(),
                                                                          now: wall) { replayed.append($0); return true }
        XCTAssertEqual(count, 1)
        XCTAssertEqual(replayed.map(\.type), [.passUsed], "no ended_early without the owner's committed terminal")
    }

    func testPassReplayDoesNotResurrectClearedOrPreHistoryOperations() throws {
        recordSessionStart(host(at: t0))
        advance(100)
        _ = try usePass()
        advance(60)
        XCTAssertTrue(host().clear(keepingRunningSession: nil))
        let state = try ledger.peek().get()
        XCTAssertEqual(FlintProtectionHistoryProjection.replayCommittedPass(state, snapshot: host().read(), now: wall,
            append: { _ in XCTFail("cleared history must stay cleared"); return true }), 0)
    }
}
