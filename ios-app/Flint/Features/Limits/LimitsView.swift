import SwiftUI
import FamilyControls
import FlintCore

@MainActor
final class LimitsViewModel: ObservableObject {
    @Published private(set) var limits: [FlintLimitRule] = []
    @Published private(set) var health = FlintArmingHealth()
    @Published private(set) var observation: FlintProtectionObservation?
    private let controller = FlintLimitsController()

    init() {
        refresh()
    }

    func refresh() {
        limits = controller.limits()
        health = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
        observation = FlintProtectionObservation.load()
    }
    func save(_ limit: FlintLimitRule, confirmation: FlintRuleConfirmation? = nil, creating: Bool = false) -> FlintRuleMutationResult {
        let result = controller.upsert(limit, confirmation: confirmation, creating: creating, origin: .interactive); refresh(); return result
    }
    func delete(_ limit: FlintLimitRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.delete(limit.id, revision: limit.protection?.revision ?? 0, confirmation: confirmation, origin: .interactive); refresh(); return result
    }
    func toggle(_ limit: FlintLimitRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.setEnabled(limit.id, !limit.enabled, revision: limit.protection?.revision ?? 0,
            confirmation: confirmation, origin: .interactive); refresh(); return result
    }
    func cancel(_ id: String, confirmation: FlintRuleConfirmation?) { controller.cancelPendingChange(id, confirmation: confirmation) }
    func cancelQueued(_ rule: FlintLimitRule, expected: FlintRuleProtection) -> FlintRuleMutationResult {
        let result = controller.cancelQueuedChange(rule.id, expected: expected); refresh(); return result
    }
}

/// App limits (M13's limit cards, M15's editor). Daily time budgets per selection.
struct LimitsView: View {
    @StateObject private var vm = LimitsViewModel()
    @StateObject private var flow = RuleMutationFlow()
    @State private var showAdd = false
    @State private var editing: FlintLimitRule?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center) {
                    Text("App limits").flintTitle(32).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button { showAdd = true } label: {
                        Image(systemName: "plus").font(.system(size: 18, weight: .bold))
                    }
                    .buttonStyle(CircleIconButtonStyle(size: 44, ember: true))
                    .accessibilityLabel("New time limit")
                }
                .padding(.top, 8)

                Text("Daily time budgets").flintEyebrow().padding(.top, 22)
                if vm.health.domains["limits"]?.failures.contains(where: { $0.activityName == "limits" }) == true {
                    Text("Saved Time Limits are unavailable. Existing protection was preserved.")
                        .font(FlintFont.text(13))
                        .foregroundStyle(FlintBrand.danger)
                }
                if vm.limits.isEmpty {
                    FlintCard(radius: 22, padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
                        Text("No time limits yet").font(FlintFont.text(15, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                        Text("Cap daily usage — say 30 minutes of social, then it rests until tomorrow.")
                            .flintSecondary(13).fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, 12)
                    Button("Add a time limit") { showAdd = true }
                        .buttonStyle(GhostButtonStyle(height: 40))
                        .padding(.top, 4)
                } else {
                    VStack(spacing: 10) {
                        ForEach(vm.limits) { limit in row(limit) }
                    }
                    .padding(.top, 12)
                }

                NavigationLink { OpenLimitsView() } label: {
                    FlintCard(radius: 22, padding: EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16)) {
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Open limits").font(FlintFont.text(15, weight: .semibold))
                                    .foregroundStyle(FlintBrand.bone)
                                Text("Cap opens per day instead of minutes").flintSecondary(12)
                            }
                            Spacer(minLength: 8)
                            FlintChevron()
                        }
                    }
                }
                .buttonStyle(.plain)
                .padding(.top, 24)
                Text("The block screen’s button spends one open to let you through.")
                    .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone).padding(.top, 8)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 32)
        }
        .background(FlintGroundBackground())
        .flintTabBarInset()
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showAdd) { LimitEditor(limit: nil) { vm.save($0, confirmation: $1, creating: true) } }
        .sheet(item: $editing) { limit in
            LimitEditor(limit: limit, onDelete: { vm.delete(limit, confirmation: $0) }) { vm.save($0, confirmation: $1) }
        }
        .ruleMutationFlow(flow)
        .onAppear { vm.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in vm.refresh() }
    }

    private func row(_ limit: FlintLimitRule) -> some View {
        let active = limit.enabled
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                Button { editing = limit } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Text(limit.name).font(FlintFont.text(15, weight: .semibold))
                                .foregroundStyle(active ? FlintBrand.bone : FlintBrand.faint)
                            Text("\(limit.thresholdMinutes) min").font(FlintFont.display(14))
                                .foregroundStyle(FlintBrand.mist).monospacedDigit()
                        }
                        Text("A day · \(limit.breakLevel.rawValue.capitalized)")
                            .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                        if limit.protection?.requiresTierReview == true {
                            Text("Review migrated tier").font(FlintFont.text(11))
                        }
                        if active {
                            Text(FlintRuleProtectionStatus.status(enabled: limit.enabled, activityName: limit.activityName,
                                domain: "limits", health: vm.health, observation: vm.observation,
                                authorized: AuthorizationCenter.shared.authorizationStatus == .approved).label)
                                .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Toggle(limit.name, isOn: Binding(get: { limit.enabled }, set: { _ in
                    flow.run(scope: limit.name, operation: { vm.toggle(limit, confirmation: $0) },
                             cancel: { vm.cancel(limit.id, confirmation: $0) })
                }))
                .toggleStyle(EmberToggleStyle()).labelsHidden().fixedSize()
            }
            if !limit.selection.flintPickedTargets.isEmpty {
                FlintAppIconStack(targets: limit.selection.flintPickedTargets, size: 28,
                                  border: FlintBrand.card, limit: 6)
            }
            if let protection = limit.protection, protection.queuedChange != nil {
                QueuedRuleChangeView(protection: protection) {
                    flow.run(scope: limit.name, operation: { _ in vm.cancelQueued(limit, expected: protection) })
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
    }
}

/// M15's app limit editor: the app, the daily allowance dial, the tier it falls back to.
struct LimitEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize

    private let existingID: String?
    private let existingEnabled: Bool
    private let original: FlintLimitRule?
    private let onSave: (FlintLimitRule, FlintRuleConfirmation?) -> FlintRuleMutationResult
    private let onDelete: ((FlintRuleConfirmation?) -> FlintRuleMutationResult)?
    @StateObject private var flow = RuleMutationFlow()

    @State private var name: String
    @State private var minutes: Int
    @State private var breakLevel: BreakLevel
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false

    init(limit: FlintLimitRule?, onDelete: ((FlintRuleConfirmation?) -> FlintRuleMutationResult)? = nil,
         onSave: @escaping (FlintLimitRule, FlintRuleConfirmation?) -> FlintRuleMutationResult) {
        self.existingID = limit?.id
        self.existingEnabled = limit?.enabled ?? true
        self.original = limit
        self.onSave = onSave
        self.onDelete = limit == nil ? nil : onDelete
        _name = State(initialValue: limit?.name ?? "")
        _minutes = State(initialValue: limit?.thresholdMinutes ?? 30)
        _breakLevel = State(initialValue: limit?.breakLevel ?? .easy)
        _selection = State(initialValue: limit?.selection ?? FamilyActivitySelection())
    }

    var body: some View {
        ZStack {
            FlintBrand.ground.ignoresSafeArea()
            VStack(spacing: 0) {
                FlintEditorHeader(title: "App limit", canSave: selectionIssue == nil,
                                  onCancel: { dismiss() }, onSave: { save() })
                    .padding(.top, 14)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        Button { showPicker = true } label: {
                            HStack(spacing: 12) {
                                if selectionCount > 0 {
                                    FlintAppIconStack(targets: selection.flintPickedTargets, size: 44,
                                                      border: FlintBrand.card, limit: 1)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(selectionCount == 0 ? "Choose apps and sites" : "\(selectionCount) \(selectionCount == 1 ? "item" : "items")")
                                        .font(FlintFont.text(18, weight: .bold))
                                        .foregroundStyle(FlintBrand.bone)
                                        .fixedSize(horizontal: false, vertical: true)
                                    Text(selectionCount == 0 ? "Apple’s picker" : "Tap to change")
                                        .font(FlintFont.text(12))
                                        .foregroundStyle(FlintBrand.mist)
                                }
                                Spacer(minLength: 8)
                                // The subtitle already says how to change it; at accessibility
                                // sizes this trailing label broke the title mid-word.
                                if !typeSize.isAccessibilitySize {
                                    Text("Change")
                                        .font(FlintFont.text(13, weight: .semibold))
                                        .foregroundStyle(FlintBrand.mist)
                                }
                            }
                            .padding(EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.card))
                            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 16)

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Name").font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                            TextField("", text: $name, prompt: Text("Social").foregroundColor(FlintBrand.faint))
                                .accessibilityLabel("Name")
                                .font(FlintFont.text(24, weight: .bold, relativeTo: .title2))
                                .foregroundStyle(FlintBrand.bone)
                                .frame(minHeight: 44)
                                .overlay(alignment: .bottom) { FlintHairline(opacity: 0.14) }
                        }
                        .padding(.horizontal, 4)
                        .padding(.top, 16)

                        if let issue = selectionIssue {
                            Text(issue.message).font(FlintFont.text(13))
                                .foregroundStyle(FlintBrand.danger).padding(.top, 8)
                        }

                        ZStack {
                            Circle().stroke(FlintBrand.bone(0.10), lineWidth: 12)
                            Circle()
                                .trim(from: 0, to: min(1, Double(minutes) / 120))
                                .stroke(FlintBrand.ember, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            VStack(spacing: 2) {
                                Text(TodayView.duration(minutes: minutes)).flintNumber(44)
                                Text("a day, then it rests").flintSecondary(12)
                            }
                        }
                        .frame(width: 200, height: 200)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 22)
                        .accessibilityElement(children: .combine)

                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Daily allowance").font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                                Text("\(minutes) min").font(FlintFont.display(24, relativeTo: .title2))
                                    .monospacedDigit().foregroundStyle(FlintBrand.bone)
                            }
                            Spacer()
                            HStack(spacing: 8) {
                                Button { minutes = max(5, minutes - 5) } label: { Image(systemName: "minus").font(.system(size: 15, weight: .medium)) }
                                    .buttonStyle(CircleIconButtonStyle(size: 44))
                                    .accessibilityLabel("Five minutes less")
                                Button { minutes = min(480, minutes + 5) } label: { Image(systemName: "plus").font(.system(size: 15, weight: .medium)) }
                                    .buttonStyle(CircleIconButtonStyle(size: 44))
                                    .accessibilityLabel("Five minutes more")
                            }
                        }
                        .padding(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 10))
                        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.card))
                        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
                        .padding(.top, 18)

                        Text("When time runs out")
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                            .padding(.horizontal, 4)
                            .padding(.top, 20)
                        FlintSegmented(options: [(BreakLevel.easy, "Easy"), (.harder, "Harder"), (.hardcore, "Hardcore")],
                                       selection: $breakLevel)
                            .padding(.top, 10)
                        Text("Rests until tomorrow.").flintSecondary().padding(.top, 10).padding(.horizontal, 4)

                        if let onDelete {
                            Button("Delete limit") {
                                flow.run(scope: name, operation: onDelete,
                                    cancel: { ticket in if let existingID { FlintLimitsController().cancelPendingChange(existingID, confirmation: ticket) } },
                                    onSaved: { dismiss() })
                            }
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.danger)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .padding(.top, 24)
                        }
                    }
                    .padding(.horizontal, 4)
                    .padding(.bottom, 32)
                }
            }
            .padding(.horizontal, 20)
        }
        .familyActivityPicker(isPresented: $showPicker, selection: $selection)
        .preferredColorScheme(.dark)
        .ruleMutationFlow(flow)
    }

    private var selectionCount: Int {
        selection.applicationTokens.count + selection.categoryTokens.count + selection.webDomainTokens.count
    }

    private func save() {
        guard selectionIssue == nil else { return }
        let limit = FlintLimitRule(
            id: existingID ?? String(UUID().uuidString.prefix(8)),
            name: name.isEmpty ? "Limit" : name,
            thresholdMinutes: minutes,
            selection: selection,
            breakLevel: breakLevel,
            enabled: existingEnabled, protection: original?.protection ?? .init()
        )
        flow.run(scope: limit.name, operation: { onSave(limit, $0) },
            cancel: { ticket in if let existingID { FlintLimitsController().cancelPendingChange(existingID, confirmation: ticket) } },
            onSaved: { dismiss() })
    }

    private var selectionIssue: FlintSelectionIssue? {
        FlintSelectionValidation.issue(for: selection)
    }
}
