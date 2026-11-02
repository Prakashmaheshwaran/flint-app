import SwiftUI
import FamilyControls
import FlintCore

/// Sleep Mode + Morning Assist — the bedtime/wake downtime vertical (I-SLEEP), blocking-only per
/// the v1 spec: configure the bedtime → wake window, which nights it runs, Sleep Assist strictness
/// (Off / Wind Down / Full Assist) and Morning Assist (Off / Slow Uplift / Full Assist with a
/// configurable wind-up), plus which saved preset stays reachable. Everything is free, including
/// Full Assist. Edits persist `FlintSleepConfig` to the shared App Group and re-materialize the
/// enforcement rules via `SleepModeController`.
///
/// This is the feature's public entry point: a pushable `View` (no own `NavigationStack`), like
/// `FocusFilterView`. The Wave-2 integrator links it into navigation — do not wire tabs here.
struct SleepModeView: View {
    @StateObject private var vm = SleepModeViewModel()
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var daySize = 44.0

    var body: some View {
        Form {
            SleepDialHeader(vm: vm)
            introSection
            masterSection
            nightsSection
            sleepAssistSection
            morningAssistSection
            tierSection
            allowedAppsSection
            Section("Saved protection") {
                ForEach(vm.effectiveRules) { rule in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(rule.name) · \(rule.breakLevel.rawValue.capitalized)")
                        Text("\(SleepPresentation.timeSummary(rule.schedule)) · \(rule.protection?.timeZoneID ?? TimeZone.current.identifier)")
                            .font(.footnote)
                        if let window = rule.currentOccurrence() {
                            Text("Current occurrence until \(window.end.formatted(date: .abbreviated, time: .shortened))")
                                .font(.footnote)
                        }
                        if let protection = rule.protection, protection.queuedChange != nil {
                            QueuedRuleChangeView(protection: protection) {
                                vm.cancelQueued(rule, expected: protection)
                            }
                        }
                    }
                }
                Button("Save Sleep changes") { vm.applyNow() }.frame(minHeight: 44)
            }
            goodToKnowSection
        }
        .flintForm()
        .navigationTitle("Sleep")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { vm.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in vm.refresh() }
        .ruleMutationFlow(vm.flow)
    }

    // MARK: Sections

    private var introSection: some View {
        Section {
            Text("Wind down at bedtime and wake up without the feed. Sleep Assist can shield apps between bedtime and wake-up. Morning Full Assist adds a shield while your day starts. Free, including Full Assist.")
                .font(.footnote)
                .foregroundStyle(FlintBrand.mist)
        }
    }

    private var masterSection: some View {
        Section {
            Toggle("Enable Sleep template", isOn: $vm.config.enabled)
                .tint(FlintBrand.ember)
            Text("These controls are a draft. Save Sleep changes to apply them; saved protection is shown below.")
                .font(.footnote).foregroundStyle(FlintBrand.mist)
            statusRow
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        if let issue = vm.protectionIssue {
            Label("Needs attention — \(issue)", systemImage: "exclamationmark.triangle")
                .font(.footnote).foregroundStyle(FlintBrand.danger)
            Button("Retry Sleep registration") { vm.retryRegistration() }
                .frame(minHeight: 44)
                .tint(FlintBrand.ember)
        } else if let note = vm.allowListNote {
            // Still armed as saved; only the allowed apps need choosing.
            Label("Needs attention — \(note) Sleep keeps running as saved until you change it.",
                  systemImage: "exclamationmark.triangle")
                .font(.footnote).foregroundStyle(FlintBrand.danger)
        } else if vm.nightWindowActiveNow {
            Label("Bedtime window is scheduled now", systemImage: "moon.zzz.fill")
                .font(.footnote)
                .foregroundStyle(FlintBrand.ember)
        } else if vm.morningWindowActiveNow {
            Label("Morning wind-up is scheduled now", systemImage: "sunrise.fill")
                .font(.footnote)
                .foregroundStyle(FlintBrand.ember)
        } else if vm.nightArmed || vm.morningArmed {
            Label("Sleep windows registered with iOS", systemImage: "moon.stars")
                .font(.footnote)
                .foregroundStyle(FlintBrand.mist)
        } else {
            Text("No Sleep or morning shield is scheduled.")
                .font(.footnote)
                .foregroundStyle(FlintBrand.mist)
        }
    }

    private var nightsSection: some View {
        Section {
            DatePicker("Bedtime",
                       selection: Binding(get: { vm.bedtime }, set: { vm.bedtime = $0 }),
                       displayedComponents: .hourAndMinute)
            DatePicker("Wake up",
                       selection: Binding(get: { vm.wake }, set: { vm.wake = $0 }),
                       displayedComponents: .hourAndMinute)
            dayCircles
                // Recreate lazy cells when their scaled target size changes in an open Form.
                .id(dynamicTypeSize)
        } header: {
            Text("Nights")
        } footer: {
            Text(nightsFooter)
        }
    }

    private var dayCircles: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: max(44, daySize)), spacing: 4)], spacing: 8) {
            ForEach(1...7, id: \.self) { weekday in
                let on = vm.config.schedule.daysOfWeek.contains(weekday)
                Button {
                    vm.toggleDay(weekday)
                } label: {
                    Text(SleepPresentation.weekdaySymbol(weekday))
                        .font(FlintFont.text(16, weight: on ? .semibold : .regular))
                        .frame(width: max(44, daySize), height: max(44, daySize))
                        .background(on ? FlintBrand.ember : FlintBrand.bone(0.06))
                        .foregroundStyle(on ? FlintBrand.onEmber : FlintBrand.bone)
                        .clipShape(Circle())
                        .overlay(Circle().strokeBorder(on ? FlintBrand.onEmber : FlintBrand.mist, lineWidth: on ? 3 : 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(SleepPresentation.weekdayName(weekday)))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    private var nightsFooter: LocalizedStringKey {
        guard vm.windowIsUsable else {
            return "Bedtime and wake-up are too close — the window needs at least \(FlintSchedule.minimumWindowMinutes) minutes, so the bedtime shield won't arm until it's longer."
        }
        return "\(SleepPresentation.daysSummary(vm.config.schedule)) · \(SleepPresentation.timeSummary(vm.config.schedule)). Days pick the night the window starts; none selected means every night."
    }

    private var sleepAssistSection: some View {
        Section {
            if dynamicTypeSize.isAccessibilitySize {
                sleepAssistPicker.pickerStyle(.inline)
            } else {
                sleepAssistPicker.pickerStyle(.segmented)
            }
        } header: {
            Text("Sleep Assist")
        } footer: {
            Text(sleepAssistFooter)
        }
    }

    private var sleepAssistPicker: some View {
        Picker("Sleep Assist", selection: $vm.config.sleepAssist) {
            Text("Off").tag(FlintSleepAssistLevel.off)
            Text("Wind Down").tag(FlintSleepAssistLevel.windDown)
            Text("Full Assist").tag(FlintSleepAssistLevel.fullAssist)
        }
    }

    private var sleepAssistFooter: LocalizedStringKey {
        switch vm.config.sleepAssist {
        case .off:
            return "No bedtime shield. Morning Assist below can still run on its own after wake-up."
        case .windDown:
            return "New Wind Down starts at Easy. Choose a tier below. Active protection changes only after you save and complete its confirmation."
        case .fullAssist:
            return "New Full Assist starts at Harder. Hardcore is an explicit choice below. Weakening an active Hardcore window can be saved for after this occurrence."
        }
    }

    private var tierSection: some View {
        Section("Protection tier") {
            Picker("Bedtime tier", selection: Binding(get: {
                vm.config.nightBreakLevel ?? (vm.config.sleepAssist == .fullAssist ? .harder : .easy)
            }, set: { vm.config.nightBreakLevel = $0 })) {
                ForEach(BreakLevel.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
            }
            Picker("Morning tier", selection: Binding(get: { vm.config.morningBreakLevel ?? .harder },
                set: { vm.config.morningBreakLevel = $0 })) {
                ForEach(BreakLevel.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
            }
            Text("Existing saved tiers are retained. Raising a tier requires confirmation when you save.").font(.footnote)
        }
    }

    private var morningAssistSection: some View {
        Section {
            if dynamicTypeSize.isAccessibilitySize {
                morningAssistPicker.pickerStyle(.inline)
            } else {
                morningAssistPicker.pickerStyle(.segmented)
            }
            if vm.config.morningAssist == .fullAssist {
                Stepper(
                    "\(vm.config.morningAssistMinutes) minutes after wake-up",
                    value: $vm.config.morningAssistMinutes,
                    in: FlintSchedule.minimumWindowMinutes...180,
                    step: 15
                )
            }
        } header: {
            Text("Morning Assist")
        } footer: {
            Text(morningAssistFooter)
        }
    }

    private var morningAssistPicker: some View {
        Picker("Morning Assist", selection: $vm.config.morningAssist) {
            Text("Off").tag(FlintMorningAssistLevel.off)
            Text("Slow Uplift").tag(FlintMorningAssistLevel.slowUplift)
            Text("Full Assist").tag(FlintMorningAssistLevel.fullAssist)
        }
    }

    private var morningAssistFooter: LocalizedStringKey {
        switch vm.config.morningAssist {
        case .off:
            return "Nothing after wake-up — the night shield (if any) simply ends."
        case .slowUplift:
            return "This build saves your Slow Uplift choice but provides no reminders or shield for it. Choose Full Assist for a morning shield."
        case .fullAssist:
            return "Morning Full Assist uses the selected tier for \(vm.config.morningAssistMinutes) minutes after wake-up. New morning windows default to Harder. Overnight windows map to the following morning."
        }
    }

    private var allowedAppsSection: some View {
        Section {
            Picker("Allowed during Sleep", selection: $vm.config.allowGroupID) {
                Text("None chosen").tag(String?.none)
                ForEach(vm.savedGroups) { group in
                    // Only individually picked apps stay reachable; categories and websites don't count.
                    let apps = group.selection.applicationTokens.count
                    Text("\(group.name) · \(apps) \(apps == 1 ? "app" : "apps")").tag(String?.some(group.id))
                }
            }
            .tint(FlintBrand.ember)
        } header: {
            Text("Allowed apps")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if vm.savedGroups.isEmpty {
                    Text("Sleep shields everything except the apps you choose. Save an app group in Block Now with the apps you need overnight, like Phone, Messages and your alarm, then choose it here.")
                } else {
                    Text("Only individually picked apps in this group stay reachable during Wind Down and Full Assist. Whole-category and website picks don't carry over, so a group needs at least one app. Include the ones you need, like Phone and Messages.")
                }
                Text("Flint can't confirm which system apps stay reachable during Sleep.")
                if vm.allowedAppsMissing {
                    Text(FlintSleepRulesController.allowedAppsRequired).foregroundStyle(FlintBrand.danger)
                }
            }
        }
    }

    private var goodToKnowSection: some View {
        Section {
            Label("Sleep windows also appear in Schedules as “Sleep Mode” and “Morning Assist”. Changes here update those windows.",
                  systemImage: "calendar.badge.clock")
                .font(.footnote)
            Label("No soundscapes, sleep stories or meditations — Flint v1 is a blocking control by design.",
                  systemImage: "speaker.slash")
                .font(.footnote)
        } header: {
            Text("Good to know")
        } footer: {
            Text("Shield enforcement needs Screen Time access on a real device — the Simulator verifies UI and scheduling logic only.")
        }
    }

}

/// Keeps a Sleep draft separate from effective saved rules. Native changes happen only after
/// an explicit save and the shared rule gate; current status always comes from saved snapshots.
@MainActor
final class SleepModeViewModel: ObservableObject {

    @Published var config: FlintSleepConfig
    @Published private(set) var savedGroups: [FlintAppGroup]
    @Published private(set) var armingReport: FlintArmingHealth.DomainReport?
    @Published private(set) var effectiveRules: [FlintScheduleRule] = []
    let flow = RuleMutationFlow()

    private let controller = SleepModeController()

    /// The tiers last taken from the saved rules; a tier picked here since then is kept.
    private var adoptedTiers: FlintSleepRulesController.TierAdoption?

    init() {
        config = FlintSleepConfig.load()
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        adoptedTiers = nil
        refreshHealth()
        adoptTiersInForce(force: true)
    }

    /// Refresh the preset list and saved protection. The draft only takes tiers now in force
    /// that the user hasn't changed during this visit; rules are never written.
    func refresh() {
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        refreshHealth()
        adoptTiersInForce(force: false)
    }

    /// R-27: show and save the tier in force, including one changed in Rules, so a Sleep save
    /// never reverts it. A tier the user picked during this visit is left alone.
    private func adoptTiersInForce(force: Bool) {
        let result = FlintSleepRulesController.adoptingTiersInForce(config, rules: effectiveRules,
            keepingPicksSince: force ? nil : adoptedTiers)
        config = result.config
        adoptedTiers = result.adopted
    }

    /// Keep the draft until the user accepts the arbiter's confirmation/wait/deferral.
    func applyNow() {
        let snapshot = config
        flow.run(scope: "Sleep and Morning Assist", operation: { [controller] in controller.apply(snapshot, confirmation: $0) },
            cancel: { [controller] in controller.cancel(confirmation: $0) }, onSaved: {
                self.config = FlintSleepConfig.load(); self.refreshHealth(); self.adoptTiersInForce(force: true)
            })
    }

    func cancelQueued(_ rule: FlintScheduleRule, expected: FlintRuleProtection) {
        flow.run(scope: rule.name, operation: { _ in
            let result = FlintSchedulesController().cancelQueuedChange(rule.id, expected: expected)
            self.refresh()
            return result
        })
    }

    // MARK: Derived state for the view

    var nightArmed: Bool { effectiveRules.contains { $0.id == SleepModeController.nightRuleID && $0.enabled } }
    var morningArmed: Bool { effectiveRules.contains { $0.id == SleepModeController.morningRuleID && $0.enabled } }

    /// The draft would save a Sleep or Morning window whose chosen group keeps no app (R-26);
    /// the controller refuses that save, so say so before the user tries.
    var allowedAppsMissing: Bool {
        guard config.enabled, config.sleepAssist != .off
                || (config.morningAssist == .fullAssist && config.schedule.hasValidEndpoints) else { return false }
        let group = savedGroups.first { $0.id == config.allowGroupID }
        return (group?.selection.applicationTokens.count ?? 0) == 0
    }

    /// A Sleep rule saved by an earlier build with no allowed app keeps running (§12), but the
    /// user should choose the apps to keep; the next Sleep save that keeps a window requires it (ADR-012 §7).
    var allowListNote: String? {
        effectiveRules.contains {
            $0.enabled && FlintSelectionValidation.needsAllowedApps(
                applications: $0.selection.applicationTokens.count, allowList: $0.allowListMode)
        } ? FlintSelectionIssue.emptyAllowList.message : nil
    }

    var protectionIssue: String? {
        if let unavailable = armingReport?.failures.first(where: { $0.activityName == "schedules" }) { return unavailable.reason }
        let enabled = effectiveRules.filter(\.enabled)
        let names = enabled.map(\.monitorName)
        guard !names.isEmpty else { return nil }
        for rule in enabled {
            if let issue = FlintSelectionValidation.armIssue(for: rule.selection, context: .allowList) {
                return issue.message(for: .allowList)
            }
        }
        return FlintSleepProtectionStatus.issue(activityNames: names, report: armingReport,
            authorized: AuthorizationCenter.shared.authorizationStatus == .approved)
    }

    func refreshHealth() {
        effectiveRules = FlintSchedulesController().rules().filter {
            $0.id == SleepModeController.nightRuleID || $0.id == SleepModeController.morningRuleID
        }
        armingReport = FlintGroupStore()?.loadArmingHealth().domains["schedules"]
    }

    func retryRegistration() {
        _ = FlintSchedulesController().reload()
        refreshHealth()
    }

    var nightWindowActiveNow: Bool {
        effectiveRules.first { $0.id == SleepModeController.nightRuleID }?.currentOccurrence() != nil
    }

    var morningWindowActiveNow: Bool {
        effectiveRules.first { $0.id == SleepModeController.morningRuleID }?.currentOccurrence() != nil
    }

    var windowIsUsable: Bool {
        config.schedule.windowMinutes >= FlintSchedule.minimumWindowMinutes
    }

    // MARK: Date bridging for the hour/minute pickers

    var bedtime: Date {
        get { Self.date(hour: config.schedule.startHour, minute: config.schedule.startMinute) }
        set {
            let calendar = Calendar.current
            var schedule = config.schedule
            schedule.startHour = calendar.component(.hour, from: newValue)
            schedule.startMinute = calendar.component(.minute, from: newValue)
            config.schedule = schedule // single mutation -> one save/apply
        }
    }

    var wake: Date {
        get { Self.date(hour: config.schedule.endHour, minute: config.schedule.endMinute) }
        set {
            let calendar = Calendar.current
            var schedule = config.schedule
            schedule.endHour = calendar.component(.hour, from: newValue)
            schedule.endMinute = calendar.component(.minute, from: newValue)
            config.schedule = schedule
        }
    }

    func toggleDay(_ weekday: Int) {
        var schedule = config.schedule
        if schedule.daysOfWeek.contains(weekday) {
            schedule.daysOfWeek.remove(weekday)
        } else {
            schedule.daysOfWeek.insert(weekday)
        }
        config.schedule = schedule
    }

    private static func date(hour: Int, minute: Int) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }
}

/// Pure presentation seam: a usable window expresses intent, not successful arming.
enum FlintSleepProtectionStatus {
    static func issue(activityNames: [String], report: FlintArmingHealth.DomainReport?,
                      authorized: Bool, now: Date = Date()) -> String? {
        guard !activityNames.isEmpty else { return nil }
        guard authorized else { return String(localized: "Screen Time access is not allowed.") }
        guard let report, report.updatedAt <= now,
              now.timeIntervalSince(report.updatedAt) <= 24 * 3600 else {
            return String(localized: "Sleep registration has not been checked recently.")
        }
        if let failure = report.failures.first(where: { activityNames.contains($0.activityName) }) {
            return failure.reason
        }
        guard report.enabled >= activityNames.count, report.armed >= activityNames.count else {
            return String(localized: "Sleep registration has not been verified.")
        }
        guard let observed = report.observedActivities,
              activityNames.allSatisfy({ observed.contains($0) }) else {
            return "Sleep registration has not been observed in iOS."
        }
        return nil
    }
}
