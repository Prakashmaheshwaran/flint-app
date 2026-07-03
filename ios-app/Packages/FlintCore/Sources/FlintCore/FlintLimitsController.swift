import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Manages daily Time Limits. Each enabled limit registers an all-day `DeviceActivitySchedule`
/// carrying a `DeviceActivityEvent` whose threshold is the daily budget; the monitor shields the
/// limit's apps when the event fires and clears them at the daily boundary (the reset).
public final class FlintLimitsController {

    public init() {}

    public func limits() -> [FlintLimitRule] {
        FlintGroupStore()?.loadLimits() ?? []
    }

    public func upsert(_ limit: FlintLimitRule) {
        guard let group = FlintGroupStore() else { return }
        var all = group.loadLimits()
        if let index = all.firstIndex(where: { $0.id == limit.id }) {
            all[index] = limit
        } else {
            all.append(limit)
        }
        group.saveLimits(all)
        reload()
    }

    public func delete(_ limitID: String) {
        guard let group = FlintGroupStore() else { return }
        var all = group.loadLimits()
        all.removeAll { $0.id == limitID }
        group.saveLimits(all)
        ManagedSettingsStore(named: ManagedSettingsStore.Name("flintL.\(limitID)")).clearAllSettings()
        FlintScheduling.stopMonitoring(["flint.limit.\(limitID)"])
        reload()
    }

    public func setEnabled(_ limitID: String, _ enabled: Bool) {
        guard let group = FlintGroupStore() else { return }
        var all = group.loadLimits()
        guard let index = all.firstIndex(where: { $0.id == limitID }) else { return }
        all[index].enabled = enabled
        group.saveLimits(all)
        if !enabled {
            ManagedSettingsStore(named: ManagedSettingsStore.Name("flintL.\(limitID)")).clearAllSettings()
        }
        reload()
    }

    /// Re-register all enabled limits. Idempotent; call on launch and after edits.
    public func reload() {
        guard let group = FlintGroupStore() else { return }
        let all = group.loadLimits()
        FlintScheduling.stopMonitoring(all.map { $0.activityName })

        let center = DeviceActivityCenter()
        for limit in all where limit.enabled {
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: 0, minute: 0),
                intervalEnd: DateComponents(hour: 23, minute: 59),
                repeats: true
            )
            let event = DeviceActivityEvent(
                applications: limit.selection.applicationTokens,
                categories: limit.selection.categoryTokens,
                webDomains: limit.selection.webDomainTokens,
                threshold: DateComponents(minute: limit.thresholdMinutes)
            )
            try? center.startMonitoring(
                DeviceActivityName(limit.activityName),
                during: schedule,
                events: [DeviceActivityEvent.Name(limit.eventName): event]
            )
        }
    }
}
#endif
