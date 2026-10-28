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
            allowedAppsSection
            goodToKnowSection
        }
        .flintForm()
        .navigationTitle("Sleep")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { vm.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in vm.refreshHealth() }
        .onDisappear { vm.applyNow() }
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
            Toggle("Sleep Mode", isOn: $vm.config.enabled)
                .tint(FlintBrand.ember)
            if vm.config.enabled {
                statusRow
            }
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        if let issue = vm.protectionIssue {
            Label("Needs attention — \(issue)", systemImage: "exclamationmark.triangle")
                .font(.footnote).foregroundStyle(FlintBrand.danger)
            Button("Retry Sleep registration") { vm.applyNow() }
                .frame(minHeight: 44)
                .tint(FlintBrand.ember)
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
            return "Wind Down uses Harder strictness from bedtime to wake-up. Allowed apps stay reachable. Turning Sleep Mode off lifts the night shield."
        case .fullAssist:
            return "Full Assist uses Hardcore strictness from bedtime to wake-up. Allowed apps stay reachable. Flint's free Emergency Pass becomes available seven days after use; in this build it ends Hardcore focus sessions, not Sleep windows. Turning Sleep Mode off lifts the night shield."
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
            return "Morning Full Assist uses Hardcore strictness for \(vm.config.morningAssistMinutes) minutes after wake-up. Allowed apps stay reachable. Overnight windows map to the following morning."
        }
    }

    private var allowedAppsSection: some View {
        Section {
            Picker("Allowed during Sleep", selection: $vm.config.allowGroupID) {
                Text("Nothing extra").tag(String?.none)
                ForEach(vm.savedGroups) { group in
                    Text("\(group.name) · \(group.itemCount)").tag(String?.some(group.id))
                }
            }
            .tint(FlintBrand.ember)
        } header: {
            Text("Allowed apps")
        } footer: {
            if vm.savedGroups.isEmpty {
                Text("Save an app group in Block Now (alarm, podcasts, meditation…) to keep it reachable overnight. Until then the shield allows nothing extra. iOS's Always Allowed apps and emergency calling are expected to remain available; this hasn't been verified on a device yet.")
            } else {
                Text("Individually-picked apps in this preset stay reachable during Wind Down and Full Assist; whole-category picks don't carry over to a night shield. iOS's Always Allowed apps and emergency calling are expected to remain available; this hasn't been verified on a device yet.")
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

/// Owns the persisted `FlintSleepConfig` and keeps the enforcement rules in sync with it.
/// Every edit saves immediately (cheap App Group write, like `FocusFilterView`); re-materializing
/// the `DeviceActivity` registrations is debounced so wheel-scrubbing a `DatePicker` doesn't
/// stop/start monitoring dozens of times, and flushed on screen exit and on the next entry.
@MainActor
final class SleepModeViewModel: ObservableObject {

    @Published var config: FlintSleepConfig {
        didSet {
            guard config != oldValue else { return }
            config.save()
            scheduleApply()
        }
    }
    @Published private(set) var savedGroups: [FlintAppGroup]
    @Published private(set) var armingReport: FlintArmingHealth.DomainReport?

    private let controller = SleepModeController()
    private var applyTask: Task<Void, Never>?

    init() {
        config = FlintSleepConfig.load()
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        controller.apply(config) // re-arm on entry; also self-heals rules deleted elsewhere
        refreshHealth()
    }

    /// Refresh the preset list (it changes in Block Now) and re-assert the rules.
    func refresh() {
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        applyNow()
    }

    /// Flush any pending debounced apply immediately (deterministic teardown on disappear).
    func applyNow() {
        applyTask?.cancel()
        applyTask = nil
        controller.apply(config)
        refreshHealth()
    }

    private func scheduleApply() {
        applyTask?.cancel()
        let snapshot = config
        applyTask = Task { [controller] in
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            controller.apply(snapshot)
            self.refreshHealth()
        }
    }

    // MARK: Derived state for the view

    var nightArmed: Bool { SleepModeController.nightWindow(for: config) != nil }
    var morningArmed: Bool { SleepModeController.morningWindow(for: config) != nil }

    var protectionIssue: String? {
        if config.enabled, config.sleepAssist != .off, let issue = config.schedule.issues.first {
            return issue.message
        }
        let names = [(nightArmed, SleepModeController.nightRuleID),
                     (morningArmed, SleepModeController.morningRuleID)]
            .filter(\.0).map { "flint.rule.\($0.1)" }
        guard !names.isEmpty else { return nil }
        let selection = savedGroups.first { $0.id == config.allowGroupID }?.selection ?? FamilyActivitySelection()
        if let issue = FlintSelectionValidation.issue(for: selection, context: .allowList) {
            return issue.message(for: .allowList)
        }
        return FlintSleepProtectionStatus.issue(activityNames: names, report: armingReport,
            authorized: AuthorizationCenter.shared.authorizationStatus == .approved)
    }

    func refreshHealth() {
        armingReport = FlintGroupStore()?.loadArmingHealth().domains["schedules"]
    }

    var nightWindowActiveNow: Bool {
        guard let window = SleepModeController.nightWindow(for: config) else { return false }
        return SleepModeController.isWindowActive(window)
    }

    var morningWindowActiveNow: Bool {
        guard let window = SleepModeController.morningWindow(for: config) else { return false }
        return SleepModeController.isWindowActive(window)
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
