import Foundation

/// A failed repair must not roll a finite cause's abandoned store forward into the next day.
/// This is host reconciliation cleanup, not a substitute for an out-of-app end callback.
enum FlintRetainedProtection {
    struct Receipt: Codable, Equatable {
        var endsAt: Date
        var cleared: Bool
    }

    enum Read: Equatable {
        case available([String: Receipt])
        case unavailable
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

    static let key = "flint.protection.retainedStoreEnds.v1"
    // All writers are host controllers. Serialize their read/merge/write so concurrent
    // reloads cannot replace another layer's newer receipts with an old snapshot.
    private static let lock = NSLock()

    static func load(from defaults: UserDefaults?) -> Read {
        lock.lock()
        defer { lock.unlock() }
        return read(from: defaults)
    }

    private static func read(from defaults: UserDefaults?) -> Read {
        guard let defaults else { return .unavailable }
        guard let object = defaults.object(forKey: key) else { return .available([:]) }
        guard let data = object as? Data,
              let receipts = try? JSONDecoder().decode([String: Receipt].self, from: data) else {
            return .unavailable
        }
        return .available(receipts)
    }

    @discardableResult
    static func save(_ receipts: [String: Receipt], prefix: String, to defaults: UserDefaults?) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let defaults, case .available(let current) = read(from: defaults) else { return false }
        var merged = current.filter { !$0.key.hasPrefix(prefix) }
        for (name, receipt) in receipts where name.hasPrefix(prefix) { merged[name] = receipt }
        guard let data = try? JSONEncoder().encode(merged) else { return false }
        defaults.set(data, forKey: key)
        return true
    }
}
