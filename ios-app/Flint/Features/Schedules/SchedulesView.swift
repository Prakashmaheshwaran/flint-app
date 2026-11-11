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
                    selectionIssue: FlintSelectionValidation.armIssue(for: rule.selection,
                        context: rule.allowListMode ? .allowList : .blockList),
                    selectionContext: rule.allowListMode ? .allowList : .blockList,
                    registrationReason: health.domains["schedules"]?.failures.first(where: {
                        $0.activityName == rule.monitorName
                    })?.reason)
            }
        }
    }

    func save(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil, creating: Bool = false) -> FlintRuleMutationResult {
        let result = controller.upsert(rule, confirmation: confirmation, creating: creating, origin: .interactive); refresh(); return result
    }
    func delete(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.delete(rule.id, revision: rule.protection?.revision ?? 0, confirmation: confirmation, origin: .interactive); refresh(); return result
    }
    func toggle(_ rule: FlintScheduleRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.setEnabled(rule.id, !rule.enabled, revision: rule.protection?.revision ?? 0,
            confirmation: confirmation, origin: .interactive); refresh(); return result
    }
    func cancel(_ id: String, confirmation: FlintRuleConfirmation?) { controller.cancelPendingChange(id, confirmation: confirmation) }
    func cancelQueued(_ rule: FlintScheduleRule, expected: FlintRuleProtection) -> FlintRuleMutationResult {
        let result = controller.cancelQueuedChange(rule.id, expected: expected); refresh(); return result
    }
}

/// The Rules tab (M13): what rests now, schedules as ember-washed cards, limits, groups.
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
    @State private var limits: [FlintLimitRule] = []
    @State private var groups: [FlintAppGroup] = []
    @State private var activeSession = FlintSessionController().activeSession
    @State private var restingSelection: FamilyActivitySelection?
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header

                    if !restingTargets.isEmpty || activeSession != nil { restingSection.padding(.top, 24) }
                    if !vm.armFailures.isEmpty { armFailures.padding(.top, 22) }
                    schedulesSection.padding(.top, 28)
                    limitsSection.padding(.top, 28)
                    if !groups.isEmpty { groupsSection.padding(.top, 28) }
                    alsoSection.padding(.top, 28)
                    templatesSection.padding(.top, 28)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
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
            .onAppear(perform: refresh)
            .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in
                vm.refresh()
                now = Date()
            }
            .ruleMutationFlow(flow)
        }
    }

    private func refresh() {
        vm.refresh()
        now = Date()
        sleep = FlintSleepConfig.load()
        limits = FlintLimitsController().limits()
        limitCount = limits.count
        openLimitCount = FlintOpenLimitsController().rules().count
        groups = FlintGroupStore()?.loadAppGroups() ?? []
        activeSession = FlintSessionController().activeSession
        // An Allow List session freezes the apps it keeps available, not blocked targets.
        restingSelection = activeSession?.isAllowList == true
            ? nil : activeSession.flatMap { FlintGroupStore()?.loadSessionSelection(sessionID: $0.id) }
    }

    private var header: some View {
        HStack(alignment: .center) {
            Text("Rules").flintTitle(32).accessibilityAddTraits(.isHeader)
            Spacer()
            Button { showAdd = true } label: {
                Image(systemName: "plus").font(.system(size: 18, weight: .bold))
            }
            .buttonStyle(CircleIconButtonStyle(size: 44, ember: true))
            .accessibilityLabel("New schedule")
        }
        .padding(.top, 8)
    }

    // MARK: Resting now

    private var restingTargets: [PickedTarget] {
        // An Allow List session keeps its picks available and blocks everything else: there is
        // no resting set to draw, and a concurrent schedule's picks must not stand in for it.
        if activeSession?.isAllowList == true { return [] }
        if let selection = restingSelection, !selection.flintPickedTargets.isEmpty {
            return selection.flintPickedTargets
        }
        if let current = FlintTodaySchedule.current(in: vm.rules, at: now) {
            return current.selection.flintPickedTargets
        }
        return []
    }

    private var restingLine: String? {
        if let session = activeSession {
            let left = session.endsAt.map { " · \(leftText(until: $0)) left" } ?? ""
            return session.isAllowList ? "\(session.name) · allow list\(left)" : "\(session.name)\(left)"
        }
        if let current = FlintTodaySchedule.current(in: vm.rules, at: now) {
            let left = current.endsAtToday(now: now).map { " · \(leftText(until: $0)) left" } ?? ""
            return "\(current.name)\(left)"
        }
        return nil
    }

    private var restingSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                HStack(spacing: 5) {
                    Text("Resting now").flintSection()
                    Text("· \(restingCountText)")
                        .font(FlintFont.text(16, weight: .medium))
                        .foregroundStyle(FlintBrand.mist)
                }
                Spacer(minLength: 8)
                if let restingLine {
                    Text(restingLine)
                        .font(FlintFont.text(12, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(FlintBrand.emberText)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
            .padding(.horizontal, 4)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 12) {
                    ForEach(Array(restingTargets.prefix(12).enumerated()), id: \.offset) { _, target in
                        FlintAppTile(target: target, size: 52, resting: true)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(activeSession?.isAllowList == true
                                ? "Allow list session, everything else blocked" : "\(restingTargets.count) items resting")
        }
    }

    /// An Allow List session names the apps it keeps available, so it has no finite resting set
    /// to count — say what it does instead of showing its exceptions as blocked.
    private var restingCountText: String {
        activeSession?.isAllowList == true
            ? "everything else blocked"
            : "\(restingTargets.count) \(restingTargets.count == 1 ? "item" : "items")"
    }

    private func leftText(until date: Date) -> String {
        let seconds = max(0, date.timeIntervalSince(now))
        let minutes = Int((seconds / 60).rounded())
        let h = minutes / 60, m = minutes % 60
        if h == 0 { return "\(m)m" }
        return m == 0 ? "\(h)h" : String(format: "%dh %02dm", h, m)
    }

    // MARK: Schedules

    private var schedulesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Schedules").flintSection()
                Image(systemName: "chevron.right")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(FlintBrand.mist)
                    .accessibilityHidden(true)
                Spacer()
            }
            .padding(.horizontal, 4)
            .accessibilityElement(children: .combine)

            if vm.health.domains["schedules"]?.failures.contains(where: { $0.activityName == "schedules" }) == true {
                Text("Saved schedules are unavailable. Existing protection was preserved.")
                    .font(FlintFont.text(13))
                    .foregroundStyle(FlintBrand.danger)
            }

            if vm.rules.isEmpty {
                FlintCard(radius: 22, padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
                    Text("No schedules yet.")
                        .font(FlintFont.text(15, weight: .semibold))
                        .foregroundStyle(FlintBrand.bone)
                    Text("Recurring blocks for work hours, study or bedtime — start from a template below or tap +.")
                        .flintSecondary(13)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(vm.rules) { rule in
                            scheduleCard(rule)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                }
            }
        }
    }

    private func scheduleCard(_ rule: FlintScheduleRule) -> some View {
        let current = FlintTodaySchedule.current(in: vm.rules, at: now)?.id == rule.id
        let active = rule.enabled && current
        let status = FlintRuleProtectionStatus.status(enabled: rule.enabled,
            activityName: rule.monitorName, domain: "schedules", health: vm.health,
            observation: vm.observation,
            authorized: AuthorizationCenter.shared.authorizationStatus == .approved)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 6) {
                ZStack {
                    Circle().fill(FlintBrand.bone(0.08))
                    Image(systemName: "calendar")
                        .font(.system(size: 15, weight: .regular))
                        .foregroundStyle(FlintBrand.bone)
                }
                .frame(width: 32, height: 32)
                Image(systemName: "arrow.right")
                    .font(.system(size: 13, weight: .regular))
                    .foregroundStyle(FlintBrand.stone)
                ZStack {
                    Circle().fill(active ? FlintBrand.ember.opacity(0.18) : FlintBrand.bone(0.08))
                    Image(systemName: "lock")
                        .font(.system(size: 15, weight: .regular))
                        .foregroundStyle(active ? FlintBrand.ember : FlintBrand.bone)
                }
                .frame(width: 32, height: 32)
                Spacer(minLength: 8)
                Toggle(rule.name, isOn: Binding(get: { rule.enabled }, set: { _ in
                    flow.run(scope: rule.name, operation: { vm.toggle(rule, confirmation: $0) },
                             cancel: { vm.cancel(rule.id, confirmation: $0) })
                }))
                .toggleStyle(EmberToggleStyle())
                .labelsHidden()
                .scaleEffect(0.8, anchor: .trailing)
                .frame(width: 44, height: 34)
                .fixedSize()
            }
            .accessibilityHidden(false)

            Button { editing = rule } label: {
                VStack(alignment: .leading, spacing: 12) {
                    FlintStatusPill(text: pillText(rule, current: current, status: status),
                                    style: active ? .emberStrong : .quiet)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(rule.name)
                            .font(FlintFont.text(16, weight: .semibold))
                            .foregroundStyle(rule.enabled ? FlintBrand.bone : FlintBrand.faint)
                            .lineLimit(2)
                            .minimumScaleFactor(0.85)
                        Text("\(Self.daysSummary(rule.schedule)) · \(timeRange(rule.schedule))")
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.mist)
                            .lineLimit(2)
                    }
                    HStack(spacing: 6) {
                        Text(rule.allowListMode ? "Allow" : "Block")
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.mist)
                        FlintAppIconStack(targets: rule.selection.flintPickedTargets, size: 20,
                                          border: FlintBrand.card, limit: 2)
                        let total = rule.selection.applicationTokens.count + rule.selection.categoryTokens.count
                            + rule.selection.webDomainTokens.count
                        if total > 2 {
                            Text("+\(total - 2)")
                                .font(FlintFont.text(12, weight: .semibold))
                                .foregroundStyle(FlintBrand.bone)
                        }
                        Spacer(minLength: 0)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(rule.name), \(Self.daysSummary(rule.schedule)), \(timeRange(rule.schedule))")
            .accessibilityHint("Opens the schedule editor")

            if rule.protection?.requiresTierReview == true {
                Text("Review migrated tier").font(.system(size: 11)).foregroundStyle(FlintBrand.ember)
            }
            if rule.enabled, FlintSelectionValidation.needsAllowedApps(
                applications: rule.selection.applicationTokens.count, allowList: rule.allowListMode) {
                Label(FlintSelectionIssue.emptyAllowList.message, systemImage: "exclamationmark.triangle")
                    .font(FlintFont.text(11)).foregroundStyle(FlintBrand.danger)
            }
            if let protection = rule.protection, protection.queuedChange != nil {
                QueuedRuleChangeView(protection: protection) {
                    flow.run(scope: rule.name, operation: { _ in vm.cancelQueued(rule, expected: protection) })
                }
            }
        }
        .padding(14)
        .frame(width: 196, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(active
                    ? LinearGradient(stops: [
                        .init(color: FlintBrand.ember.opacity(0.16), location: 0),
                        .init(color: FlintBrand.ember.opacity(0.04), location: 0.6),
                        .init(color: FlintBrand.card, location: 1),
                    ], startPoint: .top, endPoint: .bottom)
                    : LinearGradient(colors: [FlintBrand.card, FlintBrand.card], startPoint: .top, endPoint: .bottom))
        )
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
            .strokeBorder(active ? FlintBrand.ember.opacity(0.35) : FlintBrand.bone(0.08)))
        .contextMenu {
            Button(role: .destructive) {
                flow.run(scope: rule.name, operation: { vm.delete(rule, confirmation: $0) },
                         cancel: { vm.cancel(rule.id, confirmation: $0) })
            } label: { Label("Delete", systemImage: "trash") }
        }
    }

    private func pillText(_ rule: FlintScheduleRule, current: Bool, status: FlintRuleProtectionStatus) -> String {
        if !rule.enabled { return "Off" }
        if status == .needsAttention { return "Needs attention" }
        if current {
            if let end = rule.endsAtToday(now: now) {
                return "\(leftText(until: end)) left"
            }
            return "Running now"
        }
        return "Next · \(FlintTodaySchedule.clock(rule.schedule.startHour, rule.schedule.startMinute))"
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

    /// An enabled rule whose window iOS refuses is a shield the user believes in and doesn't have.
    /// Say so out loud, above the card that claims otherwise, and open the editor on a tap.
    private var armFailures: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Needs attention", systemImage: "exclamationmark.triangle")
                .font(FlintFont.text(13, weight: .semibold))
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

    // MARK: Limits

    private var limitsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            NavigationLink { LimitsView() } label: {
                HStack(spacing: 4) {
                    Text("Limits").flintSection()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(FlintBrand.mist)
                    Spacer()
                }
                .padding(.horizontal, 4)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if limits.isEmpty && openLimitCount == 0 {
                Text("No app limits yet. Cap daily usage, or cap opens per day.")
                    .flintSecondary(13)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                NavigationLink { LimitsView() } label: {
                    Text("Add a limit").frame(minHeight: 40)
                }
                .buttonStyle(GhostButtonStyle(height: 40))
                .padding(.horizontal, 4)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(limits) { limit in
                            NavigationLink { LimitsView() } label: { limitCard(limit) }
                                .buttonStyle(.plain)
                        }
                        if openLimitCount > 0 {
                            NavigationLink { OpenLimitsView() } label: {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text("Open limits").font(FlintFont.text(14, weight: .semibold))
                                        .foregroundStyle(FlintBrand.bone)
                                    Text("\(openLimitCount) \(openLimitCount == 1 ? "rule" : "rules")")
                                        .flintSecondary(12)
                                }
                                .padding(14)
                                .frame(width: 176, alignment: .leading)
                                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
                                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous)
                                    .strokeBorder(FlintBrand.bone(0.08)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 2)
                }
            }
        }
    }

    private func limitCard(_ limit: FlintLimitRule) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                FlintAppIconStack(targets: limit.selection.flintPickedTargets, size: 32,
                                  border: FlintBrand.card, limit: 1)
                VStack(alignment: .leading, spacing: 1) {
                    Text(limit.name)
                        .font(FlintFont.text(14, weight: .semibold))
                        .foregroundStyle(limit.enabled ? FlintBrand.bone : FlintBrand.faint)
                        .lineLimit(1)
                    Text("\(limit.thresholdMinutes) min a day")
                        .font(FlintFont.text(12))
                        .foregroundStyle(FlintBrand.mist)
                        .monospacedDigit()
                }
                Spacer(minLength: 0)
            }
            FlintStatusPill(text: limit.enabled ? limit.breakLevel.rawValue.capitalized : "Off",
                            style: .quiet)
        }
        .padding(14)
        .frame(width: 176, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
        .accessibilityElement(children: .combine)
    }

    // MARK: Groups

    private var groupsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Groups").flintSection().padding(.horizontal, 4)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3), spacing: 16) {
                ForEach(groups) { group in
                    NavigationLink { AppsListView() } label: {
                        VStack(spacing: 8) {
                            FlintFolderTile(targets: group.selection.flintPickedTargets, size: 92)
                            VStack(spacing: 1) {
                                Text(group.name)
                                    .font(FlintFont.text(13, weight: .semibold))
                                    .foregroundStyle(FlintBrand.bone)
                                    .lineLimit(1)
                                Text("\(group.itemCount) \(group.itemCount == 1 ? "item" : "items")")
                                    .font(FlintFont.text(11))
                                    .foregroundStyle(FlintBrand.mist)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(group.name), \(group.itemCount) items")
                }
            }
            .padding(.horizontal, 4)
        }
    }

    // MARK: Also + templates

    private var alsoSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Also").flintSection().padding(.horizontal, 4).padding(.bottom, 4)
            NavigationLink { SleepModeView() } label: {
                FlintRow(title: "Sleep",
                         subtitle: sleep.enabled
                            ? "\(FlintTodaySchedule.clock(sleep.schedule.startHour, sleep.schedule.startMinute)) — \(FlintTodaySchedule.clock(sleep.schedule.endHour, sleep.schedule.endMinute))"
                            : "Off",
                         chevron: true)
            }
            .buttonStyle(.plain)
            NavigationLink { OpenLimitsView() } label: {
                FlintRow(title: "Open limits",
                         subtitle: openLimitCount == 0 ? "None yet" : "\(openLimitCount) \(openLimitCount == 1 ? "rule" : "rules")",
                         chevron: true)
            }
            .buttonStyle(.plain)
        }
    }

    private var templatesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Start from a template").flintSection().padding(.horizontal, 4).padding(.bottom, 4)
            ForEach(FlintRoutinePreset.library) { preset in
                Button { startingTemplate = preset } label: {
                    FlintRow(title: preset.name, subtitle: preset.description, chevron: true)
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// Add / edit a schedule rule (M14). `asNew: true` treats `rule` as a prefilled draft (a routine
/// template): the editor titles itself "New schedule" and mints a fresh id on save instead of
/// overwriting the draft's throwaway id.
struct ScheduleEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

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
                FlintEditorHeader(title: "Schedule", canSave: canSave, onCancel: { dismiss() }, onSave: { save() })
                    .padding(.top, 14)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Name").font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                            TextField("", text: $name, prompt: Text("Laser Focus").foregroundColor(FlintBrand.faint))
                                .accessibilityLabel("Name")
                                .font(FlintFont.text(28, weight: .bold, relativeTo: .title))
                                .tracking(-0.5)
                                .foregroundStyle(FlintBrand.bone)
                                .frame(minHeight: 44)
                                .overlay(alignment: .bottom) { FlintHairline(opacity: 0.14) }
                        }
                        .padding(.horizontal, 4)
                        .padding(.top, 16)

                        // Side by side, the clock digits break at accessibility sizes.
                        (typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 10)) : AnyLayout(HStackLayout(spacing: 10))) {
                            timeCard("Starts", start) { editingTime = .start }
                            timeCard("Ends", end) { editingTime = .end }
                        }
                        .disabled(original?.oneShot != nil)
                        .padding(.top, 18)
                        if let window = original?.oneShot {
                            Text("One-time schedule: \(window.start.formatted(date: .abbreviated, time: .shortened)) – \(window.end.formatted(date: .abbreviated, time: .shortened)). Its saved date and time stay fixed in this editor.")
                                .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone).padding(.top, 6)
                        }
                        Text("Times use \(original?.protection?.timeZoneID ?? TimeZone.current.identifier).")
                            .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone).padding(.top, 6)
                        if let issue = draftSchedule.issues.first {
                            Text(issue.message).font(FlintFont.text(13)).foregroundStyle(FlintBrand.danger).padding(.top, 8)
                        } else if draftSchedule.isOvernight {
                            Text("Runs overnight, ending the next morning.").flintSecondary().padding(.top, 8)
                        }

                        HStack(spacing: 5) {
                            Text(original?.oneShot == nil ? "Repeats" : "Does not repeat")
                                .font(FlintFont.text(14, weight: .semibold))
                                .foregroundStyle(FlintBrand.bone)
                            if original?.oneShot == nil {
                                Text("· \(daysLabel)")
                                    .font(FlintFont.text(14, weight: .medium))
                                    .foregroundStyle(FlintBrand.mist)
                            }
                        }
                        .padding(.horizontal, 4)
                        .padding(.top, 20)

                        HStack(spacing: 6) {
                            ForEach(weekdays, id: \.day) { item in
                                let on = days.contains(item.day)
                                Button {
                                    if on { days.remove(item.day) } else { days.insert(item.day) }
                                } label: {
                                    Text(item.short)
                                        .font(FlintFont.text(14, weight: .bold))
                                        .foregroundStyle(on ? FlintBrand.onEmber : FlintBrand.mist)
                                        .frame(maxWidth: .infinity, minHeight: 44)
                                        .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                            .fill(on ? FlintBrand.ember : FlintBrand.card))
                                        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                                            .strokeBorder(on ? FlintBrand.ember : FlintBrand.bone(0.10)))
                                        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(item.full)
                                .accessibilityAddTraits(on ? .isSelected : [])
                            }
                        }
                        .disabled(original?.oneShot != nil)
                        .padding(.top, 10)

                        Button { showPicker = true } label: {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(spacing: 8) {
                                    HStack(spacing: 5) {
                                        Text(allowList ? "Allow" : "Block")
                                            .font(FlintFont.text(14, weight: .semibold))
                                            .foregroundStyle(FlintBrand.bone)
                                        if selectionCount > 0 {
                                            Text("· \(selectionSummary)")
                                                .font(FlintFont.text(14, weight: .medium))
                                                .foregroundStyle(FlintBrand.mist)
                                        }
                                    }
                                    .fixedSize(horizontal: false, vertical: true)
                                    Spacer(minLength: 8)
                                    FlintChevron()
                                }
                                if selectionCount == 0 {
                                    Text("Tap to choose what rests with Apple’s picker")
                                        .font(FlintFont.text(12))
                                        .foregroundStyle(FlintBrand.mist)
                                } else {
                                    FlintAppIconStack(targets: selection.flintPickedTargets, size: 32,
                                                      border: FlintBrand.card, limit: 6)
                                }
                            }
                            .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 20)

                        if let issue = selectionIssue {
                            Text(issue.message(for: allowList ? .allowList : .blockList)).font(FlintFont.text(13))
                                .foregroundStyle(FlintBrand.danger).padding(.top, 8)
                        }

                        Toggle(isOn: $allowList) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Allow list").flintRowTitle()
                                Text(allowList
                                     ? "Only the chosen apps stay open. Include the ones you need, like Phone and Messages."
                                     : "Only the chosen apps stay open").flintSecondary()
                            }
                        }
                        .toggleStyle(EmberToggleStyle())
                        .frame(minHeight: 58)
                        .padding(.top, 8)
                        .overlay(alignment: .bottom) { FlintHairline() }

                        if allowList {
                            Text("Only individually picked apps stay open. Website and category picks are ignored; websites remain blocked.")
                                .flintSecondary(12).padding(.top, 8)
                        }

                        Text("Break level")
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                            .padding(.horizontal, 4)
                            .padding(.top, 20)
                        FlintSegmented(options: [(BreakLevel.easy, "Easy"), (.harder, "Harder"), (.hardcore, "Hardcore")],
                                       selection: $breakLevel)
                            .padding(.top, 10)
                        Text(levelNote).flintSecondary().lineSpacing(3).padding(.top, 10).padding(.horizontal, 4)

                        if let onDelete {
                            Button("Delete schedule") {
                                flow.run(scope: name, operation: onDelete,
                                    cancel: { ticket in if let existingID { FlintSchedulesController().cancelPendingChange(existingID, confirmation: ticket) } },
                                    onSaved: { dismiss() })
                            }
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.danger)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding(.top, 24)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 36)
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
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                Text(FlintTodaySchedule.clock(Calendar.current.component(.hour, from: time),
                                              Calendar.current.component(.minute, from: time)))
                    .font(FlintFont.display(28, relativeTo: .title2))
                    .monospacedDigit()
                    .foregroundStyle(FlintBrand.bone)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) \(time.formatted(date: .omitted, time: .shortened))")
    }

    private var selectionSummary: String {
        let counts = SelectionCounts(apps: selection.applicationTokens.count,
                                     categories: selection.categoryTokens.count,
                                     sites: selection.webDomainTokens.count)
        return counts.total == 0 ? "nothing yet" : counts.summary
    }

    private var daysLabel: String {
        if days.isEmpty || days.count == 7 { return "Every day" }
        if days == [2, 3, 4, 5, 6] { return "Weekdays" }
        return "\(days.count) \(days.count == 1 ? "day" : "days")"
    }

    private var levelNote: String {
        switch breakLevel {
        case .easy: "You can end it whenever you like."
        case .harder: "No early stop. Each break waits a few minutes first."
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

extension FlintScheduleRule {
    /// The end of the occurrence running now (or of the next one later today), for time-left copy.
    func endsAtToday(now: Date = Date()) -> Date? {
        let calendar = Calendar.current
        guard let end = calendar.date(bySettingHour: schedule.endHour, minute: schedule.endMinute,
                                      second: 0, of: now) else { return nil }
        return end > now ? end : calendar.date(byAdding: .day, value: 1, to: end)
    }
}
