import Foundation
#if canImport(FamilyControls)
import FamilyControls

/// A recurring (or daily) scheduled block. Unlike Opal, Flint imposes **no product/paywall count
/// cap or 24h-advance cap**. Every enabled rule still consumes one slot in iOS's finite,
/// undocumented `DeviceActivity` registration pool shared by Flint's monitors. Each rule carries
/// its own app/site selection and break level, and gets its own registration +
/// `ManagedSettingsStore`, so multiple schedules can overlap without clobbering each other
/// (iOS 16 allows up to 50 named stores).
public struct FlintScheduleRule: Codable, Identifiable, Equatable {
    public var id: String
    public var name: String
    public var schedule: FlintSchedule
    public var breakLevel: BreakLevel
    public var selection: FamilyActivitySelection
    public var allowListMode: Bool
    public var enabled: Bool
    public var protection: FlintRuleProtection?
    /// Absolute one-shot window; absent for a recurring rule. An ended one-shot never repeats.
    public var oneShot: FlintRuleWindow?

    public init(
        id: String = String(UUID().uuidString.prefix(8)),
        name: String,
        schedule: FlintSchedule,
        breakLevel: BreakLevel = .easy,
        selection: FamilyActivitySelection = FamilyActivitySelection(),
        allowListMode: Bool = false,
        enabled: Bool = true,
        protection: FlintRuleProtection? = .init(),
        oneShot: FlintRuleWindow? = nil
    ) {
        self.id = id
        self.name = name
        self.schedule = schedule
        self.breakLevel = breakLevel
        self.selection = selection
        self.allowListMode = allowListMode
        self.enabled = enabled
        self.protection = protection
        self.oneShot = oneShot
    }

    /// The `DeviceActivityName` used to register this rule's schedule.
    public var monitorName: String {
        FlintRuleActivity.name(prefix: "flint.rule.", id: id, protection: protection)
    }
    /// The `ManagedSettingsStore` name this rule shields into (kept short for the store-name limit).
    public var storeName: String { "flint.\(id)" }

    /// Whether the rule should enforce on the given date (its day-of-week gate).
    public func appliesOn(_ date: Date, calendar: Calendar = .current) -> Bool {
        guard !schedule.daysOfWeek.isEmpty else { return true }
        return schedule.daysOfWeek.contains(calendar.component(.weekday, from: date))
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, schedule, breakLevel, selection, allowListMode, enabled, protection, oneShot
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        schedule = try c.decode(FlintSchedule.self, forKey: .schedule)
        breakLevel = try c.decode(BreakLevel.self, forKey: .breakLevel)
        selection = try c.decode(FamilyActivitySelection.self, forKey: .selection)
        allowListMode = try c.decode(Bool.self, forKey: .allowListMode)
        enabled = try c.decode(Bool.self, forKey: .enabled)
        protection = c.contains(.protection) ? try c.decode(FlintRuleProtection.self, forKey: .protection) : nil
        oneShot = try c.decodeIfPresent(FlintRuleWindow.self, forKey: .oneShot)
    }
}
#endif
