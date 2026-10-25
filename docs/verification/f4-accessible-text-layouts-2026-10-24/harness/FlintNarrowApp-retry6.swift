import SwiftUI
import UIKit
import FlintCore

/// Temporary host for actual production views at 320 × 568 points; no entitlements.
@main
struct FlintNarrowApp: App {
    init() {
        FlintFont.registerAll()
        FlintAppearance.apply()
    }
    var body: some Scene { WindowGroup { NarrowRoot() } }
}

struct NarrowRoot: View {
    private func argument(_ key: String) -> String {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: key), args.indices.contains(i + 1) else { return "" }
        return args[i + 1]
    }
    var body: some View {
        let screen = argument("-F4Screen")
        let size: DynamicTypeSize = argument("-F4TextSize") == "ax5" ? .accessibility5 : .large
        VStack(spacing: 12) {
            Text("320 × 568 pt · \(screen) · \(argument("-F4TextSize"))")
                .font(.system(size: 14)).foregroundStyle(.white)
            Group {
                if argument("-F4Host") == "embedded" {
                    HostedProductionRoot(screen: screen, size: size)
                } else {
                    IndependentWindowViewport { HostedProductionRoot(screen: screen, size: size) }
                }
            }
            .frame(width: 320, height: 568)
            .overlay(Rectangle().stroke(.gray, lineWidth: 1).allowsHitTesting(false))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
        .preferredColorScheme(.dark)
    }
}

/// The controller's own window has the tested bounds, removing the outer scene's safe area.
struct IndependentWindowViewport<Content: View>: UIViewRepresentable {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    final class Coordinator { var window: UIWindow? }
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIView { UIView() }
    func updateUIView(_ view: UIView, context: Context) {
        DispatchQueue.main.async {
            guard context.coordinator.window == nil,
                  let scene = view.window?.windowScene,
                  view.bounds.width == 320, view.bounds.height == 568 else { return }
            let frame = view.convert(view.bounds, to: nil)
            let window = UIWindow(windowScene: scene)
            window.frame = frame
            window.windowLevel = .normal + 1
            window.backgroundColor = .black
            window.rootViewController = UIHostingController(rootView: content)
            context.coordinator.window = window
            window.makeKeyAndVisible()
            window.frame = frame
            print("F4-INDEPENDENT-WINDOW frame=\(window.frame) bounds=\(window.bounds) safeArea=\(window.safeAreaInsets)")
        }
    }
    static func dismantleUIView(_ view: UIView, coordinator: Coordinator) {
        coordinator.window?.isHidden = true
        coordinator.window = nil
    }
}

/// Give SwiftUI a real local view-controller boundary for safe-area propagation.
struct NativeViewport<Content: View>: UIViewControllerRepresentable {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    func makeUIViewController(context: Context) -> UIViewController {
        let container = UIViewController()
        container.view.backgroundColor = .clear
        container.view.clipsToBounds = true
        let host = UIHostingController(rootView: content)
        host.view.backgroundColor = .clear
        host.view.translatesAutoresizingMaskIntoConstraints = false
        container.addChild(host)
        container.view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.topAnchor.constraint(equalTo: container.view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: container.view.bottomAnchor),
            host.view.leadingAnchor.constraint(equalTo: container.view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: container.view.trailingAnchor),
        ])
        host.didMove(toParent: container)
        return container
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct HostedProductionRoot: View {
    let screen: String
    let size: DynamicTypeSize
    @StateObject private var auth = AuthorizationModel(readStatus: { .approved })
    @StateObject private var vm = SessionViewModel()
    var body: some View {
        Group {
                switch screen {
                case "today": TodayView(auth: auth, vm: vm, showStart: .constant(false))
                case "start": StartFocusSheet(vm: vm, onClose: {})
                case "rules": RulesView()
                case "you": SettingsView()
                default: StatsView(auth: auth)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if screen != "start" { FlintTabBar(selection: .constant(FlintTab(rawValue: screen) ?? .today)) }
            }
            .environment(\.dynamicTypeSize, size)
            .clipped()
            .overlay(alignment: .topLeading) {
                ViewportMarker(identifier: "f4.viewport.topLeft").frame(width: 2, height: 2).allowsHitTesting(false)
            }
            .overlay(alignment: .bottomTrailing) {
                ViewportMarker(identifier: "f4.viewport.bottomRight").frame(width: 2, height: 2).allowsHitTesting(false)
            }
            .preferredColorScheme(.dark)
            .onAppear { vm.durationMinutes = 240; auth.refresh() }
    }
}

/// A leaf UIKit element measures the actual fixed host, never its descendants' union.
struct ViewportMarker: UIViewRepresentable {
    let identifier: String
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = true
        view.accessibilityIdentifier = identifier
        view.accessibilityLabel = "Viewport corner"
        return view
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
