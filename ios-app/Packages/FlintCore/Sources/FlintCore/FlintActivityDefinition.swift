import Foundation
#if canImport(DeviceActivity) && canImport(ManagedSettings)
import DeviceActivity
import ManagedSettings

/// Compare only fields Flint sets. OS round-trips may add calendars/time zones, explicit zero
/// seconds, or normalize a minute threshold into hours. No opaque framework equality required.
struct FlintActivityDefinition: Codable, Equatable {
    struct Event: Codable, Equatable {
        var applications: Set<ApplicationToken>
        var categories: Set<ActivityCategoryToken>
        var webDomains: Set<WebDomainToken>
        var seconds: Int
    }
    var start: Int
    var end: Int
    var repeats: Bool
    var warning: Int
    var events: [String: Event]

    init(schedule: DeviceActivitySchedule, events: [DeviceActivityEvent.Name: DeviceActivityEvent]) {
        start = Self.seconds(schedule.intervalStart)
        end = Self.seconds(schedule.intervalEnd)
        repeats = schedule.repeats
        warning = Self.seconds(schedule.warningTime ?? DateComponents())
        self.events = Dictionary(uniqueKeysWithValues: events.map { name, event in
            (name.rawValue, Event(applications: event.applications, categories: event.categories,
                webDomains: event.webDomains, seconds: Self.seconds(event.threshold)))
        })
    }

    private static func seconds(_ components: DateComponents) -> Int {
        (components.day ?? 0) * 86400 + (components.hour ?? 0) * 3600
            + (components.minute ?? 0) * 60 + (components.second ?? 0)
    }

    private static let key = "flint.protection.activityDefinitions.v1"
    static func load(from defaults: UserDefaults?) -> [String: Self] {
        guard let data = defaults?.data(forKey: key) else { return [:] }
        return (try? JSONDecoder().decode([String: Self].self, from: data)) ?? [:]
    }
    static func save(_ definitions: [String: Self], to defaults: UserDefaults?) {
        defaults?.set(try? JSONEncoder().encode(definitions), forKey: key)
    }
}
#endif
