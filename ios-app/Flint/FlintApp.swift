import SwiftUI
import FlintCore

@main
struct FlintApp: App {
    init() {
        FlintFont.registerAll()
        FlintAppearance.apply()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
    }
}

/// Top-level shell: an optional app-PIN gate, first-run onboarding, then the four tabs in a
/// floating bar. A running session (and its Complete screen) covers every tab.
struct RootView: View {
    @State private var locked = FlintPIN.isSet(FlintGroupStore())
    @AppStorage("flint.onboarded") private var onboarded = false
    @StateObject private var auth = AuthorizationModel()
    @StateObject private var session = SessionViewModel()
    @State private var tab: FlintTab = .today
    @State private var showStart = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if locked {
                PINGateView { locked = false }
            } else if !onboarded && auth.status != .approved {
                OnboardingView(auth: auth) { onboarded = true }
            } else {
                tabs
            }
        }
        .onAppear { auth.refresh() }
        .onChange(of: scenePhase) { phase in if phase == .active { auth.refresh() } }
    }

    private var tabs: some View {
        VStack(spacing: 0) {
            Group {
                switch tab {
                case .today: TodayView(auth: auth, vm: session, showStart: $showStart)
                case .rules: RulesView()
                case .insights: StatsView(auth: auth)
                case .you: SettingsView()
                }
            }
            .frame(maxWidth: .infinity, minHeight: 0, maxHeight: .infinity)
            .clipped()

            // Give navigation-backed content real bounds above the measured bar.
            FlintTabBar(selection: $tab)
                .fixedSize(horizontal: false, vertical: true)
        }
        .background(FlintBrand.ground.ignoresSafeArea())
        .ignoresSafeArea(.keyboard)
        .fullScreenCover(isPresented: Binding(get: { session.active != nil }, set: { _ in })) {
            SessionView(vm: session)
        }
        .fullScreenCover(item: Binding(
            get: { session.finished.map(FinishedItem.init) },
            set: { if $0 == nil { session.finished = nil } }
        )) { item in
            SessionCompleteView(finished: item.value, onDone: { session.finished = nil }, onAgain: {
                session.finished = nil
                tab = .today
                showStart = true
            })
        }
        .task {
            // Re-arm Open-Limit shields (+ their day-boundary activities) on every launch —
            // that's what puts tokens released by yesterday's grants back behind the shield.
            // Schedules/Time Limits re-arm in their tab view models; Open Limits' screen sits
            // a level deeper, so its view model may never init in a given run.
            FlintOpenLimitsController().reload()
            // Reconcile the Hardcore uninstall guard with reality on every launch:
            // re-assert it while a Hardcore session is still running, drop it if the
            // session ended while Flint was dead (e.g. the monitor never got to fire).
            FlintSessionController().reconcileUninstallGuard()
        }
    }
}

private struct FinishedItem: Identifiable {
    let value: SessionViewModel.Finished
    var id: String { "\(value.name)-\(value.minutes)" }
}

struct PINGateView: View {
    let onUnlock: () -> Void
    @State private var pin = ""
    @State private var showError = false

    var body: some View {
        ZStack {
            EmberLandscape(horizonOffset: 20, orbCenter: CGPoint(x: 195, y: 480), orbRadius: 64)
            VStack(spacing: 14) {
                FlintMark(size: 40)
                Text("Flint").font(FlintFont.display(48)).tracking(-0.8).foregroundStyle(FlintBrand.bone)
                Text("Enter PIN").flintSecondary(15)
                SecureField("PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .font(FlintFont.display(28))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(FlintBrand.bone)
                    .frame(width: 180, height: 52)
                    .overlay(alignment: .bottom) { FlintHairline(opacity: 0.16) }
                if showError {
                    Text("Incorrect PIN").font(FlintFont.text(13)).foregroundStyle(FlintBrand.danger)
                }
                Spacer()
                Button("Unlock") {
                    if FlintPIN.verify(pin, FlintGroupStore()) {
                        onUnlock()
                    } else {
                        showError = true
                        pin = ""
                    }
                }
                .buttonStyle(EmberButtonStyle())
            }
            .padding(.top, 110)
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
        }
    }
}
