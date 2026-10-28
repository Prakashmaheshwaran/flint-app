import XCTest
import DeviceActivity
@testable import FlintCore

private final class FakeActivityCenter: FlintActivityRegistering {
    var held: [DeviceActivityName: FlintActivityRegistration] = [:]
    var starts: [String] = []
    var stops: [[String]] = []
    var refused: Set<String> = []
    var hideAccepted = false
    var activities: [DeviceActivityName] { Array(held.keys) }
    func schedule(for name: DeviceActivityName) -> DeviceActivitySchedule? { held[name]?.schedule }
    func events(for name: DeviceActivityName) -> [DeviceActivityEvent.Name: DeviceActivityEvent] {
        held[name]?.events ?? [:]
    }
    func startMonitoring(_ name: DeviceActivityName, during schedule: DeviceActivitySchedule,
                         events: [DeviceActivityEvent.Name: DeviceActivityEvent]) throws {
        starts.append(name.rawValue)
        if refused.contains(name.rawValue) { throw DeviceActivityCenter.MonitoringError.excessiveActivities }
        if !hideAccepted {
            held[name] = .init(name: name.rawValue, schedule: schedule, events: events)
        }
    }
    func stopMonitoring(_ names: [DeviceActivityName]) {
        stops.append(names.map(\.rawValue).sorted())
        for name in names { held.removeValue(forKey: name) }
    }
}

final class FlintActivityRegistrationTests: XCTestCase {
    func testUnchangedRegistrationIsObservedWithoutRestarting() {
        let center = FakeActivityCenter()
        let request = activity("flint.rule.same")
        center.held[.init(request.name)] = request
        let report = reconcile([request], center)
        XCTAssertTrue(center.starts.isEmpty)
        XCTAssertTrue(center.stops.isEmpty)
        XCTAssertEqual(report.attempted, 0)
        XCTAssertEqual(report.armed, 1)
        XCTAssertEqual(report.observedActivities, [request.name])
    }

    func testOnlyMissingChangedAndRemovedActivitiesAreTouched() {
        let center = FakeActivityCenter()
        let unchanged = activity("flint.rule.unchanged")
        let changed = activity("flint.rule.changed", hour: 10)
        let removed = activity("flint.rule.removed")
        let session = activity("flint.session")
        for request in [unchanged, activity(changed.name), removed, session] {
            center.held[.init(request.name)] = request
        }
        var cleared: [String] = []
        let report = FlintActivityRegistration.reconcile(
            [unchanged, changed, activity("flint.rule.new")], prefix: "flint.rule.", enabled: 3,
            validationFailures: [], center: center, onRemove: { cleared.append($0) })
        XCTAssertEqual(center.starts, [changed.name, "flint.rule.new"])
        XCTAssertEqual(center.stops, [[removed.name]])
        XCTAssertEqual(cleared, [removed.name])
        XCTAssertNotNil(center.held[.init(session.name)])
        XCTAssertEqual(report.observed, 3)
    }

    func testEventThresholdChangesAndScheduleDefinitionChangesRestart() {
        let center = FakeActivityCenter()
        var request = activity("flint.rule.event")
        request.events = [.init("budget"): .init(threshold: .init(minute: 30))]
        center.held[.init(request.name)] = request
        request.events = [.init("budget"): .init(threshold: .init(minute: 45))]
        XCTAssertEqual(reconcile([request], center).attempted, 1)
        request.configurationChanged = true
        XCTAssertEqual(reconcile([request], center).attempted, 1)
    }

    func testRefusalIsRecordedAndOtherActivitiesStillRegister() {
        let center = FakeActivityCenter()
        center.refused = ["flint.rule.refused"]
        let report = reconcile([activity("flint.rule.refused"), activity("flint.rule.ok")], center)
        XCTAssertEqual(report.attempted, 2)
        XCTAssertEqual(report.armed, 1)
        XCTAssertEqual(report.observedActivities, ["flint.rule.ok"])
        XCTAssertEqual(report.failures.map(\.activityName), ["flint.rule.refused"])
    }

    func testAcceptedButUnobservedRegistrationIsNotHealthy() {
        let center = FakeActivityCenter()
        center.hideAccepted = true
        let report = reconcile([activity("flint.rule.hidden")], center)
        XCTAssertEqual(report.armed, 1, "accepted call and observed state are different facts")
        XCTAssertEqual(report.observed, 0)
        XCTAssertEqual(report.failures.count, 1)
    }

    func testEmptyDomainStopsOnlyItsOwnStaleActivitiesAndNeverPassesEmptyStop() {
        let center = FakeActivityCenter()
        let session = activity("flint.session")
        center.held[.init(session.name)] = session
        XCTAssertEqual(reconcile([], center).observed, 0)
        XCTAssertTrue(center.stops.isEmpty)
        let stale = activity("flint.rule.stale")
        center.held[.init(stale.name)] = stale
        _ = reconcile([], center)
        XCTAssertEqual(center.stops, [[stale.name]])
        XCTAssertEqual(center.activities, [.init(session.name)])
    }

    func testChangedDefinitionRefusalDoesNotBecomeTheAcceptedBaseline() {
        let center = FakeActivityCenter()
        var request = activity("flint.rule.changed")
        center.held[.init(request.name)] = request
        request.configurationChanged = true
        center.refused.insert(request.name)
        var cleared: [String] = []
        let report = FlintActivityRegistration.reconcile([request], prefix: "flint.rule.", enabled: 1,
            validationFailures: [], center: center, onRemove: { cleared.append($0) })
        XCTAssertEqual(report.observed, 0)
        XCTAssertEqual(report.failures.count, 1)
        XCTAssertEqual(cleared, [request.name])
        XCTAssertEqual(center.stops, [[request.name]])
        XCTAssertTrue(center.activities.isEmpty, "no old registration may reapply the failed rule")
    }

    private func activity(_ name: String, hour: Int = 9) -> FlintActivityRegistration {
        .init(name: name, schedule: .init(intervalStart: .init(hour: hour), intervalEnd: .init(hour: 17), repeats: true))
    }

    private func reconcile(_ requests: [FlintActivityRegistration], _ center: FakeActivityCenter)
        -> FlintArmingHealth.DomainReport {
        FlintActivityRegistration.reconcile(requests, prefix: "flint.rule.", enabled: requests.count,
                                             validationFailures: [], center: center)
    }
}
