import SwiftUI
import FamilyControls
import FlintCore

@MainActor
final class OpenLimitsViewModel: ObservableObject {
    @Published private(set) var rules: [FlintOpenLimitRule] = []
    private let controller = FlintOpenLimitsController()

    init() {
        controller.reload() // re-arm shields + day-boundary activities when the screen opens
        refresh()
    }

    func refresh() { rules = controller.rules() }
    func save(_ rule: FlintOpenLimitRule) { controller.upsert(rule); refresh() }
    func delete(_ id: String) { controller.delete(id); refresh() }
    func toggle(_ rule: FlintOpenLimitRule) { controller.setEnabled(rule.id, !rule.enabled); refresh() }
}

/// Create/edit daily Open Limits (launch-count caps). A pushable `View` (no own
/// `NavigationStack`, like `SleepModeView`) — `LimitsView` links here.
///
/// How the enforcement actually works (honest UX): the selected apps/sites *stay shielded*;
/// tapping the block screen's "Use app (N left)" button spends one of the day's opens and lets
/// the next launch through. `FlintOpenLimitsController` arms the shields; the ShieldAction
/// extension spends the opens. Like every shield, it only enforces on a real device.
struct OpenLimitsView: View {
    @StateObject private var vm = OpenLimitsViewModel()
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
                    .onDelete { set in set.map { vm.rules[$0].id }.forEach(vm.delete) }
                }
            }
        }
        .navigationTitle("Open Limits")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { showAdd = true } label: { Image(systemName: "plus") }
            }
        }
        .sheet(isPresented: $showAdd) { OpenLimitEditor(rule: nil) { vm.save($0) } }
        .sheet(item: $editing) { rule in OpenLimitEditor(rule: rule) { vm.save($0) } }
        .onAppear { vm.refresh() }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "hand.tap")
                .font(.system(size: 44)).foregroundStyle(FlintBrand.spark)
            Text("No open limits yet").font(.headline)
            Text("Cap how many times a day an app can be opened — e.g. 3 opens of social. "
                 + "Each pass-through from the block screen spends one. Free.")
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
                Text("\(rule.opensAllowed) \(rule.opensAllowed == 1 ? "open" : "opens")/day")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { rule.enabled }, set: { _ in vm.toggle(rule) }))
                .labelsHidden().tint(FlintBrand.spark)
        }
        .padding(.vertical, 4)
    }
}

struct OpenLimitEditor: View {
    @Environment(\.dismiss) private var dismiss

    private let existingID: String?
    private let existingEnabled: Bool
    private let onSave: (FlintOpenLimitRule) -> Void

    @State private var name: String
    @State private var opensAllowed: Int
    @State private var breakLevel: BreakLevel
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false

    init(rule: FlintOpenLimitRule?, onSave: @escaping (FlintOpenLimitRule) -> Void) {
        self.existingID = rule?.id
        self.existingEnabled = rule?.enabled ?? true
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
                Section("Opens allowed: \(opensAllowed)/day") {
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
            .navigationTitle(existingID == nil ? "New open limit" : "Edit open limit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(tokenCount == 0 || FlintTokens.exceedsShieldCap(selection))
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .familyActivityPicker(isPresented: $showPicker, selection: $selection)
        }
    }

    /// Individually-enforceable tokens only. Whole categories can't be open-limited (Apple's
    /// tokens are opaque, so a tapped app can't be matched back to a category's counter — see
    /// `FlintOpenLimitRule`), which is why category picks don't count toward Save here.
    private var tokenCount: Int {
        FlintTokens.tokenCount(in: selection)
    }

    private var footerText: Text {
        if FlintTokens.exceedsShieldCap(selection) {
            return Text("Too many items — pick at most \(FlintShieldLimits.tokenCap) apps or "
                        + "\(FlintShieldLimits.tokenCap) sites (an iOS shield cap).")
        }
        if !selection.categoryTokens.isEmpty {
            return Text("Whole categories can't be open-limited (an Apple token limitation) — "
                        + "only the specific apps & sites picked here are counted.")
        }
        return Text("The apps stay shielded; the block screen's button spends one of the day's "
                    + "opens to let you through.")
    }

    private func save() {
        let rule = FlintOpenLimitRule(
            id: existingID ?? String(UUID().uuidString.prefix(8)),
            name: name.isEmpty ? "Open limit" : name,
            opensAllowed: opensAllowed,
            selection: selection,
            breakLevel: breakLevel,
            enabled: existingEnabled
        )
        onSave(rule)
        dismiss()
    }
}
