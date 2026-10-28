import SwiftUI
import FamilyControls
import FlintCore

@MainActor
final class OpenLimitsViewModel: ObservableObject {
    @Published private(set) var rules: [FlintOpenLimitRule] = []
    @Published private(set) var health = FlintArmingHealth()
    @Published private(set) var observation: FlintProtectionObservation?
    private let controller = FlintOpenLimitsController()

    init() {
        refresh()
    }

    func refresh() {
        rules = controller.rules()
        health = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
        observation = FlintProtectionObservation.load()
    }
    func save(_ rule: FlintOpenLimitRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.upsert(rule, confirmation: confirmation); refresh(); return result
    }
    func delete(_ rule: FlintOpenLimitRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.delete(rule.id, revision: rule.protection?.revision, confirmation: confirmation); refresh(); return result
    }
    func toggle(_ rule: FlintOpenLimitRule, confirmation: FlintRuleConfirmation? = nil) -> FlintRuleMutationResult {
        let result = controller.setEnabled(rule.id, !rule.enabled, revision: rule.protection?.revision,
            confirmation: confirmation); refresh(); return result
    }
    func cancel(_ id: String) { controller.cancelPendingChange(id) }
    func delete(_ rules: [FlintOpenLimitRule], confirmation: FlintRuleConfirmation?) -> FlintRuleMutationResult {
        let result = controller.deleteBatch(rules, confirmation: confirmation); refresh(); return result
    }
}

/// Create/edit daily quotas of bounded, explicitly accepted access credits. A pushable `View` (no own
/// `NavigationStack`, like `SleepModeView`) — `LimitsView` links here.
///
/// Selected apps/sites stay shielded between accepted credits. Each credit lasts up to five
/// minutes, clipped at the quota reset; another protection can prevent its use. Native shield
/// delivery and re-application require physical-device verification.
struct OpenLimitsView: View {
    @StateObject private var vm = OpenLimitsViewModel()
    @StateObject private var flow = RuleMutationFlow()
    @State private var showAdd = false
    @State private var editing: FlintOpenLimitRule?

    var body: some View {
        Group {
            if vm.rules.isEmpty {
                emptyState
            } else {
                List {
                    ForEach(vm.rules) { rule in
                        Button { editing = rule } label: { row(rule) }
                            .buttonStyle(.plain)
                    }
                    .onDelete { set in
                        let selected = set.map { vm.rules[$0] }
                        flow.run(scope: selected.map(\.name).joined(separator: ", "),
                            operation: { vm.delete(selected, confirmation: $0) },
                            cancel: { selected.forEach { vm.cancel($0.id) } })
                    }
                }
            }
        }
        .flintForm()
        .navigationTitle("Open Limits")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showAdd = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $showAdd) { OpenLimitEditor(rule: nil) { vm.save($0, confirmation: $1) } }
        .sheet(item: $editing) { rule in OpenLimitEditor(rule: rule) { vm.save($0, confirmation: $1) } }
        .ruleMutationFlow(flow)
        .onAppear { vm.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in vm.refresh() }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "hand.tap")
                .font(.system(size: 44)).foregroundStyle(FlintBrand.spark)
            Text("No open limits yet").font(.headline)
            Text("Choose how many access credits you can use each day for an app or website. "
                 + "Each accepted credit gives up to 5 minutes, ending sooner at the daily reset. "
                 + "Apps and sites stay blocked between credits; another rule may prevent their use.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button { showAdd = true } label: { Text("Add an open limit") }
                .buttonStyle(.borderedProminent).tint(FlintBrand.spark)
        }
        .padding(32)
    }

    private func row(_ rule: FlintOpenLimitRule) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(rule.name).font(.body.weight(.medium))
                Text("\(rule.opensAllowed) \(rule.opensAllowed == 1 ? "credit" : "credits")/day")
                    .font(.caption).foregroundStyle(.secondary)
                if let queued = rule.protection?.queuedChange {
                    Text("\(queued.summary) · after \(queued.window.end.formatted(date: .abbreviated, time: .shortened))")
                        .font(.caption).foregroundStyle(FlintBrand.ember)
                }
                if rule.protection?.requiresTierReview == true { Text("Review migrated tier").font(.caption) }
                if rule.enabled {
                    Text(FlintRuleProtectionStatus.status(enabled: rule.enabled, activityName: rule.activityName,
                        domain: "openLimits", health: vm.health, observation: vm.observation,
                        authorized: AuthorizationCenter.shared.authorizationStatus == .approved).label)
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer()
            Toggle("", isOn: Binding(get: { rule.enabled }, set: { _ in
                flow.run(scope: rule.name, operation: { vm.toggle(rule, confirmation: $0) }, cancel: { vm.cancel(rule.id) })
            }))
                .labelsHidden().tint(FlintBrand.spark)
        }
        .padding(.vertical, 4)
    }
}

struct OpenLimitEditor: View {
    @Environment(\.dismiss) private var dismiss

    private let existingID: String?
    private let existingEnabled: Bool
    private let original: FlintOpenLimitRule?
    private let onSave: (FlintOpenLimitRule, FlintRuleConfirmation?) -> FlintRuleMutationResult
    @StateObject private var flow = RuleMutationFlow()

    @State private var name: String
    @State private var opensAllowed: Int
    @State private var breakLevel: BreakLevel
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false

    init(rule: FlintOpenLimitRule?, onSave: @escaping (FlintOpenLimitRule, FlintRuleConfirmation?) -> FlintRuleMutationResult) {
        self.existingID = rule?.id
        self.existingEnabled = rule?.enabled ?? true
        self.original = rule
        self.onSave = onSave
        _name = State(initialValue: rule?.name ?? "")
        _opensAllowed = State(initialValue: rule?.opensAllowed ?? 3)
        _breakLevel = State(initialValue: rule?.breakLevel ?? .easy)
        _selection = State(initialValue: rule?.selection ?? FamilyActivitySelection())
    }

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Name", text: $name) }
                Section("Credits per day: \(opensAllowed)") {
                    Stepper("", value: $opensAllowed, in: 1...20, step: 1).labelsHidden()
                }
                Section {
                    Button { showPicker = true } label: {
                        HStack {
                            Text(tokenCount == 0 ? "Choose apps & sites" : "\(tokenCount) selected")
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("Apps & sites")
                } footer: {
                    footerText
                }
                Section("Break level") {
                    Picker("Break level", selection: $breakLevel) {
                        Text("Easy").tag(BreakLevel.easy)
                        Text("Harder").tag(BreakLevel.harder)
                        Text("Hardcore").tag(BreakLevel.hardcore)
                    }
                    .pickerStyle(.segmented)
                }
            }
            .flintForm()
            .navigationTitle(existingID == nil ? "New open limit" : "Edit open limit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(selectionIssue != nil)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .familyActivityPicker(isPresented: $showPicker, selection: $selection)
            .ruleMutationFlow(flow)
        }
    }

    /// Individually-enforceable tokens only. Whole categories can't be open-limited (Apple's
    /// tokens are opaque, so a tapped app can't be matched back to a category's counter — see
    /// `FlintOpenLimitRule`), which is why category picks don't count toward Save here.
    private var tokenCount: Int {
        FlintTokens.tokenCount(in: selection)
    }

    private var footerText: Text {
        if let issue = selectionIssue {
            return Text(issue.message(for: .openLimit) + (issue == .emptySelection && !selection.categoryTokens.isEmpty
                ? " Open Limits require specific apps or websites; categories cannot be open-limited." : ""))
        }
        if !selection.categoryTokens.isEmpty {
            return Text("Categories stay saved but cannot use access credits. "
                        + "Choose individual apps or websites for up to 5 minutes per credit.")
        }
        return Text("Apps and sites stay blocked between credits. Each accepted credit gives up to 5 minutes, "
                    + "ending sooner at the daily reset. Another protection may prevent credit use.")
    }

    private func save() {
        guard selectionIssue == nil else { return }
        let rule = FlintOpenLimitRule(
            id: existingID ?? String(UUID().uuidString.prefix(8)),
            name: name.isEmpty ? "Open limit" : name,
            opensAllowed: opensAllowed,
            selection: selection,
            breakLevel: breakLevel,
            enabled: existingEnabled, protection: original?.protection ?? .init()
        )
        flow.run(scope: rule.name, operation: { onSave(rule, $0) },
            cancel: { if let existingID { FlintOpenLimitsController().cancelPendingChange(existingID) } },
            onSaved: { dismiss() })
    }

    private var selectionIssue: FlintSelectionIssue? {
        FlintSelectionValidation.issue(for: selection, context: .openLimit)
    }
}
