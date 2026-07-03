import SwiftUI
import FamilyControls
import FlintCore

@MainActor
final class LimitsViewModel: ObservableObject {
    @Published private(set) var limits: [FlintLimitRule] = []
    private let controller = FlintLimitsController()

    init() {
        controller.reload()
        refresh()
    }

    func refresh() { limits = controller.limits() }
    func save(_ limit: FlintLimitRule) { controller.upsert(limit); refresh() }
    func delete(_ id: String) { controller.delete(id); refresh() }
    func toggle(_ limit: FlintLimitRule) { controller.setEnabled(limit.id, !limit.enabled); refresh() }
}

struct LimitsView: View {
    @StateObject private var vm = LimitsViewModel()
    @State private var showAdd = false
    @State private var editing: FlintLimitRule?

    var body: some View {
        NavigationStack {
            List {
                if vm.limits.isEmpty {
                    Section {
                        emptyState
                            .frame(maxWidth: .infinity)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                } else {
                    Section("Daily time budgets") {
                        ForEach(vm.limits) { limit in
                            Button { editing = limit } label: { row(limit) }
                                .buttonStyle(.plain)
                        }
                        .onDelete { set in set.map { vm.limits[$0].id }.forEach(vm.delete) }
                    }
                }

                Section {
                    NavigationLink {
                        OpenLimitsView()
                    } label: {
                        Label("Open Limits", systemImage: "hand.tap")
                    }
                } footer: {
                    Text("Cap opens per day instead of minutes — e.g. 3 opens of social. "
                         + "The block screen's button spends one open to let you through.")
                }
            }
            .navigationTitle("Limits")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showAdd) { LimitEditor(limit: nil) { vm.save($0) } }
            .sheet(item: $editing) { limit in LimitEditor(limit: limit) { vm.save($0) } }
            .onAppear { vm.refresh() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "hourglass")
                .font(.system(size: 44)).foregroundStyle(FlintBrand.spark)
            Text("No time limits yet").font(.headline)
            Text("Cap daily usage — e.g. 30 min of social, then it blocks until tomorrow. Free.")
                .font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button { showAdd = true } label: { Text("Add a time limit") }
                .buttonStyle(.borderedProminent).tint(FlintBrand.spark)
        }
        .padding(32)
    }

    private func row(_ limit: FlintLimitRule) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(limit.name).font(.body.weight(.medium))
                Text("\(limit.thresholdMinutes) min/day").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { limit.enabled }, set: { _ in vm.toggle(limit) }))
                .labelsHidden().tint(FlintBrand.spark)
        }
        .padding(.vertical, 4)
    }
}

struct LimitEditor: View {
    @Environment(\.dismiss) private var dismiss

    private let existingID: String?
    private let existingEnabled: Bool
    private let onSave: (FlintLimitRule) -> Void

    @State private var name: String
    @State private var minutes: Int
    @State private var breakLevel: BreakLevel
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false

    init(limit: FlintLimitRule?, onSave: @escaping (FlintLimitRule) -> Void) {
        self.existingID = limit?.id
        self.existingEnabled = limit?.enabled ?? true
        self.onSave = onSave
        _name = State(initialValue: limit?.name ?? "")
        _minutes = State(initialValue: limit?.thresholdMinutes ?? 30)
        _breakLevel = State(initialValue: limit?.breakLevel ?? .easy)
        _selection = State(initialValue: limit?.selection ?? FamilyActivitySelection())
    }

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Name", text: $name) }
                Section("Daily budget: \(minutes) min") {
                    Stepper("", value: $minutes, in: 5...480, step: 5).labelsHidden()
                }
                Section("Apps & sites") {
                    Button { showPicker = true } label: {
                        HStack {
                            Text(selectionCount == 0 ? "Choose apps & sites" : "\(selectionCount) selected")
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.secondary)
                        }
                    }
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
            .navigationTitle(existingID == nil ? "New limit" : "Edit limit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(selectionCount == 0)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .familyActivityPicker(isPresented: $showPicker, selection: $selection)
        }
    }

    private var selectionCount: Int {
        selection.applicationTokens.count + selection.categoryTokens.count + selection.webDomainTokens.count
    }

    private func save() {
        let limit = FlintLimitRule(
            id: existingID ?? String(UUID().uuidString.prefix(8)),
            name: name.isEmpty ? "Limit" : name,
            thresholdMinutes: minutes,
            selection: selection,
            breakLevel: breakLevel,
            enabled: existingEnabled
        )
        onSave(limit)
        dismiss()
    }
}
