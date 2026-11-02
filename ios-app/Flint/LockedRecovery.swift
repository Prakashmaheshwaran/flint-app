import SwiftUI
import FlintCore

/// What the PIN gate may show about protection, so the Emergency Pass and the OS recovery route
/// stay reachable while Flint is locked (ADR-012 §7, PC-50). The PIN protects private content:
/// this shows only whether a block is running, its strictness and end time, and pass status —
/// never session or rule names, apps, sites, categories or history.
struct LockedRecoveryState: Equatable, Sendable {
    enum Running: Equatable, Sendable {
        case none
        /// A session record exists but can't be read: say so rather than "nothing running".
        case unreadable
        case session(tier: BreakLevel, until: Date?)
    }

    var running: Running
    var eligibility: FlintPassEligibility?
    var nextPass: Date?
    var recoveryUntil: Date?
    var timeUncertain: Bool
    var needsApplication: Bool

    /// With a `clock`, the running block and its end follow logical time (ADR-012 §9).
    static func make(active: FlintActiveSession?, hasRecord: Bool, pass: FlintPassStatus?,
                     nextPass: Date? = nil, recoveryUntil: Date? = nil,
                     timeUncertain: Bool = false, needsApplication: Bool = false,
                     now: Date, clock: FlintClock? = nil) -> Self {
        let running: Running
        if let active, active.isEnforcing(at: now, clock: clock) {
            running = .session(tier: active.breakLevel, until: clock.flatMap(active.stableLogicalEnd) ?? active.endsAt)
        } else if active == nil, hasRecord {
            running = .unreadable
        } else {
            running = .none
        }
        return Self(running: running, eligibility: pass?.eligibility, nextPass: nextPass,
                    recoveryUntil: recoveryUntil, timeUncertain: timeUncertain, needsApplication: needsApplication)
    }

    static func current(now: Date = Date()) -> Self {
        let controller = FlintSessionController()
        let store = FlintGroupStore()
        // Without the App Group nothing can be read — a block may still be enforced, so say so.
        let status = try? FlintEmergencyPassController().status().get()
        return make(active: controller.activeSession, hasRecord: store?.hasActiveSessionRecord() ?? true,
                    pass: status?.pass, nextPass: status?.nextAvailableAt,
                    recoveryUntil: status?.recoveryUntil, timeUncertain: status?.timeUncertain ?? false,
                    needsApplication: status?.needsApplication ?? false, now: now, clock: .system)
    }

    /// Any known active personal commitment can recover, including rules without a session.
    var canUsePass: Bool {
        eligibility == .available && running != .unreadable && recoveryUntil == nil && !needsApplication
    }

    var headline: String {
        switch running {
        case .none:
            if recoveryUntil != nil { return "Personal protection is suspended for recovery." }
            return canUsePass ? "Personal commitments are active." : "No active focus session is recorded."
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
        if needsApplication { return "Saved recovery needs to finish applying" }
        if let recoveryUntil { return "Recovery until \(FlintEmergencyPassStatus.dateText(recoveryUntil))" }
        guard let eligibility, eligibility != .unavailable else { return "Protection state unavailable" }
        if let nextPass {
            return "Next pass: \(FlintEmergencyPassStatus.dateText(nextPass))\(timeUncertain ? " (adjusted estimate after restart or clock change)" : "")"
        }
        return "Emergency Pass: Available"
    }
}

/// The locked gate's recovery sheet: pass status and use, and the OS route that always works.
struct LockedRecoveryView: View {
    @State private var state = LockedRecoveryState.make(active: nil, hasRecord: true, pass: nil, now: Date())
    @State private var refreshID = UUID()
    @State private var showPass = false
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
                    Button(state.recoveryUntil == nil ? "Emergency Pass" : "Manage recovery") { showPass = true }
                        .buttonStyle(EmberButtonStyle())
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
                    onKeep: { showPass = false },
                    onApplied: {
                        refresh()
                        DispatchQueue.main.async {
                            UIAccessibility.post(notification: .announcement, argument: "Protection state updated.")
                        }
                    }, redacted: true
                )
            }
        }
        .accessibilityAction(.escape) { onClose() }
        .preferredColorScheme(.dark)
        .onAppear { refresh() }
    }

    private func refresh() {
        let request = UUID(); refreshID = request
        Task {
            let current = await Task.detached(priority: .utility) { LockedRecoveryState.current() }.value
            guard refreshID == request else { return }
            state = current
        }
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
