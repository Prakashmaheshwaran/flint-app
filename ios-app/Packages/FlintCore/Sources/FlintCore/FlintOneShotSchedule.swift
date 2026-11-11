import Foundation
#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
import FamilyControls
import DeviceActivity
import ManagedSettings
#endif

/// Why a date-specific one-time window cannot become a one-shot session. Each case mirrors a hard
/// constraint: DeviceActivity's 15-minute floor, the finite-occurrence ceiling (ADR-012 §2) and
/// the rule that a window authored now must still be able to start.
public enum FlintOneShotIssue: String, Equatable, Sendable, CaseIterable {
    /// The start is already in the past when the window is created or its date/time changes.
    case startInPast
    /// Shorter than `FlintSchedule.minimumWindowMinutes` of real time.
    case tooShort
    /// A day or longer: a one-time session is always shorter than 24 hours.
    case tooLong
    /// The local date/time pair does not resolve in the saved time zone.
    case unresolvable

    public var message: String {
        switch self {
        case .startInPast:
            return "Choose a start time in the future."
        case .tooShort:
            return "iOS needs at least \(FlintSchedule.minimumWindowMinutes) minutes between "
                + "start and end, or the one-time session never arms."
        case .tooLong:
            return "A one-time session must end within 24 hours of its start."
        case .unresolvable:
            return "This date and time can't be scheduled in the saved time zone. Pick another day or time."
        }
    }
}

/// Date-specific one-shot windows (ledger 1.3/1.5).
///
/// `FlintScheduleRule.oneShot` — the absolute UTC window resolved here — is the only activation
/// authority for a one-time session. `FlintScheduleRule.schedule` keeps a single-weekday mirror so
/// shared schedule validation, registration fingerprints and copy keep working, but the mirror is
/// **never** consulted to decide whether a one-shot is active or to repeat it on another week.
public enum FlintOneShotSchedule {
    /// How far the start may already be in the past when a one-time window is authored.
    public static let startGrace: TimeInterval = 60
    /// The longest supported one-time session (exclusive), matching the finite-occurrence model.
    public static let maximumDuration: TimeInterval = 24 * 3600

    /// Resolve one local calendar date plus start/end times of day in `zone`.
    ///
    /// An end at or before the start ends on the **next** day, so the result is always positive and
    /// shorter than 24 hours; anything else is unresolvable. Endpoints follow ADR-012 §9 through
    /// `FlintScheduleWindow.endpoint`: a missing local time moves forward past the gap, an
    /// ambiguous start takes the earlier instant and an ambiguous end the later one.
    public static func window(year: Int, month: Int, day: Int, startMinute: Int, endMinute: Int,
                              zone: TimeZone) -> FlintRuleWindow? {
        guard (1...9999).contains(year), (1...12).contains(month), (1...31).contains(day),
              (0..<FlintSchedule.minutesPerDay).contains(startMinute),
              (0..<FlintSchedule.minutesPerDay).contains(endMinute) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        guard let startDay = calendar.date(from: DateComponents(year: year, month: month, day: day)),
              calendar.component(.year, from: startDay) == year,
              calendar.component(.month, from: startDay) == month,
              calendar.component(.day, from: startDay) == day else { return nil }
        let overnight = endMinute <= startMinute
        guard let endDay = overnight ? calendar.date(byAdding: .day, value: 1, to: startDay) : startDay,
              let start = FlintScheduleWindow.endpoint(hour: startMinute / 60, minute: startMinute % 60,
                                                       day: startDay, calendar: calendar, repeated: .first),
              let end = FlintScheduleWindow.endpoint(hour: endMinute / 60, minute: endMinute % 60,
                                                     day: endDay, calendar: calendar, repeated: .last),
              end > start, end.timeIntervalSince(start) < maximumDuration else { return nil }
        return .init(start: start, end: end)
    }

    /// The single-weekday mirror stored in `FlintScheduleRule.schedule`. Only the start's weekday
    /// (in the saved zone) is kept: a one-shot never repeats, so validation and copy must describe
    /// one date, and the weekday list must not imply a weekly recurrence.
    public static func mirrorSchedule(for window: FlintRuleWindow, zone: TimeZone) -> FlintSchedule {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let start = calendar.dateComponents([.weekday, .hour, .minute], from: window.start)
        let end = calendar.dateComponents([.hour, .minute], from: window.end)
        return .init(daysOfWeek: start.weekday.map { Set([$0]) } ?? [],
            startHour: start.hour ?? 0, startMinute: start.minute ?? 0,
            endHour: end.hour ?? 0, endMinute: end.minute ?? 0)
    }

    /// Length/finite-window checks only; no notion of "now". Used by rule validation, which has no
    /// clock of its own and must stay true for a window decoded from the App Group.
    public static func durationIssue(for window: FlintRuleWindow) -> FlintOneShotIssue? {
        let span = window.end.timeIntervalSince(window.start)
        guard span.isFinite, span > 0 else { return .unresolvable }
        if span >= maximumDuration { return .tooLong }
        if span < TimeInterval(FlintSchedule.minimumWindowMinutes * 60) { return .tooShort }
        return nil
    }

    /// The authoring gate. `startChanged` is false only when an existing rule's start is kept as
    /// saved: an active commitment's own window is in the past by definition, and changing its
    /// *end* must still reach the tier's ordinary edit gate rather than be refused here.
    public static func issue(for window: FlintRuleWindow, now: Date,
                             startChanged: Bool = true) -> FlintOneShotIssue? {
        if let issue = durationIssue(for: window) { return issue }
        if startChanged, window.start.timeIntervalSince(now) < -startGrace { return .startInPast }
        return nil
    }
}

/// Where a date-specific rule sits on its trusted timeline. Terminal (`finished`) one-shots stay
/// visible and deletable: Flint never rewrites the rule store from an extension to remove them.
public enum FlintOneShotPhase: Equatable, Sendable {
    case pending(FlintRuleWindow)
    case active(FlintRuleWindow)
    case finished(FlintRuleWindow)

    /// The phase's window projected onto the device's wall clock. A same-boot clock jump only moves
    /// this displayed copy; the trusted endpoints stored in the rule never move.
    public var window: FlintRuleWindow {
        switch self {
        case let .pending(window), let .active(window), let .finished(window): return window
        }
    }

    public var isActive: Bool { if case .active = self { return true }; return false }
    public var isFinished: Bool { if case .finished = self { return true }; return false }
    public var isPending: Bool { if case .pending = self { return true }; return false }
}

#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
extension FlintScheduleRule {
    /// `nil` for a recurring rule. Activation and the finished edge use the trusted occurrence
    /// clock (ADR-012 §9), so a wall-clock jump cannot start a one-shot early or end it early.
    public func oneShotPhase(at date: Date = Date(), clock: FlintClock = .system) -> FlintOneShotPhase? {
        guard let oneShot else { return nil }
        var resolved = clock
        resolved.now = { date }
        let displayed = FlintRuleOccurrenceClock.display(oneShot, for: self, clock: resolved)
        if let current = occurrence(at: date, clock: resolved) {
            return .active(FlintRuleOccurrenceClock.display(current, for: self, clock: resolved))
        }
        return FlintRuleOccurrenceClock.date(for: self, clock: resolved) >= oneShot.end
            ? .finished(displayed) : .pending(displayed)
    }

    /// Whether this rule has a date-specific window at all (as opposed to a weekly recurrence).
    public var isOneShot: Bool { oneShot != nil }
}
#endif
