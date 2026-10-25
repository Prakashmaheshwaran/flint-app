import SwiftUI
import FamilyControls
import FlintCore

/// The Start focus sheet: how long, what rests (Apple's picker), how hard it is to leave.
struct StartFocusSheet: View {
    @ObservedObject var vm: SessionViewModel
    let onClose: () -> Void

    @State private var showPicker = false
    @State private var showSaveGroup = false
    @State private var groupName = ""

    private let durations = [25, 45, 60, 90]

    var body: some View {
        ZStack {
            FlintBrand.sheet.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                FlintSheetGrabber()

                HStack {
                    Text("Start focus").flintTitle(26)
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: "xmark").font(.system(size: 14, weight: .medium))
                    }
                    .buttonStyle(CircleIconButtonStyle(size: 44, filled: true))
                    .accessibilityLabel("Close")
                }
                .padding(.top, 10)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        durationPicker.padding(.top, 30)
                        blockRow.padding(.top, 28)
                        if !vm.savedGroups.isEmpty || vm.selectionCount > 0 { groupChips.padding(.top, 12) }
                        Text("Break level").flintEyebrow().padding(.top, 30)
                        breakLevels.padding(.top, 10)
                        Text(note(for: vm.breakLevel))
                            .flintSecondary()
                            .lineSpacing(3)
                            .frame(minHeight: 38, alignment: .top)
                            .padding(.top, 12)
                    }
                }

                Button("Begin \(vm.durationMinutes) minutes") {
                    vm.start()
                    if vm.active != nil { onClose() }
                }
                .buttonStyle(EmberButtonStyle())
                .disabled(vm.selectionCount == 0)
                .padding(.top, 12)
                if let error = vm.errorText {
                    Text(error)
                        .font(FlintFont.text(13))
                        .foregroundStyle(FlintBrand.danger)
                        .frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                } else if vm.selectionCount == 0 {
                    Text("Choose what rests first.")
                        .flintSecondary()
                        .frame(maxWidth: .infinity)
                        .padding(.top, 8)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
        .presentationDragIndicator(.hidden)
        .familyActivityPicker(isPresented: $showPicker, selection: $vm.selection)
        .onChange(of: vm.selection) { _ in vm.persistSelection() }
        .alert("Save as group", isPresented: $showSaveGroup) {
            TextField("Name", text: $groupName)
            Button("Save") { vm.saveSelectionAsGroup(name: groupName); groupName = "" }
            Button("Cancel", role: .cancel) { groupName = "" }
        }
        .preferredColorScheme(.dark)
    }

    private var durationPicker: some View {
        VStack(spacing: 16) {
            HStack {
                Button { vm.durationMinutes = max(5, vm.durationMinutes - 5) } label: {
                    Image(systemName: "minus").font(.system(size: 16))
                }
                .buttonStyle(CircleIconButtonStyle())
                .accessibilityLabel("Five minutes less")
                Spacer()
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text("\(vm.durationMinutes)").flintNumber(68)
                    Text("min").flintSecondary(15)
                }
                .accessibilityElement(children: .combine)
                Spacer()
                Button { vm.durationMinutes = min(240, vm.durationMinutes + 5) } label: {
                    Image(systemName: "plus").font(.system(size: 16))
                }
                .buttonStyle(CircleIconButtonStyle())
                .accessibilityLabel("Five minutes more")
            }
            HStack(spacing: 8) {
                ForEach(durations, id: \.self) { minutes in
                    Button("\(minutes)") { vm.durationMinutes = minutes }
                        .buttonStyle(ChipButtonStyle(selected: vm.durationMinutes == minutes, height: 44, stretch: true))
                        .accessibilityLabel("\(minutes) minutes")
                        .accessibilityAddTraits(vm.durationMinutes == minutes ? .isSelected : [])
                }
            }
        }
    }

    private var blockRow: some View {
        Button { showPicker = true } label: {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(vm.selectionCount == 0 ? "Choose what rests" : "Block \(vm.selectionSummary)")
                        .flintRowTitle()
                    Text(vm.selectionCount == 0 ? "Apps, categories and sites" : "Tap to change")
                        .flintSecondary()
                }
                Spacer()
                FlintChevron()
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 64)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(FlintBrand.bone(0.04)))
            .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var groupChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(vm.savedGroups) { group in
                    Button("\(group.name) · \(group.itemCount)") { vm.applyGroup(group) }
                        .buttonStyle(ChipButtonStyle(height: 36))
                }
                if vm.selectionCount > 0 {
                    Button("Save as group") { showSaveGroup = true }
                        .font(FlintFont.text(13, weight: .medium))
                        .foregroundStyle(FlintBrand.mist)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 36)
                        .overlay(Capsule().strokeBorder(FlintBrand.bone(0.22), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                }
            }
        }
    }

    private var breakLevels: some View {
        HStack(spacing: 8) {
            ForEach(BreakLevel.allCases, id: \.self) { level in
                let selected = vm.breakLevel == level
                Button { vm.breakLevel = level } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title(for: level)).flintRowTitle()
                        Text(hint(for: level)).font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, minHeight: 72, maxHeight: .infinity, alignment: .topLeading)
                    .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(selected ? FlintBrand.ember.opacity(0.12) : FlintBrand.bone(0.03)))
                    .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(selected ? FlintBrand.ember.opacity(0.7) : FlintBrand.bone(0.10)))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func title(for level: BreakLevel) -> String {
        switch level {
        case .easy: "Easy"
        case .harder: "Harder"
        case .hardcore: "Hardcore"
        }
    }

    private func hint(for level: BreakLevel) -> String {
        switch level {
        case .easy: "Stop any time"
        case .harder: "Stopping takes friction"
        case .hardcore: "No way out"
        }
    }

    private func note(for level: BreakLevel) -> String {
        switch level {
        case .easy: "You can end this session whenever you like."
        case .harder: "You can still stop early, but only after a short breathing wait."
        case .hardcore: "Can’t be stopped, and Flint can’t be removed, until it ends. Your weekly Emergency Pass is the only exit."
        }
    }
}
