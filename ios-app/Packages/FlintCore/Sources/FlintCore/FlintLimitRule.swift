import Foundation
#if canImport(FamilyControls)
import FamilyControls

/// A daily usage **Time Limit**: once the selected apps/sites accumulate `thresholdMinutes` of
/// use today, they shield until the day resets. Implemented with a `DeviceActivityEvent` whose
/// threshold the `DeviceActivityMonitor` reacts to. Free in Flint (incl. the hard reset tier).
public struct FlintLimitRule: Codable, Identifiable, Equatable {
    public var id: String
    public var name: String
    /// Daily cumulative-usage budget, in minutes, before the shield drops.
    public var thresholdMinutes: Int
    public var selection: FamilyActivitySelection
    public var breakLevel: BreakLevel
    public var enabled: Bool

    public init(
        id: String = String(UUID().uuidString.prefix(8)),
        name: String,
        thresholdMinutes: Int,
        selection: FamilyActivitySelection = FamilyActivitySelection(),
        breakLevel: BreakLevel = .easy,
        enabled: Bool = true
    ) {
        self.id = id
        self.name = name
        self.thresholdMinutes = thresholdMinutes
        self.selection = selection
        self.breakLevel = breakLevel
        self.enabled = enabled
    }

    /// The all-day `DeviceActivityName` that hosts this limit's threshold event.
    public var activityName: String { "flint.limit.\(id)" }
    /// The `DeviceActivityEvent.Name` that fires when the budget is hit.
    public var eventName: String { "flint.limitEvent.\(id)" }
    /// The `ManagedSettingsStore` this limit shields into.
    public var storeName: String { "flintL.\(id)" }
}
#endif
