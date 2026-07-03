import SwiftUI
import FlintCore

@main
struct FlintApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Top-level shell: an optional app-PIN gate, then the tabs.
struct RootView: View {
    @State private var locked = FlintPIN.isSet(FlintGroupStore())

    var body: some View {
        if locked {
            PINGateView { locked = false }
        } else {
            TabView {
                ContentView()
                    .tabItem { Label("Focus", systemImage: "flame.fill") }
                SchedulesView()
                    .tabItem { Label("Schedules", systemImage: "calendar") }
                LimitsView()
                    .tabItem { Label("Limits", systemImage: "hourglass") }
                StatsView()
                    .tabItem { Label("Stats", systemImage: "chart.bar.fill") }
                SettingsView()
                    .tabItem { Label("Settings", systemImage: "gearshape.fill") }
            }
            .tint(FlintBrand.spark)
        }
    }
}

struct PINGateView: View {
    let onUnlock: () -> Void
    @State private var pin = ""
    @State private var showError = false

    var body: some View {
        VStack(spacing: 20) {
            Text("Flint").font(.system(size: 40, weight: .medium))
            Text("Enter PIN").foregroundStyle(.secondary)
            SecureField("PIN", text: $pin)
                .textFieldStyle(.roundedBorder)
                .frame(width: 160)
                .multilineTextAlignment(.center)
            if showError {
                Text("Incorrect PIN").font(.footnote).foregroundStyle(.red)
            }
            Button("Unlock") {
                if FlintPIN.verify(pin, FlintGroupStore()) {
                    onUnlock()
                } else {
                    showError = true
                    pin = ""
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(FlintBrand.spark)
        }
        .padding(40)
    }
}
