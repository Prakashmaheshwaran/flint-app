import Foundation
import FamilyControls

/// Wraps `AuthorizationCenter` for the `.individual` (adult self-blocker) flow.
/// Requires iOS 16+. There is no notification when the user revokes access in Settings, so the
/// app must re-check `refresh()` whenever it becomes active.
@MainActor
final class AuthorizationModel: ObservableObject {
    @Published private(set) var status: AuthorizationStatus = .notDetermined
    @Published private(set) var lastError: String?
    @Published private(set) var isRequesting = false

    private let readStatus: () -> AuthorizationStatus
    private let request: () async throws -> Void

    init(
        readStatus: @escaping () -> AuthorizationStatus = { AuthorizationCenter.shared.authorizationStatus },
        request: @escaping () async throws -> Void = {
            #if DEBUG
            // Explicit UI-only failure fixture; never changes Screen Time authorization.
            let args = ProcessInfo.processInfo.arguments
            if let index = args.firstIndex(of: "-FlintUIPreviewAuthorizationError"), args.indices.contains(index + 1) {
                throw NSError(domain: "FlintUIPreview", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: args[index + 1]])
            }
            #endif
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
        }
    ) {
        self.readStatus = readStatus
        self.request = request
    }

    func refresh() {
        status = readStatus()
        #if DEBUG
        // `-FlintUIPreview` lets the Simulator (which can't grant Screen Time) show the approved UI.
        if ProcessInfo.processInfo.arguments.contains("-FlintUIPreview") { status = .approved }
        #endif
        if status == .approved { lastError = nil }
    }

    func requestAuthorization() async {
        guard !isRequesting else { return }
        isRequesting = true
        lastError = nil
        defer { isRequesting = false }

        do {
            try await request()
            refresh()
            if status != .approved {
                lastError = "Screen Time access is still off. Try again to allow access."
            }
        } catch {
            refresh()
            if status != .approved { lastError = error.localizedDescription }
        }
    }
}
