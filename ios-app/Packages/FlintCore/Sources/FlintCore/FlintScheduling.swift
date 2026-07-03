import Foundation
#if canImport(DeviceActivity)
import DeviceActivity
#endif

#if canImport(DeviceActivity)
/// Builders over `DeviceActivityCenter` — the host app registers schedules/events and the
/// `DeviceActivityMonitor` extension receives the boundary callbacks.
public enum FlintScheduling {

    /// A daily-repeating window. For a one-off "Block Now", pass `repeats: false`.
    public static func schedule(
        startHour: Int, startMinute: Int,
        endHour: Int, endMinute: Int,
        repeats: Bool = true
    ) -> DeviceActivitySchedule {
        DeviceActivitySchedule(
            intervalStart: DateComponents(hour: startHour, minute: startMinute),
            intervalEnd: DateComponents(hour: endHour, minute: endMinute),
            repeats: repeats
        )
    }

    public static func startMonitoring(
        _ name: String,
        during schedule: DeviceActivitySchedule,
        events: [DeviceActivityEvent.Name: DeviceActivityEvent] = [:]
    ) throws {
        try DeviceActivityCenter().startMonitoring(
            DeviceActivityName(name),
            during: schedule,
            events: events
        )
    }

    public static func stopMonitoring(_ names: [String] = []) {
        let center = DeviceActivityCenter()
        if names.isEmpty {
            center.stopMonitoring()
        } else {
            center.stopMonitoring(names.map { DeviceActivityName($0) })
        }
    }
}
#endif
