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
    @StateObject private var auth = AuthorizationModel(readStatus: { .approved })
    @StateObject private var vm = SessionViewModel()
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
            .frame(width: 320, height: 568)
            .clipped()
            .overlay(alignment: .topLeading) {
                ViewportMarker(identifier: "f4.viewport.topLeft").frame(width: 2, height: 2).allowsHitTesting(false)
            }
            .overlay(alignment: .bottomTrailing) {
                ViewportMarker(identifier: "f4.viewport.bottomRight").frame(width: 2, height: 2).allowsHitTesting(false)
            }
            .overlay(Rectangle().stroke(.gray, lineWidth: 1).allowsHitTesting(false))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
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
