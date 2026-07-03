import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Manages recurring/scheduled block rules. Registers one repeating `DeviceActivitySchedule`
/// per enabled rule (unlimited count, no advance cap). The monitor extension applies each
/// rule's selection into the rule's own store on `intervalDidStart`, gated by day-of-week.
public final class FlintSchedulesController {

    public init() {}

    public func rules() -> [FlintScheduleRule] {
        FlintGroupStore()?.loadRules() ?? []
    }

    /// Insert or update a rule, then re-register monitoring.
    public func upsert(_ rule: FlintScheduleRule) {
        guard let group = FlintGroupStore() else { return }
        var all = group.loadRules()
        if let index = all.firstIndex(where: { $0.id == rule.id }) {
            all[index] = rule
        } else {
            all.append(rule)
        }
        group.saveRules(all)
        reload()
    }

    public func delete(_ ruleID: String) {
        guard let group = FlintGroupStore() else { return }
        var all = group.loadRules()
        all.removeAll { $0.id == ruleID }
        group.saveRules(all)
        ManagedSettingsStore(named: ManagedSettingsStore.Name("flint.\(ruleID)")).clearAllSettings()
        FlintScheduling.stopMonitoring(["flint.rule.\(ruleID)"])
        reload()
    }

    public func setEnabled(_ ruleID: String, _ enabled: Bool) {
        guard let group = FlintGroupStore() else { return }
        var all = group.loadRules()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        all[index].enabled = enabled
        group.saveRules(all)
        if !enabled {
            ManagedSettingsStore(named: ManagedSettingsStore.Name("flint.\(ruleID)")).clearAllSettings()
        }
        reload()
    }

    /// Re-register monitoring for all enabled rules. Idempotent; call on launch and after edits.
    public func reload() {
        guard let group = FlintGroupStore() else { return }
        let all = group.loadRules()
        FlintScheduling.stopMonitoring(all.map { $0.monitorName })
        for rule in all where rule.enabled {
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: rule.schedule.startHour, minute: rule.schedule.startMinute),
                intervalEnd: DateComponents(hour: rule.schedule.endHour, minute: rule.schedule.endMinute),
                repeats: true
            )
            try? FlintScheduling.startMonitoring(rule.monitorName, during: schedule)
        }
    }
}
#endif
