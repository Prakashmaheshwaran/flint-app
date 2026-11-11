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
    @Environment(\.dismiss) private var dismiss
    @ScaledMetric(relativeTo: .body) private var daySize = 44.0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 16, weight: .semibold))
                    }
                    .buttonStyle(CircleIconButtonStyle(size: 44, filled: true))
                    .accessibilityLabel("Back")
                    Spacer()
                    Toggle("Sleep mode", isOn: $vm.config.enabled)
                        .toggleStyle(EmberToggleStyle())
                        .labelsHidden()
                        .accessibilityLabel("Sleep mode")
                }
                .padding(.top, 8)

                Text("Sleep")
                    .flintTitle(32)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 14)
                Text(summaryLine)
                    .font(FlintFont.text(14))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)

                SleepDialHeader(vm: vm)
                    .padding(.top, 18)

                nights.padding(.top, 6)
                status.padding(.top, 14)

                sectionLabel("Sleep Assist").padding(.top, 24)
                FlintSegmented(options: [(FlintSleepAssistLevel.off, "Off"),
                                         (.windDown, "Wind Down"),
                                         (.fullAssist, "Full Assist")],
                               selection: $vm.config.sleepAssist, height: 40)
                    .padding(.top, 10)
                footnote(sleepAssistFooter)

                sectionLabel("Protection tier").padding(.top, 22)
                FlintSegmented(options: [(BreakLevel.easy, "Easy"), (.harder, "Harder"), (.hardcore, "Hardcore")],
                               selection: Binding(get: {
                                   vm.config.nightBreakLevel ?? (vm.config.sleepAssist == .fullAssist ? .harder : .easy)
                               }, set: { vm.config.nightBreakLevel = $0 }))
                    .padding(.top, 10)
                footnote("The bedtime shield keeps this tier. Existing saved tiers are retained; raising a tier asks for confirmation when you save.")

                sectionLabel("Morning Assist").padding(.top, 22)
                FlintSegmented(options: [(FlintMorningAssistLevel.off, "Off"),
                                         (.slowUplift, "Slow Uplift"),
                                         (.fullAssist, "Full Assist")],
                               selection: $vm.config.morningAssist, height: 40)
                    .padding(.top, 10)
                if vm.config.morningAssist == .fullAssist {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("After wake-up").font(FlintFont.text(14, weight: .semibold))
                                .foregroundStyle(FlintBrand.bone)
                            Text("\(vm.config.morningAssistMinutes) minutes").flintSecondary(12)
                        }
                        Spacer()
                        HStack(spacing: 8) {
                            Button { vm.config.morningAssistMinutes = max(FlintSchedule.minimumWindowMinutes, vm.config.morningAssistMinutes - 15) } label: {
                                Image(systemName: "minus").font(.system(size: 15, weight: .medium))
                            }
                            .buttonStyle(CircleIconButtonStyle(size: 44))
                            .accessibilityLabel("Fifteen minutes less")
                            Button { vm.config.morningAssistMinutes = min(180, vm.config.morningAssistMinutes + 15) } label: {
                                Image(systemName: "plus").font(.system(size: 15, weight: .medium))
                            }
                            .buttonStyle(CircleIconButtonStyle(size: 44))
                            .accessibilityLabel("Fifteen minutes more")
                        }
                    }
                    .padding(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 10))
                    .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
                    .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
                    .padding(.top, 10)
                }
                footnote(morningAssistFooter)

                sectionLabel("Allowed apps").padding(.top, 22)
                allowedApps.padding(.top, 10)
                footnote(allowedAppsFooter)

                Button("Save Sleep changes") { vm.applyNow() }
                    .buttonStyle(EmberButtonStyle())
                    .padding(.top, 24)

                goodToKnow.padding(.top, 24)
                savedProtection.padding(.top, 24)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 40)
        }
        .background(SleepBackdrop())
        .flintTabBarInset()
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .onAppear { vm.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in vm.refresh() }
        .ruleMutationFlow(vm.flow)
    }

    private var summaryLine: String {
        guard vm.config.enabled else { return "Off" }
        var parts = [SleepPresentation.daysSummary(vm.config.schedule),
                     SleepPresentation.assistSummary(vm.config.sleepAssist, tier: vm.config.nightBreakLevel)]
        let allowed = vm.savedGroups.first { $0.id == vm.config.allowGroupID }?
            .selection.applicationTokens.count ?? 0
        if allowed > 0 {
            parts.append("\(allowed) \(allowed == 1 ? "app" : "apps") stay open")
        }
        return parts.joined(separator: " · ")
    }

    private func sectionLabel(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(FlintFont.text(14, weight: .semibold))
            .foregroundStyle(FlintBrand.bone)
            .padding(.horizontal, 4)
    }

    private func footnote(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .flintSecondary(13)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
            .padding(.top, 8)
    }

    private var nights: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: max(44, daySize)), spacing: 4)], spacing: 8) {
            ForEach(1...7, id: \.self) { weekday in
                let on = vm.config.schedule.daysOfWeek.contains(weekday)
                Button { vm.toggleDay(weekday) } label: {
                    Text(SleepPresentation.weekdaySymbol(weekday))
                        .font(FlintFont.text(15, weight: .bold))
                        .frame(width: max(44, daySize), height: max(44, daySize))
                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(on ? FlintBrand.ember : FlintBrand.card))
                        .foregroundStyle(on ? FlintBrand.onEmber : FlintBrand.bone)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(on ? FlintBrand.ember : FlintBrand.bone(0.12)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(SleepPresentation.weekdayName(weekday)))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .id(dynamicTypeSize)
        .disabled(vm.config.sleepAssist == .off && vm.config.morningAssist == .off)
    }

    @ViewBuilder private var status: some View {
        if let issue = vm.protectionIssue {
            // Beside the Retry button the message broke mid-word at accessibility sizes.
            let large = dynamicTypeSize.isAccessibilitySize
            (large ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10)) : AnyLayout(HStackLayout(spacing: 10))) {
                Label("Needs attention — \(issue)", systemImage: "exclamationmark.triangle")
                    .font(FlintFont.text(13)).foregroundStyle(FlintBrand.danger)
                    .fixedSize(horizontal: false, vertical: true)
                if !large { Spacer(minLength: 8) }
                Button("Retry") { vm.retryRegistration() }
                    .buttonStyle(GhostButtonStyle(height: 36))
                    .accessibilityLabel("Retry Sleep registration")
                    .accessibilityInputLabels([Text("Retry"), Text("Retry Sleep registration")])
            }
        } else if let note = vm.allowListNote {
            Label("Needs attention — \(note) Sleep keeps running as saved until you change it.",
                  systemImage: "exclamationmark.triangle")
                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.danger)
                .fixedSize(horizontal: false, vertical: true)
        } else if vm.nightWindowActiveNow {
            Label("Bedtime window is scheduled now", systemImage: "moon.zzz.fill")
                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.ember)
        } else if vm.morningWindowActiveNow {
            Label("Morning wind-up is scheduled now", systemImage: "sunrise.fill")
                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.ember)
        } else if vm.nightArmed || vm.morningArmed {
            Label("Sleep windows registered with iOS", systemImage: "moon.stars")
                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.mist)
        } else {
            Text("No Sleep or morning shield is scheduled.")
                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.mist)
        }
    }

    private var allowedApps: some View {
        Menu {
            Picker("Allowed during Sleep", selection: $vm.config.allowGroupID) {
                Text("None chosen").tag(String?.none)
                ForEach(vm.savedGroups) { group in
                    // Only individually picked apps stay reachable; categories and websites don't count.
                    let apps = group.selection.applicationTokens.count
                    Text("\(group.name) · \(apps) \(apps == 1 ? "app" : "apps")").tag(String?.some(group.id))
                }
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Still allowed").font(FlintFont.text(14, weight: .semibold))
                        .foregroundStyle(FlintBrand.bone)
                    Text(allowedGroupName)
                        .font(FlintFont.text(12))
                        .foregroundStyle(FlintBrand.mist)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                let group = vm.savedGroups.first { $0.id == vm.config.allowGroupID }
                if let group, !group.selection.flintPickedTargets.isEmpty {
                    FlintAppIconStack(targets: group.selection.flintPickedTargets, size: 26,
                                      border: FlintBrand.card, limit: 3)
                } else {
                    FlintChevron()
                }
            }
            .padding(EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(FlintBrand.card))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
        }
        .accessibilityLabel("Allowed during Sleep, \(allowedGroupName)")
    }

    private var allowedGroupName: String {
        guard let group = vm.savedGroups.first(where: { $0.id == vm.config.allowGroupID }) else {
            return vm.savedGroups.isEmpty ? "Save a group in Start focus first" : "None chosen"
        }
        return group.name
    }

    private var goodToKnow: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Good to know").flintSection().padding(.horizontal, 4)
            VStack(alignment: .leading, spacing: 8) {
                Label("Sleep windows also appear in Schedules as “Sleep Mode” and “Morning Assist”. Changes here update those windows.",
                      systemImage: "calendar.badge.clock")
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
                Label("No soundscapes, sleep stories or meditations — Flint v1 is a blocking control by design.",
                      systemImage: "speaker.slash")
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Shield enforcement needs Screen Time access on a real device — the Simulator verifies UI and scheduling logic only.")
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.stone)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(FlintBrand.card))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
        }
    }

    @ViewBuilder private var savedProtection: some View {
        if !vm.effectiveRules.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("Saved protection").flintSection().padding(.horizontal, 4)
                ForEach(vm.effectiveRules) { rule in
                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(rule.name) · \(rule.breakLevel.rawValue.capitalized)")
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                        Text("\(SleepPresentation.timeSummary(rule.schedule)) · \(rule.protection?.timeZoneID ?? TimeZone.current.identifier)")
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.mist)
                            .fixedSize(horizontal: false, vertical: true)
                        if let window = rule.currentOccurrence() {
                            Text("Current occurrence until \(window.end.formatted(date: .abbreviated, time: .shortened))")
                                .font(FlintFont.text(12))
                                .foregroundStyle(FlintBrand.stone)
                        }
                        if let protection = rule.protection, protection.queuedChange != nil {
                            QueuedRuleChangeView(protection: protection) {
                                vm.cancelQueued(rule, expected: protection)
                            }
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(FlintBrand.card))
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
                }
            }
        }
    }

    private var sleepAssistFooter: LocalizedStringKey {
        switch vm.config.sleepAssist {
        case .off:
            return "No bedtime shield. Morning Assist below can still run on its own after wake-up."
        case .windDown:
            return "Wind Down shields the apps you choose between bedtime and wake-up."
        case .fullAssist:
            return "Full Assist shields the apps you choose between bedtime and wake-up, and adds the strictness below."
        }
    }

    private var morningAssistFooter: LocalizedStringKey {
        switch vm.config.morningAssist {
        case .off:
            return "Nothing after wake-up — the night shield (if any) simply ends."
        case .slowUplift:
            return "This build saves your Slow Uplift choice but provides no reminders or shield for it. Choose Full Assist for a morning shield."
        case .fullAssist:
            return "Morning Full Assist uses the selected tier for \(vm.config.morningAssistMinutes) minutes after wake-up. New morning windows default to Harder."
        }
    }

    private var allowedAppsFooter: LocalizedStringKey {
        if vm.savedGroups.isEmpty {
            return "Sleep shields everything except the apps you choose. Save an app group in Start focus with the apps you need overnight, like Phone, Messages and your alarm, then choose it here."
        }
        if vm.allowedAppsMissing {
            return "Choose a group with at least one app to keep reachable. Flint can’t confirm which system apps stay reachable during Sleep."
        }
        return "Only individually picked apps in this group stay reachable during Wind Down and Full Assist. Flint can’t confirm which system apps stay reachable."
    }
}

/// M11's night sky above the background with a few quiet stars.
struct SleepBackdrop: View {
    var body: some View {
        ZStack(alignment: .top) {
            Color(hex: 0x08090D)
            LinearGradient(stops: [
                .init(color: Color(hex: 0x07080C), location: 0),
                .init(color: Color(hex: 0x0E1016), location: 0.5),
                .init(color: Color(hex: 0x0B0B0E), location: 1),
            ], startPoint: .top, endPoint: .bottom)
            Canvas { ctx, size in
                for (x, y, r, a) in [(40.0, 140.0, 0.9, 0.5), (330.0, 120.0, 1.0, 0.45),
                                     (360.0, 460.0, 0.8, 0.35), (24.0, 420.0, 0.7, 0.3),
                                     (290.0, 70.0, 0.7, 0.35)] {
                    ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
                             with: .color(FlintBrand.bone.opacity(a)))
                }
            }
            .frame(height: 520)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
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
