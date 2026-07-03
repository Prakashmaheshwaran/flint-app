import Foundation
#if canImport(FamilyControls)
import FamilyControls

/// A recurring (or daily) scheduled block. Unlike Opal, Flint imposes **no count cap and no
/// 24h-advance cap**. Each rule carries its own app/site selection and break level, and gets its
/// own `DeviceActivity` registration + `ManagedSettingsStore`, so multiple schedules can overlap
/// without clobbering each other (iOS 16 allows up to 50 named stores).
public struct FlintScheduleRule: Codable, Identifiable, Equatable {
    public var id: String
    public var name: String
    public var schedule: FlintSchedule
    public var breakLevel: BreakLevel
    public var selection: FamilyActivitySelection
    public var allowListMode: Bool
    public var enabled: Bool

    public init(
        id: String = String(UUID().uuidString.prefix(8)),
        name: String,
        schedule: FlintSchedule,
        breakLevel: BreakLevel = .easy,
        selection: FamilyActivitySelection = FamilyActivitySelection(),
        allowListMode: Bool = false,
        enabled: Bool = true
    ) {
        self.id = id
        self.name = name
        self.schedule = schedule
        self.breakLevel = breakLevel
        self.selection = selection
        self.allowListMode = allowListMode
        self.enabled = enabled
    }

    /// The `DeviceActivityName` used to register this rule's schedule.
    public var monitorName: String { "flint.rule.\(id)" }
    /// The `ManagedSettingsStore` name this rule shields into (kept short for the store-name limit).
    public var storeName: String { "flint.\(id)" }

    /// Whether the rule should enforce on the given date (its day-of-week gate).
    public func appliesOn(_ date: Date, calendar: Calendar = .current) -> Bool {
        guard !schedule.daysOfWeek.isEmpty else { return true }
        return schedule.daysOfWeek.contains(calendar.component(.weekday, from: date))
    }
}
#endif
