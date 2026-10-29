import SwiftUI
import FamilyControls
import FlintCore

@MainActor
final class SchedulesViewModel: ObservableObject {
    @Published private(set) var rules: [FlintScheduleRule] = []
    /// Enabled rules iOS would not register — shown, never swallowed. See `armFailuresSection`.
    @Published private(set) var armFailures: [FlintScheduleArmFailure] = []
    @Published private(set) var health = FlintArmingHealth()
    @Published private(set) var observation: FlintProtectionObservation?
    private let controller = FlintSchedulesController()

    init() {
        refresh()
    }

    func refresh(armFailures: [FlintScheduleArmFailure]? = nil) {
        rules = controller.rules()
        health = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
        observation = FlintProtectionObservation.load()
        if let armFailures {
            self.armFailures = armFailures
        } else {
            self.armFailures = rules.compactMap { rule in
                guard rule.enabled,
                      health.domains["schedules"]?.failures.contains(where: { $0.activityName == rule.monitorName }) == true
                else { return nil }
                return FlintScheduleArmFailure(ruleID: rule.id, ruleName: rule.name, issue: rule.schedule.issues.first,
                    selectionIssue: FlintSelectionValidation.issue(for: rule.selection,
                        context: rule.allowListMode ? .allowList : .blockList),
                    selectionContext: rule.allowListMode ? .allowList : .blockList,
                    registrationReason: health.domains["schedules"]?.failures.first(where: {
                        $0.activityName == rule.monitorName
                    })?.reason)
            }
        }
    }

    func save(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil, creating: Bool = false) -> FlintRuleMutationResult {
        let result = controller.upsert(rule, confirmation: confirmation, creating: creating); refresh(); return result
    }
    func delete(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.delete(rule.id, revision: rule.protection?.revision, confirmation: confirmation); refresh(); return result
    }
    func toggle(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.setEnabled(rule.id, !rule.enabled, revision: rule.protection?.revision,
            confirmation: confirmation); refresh(); return result
    }
    func cancel(_ id: String, confirmation: FlintRuleConfirmation?) { controller.cancelPendingChange(id, confirmation: confirmation) }
}

/// The Rules tab: today's timeline, every schedule with its switch, templates, and the way
/// into Sleep and App limits.
struct RulesView: View {
    @StateObject private var vm = SchedulesViewModel()
    @StateObject private var flow = RuleMutationFlow()
    @State private var showAdd = false
    @State private var editing: FlintScheduleRule?
    @State private var startingTemplate: FlintRoutinePreset?
    @State private var now = Date()
    @State private var sleep = FlintSleepConfig.load()
    @State private var limitCount = 0
    @State private var openLimitCount = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text("Rules").flintTitle(30)
                        Spacer()
                        Button { showAdd = true } label: {
                            Image(systemName: "plus").font(.system(size: 17))
                        }
                        .buttonStyle(CircleIconButtonStyle(size: 44))
                        .accessibilityLabel("New schedule")
                    }
                    .padding(.top, 14)

                    TodayTimeline(rules: vm.rules, now: now, health: vm.health,
                                  observation: vm.observation).padding(.top, 20)

                    if !vm.armFailures.isEmpty { armFailures.padding(.top, 22) }

                    Text("Schedules").flintEyebrow().padding(.top, 22)
                    if vm.health.domains["schedules"]?.failures.contains(where: { $0.activityName == "schedules" }) == true {
                        Text("Saved schedules are unavailable. Existing protection was preserved.").foregroundStyle(FlintBrand.danger)
                    }
                    if vm.rules.isEmpty {
                        Text("No schedules yet. Recurring blocks for work hours, study or bedtime — start from a template below or tap +.")
                            .flintSecondary(14)
                            .lineSpacing(3)
                            .padding(.vertical, 14)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(vm.rules) { rule in row(rule) }
                        }
                        .padding(.top, 6)
                    }

                    Text("Also").flintEyebrow().padding(.top, 20)
                    let alsoLayout = typeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(spacing: 10))
                        : AnyLayout(HStackLayout(spacing: 10))
                    alsoLayout {
                        NavigationLink { SleepModeView() } label: {
                            alsoCard("Sleep", sleep.enabled
                                     ? "\(FlintTodaySchedule.clock(sleep.schedule.startHour, sleep.schedule.startMinute)) — \(FlintTodaySchedule.clock(sleep.schedule.endHour, sleep.schedule.endMinute))"
                                     : "Off")
                        }
                        NavigationLink { LimitsView() } label: {
                            alsoCard("App limits", limitsSummary)
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 10)

                    Text("Start from a template").flintEyebrow().padding(.top, 26)
                    VStack(spacing: 0) {
                        ForEach(FlintRoutinePreset.library) { preset in
                            Button { startingTemplate = preset } label: {
                                FlintRow(title: preset.name, subtitle: preset.description, chevron: true)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.top, 6)
                }
                .padding(.horizontal, 24)
            }
            .background(FlintGroundBackground())
            .flintTabBarInset()
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $showAdd) {
                ScheduleEditor(rule: nil, onSave: { vm.save($0, confirmation: $1, creating: true) })
            }
            .sheet(item: $editing) { rule in
                ScheduleEditor(rule: rule, onSave: { vm.save($0, confirmation: $1) },
                    onDelete: { vm.delete(rule, confirmation: $0) })
            }
            .sheet(item: $startingTemplate) { preset in
                ScheduleEditor(rule: preset.draftRule(), asNew: true, onSave: { vm.save($0, confirmation: $1, creating: true) })
            }
            .onAppear {
                vm.refresh()
                now = Date()
                sleep = FlintSleepConfig.load()
                limitCount = FlintLimitsController().limits().count
                openLimitCount = FlintOpenLimitsController().rules().count
            }
            .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in
                vm.refresh()
                now = Date()
            }
            .ruleMutationFlow(flow)
        }
    }

    private var limitsSummary: String {
        if limitCount == 0 && openLimitCount == 0 { return "None yet" }
        var parts: [String] = []
        if limitCount > 0 { parts.append("\(limitCount) time \(limitCount == 1 ? "limit" : "limits")") }
        if openLimitCount > 0 { parts.append("\(openLimitCount) open \(openLimitCount == 1 ? "limit" : "limits")") }
        return parts.joined(separator: " · ")
    }

    private func alsoCard(_ title: String, _ detail: String) -> some View {
        FlintCard {
            Text(title).flintRowTitle()
            Text(detail).font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist).monospacedDigit()
        }
    }

    /// An enabled rule whose window iOS refuses is a shield the user believes in and doesn't have.
    /// Say so out loud, above the toggle that claims otherwise, and open the editor on a tap.
    private var armFailures: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Needs attention", systemImage: "exclamationmark.triangle")
                .font(FlintFont.text(13, weight: .medium))
                .foregroundStyle(FlintBrand.ember)
            ForEach(vm.armFailures, id: \.ruleID) { failure in
                Button {
                    editing = vm.rules.first { $0.id == failure.ruleID }
                } label: {
                    FlintRow(title: failure.ruleName, subtitle: failure.message, chevron: true)
                }
                .buttonStyle(.plain)
            }
            Text("These schedules are switched on but their last arming attempt failed. Tap one to fix its window or its app/site selection.")
                .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
        }
    }

    private func row(_ rule: FlintScheduleRule) -> some View {
        HStack(spacing: 14) {
            Button { editing = rule } label: {
                VStack(alignment: .leading, spacing: 3) {
                    let labelLayout = typeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
                        : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 10))
                    labelLayout {
                        Text(rule.name)
                            .font(FlintFont.text(15, weight: .medium))
                            .foregroundStyle(rule.enabled ? FlintBrand.bone : FlintBrand.faint)
                        Text(timeRange(rule.schedule))
                            .font(FlintFont.display(14))
                            .foregroundStyle(FlintBrand.mist)
                            .monospacedDigit()
                    }
                    Text(meta(rule)).font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                    if let queued = rule.protection?.queuedChange {
                        Text("\(queued.summary) · after \(queued.window.end.formatted(date: .abbreviated, time: .shortened))")
                            .font(.caption).foregroundStyle(FlintBrand.ember)
                    }
                    if rule.protection?.requiresTierReview == true {
                        Text("Review migrated tier before the next occurrence").font(.caption)
                    }
                    let status = FlintRuleProtectionStatus.status(enabled: rule.enabled,
                        activityName: rule.monitorName, domain: "schedules", health: vm.health,
                        observation: vm.observation,
                        authorized: AuthorizationCenter.shared.authorizationStatus == .approved)
                    if rule.enabled {
                        Text(status.label).font(FlintFont.text(12))
                            .foregroundStyle(status == .needsAttention ? FlintBrand.danger : FlintBrand.stone)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Toggle(rule.name, isOn: Binding(get: { rule.enabled }, set: { _ in
                flow.run(scope: rule.name, operation: { vm.toggle(rule, confirmation: $0) }, cancel: { vm.cancel(rule.id, confirmation: $0) })
            }))
                .toggleStyle(EmberToggleStyle())
                .labelsHidden()
                .fixedSize()
        }
        .frame(minHeight: 60)
        .overlay(alignment: .bottom) { FlintHairline(opacity: 0.07) }
        .contextMenu {
            Button(role: .destructive) {
                flow.run(scope: rule.name, operation: { vm.delete(rule, confirmation: $0) }, cancel: { vm.cancel(rule.id, confirmation: $0) })
            } label: { Label("Delete", systemImage: "trash") }
        }
    }

    private func meta(_ rule: FlintScheduleRule) -> String {
        let level: String
        switch rule.breakLevel {
        case .easy: level = "Easy"
        case .harder: level = "Harder"
        case .hardcore: level = "Hardcore"
        }
        let count = rule.selection.applicationTokens.count + rule.selection.categoryTokens.count
            + rule.selection.webDomainTokens.count
        let target = rule.allowListMode ? "Allow list" : "\(count) \(count == 1 ? "item" : "items")"
        return "\(Self.daysSummary(rule.schedule)) · \(level) · \(target) · \(rule.protection?.timeZoneID ?? TimeZone.current.identifier)"
    }

    private func timeRange(_ s: FlintSchedule) -> String {
        String(format: "%02d:%02d — %02d:%02d", s.startHour, s.startMinute, s.endHour, s.endMinute)
    }

    /// Weekday numbering is `Calendar`'s (1 = Sunday … 7 = Saturday), but the rules are decoded
    /// from the App Group, so an out-of-range day is possible and must not index off the end.
    static func daysSummary(_ s: FlintSchedule) -> String {
        let days = s.daysOfWeek
        if days.isEmpty || days.count == 7 { return "Every day" }
        if days == [2, 3, 4, 5, 6] { return "Weekdays" }
        if days == [1, 7] { return "Sat Sun" }
        let names = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        // Monday-first, like the editor.
        let ordered = days.sorted { ($0 + 5) % 7 < ($1 + 5) % 7 }
        let labels = ordered.compactMap { weekday in
            names.indices.contains(weekday - 1) ? names[weekday - 1] : nil
        }
        return labels.isEmpty ? "No valid days" : labels.joined(separator: " ")
    }
}

/// Today's 24 hours as one line: each schedule running today a short bar, the current one
/// bright, and the ember at "now".
struct TodayTimeline: View {
    let rules: [FlintScheduleRule]
    let now: Date
    var health = FlintArmingHealth()
    var observation: FlintProtectionObservation?

    var body: some View {
        let cal = Calendar.current
        let weekday = cal.component(.weekday, from: now)
        let nowMinute = Double(cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now))
        let current = FlintTodaySchedule.current(in: rules.filter {
            FlintRuleProtectionStatus.status(enabled: $0.enabled, activityName: $0.monitorName,
                domain: "schedules", health: health, observation: observation, at: now,
                authorized: AuthorizationCenter.shared.authorizationStatus == .approved) == .registered
        }, at: now)
        VStack(spacing: 8) {
            ViewThatFits(in: .horizontal) {
                HStack {
                    Text("Today").flintEyebrow().fixedSize()
                    Spacer()
                    Text("Now \(now.formatted(date: .omitted, time: .shortened))").flintEyebrow().fixedSize()
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("Today").flintEyebrow()
                    Text("Now \(now.formatted(date: .omitted, time: .shortened))").flintEyebrow()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            GeometryReader { geo in
                let w = geo.size.width
                ZStack(alignment: .topLeading) {
                    Rectangle().fill(FlintBrand.bone(0.14)).frame(height: 1).offset(y: 10)
                    ForEach(segments(weekday: weekday), id: \.id) { seg in
                        Capsule()
                            .fill(seg.ruleID == current?.id ? FlintBrand.bone
                                  : seg.end <= nowMinute ? Color(hex: 0x3E3A35) : Color(hex: 0x6E665A))
                            .frame(width: max(4, w * (seg.end - seg.start) / 1440), height: 7)
                            .offset(x: w * seg.start / 1440, y: 7)
                    }
                    Circle().fill(FlintBrand.ember.opacity(0.25)).frame(width: 15, height: 15)
                        .offset(x: w * nowMinute / 1440 - 7.5, y: 3)
                    Circle().fill(FlintBrand.ember).frame(width: 7, height: 7)
                        .offset(x: w * nowMinute / 1440 - 3.5, y: 7)
                }
            }
            .frame(height: 22)
            HStack {
                ForEach(["00", "06", "12", "18", "24"], id: \.self) { label in
                    Text(label)
                    if label != "24" { Spacer() }
                }
            }
            .font(FlintFont.text(11))
            .foregroundStyle(FlintBrand.stone)
            .monospacedDigit()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(current.map { "Scheduled now: \($0.name), registered with iOS" }
                             ?? "No verified schedule registration running now")
    }

    private struct Segment { let id: String; let ruleID: String; let start: Double; let end: Double }

    private func segments(weekday: Int) -> [Segment] {
        let yesterday = weekday == 1 ? 7 : weekday - 1
        var out: [Segment] = []
        for rule in rules where rule.enabled {
            let s = rule.schedule
            let start = Double(s.startHour * 60 + s.startMinute), end = Double(s.endHour * 60 + s.endMinute)
            let today = s.daysOfWeek.isEmpty || s.daysOfWeek.contains(weekday)
            let ranYesterday = s.daysOfWeek.isEmpty || s.daysOfWeek.contains(yesterday)
            if start < end {
                if today { out.append(Segment(id: rule.id, ruleID: rule.id, start: start, end: end)) }
            } else {
                if ranYesterday { out.append(Segment(id: rule.id + "-am", ruleID: rule.id, start: 0, end: end)) }
                if today { out.append(Segment(id: rule.id + "-pm", ruleID: rule.id, start: start, end: 1440)) }
            }
        }
        return out
    }
}

/// Add / edit a schedule rule. `asNew: true` treats `rule` as a prefilled draft (a routine
/// template): the editor titles itself "New schedule" and mints a fresh id on save instead of
/// overwriting the draft's throwaway id.
struct ScheduleEditor: View {
    @Environment(\.dismiss) private var dismiss

    private let existingID: String?
    private let existingEnabled: Bool
    private let original: FlintScheduleRule?
    private let onSave: (FlintScheduleRule, FlintRuleConfirmation?) -> FlintRuleMutationResult
    private let onDelete: ((FlintRuleConfirmation?) -> FlintRuleMutationResult)?
    @StateObject private var flow = RuleMutationFlow()

    @State private var name: String
    @State private var days: Set<Int>
    @State private var start: Date
    @State private var end: Date
    @State private var breakLevel: BreakLevel
    @State private var allowList: Bool
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false
    @State private var editingTime: TimeField?

    private enum TimeField: Identifiable { case start, end; var id: Self { self } }

    init(rule: FlintScheduleRule?, asNew: Bool = false,
         onSave: @escaping (FlintScheduleRule, FlintRuleConfirmation?) -> FlintRuleMutationResult,
         onDelete: ((FlintRuleConfirmation?) -> FlintRuleMutationResult)? = nil) {
        self.existingID = asNew ? nil : rule?.id
        self.existingEnabled = rule?.enabled ?? true
        self.original = asNew ? nil : rule
        self.onSave = onSave
        self.onDelete = asNew ? nil : onDelete
        _name = State(initialValue: rule?.name ?? "")
        // Drop days the chips below can't represent (they toggle 1…7 only). A rule decoded from
        // the App Group with a weekday of 8 would otherwise fail validation with no way to fix it:
        // Save stays disabled and no chip can clear the offending day. Mirrors Android's ruleDraftOf.
        _days = State(initialValue: (rule?.schedule.daysOfWeek ?? []).filter(FlintSchedule.weekdayRange.contains))
        _breakLevel = State(initialValue: rule?.breakLevel ?? .easy)
        _allowList = State(initialValue: rule?.allowListMode ?? false)
        _selection = State(initialValue: rule?.selection ?? FamilyActivitySelection())
        let cal = Calendar.current
        let today = Date()
        _start = State(initialValue: cal.date(bySettingHour: rule?.schedule.startHour ?? 9,
                                              minute: rule?.schedule.startMinute ?? 0, second: 0, of: today) ?? today)
        _end = State(initialValue: cal.date(bySettingHour: rule?.schedule.endHour ?? 17,
                                            minute: rule?.schedule.endMinute ?? 0, second: 0, of: today) ?? today)
    }

    /// Monday-first chips; values are `Calendar` weekdays (1 = Sunday).
    private let weekdays: [(day: Int, short: String, full: String)] = [
        (2, "M", "Monday"), (3, "T", "Tuesday"), (4, "W", "Wednesday"), (5, "T", "Thursday"),
        (6, "F", "Friday"), (7, "S", "Saturday"), (1, "S", "Sunday"),
    ]

    var body: some View {
        ZStack {
            FlintBrand.ground.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Button("Cancel") { dismiss() }
                        .font(FlintFont.text(15)).foregroundStyle(FlintBrand.mist).frame(minHeight: 44)
                    Spacer()
                    Text("Schedule").flintEyebrow()
                    Spacer()
                    Button("Save") { save() }
                        .buttonStyle(BoneButtonStyle(height: 36))
                        .disabled(!canSave)
                }
                .padding(.top, 14)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Name").flintSecondary(12)
                            TextField("", text: $name, prompt: Text("Laser Focus").foregroundColor(FlintBrand.faint))
                                .font(FlintFont.display(28))
                                .foregroundStyle(FlintBrand.bone)
                                .frame(height: 44)
                                .overlay(alignment: .bottom) { FlintHairline(opacity: 0.16) }
                        }
                        .padding(.top, 18)

                        HStack(spacing: 10) {
                            timeCard("Starts", start) { editingTime = .start }
                            timeCard("Ends", end) { editingTime = .end }
                        }
                        .padding(.top, 22)
                        Text("Times use \(original?.protection?.timeZoneID ?? TimeZone.current.identifier).")
                            .font(.caption).foregroundStyle(FlintBrand.stone).padding(.top, 6)
                        if let issue = draftSchedule.issues.first {
                            Text(issue.message).font(FlintFont.text(13)).foregroundStyle(FlintBrand.danger).padding(.top, 8)
                        } else if draftSchedule.isOvernight {
                            Text("Runs overnight, ending the next morning.").flintSecondary().padding(.top, 8)
                        }

                        Text("Repeats · \(daysLabel)").flintEyebrow().padding(.top, 24)
                        HStack(spacing: 6) {
                            ForEach(weekdays, id: \.day) { item in
                                let on = days.contains(item.day)
                                Button {
                                    if on { days.remove(item.day) } else { days.insert(item.day) }
                                } label: {
                                    Text(item.short)
                                        .font(FlintFont.text(14, weight: .semibold))
                                        .foregroundStyle(on ? FlintBrand.onEmber : FlintBrand.mist)
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                        .background(Capsule().fill(on ? FlintBrand.ember : .clear))
                                        .overlay(Capsule().strokeBorder(on ? FlintBrand.ember : FlintBrand.bone(0.16)))
                                        .contentShape(Capsule())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(item.full)
                                .accessibilityAddTraits(on ? .isSelected : [])
                            }
                        }
                        .padding(.top, 10)

                        Button { showPicker = true } label: {
                            HStack(spacing: 14) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(selectionTitle).flintRowTitle()
                                    Text(allowList ? "Everything else rests" : "Apple’s picker").flintSecondary()
                                }
                                Spacer()
                                FlintChevron()
                            }
                            .padding(.horizontal, 16)
                            .frame(minHeight: 64)
                            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.bone(0.03)))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 24)

                        if let issue = selectionIssue {
                            Text(issue.message(for: allowList ? .allowList : .blockList)).font(FlintFont.text(13))
                                .foregroundStyle(FlintBrand.danger).padding(.top, 8)
                        }

                        Toggle(isOn: $allowList) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Allow list").flintRowTitle()
                                Text("Only the chosen apps stay open").flintSecondary()
                            }
                        }
                        .toggleStyle(EmberToggleStyle())
                        .frame(minHeight: 58)
                        .overlay(alignment: .bottom) { FlintHairline() }

                        if allowList {
                            Text("Only individually picked apps stay open. Website and category picks are ignored; websites remain blocked.")
                                .flintSecondary(12).padding(.top, 8)
                        }

                        Text("Break level").flintEyebrow().padding(.top, 24)
                        FlintSegmented(options: [(BreakLevel.easy, "Easy"), (.harder, "Harder"), (.hardcore, "Hardcore")],
                                       selection: $breakLevel)
                            .padding(.top, 10)
                        Text(levelNote).flintSecondary().lineSpacing(3).padding(.top, 10)

                        if let onDelete {
                            Button("Delete schedule") {
                                flow.run(scope: name, operation: onDelete,
                                    cancel: { ticket in if let existingID { FlintSchedulesController().cancelPendingChange(existingID, confirmation: ticket) } },
                                    onSaved: { dismiss() })
                            }
                            .font(FlintFont.text(14, weight: .medium))
                            .foregroundStyle(FlintBrand.danger)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding(.top, 28)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 24)
                }
            }
            .padding(.horizontal, 20)
        }
        .familyActivityPicker(isPresented: $showPicker, selection: $selection)
        .sheet(item: $editingTime) { field in
            TimePickerSheet(title: field == .start ? "Starts" : "Ends",
                            time: field == .start ? $start : $end)
        }
        .preferredColorScheme(.dark)
        .ruleMutationFlow(flow)
    }

    private func timeCard(_ label: String, _ time: Date, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            FlintCard {
                Text(label).flintSecondary(12)
                Text(FlintTodaySchedule.clock(Calendar.current.component(.hour, from: time),
                                              Calendar.current.component(.minute, from: time)))
                    .flintNumber(28)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) \(time.formatted(date: .omitted, time: .shortened))")
    }

    private var selectionTitle: String {
        if selectionCount == 0 { return allowList ? "Choose what stays open" : "Choose what rests" }
        return allowList ? "Allow \(selectionCount) \(selectionCount == 1 ? "item" : "items")"
            : "Block \(selectionCount) \(selectionCount == 1 ? "item" : "items")"
    }

    private var daysLabel: String {
        if days.isEmpty || days.count == 7 { return "Every day" }
        if days == [2, 3, 4, 5, 6] { return "Weekdays" }
        return "\(days.count) \(days.count == 1 ? "day" : "days")"
    }

    private var levelNote: String {
        switch breakLevel {
        case .easy: "You can end it whenever you like."
        case .harder: "Stopping early takes friction."
        case .hardcore: "Active protection stays until this occurrence ends. Weakening changes can be saved for afterwards. An available Emergency Pass is a separate recovery option."
        }
    }

    private var selectionCount: Int {
        selection.applicationTokens.count + selection.categoryTokens.count + selection.webDomainTokens.count
    }

    /// The window the pickers currently describe. The time pickers only ever yield real
    /// times, so in practice the reachable issues are a zero-length window and one under
    /// DeviceActivity's 15-minute floor — both of which used to save, toggle on, and block nothing.
    private var draftSchedule: FlintSchedule {
        let cal = Calendar.current
        return FlintSchedule(
            daysOfWeek: days,
            startHour: cal.component(.hour, from: start),
            startMinute: cal.component(.minute, from: start),
            endHour: cal.component(.hour, from: end),
            endMinute: cal.component(.minute, from: end)
        )
    }

    private var canSave: Bool {
        selectionIssue == nil && draftSchedule.isValid
    }

    private var selectionIssue: FlintSelectionIssue? {
        FlintSelectionValidation.issue(for: selection, context: allowList ? .allowList : .blockList)
    }

    private func save() {
        guard canSave else { return }
        let rule = FlintScheduleRule(
            id: existingID ?? String(UUID().uuidString.prefix(8)),
            name: name.isEmpty ? "Schedule" : name,
            schedule: draftSchedule,
            breakLevel: breakLevel,
            selection: selection,
            allowListMode: allowList,
            enabled: existingEnabled,
            protection: original?.protection ?? .init(), oneShot: original?.oneShot
        )
        flow.run(scope: rule.name, operation: { onSave(rule, $0) },
            cancel: { ticket in if let existingID { FlintSchedulesController().cancelPendingChange(existingID, confirmation: ticket) } },
            onSaved: { dismiss() })
    }
}

/// A wheel time picker in a short night sheet.
struct TimePickerSheet: View {
    let title: String
    @Binding var time: Date
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            FlintBrand.sheet.ignoresSafeArea()
            VStack(spacing: 8) {
                HStack {
                    Text(title).flintTitle(26)
                    Spacer()
                    Button("Done") { dismiss() }.buttonStyle(BoneButtonStyle(height: 36))
                }
                .padding(.top, 20)
                DatePicker(title, selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel)
                    .labelsHidden()
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
        }
        .presentationDetents([.height(320)])
        .preferredColorScheme(.dark)
    }
}
