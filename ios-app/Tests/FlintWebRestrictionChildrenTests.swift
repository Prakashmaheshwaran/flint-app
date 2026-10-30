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
    private func session(_ h: RuleHarness) -> FlintActiveSession {
        .init(id: "child-session", name: "Focus", startedAt: h.now, endsAt: h.now.addingTimeInterval(60),
              breakLevel: .hardcore, monitorName: "flint.session.child", monotonicStart: h.monotonic,
              bootSessionID: h.boot, contractVersion: FlintActiveSession.currentContractVersion, plannedDuration: 60)
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
        let store = FlintWebRestrictionChildren(defaults: h.defaults)
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
