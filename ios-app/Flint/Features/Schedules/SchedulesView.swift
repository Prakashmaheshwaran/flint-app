import SwiftUI
import FamilyControls
import FlintCore

@MainActor
final class SchedulesViewModel: ObservableObject {
    @Published private(set) var rules: [FlintScheduleRule] = []
    private let controller = FlintSchedulesController()

    init() {
        controller.reload() // re-arm monitoring on launch
        refresh()
    }

    func refresh() { rules = controller.rules() }
    func save(_ rule: FlintScheduleRule) { controller.upsert(rule); refresh() }
    func delete(_ id: String) { controller.delete(id); refresh() }
    func toggle(_ rule: FlintScheduleRule) { controller.setEnabled(rule.id, !rule.enabled); refresh() }
}

struct SchedulesView: View {
    @StateObject private var vm = SchedulesViewModel()
    @State private var showAdd = false
    @State private var editing: FlintScheduleRule?
    @State private var startingTemplate: FlintRoutinePreset?

    var body: some View {
        NavigationStack {
            List {
                if vm.rules.isEmpty {
                    Section {
                        emptyState
                            .frame(maxWidth: .infinity)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                    }
                } else {
                    Section {
                        ForEach(vm.rules) { rule in
                            Button { editing = rule } label: { row(rule) }
                                .buttonStyle(.plain)
                        }
                        .onDelete { indexSet in
                            indexSet.map { vm.rules[$0].id }.forEach(vm.delete)
                        }
                    }
                }

                Section("Start from a template") {
                    ForEach(FlintRoutinePreset.library) { preset in
                        Button { startingTemplate = preset } label: { templateRow(preset) }
                            .buttonStyle(.plain)
                    }
                }
            }
            .navigationTitle("Schedules")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $showAdd) {
                ScheduleEditor(rule: nil) { vm.save($0) }
            }
            .sheet(item: $editing) { rule in
                ScheduleEditor(rule: rule) { vm.save($0) }
            }
            .sheet(item: $startingTemplate) { preset in
                ScheduleEditor(rule: preset.draftRule(), asNew: true) { vm.save($0) }
            }
            .onAppear { vm.refresh() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "calendar.badge.clock")
                .font(.system(size: 44))
                .foregroundStyle(FlintBrand.spark)
            Text("No schedules yet")
                .font(.headline)
            Text("Recurring blocks — work hours, bedtime, study time. Unlimited and free.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button { showAdd = true } label: { Text("Add a schedule") }
                .buttonStyle(.borderedProminent)
                .tint(FlintBrand.spark)
        }
        .padding(32)
    }

    private func row(_ rule: FlintScheduleRule) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(rule.name).font(.body.weight(.medium))
                Text("\(timeRange(rule.schedule)) · \(daysSummary(rule.schedule))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { rule.enabled }, set: { _ in vm.toggle(rule) }))
                .labelsHidden()
                .tint(FlintBrand.spark)
        }
        .padding(.vertical, 4)
    }

    /// One routine template: tapping opens the new-schedule editor prefilled from the preset.
    /// Targets stay the user's to pick — the editor's Save keeps its selection gate.
    private func templateRow(_ preset: FlintRoutinePreset) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(preset.name).font(.body.weight(.medium))
            Text(preset.description).font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }

    private func timeRange(_ s: FlintSchedule) -> String {
        String(format: "%02d:%02d–%02d:%02d", s.startHour, s.startMinute, s.endHour, s.endMinute)
    }

    private func daysSummary(_ s: FlintSchedule) -> String {
        if s.daysOfWeek.isEmpty { return "Every day" }
        let names = ["", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
        return s.daysOfWeek.sorted().map { names[$0] }.joined(separator: " ")
    }
}

/// Add / edit a schedule rule. `asNew: true` treats `rule` as a prefilled draft (a routine
/// template): the editor titles itself "New schedule" and mints a fresh id on save instead of
/// overwriting the draft's throwaway id.
struct ScheduleEditor: View {
    @Environment(\.dismiss) private var dismiss

    private let existingID: String?
    private let existingEnabled: Bool
    private let onSave: (FlintScheduleRule) -> Void

    @State private var name: String
    @State private var days: Set<Int>
    @State private var start: Date
    @State private var end: Date
    @State private var breakLevel: BreakLevel
    @State private var allowList: Bool
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false

    init(rule: FlintScheduleRule?, asNew: Bool = false, onSave: @escaping (FlintScheduleRule) -> Void) {
        self.existingID = asNew ? nil : rule?.id
        self.existingEnabled = rule?.enabled ?? true
        self.onSave = onSave
        _name = State(initialValue: rule?.name ?? "")
        _days = State(initialValue: rule?.schedule.daysOfWeek ?? [])
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

    private let dayNames = ["S", "M", "T", "W", "T", "F", "S"] // index 0 -> weekday 1 (Sun)

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("Name", text: $name) }

                Section("Days (none = every day)") {
                    HStack {
                        ForEach(1...7, id: \.self) { weekday in
                            let on = days.contains(weekday)
                            Button {
                                if on { days.remove(weekday) } else { days.insert(weekday) }
                            } label: {
                                Text(dayNames[weekday - 1])
                                    .frame(width: 34, height: 34)
                                    .background(on ? FlintBrand.spark : Color.gray.opacity(0.15))
                                    .foregroundStyle(on ? Color.black : Color.primary)
                                    .clipShape(Circle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }

                Section("Time") {
                    DatePicker("Start", selection: $start, displayedComponents: .hourAndMinute)
                    DatePicker("End", selection: $end, displayedComponents: .hourAndMinute)
                }

                Section("Apps & sites") {
                    Button { showPicker = true } label: {
                        HStack {
                            Text(selectionCount == 0 ? "Choose apps & sites" : "\(selectionCount) selected")
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.secondary)
                        }
                    }
                    Toggle("Allow-list (block everything else)", isOn: $allowList)
                        .tint(FlintBrand.spark)
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
            .navigationTitle(existingID == nil ? "New schedule" : "Edit schedule")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(!allowList && selectionCount == 0)
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
        let cal = Calendar.current
        let schedule = FlintSchedule(
            daysOfWeek: days,
            startHour: cal.component(.hour, from: start),
            startMinute: cal.component(.minute, from: start),
            endHour: cal.component(.hour, from: end),
            endMinute: cal.component(.minute, from: end)
        )
        let rule = FlintScheduleRule(
            id: existingID ?? String(UUID().uuidString.prefix(8)),
            name: name.isEmpty ? "Schedule" : name,
            schedule: schedule,
            breakLevel: breakLevel,
            selection: selection,
            allowListMode: allowList,
            enabled: existingEnabled
        )
        onSave(rule)
        dismiss()
    }
}
