import SwiftUI
import FamilyControls
import FlintCore

/// Home screen — the Block Now vertical: authorize → pick apps → start an immediate timed block
/// with a break level → live countdown → stop (gated by break level).
struct ContentView: View {
    @StateObject private var auth = AuthorizationModel()
    @StateObject private var vm = SessionViewModel()
    @State private var showPicker = false
    @State private var showSaveGroup = false
    @State private var groupName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    header

                    switch auth.status {
                    case .approved:
                        if let active = vm.active {
                            activeCard(active)
                        } else {
                            setupCard
                        }
                    default:
                        grantCard
                    }

                    if let message = vm.errorText ?? auth.lastError {
                        errorBanner(message)
                    }

                    footer
                }
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .navigationBarTitleDisplayMode(.inline)
            .familyActivityPicker(isPresented: $showPicker, selection: $vm.selection)
            .onChange(of: vm.selection) { _ in vm.persistSelection() }
            .alert("Save as group", isPresented: $showSaveGroup) {
                TextField("Name", text: $groupName)
                Button("Save") { vm.saveSelectionAsGroup(name: groupName); groupName = "" }
                Button("Cancel", role: .cancel) { groupName = "" }
            }
            .task { auth.refresh() }
        }
    }

    // MARK: Sections

    private var header: some View {
        VStack(spacing: 4) {
            Text("Flint").font(.system(size: 40, weight: .medium))
            Text("PEAK FOCUS")
                .font(.caption).tracking(3)
                .foregroundStyle(FlintBrand.spark)
        }
        .padding(.top, 8)
    }

    private var grantCard: some View {
        VStack(spacing: 16) {
            Text("Flint needs Screen Time access to block apps. Nothing leaves your device — no account, no analytics.")
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button {
                Task { await auth.requestAuthorization() }
            } label: {
                Text("Grant Screen Time Access").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(FlintBrand.spark)
        }
    }

    private var setupCard: some View {
        VStack(spacing: 20) {
            Button {
                showPicker = true
            } label: {
                HStack {
                    Image(systemName: "square.grid.2x2")
                    Text(vm.selectionCount == 0 ? "Choose apps & sites to block"
                         : "\(vm.selectionCount) selected")
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            if !vm.savedGroups.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(vm.savedGroups) { group in
                            Button { vm.applyGroup(group) } label: {
                                Text("\(group.name) · \(group.itemCount)").font(.caption)
                            }
                            .buttonStyle(.bordered)
                            .tint(FlintBrand.bronze)
                        }
                    }
                }
            }
            if vm.selectionCount > 0 {
                Button("Save selection as group") { showSaveGroup = true }
                    .font(.caption)
                    .foregroundStyle(FlintBrand.spark)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("Duration: \(vm.durationMinutes) min")
                    .font(.subheadline.weight(.medium))
                Stepper("", value: $vm.durationMinutes, in: 5...240, step: 5)
                    .labelsHidden()
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .leading, spacing: 8) {
                Text("Break level").font(.subheadline.weight(.medium))
                Picker("Break level", selection: $vm.breakLevel) {
                    ForEach(BreakLevel.allCases, id: \.self) { level in
                        Text(levelLabel(level)).tag(level)
                    }
                }
                .pickerStyle(.segmented)
                Text(vm.breakLevel == .hardcore
                     ? "Hardcore (Deep Focus): can't be stopped until it ends. Free in Flint."
                     : "You can stop early.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                vm.start()
            } label: {
                Text("Start Focus").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(FlintBrand.spark)
            .disabled(vm.selectionCount == 0)
        }
    }

    private func activeCard(_ active: FlintActiveSession) -> some View {
        VStack(spacing: 16) {
            Label("Focus active", systemImage: "flame.fill")
                .foregroundStyle(FlintBrand.spark)
                .font(.headline)

            if active.endsAt != nil {
                Text(timeString(vm.remaining))
                    .font(.system(size: 56, weight: .semibold, design: .rounded))
                    .monospacedDigit()
            } else {
                Text("No time limit").font(.title2)
            }

            Text("Break level: \(levelLabel(active.breakLevel))")
                .font(.caption).foregroundStyle(.secondary)

            if vm.canStopActive {
                Button(role: .destructive) { vm.stop() } label: {
                    Text("Stop").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            } else {
                VStack(spacing: 8) {
                    Label("Locked until it ends", systemImage: "lock.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .foregroundStyle(.secondary)
                    if vm.emergencyPassAvailable {
                        Button { vm.useEmergencyPass() } label: {
                            Label("Use Emergency Pass (1/week)", systemImage: "key.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(FlintBrand.bronze)
                    } else {
                        Text("Emergency Pass already used this week.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(.vertical, 8)
    }

    private func errorBanner(_ message: String) -> some View {
        Text(message)
            .font(.footnote)
            .foregroundStyle(.red)
            .multilineTextAlignment(.center)
    }

    private var footer: some View {
        Text("Free · open source · no accounts")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .padding(.top, 8)
    }

    // MARK: Helpers

    private func levelLabel(_ level: BreakLevel) -> String {
        switch level {
        case .easy: "Easy"
        case .harder: "Harder"
        case .hardcore: "Hardcore"
        }
    }

    private func timeString(_ t: TimeInterval) -> String {
        let total = Int(t.rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s)
                     : String(format: "%02d:%02d", m, s)
    }
}
