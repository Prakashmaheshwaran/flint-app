import SwiftUI
import FlintCore

/// What the PIN gate may show about protection, so the Emergency Pass and the OS recovery route
/// stay reachable while Flint is locked (ADR-012 §7, PC-50). The PIN protects private content:
/// this shows only whether a block is running, its strictness and end time, and pass status —
/// never session or rule names, apps, sites, categories or history.
struct LockedRecoveryState: Equatable {
    enum Running: Equatable {
        case none
        /// A session record exists but can't be read: say so rather than "nothing running".
        case unreadable
        case session(tier: BreakLevel, until: Date?)
    }

    var running: Running
    var passAvailable: Bool
    var nextPass: Date?

    /// With a `clock`, the running block and its end follow logical time (ADR-012 §9).
    static func make(active: FlintActiveSession?, hasRecord: Bool, passAvailable: Bool,
                     passLastUsed: Date?, now: Date, clock: FlintClock? = nil) -> Self {
        let running: Running
        if let active, active.isEnforcing(at: now, clock: clock) {
            running = .session(tier: active.breakLevel, until: clock.flatMap(active.stableLogicalEnd) ?? active.endsAt)
        } else if active == nil, hasRecord {
            running = .unreadable
        } else {
            running = .none
        }
        let next = FlintEmergencyPass.nextAvailable(lastUsed: passLastUsed).flatMap { $0 > now ? $0 : nil }
        return Self(running: running, passAvailable: passAvailable, nextPass: passAvailable ? nil : next)
    }

    static func current(now: Date = Date()) -> Self {
        let controller = FlintSessionController()
        let store = FlintGroupStore()
        // Without the App Group nothing can be read — a block may still be enforced, so say so.
        return make(active: controller.activeSession, hasRecord: store?.hasActiveSessionRecord() ?? true,
                    passAvailable: controller.emergencyPassAvailable(now: now),
                    passLastUsed: store?.emergencyPassLastUsed(), now: now, clock: .system)
    }

    /// The pass ends a running Hardcore block (the only block the app can't end otherwise).
    var canUsePass: Bool {
        guard passAvailable, case .session(.hardcore, _) = running else { return false }
        return true
    }

    var headline: String {
        switch running {
        case .none:
            return String(localized: "No Flint block is running.")
        case .unreadable:
            return String(localized: "Flint couldn't read the block that's running, so it keeps it in place.")
        case let .session(tier, nil):
            switch tier {
            case .easy: return String(localized: "An Easy block is running until you end it.")
            case .harder: return String(localized: "A Harder block is running until you end it.")
            case .hardcore: return String(localized: "A Hardcore block is running until you end it.")
            }
        case let .session(tier, until?):
            let time = until.formatted(date: .omitted, time: .shortened)
            switch tier {
            case .easy: return String(localized: "An Easy block is running until \(time).")
            case .harder: return String(localized: "A Harder block is running until \(time).")
            case .hardcore: return String(localized: "A Hardcore block is running until \(time).")
            }
        }
    }

    var passLine: String {
        if passAvailable {
            return running == .none
                ? String(localized: "Emergency Pass: available for a running Hardcore block")
                : String(localized: "Emergency Pass: available")
        }
        guard let nextPass else { return String(localized: "Emergency Pass: not available right now") }
        // Date, time and zone (ADR-012 §6).
        let when = nextPass.formatted(.dateTime.month(.abbreviated).day().hour().minute().timeZone(.specificName(.short)))
        return String(localized: "Emergency Pass: available again \(when)")
    }
}

/// The locked gate's recovery sheet: pass status and use, and the OS route that always works.
struct LockedRecoveryView: View {
    @State private var state = LockedRecoveryState.current()
    @State private var showPass = false
    @State private var passFailed = false
    let onClose: () -> Void

    var body: some View {
        ZStack {
            FlintBrand.ground.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("While Flint is locked").flintTitle(26).accessibilityAddTraits(.isHeader)
                    Text(state.headline).flintSecondary(15).fixedSize(horizontal: false, vertical: true)
                    Text(state.passLine)
                        .font(FlintFont.text(13, weight: .medium))
                        .foregroundStyle(FlintBrand.flame)
                    if state.canUsePass {
                        Button("Use Emergency Pass") { showPass = true }.buttonStyle(EmberButtonStyle())
                    }
                    if passFailed {
                        Text("The pass couldn't be used. Nothing changed.")
                            .font(FlintFont.text(13)).foregroundStyle(FlintBrand.danger)
                    }
                    Text("To lift every Flint block yourself, turn off Flint's access in Settings → Screen Time. Flint can't prevent that, and it doesn't need your PIN.")
                        .flintSecondary(13).fixedSize(horizontal: false, vertical: true)
                    Button("Back to PIN", action: onClose).buttonStyle(BoneButtonStyle())
                }
                .padding(24)
            }
            .scrollBounceBehaviorBasedOnSizeIfAvailable()
            // VoiceOver stays inside the pass sheet while it's up.
            .accessibilityHidden(showPass)
            if showPass {
                EmergencyPassSheet(
                    sessionName: "the running Hardcore block",
                    available: state.canUsePass,
                    onKeep: { showPass = false },
                    onUse: {
                        showPass = false
                        passFailed = false
                        guard state.canUsePass else { return }
                        let used = FlintSessionController().useEmergencyPass()
                        if used {
                            NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
                        }
                        passFailed = !used
                        state = LockedRecoveryState.current()
                        // Focus doesn't move when the state refreshes: say what happened.
                        let message = used ? String(localized: "Emergency Pass used. The block has ended.")
                                           : String(localized: "The pass couldn't be used. Nothing changed.")
                        DispatchQueue.main.async {
                            UIAccessibility.post(notification: .announcement, argument: message)
                        }
                    }
                )
            }
        }
        .accessibilityAction(.escape) { onClose() }
        .preferredColorScheme(.dark)
    }
}

private extension View {
    /// Rubber-banding only when content overflows (iOS 16.4+); a no-op earlier.
    @ViewBuilder func scrollBounceBehaviorBasedOnSizeIfAvailable() -> some View {
        if #available(iOS 16.4, *) {
            scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}
