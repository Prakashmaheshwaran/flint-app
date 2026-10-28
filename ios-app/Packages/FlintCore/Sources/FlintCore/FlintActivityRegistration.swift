import Foundation
#if canImport(DeviceActivity)
import DeviceActivity

/// Small OS seam: tests exercise real schedules/events against a fake center, never Screen Time.
protocol FlintActivityRegistering {
    var activities: [DeviceActivityName] { get }
    func schedule(for name: DeviceActivityName) -> DeviceActivitySchedule?
    func events(for name: DeviceActivityName) -> [DeviceActivityEvent.Name: DeviceActivityEvent]
    func startMonitoring(_ name: DeviceActivityName, during schedule: DeviceActivitySchedule,
                         events: [DeviceActivityEvent.Name: DeviceActivityEvent]) throws
    func stopMonitoring(_ names: [DeviceActivityName])
}

extension DeviceActivityCenter: FlintActivityRegistering {}

struct FlintActivityRegistration {
    let name: String
    let schedule: DeviceActivitySchedule
    var events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
    // Schedule selection/day/allow-list edits are not represented in OS schedules/events.
    var configurationChanged = false

    static func reconcile(
        _ desired: [Self],
        prefix: String,
        enabled: Int,
        validationFailures: [FlintArmingHealth.Failure],
        center: FlintActivityRegistering = DeviceActivityCenter(),
        onRemove: (String) -> Void = { _ in }
    ) -> FlintArmingHealth.DomainReport {
        let wanted = Set(desired.map { DeviceActivityName($0.name) })
        let before = Set(center.activities)
        let removed = before.filter { $0.rawValue.hasPrefix(prefix) && !wanted.contains($0) }
        // Never forward [] to Apple's stop-all default.
        if !removed.isEmpty {
            removed.forEach { onRemove($0.rawValue) }
            center.stopMonitoring(Array(removed))
        }
        var attempted = 0
        var armed = 0
        var failures = validationFailures
        var accepted: Set<String> = []
        for request in desired {
            let name = DeviceActivityName(request.name)
            if before.contains(name), !request.configurationChanged,
               center.schedule(for: name) == request.schedule,
               center.events(for: name) == request.events {
                // Already held by iOS, with the requested definition. No restart/callback churn.
                armed += 1
                accepted.insert(request.name)
                continue
            }
            attempted += 1
            do {
                try center.startMonitoring(name, during: request.schedule, events: request.events)
                armed += 1
                accepted.insert(request.name)
            } catch {
                // A refused replacement can leave the old registration/store behind, while
                // Open Limits may already have applied its launch shield. Release this cause
                // explicitly: a failed arm must not leave a shield with no dependable end.
                center.stopMonitoring([name])
                onRemove(request.name)
                failures.append(.init(activityName: request.name, reason: String(describing: error)))
            }
        }
        // Observe after writes as well. Acceptance and observation are separate facts.
        let after = Set(center.activities)
        let observed = desired.filter {
            let name = DeviceActivityName($0.name)
            return accepted.contains($0.name) && after.contains(name) && center.schedule(for: name) == $0.schedule
                && center.events(for: name) == $0.events
        }.map(\.name).sorted()
        for request in desired where !observed.contains(request.name)
            && !failures.contains(where: { $0.activityName == request.name }) {
            failures.append(.init(activityName: request.name,
                                  reason: "The requested registration was not observed in iOS after reconciliation."))
        }
        return .init(enabled: enabled, attempted: attempted, armed: armed, failures: failures,
                     observedActivities: observed)
    }
}

/// A schedule also depends on its selection, day gate and allow-list mode. Persist its last
/// accepted definition separately; never turn an unobserved/failed attempt into the new baseline.
enum FlintScheduleRegistrationState {
    private static let key = "flint.protection.scheduleDefinitions.v1"

    static func load(from defaults: UserDefaults? = .flintGroup) -> [String: FlintScheduleRule] {
        guard let data = defaults?.data(forKey: key),
              let rules = try? JSONDecoder().decode([String: FlintScheduleRule].self, from: data) else { return [:] }
        return rules
    }

    static func save(_ rules: [FlintScheduleRule], observed: [String],
                     to defaults: UserDefaults? = .flintGroup) {
        let accepted = rules.filter { observed.contains($0.monitorName) }
        let byName = Dictionary(accepted.map { ($0.monitorName, $0) }, uniquingKeysWith: { _, last in last })
        defaults?.set(try? JSONEncoder().encode(byName), forKey: key)
    }
}
#endif
