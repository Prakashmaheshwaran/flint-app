import SwiftUI
import FlintCore

// Test-only host: compile the production form unchanged and keep it mounted while
// driving the real lock object. Production privacy protection normally hides it.
@main
struct PINFormPhaseApp: App {
    @StateObject private var fixture = PhaseFixture()

    init() {
        FlintFont.registerAll()
        FlintAppearance.apply()
    }

    var body: some Scene {
        WindowGroup {
            PhaseFixtureView(fixture: fixture, appLock: fixture.appLock)
                .preferredColorScheme(.dark)
        }
    }
}

@MainActor
final class PhaseFixture: ObservableObject {
    let suiteName = "com.flint.audit.f3-review-form." + UUID().uuidString
    let store: FlintGroupStore
    let appLock: FlintAppLock
    @Published var attempts = 0
    @Published var lastResult = "none"
    private var savedPIN: String?

    init() {
        store = FlintGroupStore(suiteName: suiteName)!
        appLock = FlintAppLock(store: store)
        appLock.transition(to: .active)
    }

    func save(_ pin: String) -> Bool {
        attempts += 1
        let accepted = appLock.savePIN(pin)
        lastResult = accepted ? "saved" : "rejected"
        if accepted { savedPIN = pin }
        return accepted
    }

    func prepareLocked() {
        guard appLock.savePIN("2468") else { lastResult = "seed failed"; return }
        savedPIN = "2468"
        appLock.transition(to: .background)
        appLock.transition(to: .active)
    }

    func unlockAndAuthorize() {
        guard appLock.unlock(with: "2468"), appLock.authorizePINChange(currentPIN: "2468") else {
            lastResult = "authorization failed"
            return
        }
        lastResult = "authorized"
    }

    func cleanup() {
        if let pin = savedPIN {
            appLock.transition(to: .active)
            if appLock.isLocked { _ = appLock.unlock(with: pin) }
            guard appLock.removePIN(currentPIN: pin) else { lastResult = "cleanup failed"; return }
        }
        guard store.pinRecord() == nil else { lastResult = "cleanup failed"; return }
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
        savedPIN = nil
        lastResult = "clean"
    }
}

private struct PhaseFixtureView: View {
    @ObservedObject var fixture: PhaseFixture
    @ObservedObject var appLock: FlintAppLock

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button("Active") { appLock.transition(to: .active) }.accessibilityIdentifier("fixture.active")
                Button("Inactive") { appLock.transition(to: .inactive) }.accessibilityIdentifier("fixture.inactive")
                Button("Background") { appLock.transition(to: .background) }.accessibilityIdentifier("fixture.background")
            }
            HStack {
                Button("Lock") { fixture.prepareLocked() }.accessibilityIdentifier("fixture.lock")
                Button("Authorize") { fixture.unlockAndAuthorize() }.accessibilityIdentifier("fixture.authorize")
                Button("Cleanup") { fixture.cleanup() }.accessibilityIdentifier("fixture.cleanup")
            }
            Text("phase=\(String(describing: appLock.state.phase)); locked=\(appLock.isLocked); attempts=\(fixture.attempts); result=\(fixture.lastResult); pin=\(appLock.pinIsSet)")
                .font(.caption)
                .accessibilityIdentifier("fixture.status")
            PINSetupView(appLock: appLock, onSave: fixture.save)
        }
        .padding(.top, 8)
    }
}
