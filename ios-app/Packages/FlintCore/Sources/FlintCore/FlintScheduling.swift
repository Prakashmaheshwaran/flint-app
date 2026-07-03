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

    /// Stop monitoring the named activities. An empty list is an **explicit no-op** — it must
    /// never fall through to `DeviceActivityCenter.stopMonitoring()` (the stop-everything
    /// overload), which would cancel *all* Flint activities: the active session's one-shot
    /// auto-clear, every schedule, every limit. Controllers `reload()` with `all.map {...}` at
    /// launch, so an empty rule list used to nuke unrelated monitoring. Callers that really
    /// mean "stop everything" must say so via `stopAllMonitoring()`.
    public static func stopMonitoring(_ names: [String]) {
        guard !names.isEmpty else { return }
        DeviceActivityCenter().stopMonitoring(names.map { DeviceActivityName($0) })
    }

    /// Intentionally stop **all** DeviceActivity monitoring Flint has registered — sessions,
    /// schedules, time limits, open limits. Only for deliberate tear-everything-down flows;
    /// no code path calls this today.
    public static func stopAllMonitoring() {
        DeviceActivityCenter().stopMonitoring()
    }
}
#endif
