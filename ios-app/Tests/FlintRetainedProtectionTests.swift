import XCTest
import DeviceActivity
@testable import FlintCore

final class FlintRetainedProtectionTests: XCTestCase {
    private let end = Date(timeIntervalSinceReferenceDate: 12345)

    func testSuccessfulButUnobservedCallsPinTheFirstFiniteEnd() throws {
        try withDefaults { defaults in
            for hideSchedule in [false, true] {
                let center = FakeActivityCenter()
                center.hideAccepted = !hideSchedule
                let name = "flint.rule.unobserved.\(hideSchedule)"
                if hideSchedule { center.hiddenSchedules = [name] }
                var request = activity(name)
                var cleared: [String] = []
                let first = reconcile(request, center, defaults, at: end.addingTimeInterval(-60)) { cleared.append($0) }
                XCTAssertEqual(first.armed, 1)
                XCTAssertEqual(first.observed, 0)
                XCTAssertTrue(cleared.isEmpty)
                XCTAssertEqual(receipts(defaults)[name]?.endsAt, end)
                request.retainedUntil = end.addingTimeInterval(86400)
                let later = reconcile(request, center, defaults, at: end.addingTimeInterval(1)) { cleared.append($0) }
                XCTAssertEqual(later.observed, 0)
                XCTAssertEqual(cleared, [name])
                XCTAssertEqual(receipts(defaults)[name]?.endsAt, end)
                XCTAssertEqual(receipts(defaults)[name]?.cleared, true)
            }
        }
    }

    func testLateSuccessfulRepairClearsTheExpiredStoreBeforeDiscardingItsReceipt() throws {
        try withDefaults { defaults in
            let center = FakeActivityCenter()
            let name = "flint.rule.late"
            var request = activity(name)
            center.refused = [name]
            _ = reconcile(request, center, defaults, at: end.addingTimeInterval(-60)) { _ in XCTFail() }
            request.retainedUntil = end.addingTimeInterval(3600)
            center.refused = []
            var cleared: [String] = []
            let report = reconcile(request, center, defaults, at: end.addingTimeInterval(60)) {
                XCTAssertEqual(self.receipts(defaults)[name]?.endsAt, self.end,
                               "clear the stale store before losing its old bound")
                cleared.append($0)
            }
            XCTAssertEqual(report.observed, 1)
            XCTAssertEqual(cleared, [name])
            XCTAssertNil(receipts(defaults)[name])
            XCTAssertTrue(report.failures.contains { $0.reason.contains("before registration recovered") })
            _ = reconcile(request, center, defaults, at: end.addingTimeInterval(120)) { _ in XCTFail("already cleared") }
        }
    }

    func testSuccessfulRepairBeforeExpiryCanRelyOnItsObservedNativeEnd() throws {
        try withDefaults { defaults in
            let center = FakeActivityCenter()
            let request = activity("flint.rule.early")
            center.refused = [request.name]
            _ = reconcile(request, center, defaults, at: end.addingTimeInterval(-120)) { _ in XCTFail() }
            center.refused = []
            let report = reconcile(request, center, defaults, at: end.addingTimeInterval(-60)) { _ in XCTFail() }
            XCTAssertEqual(report.observed, 1)
            XCTAssertNil(receipts(defaults)[request.name])
        }
    }

    func testRefusedReplacementWithSurvivingEndPreservesLastGoodShield() throws {
        try withDefaults { defaults in
            let center = FakeActivityCenter()
            var request = activity("flint.limit.survives")
            XCTAssertTrue(FlintRetainedProtection.save([request.name: .init(endsAt: end, cleared: false)],
                prefix: "flint.limit.", to: defaults))
            center.held[.init(request.name)] = request
            request.configurationChanged = true
            center.refused = [request.name]
            let report = FlintActivityRegistration.reconcile([request], prefix: "flint.limit.", enabled: 1,
                validationFailures: [], center: center, defaults: defaults, now: end.addingTimeInterval(60),
                onRemove: { _ in XCTFail("an observed last-good native end still bounds this store") })
            XCTAssertEqual(report.observed, 0)
            XCTAssertEqual(receipts(defaults)[request.name]?.cleared, false)
        }
    }

    func testUnavailableReceiptsClearUnboundedFiniteStoresWithoutRepinningOrOverwriting() throws {
        try withDefaults { defaults in
            let unreadable: [Any] = [Data("{partial".utf8), "wrong type"]
            for value in unreadable {
                defaults.set(value, forKey: FlintRetainedProtection.key)
                XCTAssertEqual(FlintRetainedProtection.load(from: defaults), .unavailable)
                let center = FakeActivityCenter()
                center.hideAccepted = true
                var request = activity("flint.rule.corrupt")
                var cleared: [String] = []
                for day in 0...1 {
                    request.retainedUntil = end.addingTimeInterval(Double(day + 1) * 86400)
                    let report = reconcile(request, center, defaults, at: end.addingTimeInterval(Double(day) * 86400)) {
                        cleared.append($0)
                    }
                    XCTAssertTrue(report.failures.contains { $0.reason.contains("deadline is unavailable") })
                    XCTAssertEqual(FlintRetainedProtection.load(from: defaults), .unavailable)
                }
                XCTAssertEqual(cleared, [request.name, request.name])
                XCTAssertFalse(FlintRetainedProtection.save([request.name: .init(endsAt: end, cleared: false)],
                    prefix: "flint.rule.", to: defaults))
                if let data = value as? Data { XCTAssertEqual(defaults.data(forKey: FlintRetainedProtection.key), data) }
                else { XCTAssertEqual(defaults.string(forKey: FlintRetainedProtection.key), value as? String) }
            }
        }
    }

    func testMissingStoreAndLegacyReceiptDecodeRemainDistinctFromUnavailable() throws {
        try withDefaults { defaults in
            XCTAssertEqual(FlintRetainedProtection.load(from: defaults), .available([:]))
            XCTAssertEqual(FlintRetainedProtection.load(from: nil), .unavailable)
            defaults.set(Data("{\"flint.rule.legacy\":{\"endsAt\":12345,\"cleared\":false}}".utf8),
                         forKey: FlintRetainedProtection.key)
            XCTAssertEqual(receipts(defaults)["flint.rule.legacy"], .init(endsAt: end, cleared: false))
        }
    }

    func testLayerScopedSavesMergeLatestOtherLayerReceiptsAndPruneOnlyTheirOwn() throws {
        try withDefaults { defaults in
            let schedule = ["flint.rule.keep": FlintRetainedProtection.Receipt(endsAt: end, cleared: false)]
            let limit = ["flint.limit.keep": FlintRetainedProtection.Receipt(endsAt: end, cleared: false)]
            // Both layers may have taken their snapshots before either writes.
            XCTAssertTrue(FlintRetainedProtection.save(schedule, prefix: "flint.rule.", to: defaults))
            XCTAssertTrue(FlintRetainedProtection.save(limit, prefix: "flint.limit.", to: defaults))
            XCTAssertEqual(Set(receipts(defaults).keys), ["flint.rule.keep", "flint.limit.keep"])
            XCTAssertTrue(FlintRetainedProtection.save([:], prefix: "flint.rule.", to: defaults))
            XCTAssertEqual(receipts(defaults), limit)
        }
    }

    func testConcurrentLayerWritesCannotDropOneAnothersReceipts() throws {
        try withDefaults { defaults in
            DispatchQueue.concurrentPerform(iterations: 40) { index in
                let prefix = index.isMultiple(of: 2) ? "flint.rule." : "flint.limit."
                XCTAssertTrue(FlintRetainedProtection.save([prefix + "keep": .init(endsAt: self.end, cleared: false)],
                    prefix: prefix, to: defaults))
            }
            XCTAssertEqual(Set(receipts(defaults).keys), ["flint.rule.keep", "flint.limit.keep"])
        }
    }

    func testRemovingCauseClearsItsRetainedStoreEvenWhenNativeActivityAlreadyVanished() throws {
        try withDefaults { defaults in
            let name = "flint.rule.removed"
            XCTAssertTrue(FlintRetainedProtection.save([name: .init(endsAt: end, cleared: false)],
                prefix: "flint.rule.", to: defaults))
            let center = FakeActivityCenter()
            var cleared: [String] = []
            _ = FlintActivityRegistration.reconcile([], prefix: "flint.rule.", enabled: 0,
                validationFailures: [], center: center, defaults: defaults, onRemove: { cleared.append($0) })
            XCTAssertEqual(cleared, [name])
            XCTAssertTrue(center.stops.isEmpty)
            XCTAssertTrue(receipts(defaults).isEmpty)
        }
    }

    func testReceiptBecomingUnreadableDuringRegistrationCannotLeaveANewUnsavedBound() throws {
        try withDefaults { defaults in
            let center = FakeActivityCenter()
            center.hideAccepted = true
            let corrupt = Data("{partial".utf8)
            center.onStart = { _ in defaults.set(corrupt, forKey: FlintRetainedProtection.key) }
            let request = activity("flint.rule.writeRace")
            var cleared: [String] = []
            let report = reconcile(request, center, defaults, at: end.addingTimeInterval(-60)) { cleared.append($0) }
            XCTAssertEqual(cleared, [request.name])
            XCTAssertEqual(defaults.data(forKey: FlintRetainedProtection.key), corrupt)
            XCTAssertTrue(report.failures.contains { $0.reason.contains("could not be saved") })
        }
    }

    private func activity(_ name: String) -> FlintActivityRegistration {
        .init(name: name, schedule: .init(intervalStart: .init(hour: 9), intervalEnd: .init(hour: 17), repeats: true),
              retainedUntil: end)
    }

    private func reconcile(_ request: FlintActivityRegistration, _ center: FakeActivityCenter,
                           _ defaults: UserDefaults, at now: Date, onRemove: (String) -> Void)
        -> FlintArmingHealth.DomainReport {
        FlintActivityRegistration.reconcile([request], prefix: "flint.rule.", enabled: 1,
            validationFailures: [], center: center, defaults: defaults, now: now, onRemove: onRemove)
    }

    private func receipts(_ defaults: UserDefaults) -> [String: FlintRetainedProtection.Receipt] {
        guard case .available(let receipts) = FlintRetainedProtection.load(from: defaults) else {
            XCTFail("expected readable receipts")
            return [:]
        }
        return receipts
    }

    private func withDefaults(_ body: (UserDefaults) throws -> Void) throws {
        let suite = "flint.tests.retained.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        try body(defaults)
    }
}
