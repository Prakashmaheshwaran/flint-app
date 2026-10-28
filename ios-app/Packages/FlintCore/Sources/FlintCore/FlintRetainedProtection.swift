import Foundation

/// A failed repair must not roll a finite cause's abandoned store forward into the next day.
/// This is host reconciliation cleanup, not a substitute for an out-of-app end callback.
enum FlintRetainedProtection {
    struct Receipt: Codable {
        var endsAt: Date
        var cleared: Bool
    }

    static func windowEnd(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int,
                          daysOfWeek: Set<Int> = [], now: Date, calendar: Calendar = .autoupdatingCurrent) -> Date {
        let today = calendar.startOfDay(for: now)
        for offset in 0...7 {
            guard let day = calendar.date(byAdding: .day, value: -offset, to: today),
                  daysOfWeek.isEmpty || daysOfWeek.contains(calendar.component(.weekday, from: day)),
                  let start = calendar.date(bySettingHour: startHour, minute: startMinute, second: 0, of: day),
                  start <= now else { continue }
            let overnight = endHour * 60 + endMinute <= startHour * 60 + startMinute
            guard let endDay = calendar.date(byAdding: .day, value: overnight ? 1 : 0, to: day),
                  let end = calendar.date(bySettingHour: endHour, minute: endMinute, second: 0, of: endDay,
                    matchingPolicy: .nextTime, repeatedTimePolicy: .last) else { continue }
            return end
        }
        return now
    }

    private static let key = "flint.protection.retainedStoreEnds.v1"
    static func load(from defaults: UserDefaults?) -> [String: Receipt] {
        guard let data = defaults?.data(forKey: key) else { return [:] }
        return (try? JSONDecoder().decode([String: Receipt].self, from: data)) ?? [:]
    }
    static func save(_ receipts: [String: Receipt], to defaults: UserDefaults?) {
        guard let data = try? JSONEncoder().encode(receipts) else { return }
        defaults?.set(data, forKey: key)
    }
}
