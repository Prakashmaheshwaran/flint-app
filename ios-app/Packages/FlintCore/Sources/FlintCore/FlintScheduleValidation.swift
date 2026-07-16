import Foundation

/// Why a `FlintSchedule` cannot be registered as a repeating `DeviceActivitySchedule`.
///
/// These are not style rules. Each maps to a hard constraint `DeviceActivityCenter` enforces by
/// **throwing** from `startMonitoring(_:during:events:)`. Flint's controllers arm with `try?`, so
/// an unregistrable window used to fail *silently*: the rule kept its enabled toggle in the
/// Schedules list, the user believed they were blocked, and nothing was ever shielded. In a
/// blocking app that is the worst failure mode there is — so windows are validated before arming,
/// and anything that can't arm is surfaced rather than swallowed.
///
/// The wording of the shared cases mirrors Android's `RuleDraftError` so both platforms tell the
/// user the same thing. `windowTooShort` has no Android counterpart: it is DeviceActivity's floor.
public enum FlintScheduleIssue: String, Equatable, Sendable, CaseIterable {
    /// An endpoint isn't a wall-clock time (hour 0…23, minute 0…59).
    case invalidTime
    /// `daysOfWeek` holds a value outside `Calendar`'s 1 (Sunday) … 7 (Saturday).
    case invalidDays
    /// Start equals end. There is no 24-hour window — the widest daily window is 00:00–23:59.
    case zeroLengthWindow
    /// Shorter than `FlintSchedule.minimumWindowMinutes`.
    case windowTooShort

    /// The user-facing line the schedule editor shows.
    public var message: String {
        switch self {
        case .invalidTime:
            return "Start and end must be times between 00:00 and 23:59."
        case .invalidDays:
            return "This schedule has an unrecognized day — reselect the days it runs on."
        case .zeroLengthWindow:
            return "Start and end can't be the same time. For all day, use 00:00–23:59."
        case .windowTooShort:
            return "iOS needs at least \(FlintSchedule.minimumWindowMinutes) minutes between "
                + "start and end, or the block never arms."
        }
    }
}

extension FlintSchedule {

    /// `DeviceActivitySchedule` rejects intervals shorter than 15 minutes.
    public static let minimumWindowMinutes = 15
    /// `Calendar`'s weekday numbering: 1 = Sunday … 7 = Saturday.
    public static let weekdayRange = 1...7
    /// Modulus for the clock arithmetic that wraps a window past midnight.
    public static let minutesPerDay = 24 * 60

    public var startMinuteOfDay: Int { startHour * 60 + startMinute }
    public var endMinuteOfDay: Int { endHour * 60 + endMinute }

    /// Whether both endpoints are real wall-clock times. Windows are decoded from the App Group,
    /// so a truncated or hand-edited payload can carry an hour of 30 — checked, never assumed.
    public var hasValidEndpoints: Bool {
        (0...23).contains(startHour) && (0...59).contains(startMinute)
            && (0...23).contains(endHour) && (0...59).contains(endMinute)
    }

    /// Whether the window wraps past midnight (22:00 → 07:00). Legal and common: DeviceActivity
    /// reads an `intervalEnd` earlier than `intervalStart` as ending the next day. The monitor
    /// gates such a rule on the weekday its interval *started*, which is what the user picked.
    public var isOvernight: Bool { hasValidEndpoints && endMinuteOfDay < startMinuteOfDay }

    /// Window length in minutes, wrapping across midnight. Zero for a degenerate window
    /// (start == end) or one whose endpoints aren't times at all.
    public var windowMinutes: Int {
        guard hasValidEndpoints else { return 0 }
        return (endMinuteOfDay - startMinuteOfDay + Self.minutesPerDay) % Self.minutesPerDay
    }

    /// Everything that stops this window from being registered, empty when it will arm. Time
    /// problems report one at a time (an out-of-range endpoint makes the length meaningless);
    /// a day problem is independent, so it can accompany a time problem.
    public var issues: [FlintScheduleIssue] {
        var found: [FlintScheduleIssue] = []
        if !hasValidEndpoints {
            found.append(.invalidTime)
        } else if windowMinutes == 0 {
            found.append(.zeroLengthWindow)
        } else if windowMinutes < Self.minimumWindowMinutes {
            found.append(.windowTooShort)
        }
        if !daysOfWeek.allSatisfy(Self.weekdayRange.contains) {
            found.append(.invalidDays)
        }
        return found
    }

    /// True when `DeviceActivityCenter` will accept this window.
    public var isValid: Bool { issues.isEmpty }
}
