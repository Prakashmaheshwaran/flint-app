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

    var body: some View {
        Form {
            introSection
            masterSection
            nightsSection
            sleepAssistSection
            morningAssistSection
            allowedAppsSection
            goodToKnowSection
        }
        .navigationTitle("Sleep Mode")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { vm.refresh() }
        .onDisappear { vm.applyNow() }
    }

    // MARK: Sections

    private var introSection: some View {
        Section {
            Text("Wind down at bedtime and wake up without the feed. Between bedtime and wake-up, "
                 + "Sleep Mode shields everything except the apps you allow — and Morning Assist "
                 + "keeps that shield up while your day starts. Free, including Full Assist.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private var masterSection: some View {
        Section {
            Toggle("Sleep Mode", isOn: $vm.config.enabled)
                .tint(FlintBrand.spark)
            if vm.config.enabled {
                statusRow
            }
        }
    }

    @ViewBuilder
    private var statusRow: some View {
        if vm.nightWindowActiveNow {
            Label("Bedtime window is on right now", systemImage: "moon.zzz.fill")
                .font(.footnote)
                .foregroundStyle(FlintBrand.spark)
        } else if vm.morningWindowActiveNow {
            Label("Morning wind-up is on right now", systemImage: "sunrise.fill")
                .font(.footnote)
                .foregroundStyle(FlintBrand.spark)
        } else if vm.nightArmed || vm.morningArmed {
            Label("Armed — the next window starts on schedule", systemImage: "moon.stars")
                .font(.footnote)
                .foregroundStyle(.secondary)
        } else {
            Text("Both assists are Off — no window will be shielded.")
                .font(.footnote)
                .foregroundStyle(.secondary)
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
        } header: {
            Text("Nights")
        } footer: {
            Text(nightsFooter)
        }
    }

    private var dayCircles: some View {
        HStack {
            ForEach(1...7, id: \.self) { weekday in
                let on = vm.config.schedule.daysOfWeek.contains(weekday)
                Button {
                    vm.toggleDay(weekday)
                } label: {
                    Text(Self.dayLetters[weekday - 1])
                        .frame(width: 34, height: 34)
                        .background(on ? FlintBrand.spark : Color.gray.opacity(0.15))
                        .foregroundStyle(on ? FlintBrand.onAccent : Color.primary)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var nightsFooter: String {
        guard vm.windowIsUsable else {
            return "Bedtime and wake-up are too close — the window needs at least "
                + "\(SleepModeController.minimumWindowMinutes) minutes, so the bedtime shield "
                + "won't arm until it's longer."
        }
        return "\(daysSummary(vm.config.schedule)) · \(timeSummary(vm.config.schedule)). "
            + "Days pick the night the window starts; none selected means every night."
    }

    private var sleepAssistSection: some View {
        Section {
            Picker("Sleep Assist", selection: $vm.config.sleepAssist) {
                Text("Off").tag(FlintSleepAssistLevel.off)
                Text("Wind Down").tag(FlintSleepAssistLevel.windDown)
                Text("Full Assist").tag(FlintSleepAssistLevel.fullAssist)
            }
            .pickerStyle(.segmented)
        } header: {
            Text("Sleep Assist")
        } footer: {
            Text(sleepAssistFooter)
        }
    }

    private var sleepAssistFooter: String {
        switch vm.config.sleepAssist {
        case .off:
            return "No bedtime shield. Morning Assist below can still run on its own after wake-up."
        case .windDown:
            return "From bedtime to wake-up, everything except your Allowed apps is shielded at "
                + "Harder strictness — a strong nudge, and you stay in control."
        case .fullAssist:
            return "From bedtime to wake-up, everything except your Allowed apps is shielded, "
                + "registered at Hardcore strictness. Flint's free weekly Emergency Pass is the "
                + "escape hatch for Hardcore blocks; in this build, turning Sleep Mode off also "
                + "lifts the night shield."
        }
    }

    private var morningAssistSection: some View {
        Section {
            Picker("Morning Assist", selection: $vm.config.morningAssist) {
                Text("Off").tag(FlintMorningAssistLevel.off)
                Text("Slow Uplift").tag(FlintMorningAssistLevel.slowUplift)
                Text("Full Assist").tag(FlintMorningAssistLevel.fullAssist)
            }
            .pickerStyle(.segmented)
            if vm.config.morningAssist == .fullAssist {
                Stepper(
                    "\(vm.config.morningAssistMinutes) minutes after wake-up",
                    value: $vm.config.morningAssistMinutes,
                    in: SleepModeController.minimumWindowMinutes...180,
                    step: 15
                )
            }
        } header: {
            Text("Morning Assist")
        } footer: {
            Text(morningAssistFooter)
        }
    }

    private var morningAssistFooter: String {
        switch vm.config.morningAssist {
        case .off:
            return "Nothing after wake-up — the night shield (if any) simply ends."
        case .slowUplift:
            return "Slow Uplift is a reminder-style nudge, not a block. This blocking-only build "
                + "stores your choice but arms no shield for it — choose Full Assist for an "
                + "enforced morning window."
        case .fullAssist:
            return "Everything except your Allowed apps stays shielded for "
                + "\(vm.config.morningAssistMinutes) minutes after wake-up. Nights map to their "
                + "mornings — Sun–Thu nights guard Mon–Fri mornings."
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
            .tint(FlintBrand.spark)
        } header: {
            Text("Allowed apps")
        } footer: {
            Text(vm.savedGroups.isEmpty
                 ? "Save an app group in Block Now (alarm, podcasts, meditation…) to keep it "
                   + "reachable overnight. Until then the shield allows nothing extra — the "
                   + "system's own Always Allowed apps (like Phone) stay available regardless."
                 : "Individually-picked apps in this preset stay reachable during Wind Down and "
                   + "Full Assist; whole-category picks don't carry over to a night shield. The "
                   + "system's Always Allowed apps stay available regardless.")
        }
    }

    private var goodToKnowSection: some View {
        Section {
            Label("Sleep Mode runs on Flint's Schedules engine — its windows appear in Schedules "
                  + "as \u{201C}Sleep Mode\u{201D} and \u{201C}Morning Assist\u{201D} and re-arm "
                  + "whenever you edit here.",
                  systemImage: "calendar.badge.clock")
                .font(.footnote)
            Label("No soundscapes, sleep stories or meditations — Flint v1 is a blocking control "
                  + "by design.",
                  systemImage: "speaker.slash")
                .font(.footnote)
        } header: {
            Text("Good to know")
        } footer: {
            Text("Shield enforcement needs Screen Time access on a real device — the Simulator "
                 + "verifies UI and scheduling logic only.")
        }
    }

    // MARK: Helpers

    private static let dayLetters = ["S", "M", "T", "W", "T", "F", "S"] // index 0 -> weekday 1 (Sun)

    private func daysSummary(_ schedule: FlintSchedule) -> String {
        if schedule.daysOfWeek.isEmpty { return "Every night" }
        let names = ["", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        return schedule.daysOfWeek.sorted().map { names[$0] }.joined(separator: " ") + " nights"
    }

    private func timeSummary(_ schedule: FlintSchedule) -> String {
        let start = String(format: "%02d:%02d", schedule.startHour, schedule.startMinute)
        let end = String(format: "%02d:%02d", schedule.endHour, schedule.endMinute)
        let startMinutes = schedule.startHour * 60 + schedule.startMinute
        let endMinutes = schedule.endHour * 60 + schedule.endMinute
        return startMinutes >= endMinutes ? "\(start) → \(end) next morning" : "\(start) → \(end)"
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

    private let controller = SleepModeController()
    private var applyTask: Task<Void, Never>?

    init() {
        config = FlintSleepConfig.load()
        savedGroups = FlintGroupStore()?.loadAppGroups() ?? []
        controller.apply(config) // re-arm on entry; also self-heals rules deleted elsewhere
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
    }

    private func scheduleApply() {
        applyTask?.cancel()
        let snapshot = config
        applyTask = Task { [controller] in
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            controller.apply(snapshot)
        }
    }

    // MARK: Derived state for the view

    var nightArmed: Bool { SleepModeController.nightWindow(for: config) != nil }
    var morningArmed: Bool { SleepModeController.morningWindow(for: config) != nil }

    var nightWindowActiveNow: Bool {
        guard let window = SleepModeController.nightWindow(for: config) else { return false }
        return SleepModeController.isWindowActive(window)
    }

    var morningWindowActiveNow: Bool {
        guard let window = SleepModeController.morningWindow(for: config) else { return false }
        return SleepModeController.isWindowActive(window)
    }

    var windowIsUsable: Bool {
        SleepModeController.windowMinutes(config.schedule) >= SleepModeController.minimumWindowMinutes
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
