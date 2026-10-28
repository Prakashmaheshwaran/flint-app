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
    public var protection: FlintRuleProtection?
    public var tierNeedsReview: Bool?

    public init(
        id: String = String(UUID().uuidString.prefix(8)),
        name: String,
        thresholdMinutes: Int,
        selection: FamilyActivitySelection = FamilyActivitySelection(),
        breakLevel: BreakLevel = .easy,
        enabled: Bool = true,
        protection: FlintRuleProtection? = .init()
    ) {
        self.id = id
        self.name = name
        self.thresholdMinutes = thresholdMinutes
        self.selection = selection
        self.breakLevel = breakLevel
        self.enabled = enabled
        self.protection = protection
    }

    /// The all-day `DeviceActivityName` that hosts this limit's threshold event.
    public var activityName: String { FlintRuleActivity.name(prefix: "flint.limit.", id: id, protection: protection) }
    /// The `DeviceActivityEvent.Name` that fires when the budget is hit.
    public var eventName: String { FlintRuleActivity.name(prefix: "flint.limitEvent.", id: id, protection: protection) }
    /// The `ManagedSettingsStore` this limit shields into.
    public var storeName: String { "flintL.\(id)" }

    private enum CodingKeys: String, CodingKey {
        case id, name, thresholdMinutes, selection, breakLevel, enabled, protection, tierNeedsReview
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        thresholdMinutes = try c.decode(Int.self, forKey: .thresholdMinutes)
        selection = try c.decode(FamilyActivitySelection.self, forKey: .selection)
        enabled = try c.decode(Bool.self, forKey: .enabled)
        protection = c.contains(.protection) ? try c.decode(FlintRuleProtection.self, forKey: .protection) : nil
        // Old iOS tierless limits used Easy. Only ABSENCE in a valid legacy schema gets it;
        // null, unknown strings, and a partial current schema are unavailable (ADR-012 §12).
        if c.contains(.breakLevel) || protection != nil {
            breakLevel = try c.decode(BreakLevel.self, forKey: .breakLevel)
            tierNeedsReview = try c.decodeIfPresent(Bool.self, forKey: .tierNeedsReview)
        } else {
            breakLevel = .easy
            tierNeedsReview = true
        }
    }
}
#endif
