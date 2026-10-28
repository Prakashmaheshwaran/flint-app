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
    /// End of the current or most recently started window (never a future window's end).
    /// A future value therefore means that window contains now. nil for Open Limits.
    var retainedUntil: Date? = nil
    var definition: FlintActivityDefinition { .init(schedule: schedule, events: events) }

    static func reconcile(
        _ desired: [Self],
        prefix: String,
        enabled: Int,
        validationFailures: [FlintArmingHealth.Failure],
        center: FlintActivityRegistering = DeviceActivityCenter(),
        defaults: UserDefaults? = .flintGroup,
        now: Date = Date(),
        onRemove: (String) -> Void = { _ in }
    ) -> FlintArmingHealth.DomainReport {
        let wanted = Set(desired.map { DeviceActivityName($0.name) })
        let before = Set(center.activities)
        var definitions = FlintActivityDefinition.load(from: defaults)
        let retainedRead = FlintRetainedProtection.load(from: defaults)
        var retained: [String: FlintRetainedProtection.Receipt]
        switch retainedRead {
        case .available(let receipts): retained = receipts
        case .unavailable: retained = [:]
        }
        let removed = before.filter { $0.rawValue.hasPrefix(prefix) && !wanted.contains($0) }
        // A removed cause can still have a retained store after its native activity vanished.
        let removedReceipts = retained.filter {
            $0.key.hasPrefix(prefix) && !wanted.contains(.init($0.key)) && !$0.value.cleared
        }.map(\.key)
        var attempted = 0
        var armed = 0
        var failures = validationFailures
        var accepted: Set<String> = []
        for request in desired {
            let name = DeviceActivityName(request.name)
            let matchesNative = center.schedule(for: name).map {
                FlintActivityDefinition(schedule: $0, events: center.events(for: name)) == request.definition
            } ?? false
            if before.contains(name), center.schedule(for: name) != nil, !request.configurationChanged,
               matchesNative || definitions[request.name] == request.definition {
                // Already held by iOS, with the requested definition. No restart/callback churn.
                armed += 1
                accepted.insert(request.name)
                definitions[request.name] = request.definition
                continue
            }
            attempted += 1
            do {
                try center.startMonitoring(name, during: request.schedule, events: request.events)
                armed += 1
                accepted.insert(request.name)
                definitions[request.name] = request.definition
            } catch {
                // A refused replacement is not authority to erase a last-good registration or
                // shield (including a spent Time Limit or non-expiring Open-Limit shield).
                // A refused new attempt has no newly applied shield to clear. Report the fault.
                failures.append(.init(activityName: request.name, reason: String(describing: error)))
            }
        }
        // Observe after writes as well. Acceptance and observation are separate facts.
        let after = Set(center.activities)
        let observed = desired.filter {
            let name = DeviceActivityName($0.name)
            // Real name observation + accepted/persisted definition also tolerates OS
            // normalization beyond the fields above. This is registration evidence only.
            return accepted.contains($0.name) && after.contains(name) && center.schedule(for: name) != nil
                && definitions[$0.name] == $0.definition
        }.map(\.name).sorted()
        for request in desired where !observed.contains(request.name)
            && !failures.contains(where: { $0.activityName == request.name }) {
            failures.append(.init(activityName: request.name,
                                  reason: "The requested registration was not observed in iOS after reconciliation."))
        }
        // Retire only AFTER replacement registration/observation. A refused successor never
        // authorizes removal of its predecessor's end callback or last-good shield (PC-12).
        let retired = Set(removed.map(\.rawValue) + removedReceipts).filter { old in
            guard let id = FlintRuleActivity.ruleID(old, prefix: prefix),
                  let successor = desired.first(where: { FlintRuleActivity.ruleID($0.name, prefix: prefix) == id }) else { return true }
            return observed.contains(successor.name)
        }
        retired.forEach(onRemove)
        let stop = removed.filter { retired.contains($0.rawValue) }
        if !stop.isEmpty { center.stopMonitoring(Array(stop)) }
        definitions = definitions.filter { !retired.contains($0.key) }
        retained = retained.filter { !retired.contains($0.key) }
        // Do not cache accepted-but-unobserved requests as a later no-churn baseline.
        let previous = FlintActivityDefinition.load(from: defaults)
        for request in desired where !observed.contains(request.name) {
            definitions[request.name] = previous[request.name]
        }
        FlintActivityDefinition.save(definitions, to: defaults)
        let observedNames = Set(observed)
        var clearedThisPass: Set<String> = []
        var unbounded: Set<String> = []
        func clearRetained(_ name: String, reason: String) {
            if clearedThisPass.insert(name).inserted { onRemove(name) }
            let previousReasons = failures.filter { $0.activityName == name }.map(\.reason)
            failures.removeAll { $0.activityName == name }
            failures.append(.init(activityName: name,
                reason: ([reason] + previousReasons.filter { $0 != reason }).joined(separator: " ")))
        }
        // Acceptance can be unobserved without throwing. Evaluate every finite cause only
        // after the native read, including whether an older registration still has an end.
        for request in desired {
            guard let end = request.retainedUntil else { continue } // Open Limits have no finite expiry.
            let isObserved = observedNames.contains(request.name)
            let name = DeviceActivityName(request.name)
            let hasEnd = after.contains(name) && center.schedule(for: name) != nil
            if isObserved {
                // Outside an active window, clear the abandoned store BEFORE dropping its
                // expired bound. A repaired current window has its own observed native end;
                // keep its shield even when an old window's receipt expired before this edit.
                if let receipt = retained[request.name], !receipt.cleared,
                   now >= receipt.endsAt, now >= end {
                    clearRetained(request.name,
                        reason: "The previous protection window ended before registration recovered. Its retained shield was cleared.")
                }
                retained.removeValue(forKey: request.name)
            } else if !hasEnd {
                unbounded.insert(request.name)
                guard case .available = retainedRead else {
                    // An unreadable old bound is never permission to pin a fresh deadline.
                    // Treat it as expired and leave the unreadable blob untouched.
                    clearRetained(request.name,
                        reason: "The retained protection deadline is unavailable. Its retained shield was cleared instead of extending it.")
                    continue
                }
                var receipt = retained[request.name] ?? .init(endsAt: end, cleared: false)
                if now >= receipt.endsAt, !receipt.cleared {
                    clearRetained(request.name,
                        reason: "The previous protection window ended without a registered end. Its retained shield was cleared.")
                    receipt.cleared = true
                }
                retained[request.name] = receipt
            }
        }
        for name in observed { retained.removeValue(forKey: name) }
        if case .available = retainedRead,
           !FlintRetainedProtection.save(retained, prefix: prefix, to: defaults) {
            // A write failure or newly unreadable blob cannot leave an unpersisted bound.
            for name in unbounded where retained[name]?.cleared != true && !clearedThisPass.contains(name) {
                clearRetained(name,
                    reason: "The retained protection deadline could not be saved. Its retained shield was cleared.")
            }
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

    static func changed(_ rule: FlintScheduleRule, previous: FlintScheduleRule?) -> Bool {
        // Seed an upgrade's baseline from its first matching native observation. Renaming or
        // changing break difficulty does not change monitoring or the callback's selection.
        guard let previous else { return false }
        return previous.schedule != rule.schedule || previous.selection != rule.selection
            || previous.allowListMode != rule.allowListMode
    }

    static func save(_ rules: [FlintScheduleRule], observed: [String],
                     to defaults: UserDefaults? = .flintGroup) {
        let names = Set(rules.map(\.monitorName))
        var byName = load(from: defaults).filter { names.contains($0.key) }
        for rule in rules where observed.contains(rule.monitorName) { byName[rule.monitorName] = rule }
        defaults?.set(try? JSONEncoder().encode(byName), forKey: key)
    }
}
#endif
