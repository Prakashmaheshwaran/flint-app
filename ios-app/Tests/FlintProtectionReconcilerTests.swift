import XCTest
@testable import FlintCore

@MainActor
private struct FakeProtectionLayer: FlintProtectionLayer {
    let domain: String
    let run: (FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport
    func reconcile(trigger: FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport {
        try await run(trigger)
    }
}

@MainActor
final class FlintProtectionReconcilerTests: XCTestCase {
    func testAsynchronousPreparationCompletesBeforeAnyLayerRuns() async {
        var calls: [String] = []
        let engine = FlintProtectionReconciler(layers: [FakeProtectionLayer(domain: "rules") { _ in
            calls.append("layer")
            return .init(enabled: 0, attempted: 0, armed: 0, failures: [])
        }], persist: { _ in }, prepare: {
            calls.append("prepare")
            await Task.yield()
            calls.append("prepared")
        })
        _ = await engine.reconcile(trigger: .launch)
        XCTAssertEqual(calls, ["prepare", "prepared", "layer"])
    }

    func testEveryTriggerRunsEveryLayerAndPersistsTheObservation() async {
        let names = ["session", "schedules", "limits", "openLimits", "webRestrictions"]
        var calls: [String] = []
        var persisted: [FlintProtectionObservation] = []
        let date = Date(timeIntervalSinceReferenceDate: 12345)
        let layers = names.map { name in
            FakeProtectionLayer(domain: name) { trigger in
                calls.append("\(trigger.rawValue):\(name)")
                return .init(enabled: 1, attempted: 1, armed: 1, failures: [], updatedAt: date,
                             observedActivities: [name])
            }
        }
        let engine = FlintProtectionReconciler(layers: layers, now: { date }, persist: { persisted.append($0) })
        for trigger in FlintProtectionTrigger.allCases {
            let observation = await engine.reconcile(trigger: trigger)
            XCTAssertEqual(observation.trigger, trigger)
            XCTAssertEqual(observation.observedAt, date)
            XCTAssertEqual(Set(observation.domains.keys), Set(names))
        }
        XCTAssertEqual(calls, FlintProtectionTrigger.allCases.flatMap { trigger in
            names.map { "\(trigger.rawValue):\($0)" }
        })
        XCTAssertEqual(persisted.count, FlintProtectionTrigger.allCases.count)
    }

    func testThrownFailureDoesNotStopOtherLayers() async {
        enum Refusal: Error { case refused }
        var laterRan = false
        let engine = FlintProtectionReconciler(layers: [
            FakeProtectionLayer(domain: "session") { _ in throw Refusal.refused },
            FakeProtectionLayer(domain: "schedules") { _ in
                laterRan = true
                return .init(enabled: 1, attempted: 1, armed: 1, failures: [])
            }
        ], persist: { _ in })
        let observation = await engine.reconcile(trigger: .launch)
        XCTAssertTrue(laterRan)
        XCTAssertEqual(observation.domains["session"]?.failures.count, 1)
        XCTAssertEqual(observation.domains["schedules"]?.armed, 1)
    }

    func testOverlappingLaunchAndForegroundSerializeAcrossSuspensionPoints() async {
        var running = 0
        var maximumRunning = 0
        var calls: [FlintProtectionTrigger] = []
        let layers = ["first", "second", "third"].map { name in
            FakeProtectionLayer(domain: name) { trigger in
                running += 1
                maximumRunning = max(maximumRunning, running)
                calls.append(trigger)
                for _ in 0..<5 { await Task.yield() }
                running -= 1
                return .init(enabled: 0, attempted: 0, armed: 0, failures: [])
            }
        }
        let engine = FlintProtectionReconciler(layers: layers, persist: { _ in })
        let launch = Task { await engine.reconcile(trigger: .launch) }
        let foreground = Task { await engine.reconcile(trigger: .foreground) }
        _ = await launch.value
        _ = await foreground.value
        XCTAssertEqual(maximumRunning, 1)
        XCTAssertEqual(calls.count, 6)
        XCTAssertEqual(Set(calls.prefix(3)).count, 1)
        XCTAssertEqual(Set(calls.suffix(3)).count, 1)
        XCTAssertNotEqual(calls.first, calls.last)
    }
}
