import SwiftUI
import FlintCore

struct FlintRemovalGuardSetting: View {
    @State private var status = FlintRemovalGuardSettings.Status()
    @State private var busy = true
    @State private var showDisclosure = false
    @State private var feedback: String?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(isOn: Binding(get: { status.enabled }, set: { enabled in
                if enabled { showDisclosure = true } else { change(false) }
            })) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Restrict app deletion during Hardcore").flintRowTitle()
                    Text(status.label).flintSecondary(13)
                }
            }
            .tint(FlintBrand.ember)
            .disabled(busy || status.unavailable)
            .accessibilityIdentifier("settings.removalGuard.toggle")
            if status.pendingOff {
                Text("Turning off when the current Hardcore commitments end. New blocks will not request this guard.")
                    .flintSecondary(13)
                Button("Keep app deletion restriction on") { showDisclosure = true }
                    .font(FlintFont.text(15, weight: .medium))
                    .foregroundStyle(FlintBrand.bone)
                    .frame(minHeight: 44)
                    .disabled(busy || status.unavailable)
                    .accessibilityIdentifier("settings.removalGuard.cancelPendingOff")
            }
            if let feedback { Text(feedback).flintSecondary(13) }
            Text("Applies to app deletion across this device. The status reflects Screen Time configuration; behavior varies by iOS version. Recovery remains in You → Emergency Pass and iOS Settings → Screen Time.")
                .flintSecondary(13)
        }
        .padding(.vertical, 16)
        .overlay(alignment: .top) { FlintHairline() }
        .confirmationDialog("Restrict app deletion?", isPresented: $showDisclosure, titleVisibility: .visible) {
            Button("Enable for future Hardcore blocks") { change(true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(FlintRemovalGuardSettings.disclosure)
        }
        .task { await refresh() }
        .onChange(of: scenePhase) { if $0 == .active { Task { await refresh() } } }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in
            Task { await refresh() }
        }
    }

    @MainActor private func refresh() async {
        busy = true
        status = await Task.detached(priority: .userInitiated) { FlintRemovalGuardSettings().status() }.value
        busy = false
    }

    @MainActor private func change(_ enabled: Bool) {
        guard !busy else { return }
        busy = true
        Task {
            let result = await Task.detached(priority: .userInitiated) {
                FlintRemovalGuardSettings().request(enabled: enabled, disclosureAccepted: enabled)
            }.value
            switch result {
            case .saved: feedback = nil
            case .deferred: feedback = "Off requested. Current Hardcore commitments keep their accepted guard until they end."
            case .unavailable: feedback = "Protection state is unavailable. Your saved preference was retained. Try again after recovery."
            case .disclosureRequired: feedback = "Read and accept the disclosure before enabling."
            }
            await refresh()
        }
    }
}
