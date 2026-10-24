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
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("App limits").flintTitle(30)
                    Spacer()
                    Button { showAdd = true } label: { Image(systemName: "plus").font(.system(size: 17)) }
                        .buttonStyle(CircleIconButtonStyle(size: 44))
                        .accessibilityLabel("New time limit")
                }

                Text("Daily time budgets").flintEyebrow().padding(.top, 22)
                if vm.limits.isEmpty {
                    Text("Cap daily usage — say 30 minutes of social, then it rests until tomorrow.")
                        .flintSecondary(14).lineSpacing(3).padding(.vertical, 14)
                    Button("Add a time limit") { showAdd = true }.buttonStyle(GhostButtonStyle())
                } else {
                    VStack(spacing: 0) {
                        ForEach(vm.limits) { limit in row(limit) }
                    }
                    .padding(.top, 6)
                }

                Text("Open limits").flintEyebrow().padding(.top, 26)
                NavigationLink { OpenLimitsView() } label: {
                    FlintRow(title: "Open limits", subtitle: "Cap opens per day instead of minutes", chevron: true)
                }
                .buttonStyle(.plain)
                .padding(.top, 6)
                Text("The block screen’s button spends one open to let you through.")
                    .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone).padding(.top, 8)
            }
            .padding(.horizontal, 24)
            .padding(.top, 8)
        }
        .background(FlintGroundBackground())
        .flintTabBarInset()
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showAdd) { LimitEditor(limit: nil) { vm.save($0) } }
        .sheet(item: $editing) { limit in LimitEditor(limit: limit, onDelete: { vm.delete(limit.id) }) { vm.save($0) } }
        .onAppear { vm.refresh() }
    }

    private func row(_ limit: FlintLimitRule) -> some View {
        HStack(spacing: 14) {
            Button { editing = limit } label: {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(limit.name).font(FlintFont.text(15, weight: .medium))
                            .foregroundStyle(limit.enabled ? FlintBrand.bone : FlintBrand.faint)
                        Text("\(limit.thresholdMinutes) min").font(FlintFont.display(14))
                            .foregroundStyle(FlintBrand.mist).monospacedDigit()
                    }
                    Text("A day").font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Toggle(limit.name, isOn: Binding(get: { limit.enabled }, set: { _ in vm.toggle(limit) }))
                .toggleStyle(EmberToggleStyle()).labelsHidden().fixedSize()
        }
        .frame(minHeight: 60)
        .overlay(alignment: .bottom) { FlintHairline(opacity: 0.07) }
    }
}

struct LimitEditor: View {
    @Environment(\.dismiss) private var dismiss

    private let existingID: String?
    private let existingEnabled: Bool
    private let onSave: (FlintLimitRule) -> Void
    private let onDelete: (() -> Void)?

    @State private var name: String
    @State private var minutes: Int
    @State private var breakLevel: BreakLevel
    @State private var selection: FamilyActivitySelection
    @State private var showPicker = false

    init(limit: FlintLimitRule?, onDelete: (() -> Void)? = nil, onSave: @escaping (FlintLimitRule) -> Void) {
        self.existingID = limit?.id
        self.existingEnabled = limit?.enabled ?? true
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
                HStack {
                    Button("Cancel") { dismiss() }
                        .font(FlintFont.text(15)).foregroundStyle(FlintBrand.mist).frame(minHeight: 44)
                    Spacer()
                    Text("App limit").flintEyebrow()
                    Spacer()
                    Button("Save") { save() }
                        .buttonStyle(BoneButtonStyle(height: 36))
                        .disabled(selectionCount == 0)
                }
                .padding(.top, 14)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Name").flintSecondary(12)
                            TextField("", text: $name, prompt: Text("Social").foregroundColor(FlintBrand.faint))
                                .font(FlintFont.display(26))
                                .foregroundStyle(FlintBrand.bone)
                                .frame(height: 44)
                                .overlay(alignment: .bottom) { FlintHairline(opacity: 0.16) }
                        }
                        .padding(.top, 18)

                        Button { showPicker = true } label: {
                            HStack(spacing: 14) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(selectionCount == 0 ? "Choose apps and sites" : "\(selectionCount) \(selectionCount == 1 ? "item" : "items") limited")
                                        .flintRowTitle()
                                    Text("Apple’s picker").flintSecondary()
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
                        .padding(.top, 22)

                        ZStack {
                            Circle().stroke(FlintBrand.bone(0.1), lineWidth: 10)
                            Circle()
                                .trim(from: 0, to: min(1, Double(minutes) / 120))
                                .stroke(FlintBrand.ember, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                                .rotationEffect(.degrees(-90))
                            VStack(spacing: 4) {
                                Text(TodayView.duration(minutes: minutes)).flintNumber(44)
                                Text("a day, then it rests").flintSecondary(12)
                            }
                        }
                        .frame(width: 210, height: 210)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 26)
                        .accessibilityElement(children: .combine)

                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Daily allowance").flintSecondary(12)
                                Text("\(minutes) min").flintNumber(22)
                            }
                            Spacer()
                            HStack(spacing: 8) {
                                Button { minutes = max(5, minutes - 5) } label: { Image(systemName: "minus").font(.system(size: 15)) }
                                    .buttonStyle(CircleIconButtonStyle(size: 44))
                                    .accessibilityLabel("Five minutes less")
                                Button { minutes = min(480, minutes + 5) } label: { Image(systemName: "plus").font(.system(size: 15)) }
                                    .buttonStyle(CircleIconButtonStyle(size: 44))
                                    .accessibilityLabel("Five minutes more")
                            }
                        }
                        .padding(EdgeInsets(top: 10, leading: 18, bottom: 10, trailing: 10))
                        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.bone(0.03)))
                        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
                        .padding(.top, 24)

                        Text("When time runs out").flintEyebrow().padding(.top, 24)
                        FlintSegmented(options: [(BreakLevel.easy, "Easy"), (.harder, "Harder"), (.hardcore, "Hardcore")],
                                       selection: $breakLevel)
                            .padding(.top, 10)
                        Text("Rests until tomorrow.").flintSecondary().padding(.top, 10)

                        if let onDelete {
                            Button("Delete limit") {
                                onDelete()
                                dismiss()
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
        .preferredColorScheme(.dark)
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
