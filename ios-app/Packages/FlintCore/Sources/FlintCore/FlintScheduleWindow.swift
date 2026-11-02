import Foundation

/// Shared weekday/overnight occurrence calculation. The weekday belongs to the start date;
/// callers supply the saved rule's calendar/zone, rather than moving an active occurrence.
/// ADR-012 §9 requires an earlier ambiguous start and a later ambiguous end. The same
/// materialization policy governs enforcement; a repeated hour does not shorten that end.
public enum FlintScheduleWindow {
    public struct Resolution: Equatable, Sendable {
        public let current: DateInterval?
        public let next: DateInterval?
    }

    public static func resolve(_ schedule: FlintSchedule, at now: Date,
                               calendar: Calendar) -> Resolution? {
        guard schedule.isValid else { return nil }
        let today = calendar.startOfDay(for: now)
        var current: DateInterval?
        var next: DateInterval?
        // Yesterday captures an overnight tail; eight days captures the next weekly start.
        for offset in -1...8 {
            guard let day = calendar.date(byAdding: .day, value: offset, to: today) else { return nil }
            let weekday = calendar.component(.weekday, from: day)
            guard schedule.daysOfWeek.isEmpty || schedule.daysOfWeek.contains(weekday) else { continue }
            guard let start = endpoint(hour: schedule.startHour, minute: schedule.startMinute,
                                       day: day, calendar: calendar, repeated: .first),
                  let endDay = calendar.date(byAdding: .day, value: schedule.isOvernight ? 1 : 0, to: day),
                  let end = endpoint(hour: schedule.endHour, minute: schedule.endMinute,
                                     day: endDay, calendar: calendar, repeated: .last),
                  end > start, end.timeIntervalSince(start) <= 26 * 3600 else { return nil }
            let interval = DateInterval(start: start, end: end)
            if start <= now && now < end { current = interval }
            if start > now && (next == nil || start < next!.start) { next = interval }
        }
        return Resolution(current: current, next: next)
    }

    private static func endpoint(hour: Int, minute: Int, day: Date, calendar: Calendar,
                                 repeated: Calendar.RepeatedTimePolicy) -> Date? {
        guard let result = calendar.nextDate(after: day.addingTimeInterval(-1),
            matching: DateComponents(hour: hour, minute: minute, second: 0),
            matchingPolicy: .nextTimePreservingSmallerComponents,
            repeatedTimePolicy: repeated, direction: .forward),
            calendar.isDate(result, inSameDayAs: day) else { return nil }
        return result
    }
}

extension FlintSchedule {
    /// Uses the shared start-weekday and DST endpoint policy, including overnight tails.
    public func isWindowActive(at date: Date = Date(), calendar: Calendar = .current) -> Bool {
        FlintScheduleWindow.resolve(self, at: date, calendar: calendar)?.current != nil
    }
}
