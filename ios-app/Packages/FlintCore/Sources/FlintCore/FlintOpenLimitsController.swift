import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import FamilyControls
import ManagedSettings
import DeviceActivity

/// Manages daily Open Limits (launch-count caps) — the host-app arming side of the
/// `FlintOpenLimitEnforcer` seam. Mirrors `FlintLimitsController` (same CRUD + `reload()` shape).
///
/// Arming is two idempotent moves, both re-done by every `reload()`:
///  1. each enabled rule's selection is shielded into the rule's own store
///     (`FlintOpenLimitEnforcer.applyShield`) — this also puts tokens released by earlier
///     grants back behind the shield;
///  2. each enabled rule registers an all-day repeating `DeviceActivitySchedule`
///     (`rule.activityName`), so the monitor extension re-applies the shield at the day
///     boundary even if the app never launches. The daily open counter itself resets via
///     `FlintOpenLimitState.dayStart` — no schedule needed for that.
public final class FlintOpenLimitsController {

    public init() {}

    public func rules() -> [FlintOpenLimitRule] {
        FlintOpenLimitRule.loadAll()
    }

    public func upsert(_ rule: FlintOpenLimitRule) {
        var all = FlintOpenLimitRule.loadAll()
        if let index = all.firstIndex(where: { $0.id == rule.id }) {
            all[index] = rule
        } else {
            all.append(rule)
        }
        FlintOpenLimitRule.saveAll(all)
        reload()
    }

    public func delete(_ ruleID: String) {
        var all = FlintOpenLimitRule.loadAll()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        let removed = all.remove(at: index)
        FlintOpenLimitRule.saveAll(all)
        FlintOpenLimitEnforcer.clearShield(for: removed)
        FlintScheduling.stopMonitoring([removed.activityName])
        reload()
    }

    public func setEnabled(_ ruleID: String, _ enabled: Bool) {
        var all = FlintOpenLimitRule.loadAll()
        guard let index = all.firstIndex(where: { $0.id == ruleID }) else { return }
        all[index].enabled = enabled
        FlintOpenLimitRule.saveAll(all)
        if !enabled {
            FlintOpenLimitEnforcer.clearShield(for: all[index])
        }
        reload()
    }

    /// Re-arm every enabled rule: shield its selection and (re)register its day-boundary
    /// activity. Idempotent; call on launch and after edits.
    public func reload() {
        let all = FlintOpenLimitRule.loadAll()
        // `FlintScheduling.stopMonitoring([])` is a documented no-op (stop-everything now takes
        // the explicit `stopAllMonitoring()`), but keep the local guard as belt-and-braces —
        // this call site must never even flirt with the framework's stop-all overload.
        let names = all.map { $0.activityName }
        if !names.isEmpty {
            FlintScheduling.stopMonitoring(names)
        }
        for rule in all where rule.enabled {
            FlintOpenLimitEnforcer.applyShield(for: rule)
            let schedule = DeviceActivitySchedule(
                intervalStart: DateComponents(hour: 0, minute: 0),
                intervalEnd: DateComponents(hour: 23, minute: 59),
                repeats: true
            )
            try? FlintScheduling.startMonitoring(rule.activityName, during: schedule)
        }
    }
}
#endif
