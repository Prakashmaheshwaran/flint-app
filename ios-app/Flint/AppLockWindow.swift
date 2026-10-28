import Combine
import SwiftUI
import UIKit
import FlintCore

extension FlintAppLock.Phase {
    /// The lock phase implied by UIKit's authoritative scene activation state.
    init(sceneActivationState: UIScene.ActivationState) {
        switch sceneActivationState {
        case .foregroundActive: self = .active
        case .background: self = .background
        case .foregroundInactive, .unattached: self = .inactive
        @unknown default: self = .inactive
        }
    }
}

/// Live activation state of the scene hosting the app. SwiftUI can deliver a coalesced
/// `.background` after the scene has already reactivated; this is the authority used to
/// reject that stale report and keep the gate usable.
@MainActor
enum AppLockSceneState {
    static var phase: FlintAppLock.Phase? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first
            .map { FlintAppLock.Phase(sceneActivationState: $0.activationState) }
    }
}

/// A separate scene window keeps the gate above navigation, sheets, alerts and session
/// covers. A root SwiftUI overlay alone would sit *under* those presentations.
struct AppLockWindow: UIViewRepresentable {
    let lock: FlintAppLock

    func makeCoordinator() -> Coordinator { Coordinator(lock: lock) }

    func makeUIView(context: Context) -> WindowAnchor {
        let view = WindowAnchor()
        view.didAttach = { [weak coordinator = context.coordinator] window in
            coordinator?.attach(to: window)
        }
        return view
    }

    func updateUIView(_ uiView: WindowAnchor, context: Context) {}

    static func dismantleUIView(_ uiView: WindowAnchor, coordinator: Coordinator) {
        coordinator.detach()
    }

    final class WindowAnchor: UIView {
        var didAttach: ((UIWindow) -> Void)?
        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let window { didAttach?(window) }
        }
    }

    @MainActor
    final class Coordinator {
        private let lock: FlintAppLock
        private weak var sourceWindow: UIWindow?
        private var gateWindow: UIWindow?
        private var privacyCurtain: UIView?
        private var observation: AnyCancellable?
        private var notifications: [NSObjectProtocol] = []

        init(lock: FlintAppLock) { self.lock = lock }

        func attach(to source: UIWindow) {
            guard sourceWindow !== source, let scene = source.windowScene else { return }
            detach()
            sourceWindow = source
            let window = UIWindow(windowScene: scene)
            window.windowLevel = .alert + 1
            window.backgroundColor = UIColor(FlintBrand.ground)
            window.overrideUserInterfaceStyle = .dark
            window.accessibilityViewIsModal = true
            let host = UIHostingController(rootView: GateContent(lock: lock))
            window.rootViewController = host
            gateWindow = window

            // UIKit snapshots shortly after didEnterBackground. Cover synchronously in
            // willDeactivate, without waiting for SwiftUI's next render or an animation.
            let curtain = UIView(frame: host.view.bounds)
            curtain.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            curtain.backgroundColor = UIColor(FlintBrand.ground)
            curtain.isAccessibilityElement = true
            curtain.accessibilityLabel = String(localized: "Flint is hidden")
            host.view.addSubview(curtain)
            privacyCurtain = curtain

            observe(UIScene.willDeactivateNotification, scene: scene, phase: .inactive)
            observe(UIScene.didEnterBackgroundNotification, scene: scene, phase: .background)
            observe(UIScene.willEnterForegroundNotification, scene: scene, phase: .inactive)
            observe(UIScene.didActivateNotification, scene: scene, phase: .active)
            let phase: FlintAppLock.Phase = scene.activationState == .foregroundActive ? .active
                : scene.activationState == .background ? .background : .inactive
            lock.transition(to: phase)
            render(lock.state)
            // @Published emits before storage changes. Window/key/keyboard operations can
            // synchronously trigger lifecycle callbacks, so never run them in that willSet.
            // Read the latest committed state when delivered, not a queued stale phase.
            observation = lock.$state.receive(on: DispatchQueue.main).sink { [weak self] _ in
                guard let self else { return }
                self.render(self.lock.state)
            }
        }

        private func observe(_ name: Notification.Name, scene: UIScene, phase: FlintAppLock.Phase) {
            notifications.append(NotificationCenter.default.addObserver(
                forName: name, object: scene, queue: .main
            ) { [weak self] _ in
                guard let self else { return }
                // Cross-check the notification's implied phase against the scene's live
                // activation state: a late/coalesced background report must not lock a
                // foregrounded app into an unusable gate.
                let observed = FlintAppLock.Phase(sceneActivationState: scene.activationState)
                self.lock.transition(to: phase, ifSceneIs: observed)
                // Snapshot protection still happens synchronously before UIKit returns
                // from a background/deactivation notification.
                self.render(self.lock.state)
            })
        }

        private func render(_ state: FlintAppLock.State) {
            guard let window = gateWindow, let source = sourceWindow else { return }
            source.accessibilityElementsHidden = state.isProtected
            source.isUserInteractionEnabled = !state.isProtected
            privacyCurtain?.isHidden = state.phase == .active
            if state.isProtected {
                if window.isHidden { source.endEditing(true) }
                window.isHidden = false
                if state.phase == .active && !window.isKeyWindow { window.makeKey() }
                if let curtain = privacyCurtain {
                    window.rootViewController?.view.bringSubviewToFront(curtain)
                }
                window.layoutIfNeeded()
            } else {
                guard !window.isHidden else { return }
                window.endEditing(true)
                let wasKey = window.isKeyWindow
                window.isHidden = true
                if wasKey { source.makeKey() }
            }
        }

        func detach() {
            observation = nil
            notifications.forEach(NotificationCenter.default.removeObserver)
            notifications.removeAll()
            sourceWindow?.accessibilityElementsHidden = false
            sourceWindow?.isUserInteractionEnabled = true
            let wasKey = gateWindow?.isKeyWindow == true
            gateWindow?.isHidden = true
            if wasKey { sourceWindow?.makeKey() }
            gateWindow = nil
            privacyCurtain = nil
            sourceWindow = nil
        }
    }
}

private struct GateContent: View {
    @ObservedObject var lock: FlintAppLock

    var body: some View {
        Group {
            if lock.isLocked && lock.state.phase == .active {
                PINGateView(lock: lock)
            } else {
                FlintBrand.ground.ignoresSafeArea()
            }
        }
        .preferredColorScheme(.dark)
    }
}
