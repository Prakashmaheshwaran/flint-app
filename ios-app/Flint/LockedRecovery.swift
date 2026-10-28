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

    static func make(active: FlintActiveSession?, hasRecord: Bool, passAvailable: Bool,
                     passLastUsed: Date?, now: Date) -> Self {
        let running: Running
        if let active, !active.isExpired(at: now) {
            running = .session(tier: active.breakLevel, until: active.endsAt)
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
        return make(active: controller.activeSession, hasRecord: store?.hasActiveSessionRecord() ?? false,
                    passAvailable: controller.emergencyPassAvailable(now: now),
                    passLastUsed: store?.emergencyPassLastUsed(), now: now)
    }

    /// The pass ends a running Hardcore block (the only block the app can't end otherwise).
    var canUsePass: Bool {
        guard passAvailable, case .session(.hardcore, _) = running else { return false }
        return true
    }

    var headline: String {
        switch running {
        case .none:
            return "No Flint block is running."
        case .unreadable:
            return "Flint couldn't read the block that's running, so it keeps it in place."
        case let .session(tier, until):
            let label: String
            switch tier {
            case .easy: label = "An Easy"
            case .harder: label = "A Harder"
            case .hardcore: label = "A Hardcore"
            }
            guard let until else { return "\(label) block is running until you end it." }
            return "\(label) block is running until \(until.formatted(date: .omitted, time: .shortened))."
        }
    }

    var passLine: String {
        if passAvailable { return "Emergency Pass: available" }
        guard let nextPass else { return "Emergency Pass: not available right now" }
        return "Emergency Pass: available again \(nextPass.formatted(date: .abbreviated, time: .shortened))"
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
                        .foregroundStyle(Color(hex: 0xF4C27E))
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
            if showPass {
                EmergencyPassSheet(
                    sessionName: "the running Hardcore block",
                    available: state.canUsePass,
                    onKeep: { showPass = false },
                    onUse: {
                        showPass = false
                        if FlintSessionController().useEmergencyPass() {
                            NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
                        } else {
                            passFailed = true
                        }
                        state = LockedRecoveryState.current()
                    }
                )
            }
        }
        .preferredColorScheme(.dark)
    }
}
