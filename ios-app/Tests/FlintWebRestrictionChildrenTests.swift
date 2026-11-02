import XCTest
@testable import FlintCore

private final class FailingChildDefaults: UserDefaults {
    var flushesToFailure: Int?
    override func synchronize() -> Bool {
        if let remaining = flushesToFailure {
            if remaining == 1 { flushesToFailure = nil; return false }
            flushesToFailure = remaining - 1
        }
        return super.synchronize()
    }
}

final class FlintWebRestrictionChildrenTests: XCTestCase {
    private final class SessionChildWriter: FlintWebRestrictionWriting {
        var config: FlintWebRestrictions?
        var stores: [String: FlintWebRestrictions] = [:]
        var writes = 0
        func apply(_ config: FlintWebRestrictions, toStore storeName: String) { self.config = config; stores[storeName] = config; writes += 1 }
        func clear(store storeName: String) { config = nil; stores.removeValue(forKey: storeName); writes += 1 }
    }
    private func session(_ h: RuleHarness, tier: BreakLevel = .hardcore, duration: TimeInterval = 60,
                         contractVersion: Int? = FlintActiveSession.currentContractVersion) -> FlintActiveSession {
        .init(id: "child-session", name: "Focus", startedAt: h.now, endsAt: h.now.addingTimeInterval(duration),
              breakLevel: tier, monitorName: "flint.session.child", monotonicStart: h.monotonic,
              bootSessionID: h.boot, contractVersion: contractVersion, plannedDuration: duration)
    }

    func testPreparedReplacementCarriesOldFrozenStrengthOrAdoptsProvenStrengtheningR29() throws {
        for strengthening in [false, true] {
            let h = try RuleHarness(); defer { h.clean() }
            let old = session(h)
            let replacement = FlintActiveSession(id: "replacement", name: "Longer", startedAt: h.now,
                endsAt: h.now.addingTimeInterval(600), breakLevel: .hardcore, monitorName: "flint.session.replacement",
                monotonicStart: h.monotonic, bootSessionID: h.boot,
                contractVersion: FlintActiveSession.currentContractVersion, plannedDuration: 600)
            let config = FlintWebRestrictions(enabled: true, blockedDomains: ["blocked.example"], denyExplicitMedia: true)
            let children = FlintWebRestrictionChildren(defaults: h.defaults)
            let writer = SessionChildWriter()
            var future = config
            let adapter = FlintSessionWebRestrictionAdapter(writer: writer, configuration: { future },
                clock: h.clock, children: children)
            adapter.reconcile(old, clock: h.clock)
            XCTAssertEqual(writer.config, config)
            if strengthening { future.blockedDomains.append("extra.example") }
            else { future = .default }
            let writes = writer.writes
            XCTAssertTrue(adapter.prepareReplacement(replacement, replacing: old, clock: h.clock))
            XCTAssertEqual(writer.writes, writes, "Preparation commits child metadata without touching native keys")
            adapter.reconcile(replacement, clock: h.clock)
            XCTAssertEqual(writer.config, strengthening ? future : config)
            XCTAssertEqual(children.frozen(.session(replacement), fallback: nil), strengthening ? future : config)
        }
    }

    func testFailedOptionalReplacementWriteAllowsParentAndRecoversOldChildStrength() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let old = session(h)
        let replacement = FlintActiveSession(id: "replacement", name: "Longer", startedAt: h.now,
            endsAt: h.now.addingTimeInterval(600), breakLevel: .hardcore, monitorName: "flint.session.replacement")
        let config = FlintWebRestrictions(enabled: true)
        var accepts = true
        let children = FlintWebRestrictionChildren(defaults: h.defaults, acceptSave: { accepts })
        let writer = SessionChildWriter()
        var global = config
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, configuration: { global }, children: children)
        adapter.reconcile(old, clock: h.clock)
        let before = children.read(), writes = writer.writes
        accepts = false
        XCTAssertTrue(adapter.prepareReplacement(replacement, replacing: old, clock: h.clock))
        XCTAssertEqual(children.read(), before)
        XCTAssertEqual(writer.config, config)
        XCTAssertEqual(writer.writes, writes)
        global = .default
        adapter.reconcile(replacement, clock: h.clock)
        XCTAssertEqual(writer.config, config, "Failed optional storage preserves the native predecessor")
        accepts = true
        // A reconstructed adapter must inherit the durable predecessor after process death.
        let recovered = FlintSessionWebRestrictionAdapter(writer: writer, configuration: { global },
            children: .init(defaults: h.defaults))
        recovered.reconcile(replacement, clock: h.clock)
        XCTAssertEqual(writer.config, config)
        XCTAssertEqual(children.frozen(.session(replacement), fallback: nil), config)
    }

    func testSessionEnforcementDistinguishesBusyFromUnavailableAndKeepsKnownFrozenChild() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let active = session(h)
        let config = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = SessionChildWriter()
        var state = FlintWebRestrictionRead.available(config)
        var reports: [FlintArmingHealth.DomainReport] = []
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, read: { state }, children: children,
            recordHealth: { reports.append($0) })
        adapter.reconcile(active, clock: h.clock)
        let frozen = children.read(), writes = writer.writes
        state = .busy
        adapter.reconcile(active, clock: h.clock)
        XCTAssertEqual(writer.writes, writes)
        XCTAssertEqual(children.read(), frozen)
        XCTAssertTrue(reports.last?.failures.first?.reason.contains("busy") == true)
        let replacement = FlintActiveSession(id: "replacement", name: "New", startedAt: h.now,
            endsAt: h.now.addingTimeInterval(600), breakLevel: .hardcore, monitorName: "flint.session.new")
        XCTAssertTrue(adapter.prepareReplacement(replacement, replacing: active, clock: h.clock))
        XCTAssertEqual(children.frozen(.session(replacement), fallback: nil), config)
        XCTAssertEqual(writer.writes, writes)
        state = .unavailable
        adapter.reconcile(active, clock: h.clock)
        XCTAssertEqual(writer.config, config)
        XCTAssertEqual(writer.writes, writes + 1, "Known child survives unavailable future preferences")
        XCTAssertEqual(reports.last?.armed, 1)
        XCTAssertTrue(reports.last?.failures.first?.reason.contains("unavailable") == true)
        XCTAssertTrue(adapter.prepareReplacement(replacement, replacing: active, clock: h.clock))
        XCTAssertEqual(children.frozen(.session(replacement), fallback: nil), config)
    }

    func testFirstUnavailableSessionReconcileFreezesUnknownUntilGatedRepair() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let active = session(h)
        let config = FlintWebRestrictions(enabled: true)
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        let writer = SessionChildWriter()
        var state = FlintWebRestrictionRead.unavailable
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, read: { state }, children: children)
        adapter.reconcile(active, clock: h.clock)
        XCTAssertNil(writer.config)
        XCTAssertTrue(children.needsRecovery)
        state = .available(config)
        adapter.reconcile(active, clock: h.clock)
        XCTAssertEqual(writer.writes, 0, "A later preference cannot silently replace unavailable freeze-time intent")
        XCTAssertNil(children.frozen(.session(active), fallback: config))
    }

    func testUnknownChildDoesNotBlockStrongerSessionReplacementOrBecomeAnOptOut() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let active = session(h)
        let replacement = FlintActiveSession(id: "stronger", name: "Longer", startedAt: h.now,
            endsAt: h.now.addingTimeInterval(600), breakLevel: .hardcore, monitorName: "flint.session.stronger")
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        XCTAssertTrue(children.capture([.session(active)], global: nil))
        let writer = SessionChildWriter(); writer.config = .init(enabled: true)
        var reports: [FlintArmingHealth.DomainReport] = []
        let adapter = FlintSessionWebRestrictionAdapter(writer: writer, read: { .unavailable }, children: children,
            recordHealth: { reports.append($0) })
        XCTAssertTrue(adapter.prepareReplacement(replacement, replacing: active, clock: h.clock))
        XCTAssertEqual(writer.writes, 0)
        guard case let .available(envelope) = children.read() else { return XCTFail() }
        XCTAssertTrue(envelope.children.contains { $0.cause == .session(replacement) && $0.config == nil })
        XCTAssertFalse(reports.last?.failures.isEmpty ?? true)
        adapter.reconcile(replacement, clock: h.clock)
        XCTAssertEqual(writer.writes, 0, "Unknown policy preserves the existing native keys")
    }

    func testHostNeverRecapturesMissingEstablishedRuleChildAfterOptionalFreezeWriteFails() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let config = FlintWebRestrictions(applyDuringSchedules: true)
        let store = FlintWebRestrictionChildren(defaults: h.defaults)
        XCTAssertTrue(store.capture([], global: config))
        let cause = FlintWebRestrictionChildren.Cause.rule(rule,
            window: try XCTUnwrap(rule.occurrence(at: h.now, clock: h.clock)))
        let rejecting = FlintWebRestrictionChildren(defaults: h.defaults, acceptSave: { false })
        XCTAssertFalse(rejecting.capture([cause], global: config))
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        let writer = SessionChildWriter(); writer.stores[rule.storeName] = config
        FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: store)
            .reconcile(activeSession: nil, rules: [rule], config: .default, now: h.now, clock: h.clock)
        XCTAssertEqual(writer.stores[rule.storeName], config)
        guard case let .available(envelope) = store.read() else { return XCTFail() }
        XCTAssertTrue(envelope.children.contains { $0.cause == cause && $0.config == nil })
        XCTAssertTrue(store.needsRecovery)
    }

    func testBlockedRecoveryDoesNotCreateAPhantomReplacementChild() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let active = session(h), config = FlintWebRestrictions(enabled: true)
        let replacement = FlintActiveSession(id: "blocked", name: "New", startedAt: h.now,
            endsAt: h.now.addingTimeInterval(600), breakLevel: .hardcore, monitorName: "flint.session.blocked")
        let children = FlintWebRestrictionChildren(defaults: h.defaults)
        XCTAssertEqual(children.resolve(.session(active), global: config), config)
        let before = children.read()
        let writer = SessionChildWriter()
        for gate: FlintPassRecoveryGate in [.unavailable, .suspended(until: h.now.addingTimeInterval(600))] {
            let lifecycle = FlintWebRestrictionLifecycle(writer: writer, recoveryGate: { gate }, children: children)
            XCTAssertTrue(lifecycle.prepareReplacement(replacement, replacing: active, config: nil, clock: h.clock))
            XCTAssertTrue(lifecycle.prepareReplacement(replacement, replacing: nil, config: nil, clock: h.clock))
            XCTAssertEqual(children.read(), before)
            XCTAssertFalse(children.needsRecovery)
            XCTAssertEqual(writer.writes, 0)
        }
    }

    func testUnparseableSessionChildPreservesLiveKeysWithoutPoisoningRuleCaptures() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let rule = h.install(h.schedule(tier: .hardcore))
        let cause = FlintWebRestrictionChildren.Cause.rule(rule,
            window: try XCTUnwrap(rule.occurrence(at: h.now, clock: h.clock)))
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        let damaged = Data("{\"cause\":{\"scope\":\"session\"},\"config\":false}".utf8)
        XCTAssertTrue(FlintWebRestrictions.writeStorage(to: h.defaults, config: .default,
            children: try JSONSerialization.data(withJSONObject: ["version": 1,
                "children": [JSONSerialization.jsonObject(with: damaged)]])))
        let children = FlintWebRestrictionChildren(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory)
        guard case let .available(before) = children.read() else { return XCTFail() }
        XCTAssertEqual(before.retainedUnavailable.count, 1)
        XCTAssertTrue(children.capture([cause], global: .default))
        XCTAssertEqual(children.frozen(cause, fallback: nil), .default, "The unparseable session record does not poison rule capture")
        let active = session(h)
        let writer = SessionChildWriter(); writer.stores[FlintWebRestrictionLifecycle.sessionStoreName] = config
        let lifecycle = FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: children)
        let health = FlintArmingHealth(domains: [
            "session": .init(enabled: 1, attempted: 1, armed: 1, failures: [], observedActivities: [active.monitorName]),
            "schedules": .init(enabled: 1, attempted: 1, armed: 1, failures: [], observedActivities: [rule.activityName])])
        let report = FlintWebRestrictionHostReconciler.reconcile(snapshot: .init(session: active, rules: [rule]),
            config: .default, health: health, lifecycle: lifecycle, now: h.now, clock: h.clock)
        XCTAssertNil(children.frozen(.session(active), fallback: .default))
        XCTAssertEqual(writer.stores[FlintWebRestrictionLifecycle.sessionStoreName], config, "A weakened global cannot replace the damaged live session policy")
        XCTAssertTrue(lifecycle.unavailableStores.contains(FlintWebRestrictionLifecycle.sessionStoreName))
        XCTAssertFalse(report.failures.isEmpty)
        guard case let .available(saved) = children.read() else { return XCTFail() }
        XCTAssertEqual(saved.retainedUnavailable, before.retainedUnavailable)
        XCTAssertTrue(children.needsRecovery, "The unrelated damaged record is retained for gated repair")
    }

    func testEditorGatesFrozenHardcoreChildAfterFuturePreferencesWereWeakenedR29() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        var active = session(h)
        let children = FlintWebRestrictionChildren(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory)
        XCTAssertEqual(children.resolve(.session(active), global: config), config)
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        let editor = FlintWebRestrictionEditor(read: {
            FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }).map(FlintWebRestrictionRead.available) ?? .unavailable
        }, snapshot: { .init(session: active, rules: []) }, persist: { _ in XCTFail("Atomic child writer owns the save"); return false },
        ledger: { h.ledger }, clock: h.clock, children: children)
        let before = h.defaults.data(forKey: FlintWebRestrictions.storageKey)
        XCTAssertEqual(editor.save(.default, requestID: "future-off", confirmed: true), .protected(until: active.endsAt))
        XCTAssertEqual(h.defaults.data(forKey: FlintWebRestrictions.storageKey), before)
        active = session(h, tier: .easy) // Current owner rights, rather than a stale child tier, gate the edit.
        XCTAssertEqual(editor.save(.default, requestID: "easy-owner"), .saved)
        XCTAssertEqual(children.frozen(.session(active), fallback: nil), .default)
    }

    func testAtomicHarderEditorRetrySpendsOnceAndChangesFrozenAndFuturePolicyTogether() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        let active = session(h, tier: .harder, duration: 600)
        var accepts = true
        let children = FlintWebRestrictionChildren(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory, acceptSave: { accepts })
        XCTAssertEqual(children.resolve(.session(active), global: config), config)
        let editor = FlintWebRestrictionEditor(read: {
            FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }).map(FlintWebRestrictionRead.available) ?? .unavailable
        }, snapshot: { .init(session: active, rules: []) }, persist: { _ in false }, ledger: { h.ledger }, clock: h.clock, children: children)
        XCTAssertEqual(editor.save(.default, requestID: "same"), .waiting(seconds: 60))
        h.advance(60)
        XCTAssertEqual(editor.save(.default, requestID: "same"), .confirmationRequired)
        accepts = false
        guard case .invalid = editor.save(.default, requestID: "same", confirmed: true) else { return XCTFail("Expected retryable authorized write failure") }
        XCTAssertEqual(children.frozen(.session(active), fallback: nil), config)
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }), config)
        XCTAssertEqual(try h.ledger.read().get().harder[active.id]?.committedCount, 1)
        accepts = true
        XCTAssertEqual(editor.save(.default, requestID: "same", confirmed: true), .saved)
        XCTAssertEqual(children.frozen(.session(active), fallback: nil), .default)
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }), .default)
        XCTAssertEqual(try h.ledger.read().get().harder[active.id]?.committedCount, 1)
    }

    func testCorruptChildRepairIgnoresLegacyRightsAndRetainsOriginalBlobAfterEnd() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(enabled: true)
        XCTAssertTrue(config.save(to: h.defaults))
        let active = session(h, contractVersion: nil)
        let children = FlintWebRestrictionChildren(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory)
        XCTAssertEqual(children.resolve(.session(active), global: config), config)
        var object = try root(h); object[FlintWebRestrictionChildren.schemaKey] = 999
        try saveRoot(object, h)
        let original = try XCTUnwrap(h.defaults.data(forKey: FlintWebRestrictions.storageKey))
        XCTAssertTrue(children.needsRecovery)
        let editor = FlintWebRestrictionEditor(read: { .available(config) }, snapshot: { .init(session: active, rules: []) },
            persist: { _ in false }, ledger: { h.ledger }, clock: h.clock, children: children)
        XCTAssertEqual(editor.save(config, requestID: "repair"), .protected(until: active.endsAt))
        XCTAssertEqual(h.defaults.data(forKey: FlintWebRestrictions.storageKey), original)
        h.advance(60)
        XCTAssertEqual(editor.save(config, requestID: "repair"), .saved)
        XCTAssertFalse(children.needsRecovery)
        let key = try XCTUnwrap(h.defaults.dictionaryRepresentation().keys.first { $0.hasPrefix("flint.webRestrictions.recovered.") })
        XCTAssertEqual(h.backup(key), original)
    }

    func testDamagedEntryIsIsolatedFromHealthyChildrenAndArchivedByAuthorizedRepair() throws {
        for damage in ["config", "store", "duplicate", "payload"] {
            let h = try RuleHarness(); defer { h.clean() }
            let active = session(h)
            let rule = h.install(h.schedule(tier: .hardcore))
            let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true, denyExplicitMedia: true)
            let healthy = FlintWebRestrictionChildren.Cause.rule(rule,
                window: try XCTUnwrap(rule.occurrence(at: h.now, clock: h.clock)))
            let damaged = FlintWebRestrictionChildren.Cause.session(active)
            let envelope = FlintWebRestrictionChildren.Envelope(children: [
                .init(cause: damaged, config: config), .init(cause: healthy, config: config)])
            var raw = try XCTUnwrap(try JSONSerialization.jsonObject(with: JSONEncoder().encode(envelope)) as? [String: Any])
            var records = try XCTUnwrap(raw["children"] as? [[String: Any]])
            switch damage {
            case "config": records[0]["config"] = "wrong type"
            case "store":
                var cause = try XCTUnwrap(records[0]["cause"] as? [String: Any])
                cause["storeName"] = "flint.some-other-owner"; records[0]["cause"] = cause
            case "duplicate": records.append(records[0])
            default: records[0].removeValue(forKey: "config")
            }
            raw["children"] = records
            XCTAssertTrue(FlintWebRestrictions.writeStorage(to: h.defaults, config: config,
                children: try JSONSerialization.data(withJSONObject: raw)))
            let original = try XCTUnwrap(h.defaults.data(forKey: FlintWebRestrictions.storageKey))
            let children = FlintWebRestrictionChildren(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory)
            guard case .available = children.read() else { return XCTFail("One damaged entry must not disable its siblings: \(damage)") }
            XCTAssertEqual(children.frozen(healthy, fallback: nil), config, damage)
            XCTAssertNil(children.resolve(damaged, global: .default), damage)
            XCTAssertTrue(children.needsRecovery, damage)
            let writer = SessionChildWriter()
            FlintWebRestrictionLifecycle(writer: writer, clock: h.clock, children: children)
                .reconcile(activeSession: active, rules: [rule], config: .default, now: h.now, clock: h.clock)
            XCTAssertEqual(writer.config, config, "Healthy rule must still apply during full host reconciliation")
            XCTAssertEqual(children.frozen(healthy, fallback: nil), config)
            // Ordinary writes can normalize damaged records, but retain their exact original
            // bytes. The repair archive retains the complete pre-repair blob as well.
            let beforeRepair = try XCTUnwrap(h.defaults.data(forKey: FlintWebRestrictions.storageKey))
            XCTAssertTrue(children.saveAuthorized(.default, snapshot: .init(session: active, rules: [rule]), clock: h.clock))
            XCTAssertFalse(children.needsRecovery)
            let key = try XCTUnwrap(h.defaults.dictionaryRepresentation().keys.first { $0.hasPrefix("flint.webRestrictions.recovered.") })
            XCTAssertEqual(h.backup(key), beforeRepair)
            if damage != "store" { XCTAssertEqual(beforeRepair, original) }
        }
    }

    func testRawFuturePreferenceWeakeningRetainsFrozenChildAcrossReloadR29() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(enabled: true, blockedDomains: ["blocked.example"], denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        let child = FlintWebRestrictionChildren.Cause.session(session(h))
        let store = FlintWebRestrictionChildren(defaults: h.defaults)
        XCTAssertEqual(store.resolve(child, global: config), config)
        XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }), .default)
        XCTAssertEqual(FlintWebRestrictionChildren(defaults: h.defaults).resolve(child, global: .default), config)
        var stronger = config; stronger.blockedDomains.append("more.example")
        XCTAssertEqual(store.resolve(child, global: stronger), stronger)
        XCTAssertEqual(FlintWebRestrictionChildren(defaults: h.defaults).resolve(child, global: nil), stronger)
    }

    func testUnavailableFreezeDoesNotBecomeOptOutOrAdoptAnInterveningRawEdit() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let raw = Data("{unavailable global".utf8)
        h.defaults.set(raw, forKey: FlintWebRestrictions.storageKey)
        let cause = FlintWebRestrictionChildren.Cause.session(session(h))
        let store = FlintWebRestrictionChildren(defaults: h.defaults, recoveryDirectory: h.recoveryDirectory)
        XCTAssertTrue(store.capture([cause], global: nil))
        let config = FlintWebRestrictions(enabled: true)
        XCTAssertTrue(config.save(to: h.defaults))
        XCTAssertNil(store.resolve(cause, global: config))
        XCTAssertEqual(try root(h)["_flintWebRetainedUnavailable"] as? String, raw.base64EncodedString())
        XCTAssertTrue(store.saveAuthorized(config, snapshot: .init(session: session(h), rules: []), clock: h.clock))
        XCTAssertEqual(store.resolve(cause, global: nil), config)
    }

    func testExplicitEmptyEnvelopeAndLostCorruptOrFutureChildrenNeverResetToEmpty() throws {
        for damage in ["missing", "corrupt", "future", "lost-root"] {
            let h = try RuleHarness(); defer { h.clean() }
            XCTAssertTrue(FlintWebRestrictions(enabled: true).save(to: h.defaults))
            let store = FlintWebRestrictionChildren(defaults: h.defaults)
            XCTAssertTrue(store.capture([], global: .default))
            guard case let .available(empty) = store.read() else { return XCTFail("Explicit empty envelope missing") }
            XCTAssertTrue(empty.children.isEmpty)
            var object = try root(h)
            switch damage {
            case "missing": object.removeValue(forKey: FlintWebRestrictionChildren.bytesKey)
            case "corrupt": object[FlintWebRestrictionChildren.bytesKey] = Data("{".utf8).base64EncodedString()
            case "future": object[FlintWebRestrictionChildren.schemaKey] = 999
            default: h.defaults.removeObject(forKey: FlintWebRestrictions.storageKey)
            }
            if damage != "lost-root" { try saveRoot(object, h) }
            guard case .unavailable = store.read() else { return XCTFail(damage) }
            XCTAssertTrue(FlintWebRestrictions.default.save(to: h.defaults))
            guard case .unavailable = store.read() else { return XCTFail("Raw global save reset \(damage)") }
            XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }), .default)
            if damage != "lost-root" {
                XCTAssertEqual(try root(h)[FlintWebRestrictionChildren.bytesKey] as? String,
                    object[FlintWebRestrictionChildren.bytesKey] as? String, "Preserve exact child bytes, including absence")
            }
        }
    }

    func testStaleGlobalDraftSavePreservesNewlyCapturedChildBytes() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(enabled: true, applyDuringSchedules: true)
        XCTAssertTrue(config.save(to: h.defaults))
        var draft = try XCTUnwrap(FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }))
        let store = FlintWebRestrictionChildren(defaults: h.defaults)
        _ = store.resolve(.session(session(h)), global: config)
        let rule = h.install(h.schedule(tier: .hardcore))
        let cause = FlintWebRestrictionChildren.Cause.rule(rule, window: try XCTUnwrap(rule.occurrence(at: h.now, clock: h.clock)))
        _ = FlintWebRestrictionChildren(defaults: h.defaults).resolve(cause, global: config)
        let bytes = try root(h)[FlintWebRestrictionChildren.bytesKey] as? String
        draft.enabled = false
        XCTAssertTrue(draft.save(to: h.defaults))
        XCTAssertEqual(try root(h)[FlintWebRestrictionChildren.bytesKey] as? String, bytes)
        guard case let .available(envelope) = store.read() else { return XCTFail() }
        XCTAssertEqual(envelope.children.count, 2)
    }

    func testTrustedEndPrunesOnlyItsOwnChildAndWallJumpDoesNot() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let active = session(h)
        let rule = h.install(h.schedule(tier: .hardcore))
        let snapshot = FlintWebRestrictionEditor.Snapshot(session: active, rules: [rule])
        let store = FlintWebRestrictionChildren(defaults: h.defaults)
        XCTAssertTrue(store.capture(FlintWebRestrictionChildren.causes(snapshot, clock: h.clock),
            global: .init(enabled: true, applyDuringSchedules: true)))
        h.now.addTimeInterval(86400)
        XCTAssertTrue(store.prune(snapshot, clock: h.clock))
        guard case let .available(live) = store.read() else { return XCTFail() }
        XCTAssertEqual(live.children.count, 2)
        h.advance(60)
        XCTAssertTrue(store.prune(snapshot, clock: h.clock))
        guard case let .available(ended) = store.read() else { return XCTFail() }
        XCTAssertEqual(ended.children.map { $0.cause.scope }, [.rule])
        h.advance(5 * 3600)
        XCTAssertTrue(store.prune(snapshot, clock: h.clock))
        guard case let .available(empty) = store.read() else { return XCTFail() }
        XCTAssertTrue(empty.children.isEmpty)
    }

    func testRejectedAtomicEditorWriteKeepsGlobalAndChildThenRetryChangesBoth() throws {
        let h = try RuleHarness(); defer { h.clean() }
        let config = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
        XCTAssertTrue(config.save(to: h.defaults))
        let active = session(h), snapshot = FlintWebRestrictionEditor.Snapshot(session: session(h), rules: [])
        let cause = FlintWebRestrictionChildren.Cause.session(active)
        let store = FlintWebRestrictionChildren(defaults: h.defaults)
        XCTAssertEqual(store.resolve(cause, global: config), config)
        let before = h.defaults.data(forKey: FlintWebRestrictions.storageKey)
        let rejecting = FlintWebRestrictionChildren(defaults: h.defaults, acceptSave: { false })
        XCTAssertFalse(rejecting.saveAuthorized(.default, snapshot: snapshot, clock: h.clock))
        XCTAssertEqual(h.defaults.data(forKey: FlintWebRestrictions.storageKey), before)
        XCTAssertTrue(store.saveAuthorized(.default, snapshot: snapshot, clock: h.clock))
        XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: h.defaults, existingWebStores: { false }), .default)
        XCTAssertEqual(store.frozen(cause, fallback: config), .default)
    }

    func testFailedChildBlobOrMarkerFlushRollsBackAndAtomicEditorRetryChangesBoth() throws {
        for failedFlush in [3, 4] { // read, synchronized before-image, blob, child marker
            let h = try RuleHarness(); defer { h.clean() }
            let defaults = try XCTUnwrap(FailingChildDefaults(suiteName: h.suite))
            let config = FlintWebRestrictions(enabled: true, denyExplicitMedia: true)
            XCTAssertTrue(config.save(to: defaults))
            let cause = FlintWebRestrictionChildren.Cause.session(session(h))
            let store = FlintWebRestrictionChildren(defaults: defaults)
            let before = defaults.data(forKey: FlintWebRestrictions.storageKey)
            defaults.flushesToFailure = failedFlush
            XCTAssertFalse(store.capture([cause], global: config))
            XCTAssertEqual(defaults.data(forKey: FlintWebRestrictions.storageKey), before)
            XCTAssertNil(defaults.object(forKey: FlintWebRestrictionChildren.markerKey))
            guard case .legacy = store.read() else { return XCTFail("Failed freeze must retain its exact before-image") }
            XCTAssertTrue(store.capture([cause], global: config))
            let frozen = defaults.data(forKey: FlintWebRestrictions.storageKey)
            defaults.flushesToFailure = 3
            XCTAssertFalse(store.saveAuthorized(.default, snapshot: .init(session: session(h), rules: []), clock: h.clock))
            XCTAssertEqual(defaults.data(forKey: FlintWebRestrictions.storageKey), frozen)
            XCTAssertEqual(store.frozen(cause, fallback: nil), config)
            XCTAssertTrue(store.saveAuthorized(.default, snapshot: .init(session: session(h), rules: []), clock: h.clock))
            XCTAssertEqual(store.frozen(cause, fallback: config), .default)
            XCTAssertEqual(FlintWebRestrictions.loadForEnforcement(from: defaults, existingWebStores: { false }), .default)
        }
    }

    private func root(_ h: RuleHarness) throws -> [String: Any] {
        try XCTUnwrap(try JSONSerialization.jsonObject(with: XCTUnwrap(h.defaults.data(forKey: FlintWebRestrictions.storageKey))) as? [String: Any])
    }
    private func saveRoot(_ root: [String: Any], _ h: RuleHarness) throws {
        h.defaults.set(try JSONSerialization.data(withJSONObject: root), forKey: FlintWebRestrictions.storageKey)
        XCTAssertTrue(h.defaults.synchronize())
    }
}
