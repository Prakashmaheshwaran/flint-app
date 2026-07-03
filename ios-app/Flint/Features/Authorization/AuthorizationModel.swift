import Foundation
import FamilyControls

/// Wraps `AuthorizationCenter` for the `.individual` (adult self-blocker) flow.
/// Requires iOS 16+. There is no notification when the user revokes access in Settings, so the
/// app must re-check `refresh()` whenever it becomes active.
@MainActor
final class AuthorizationModel: ObservableObject {
    @Published private(set) var status: AuthorizationStatus = .notDetermined
    @Published var lastError: String?

    func refresh() {
        status = AuthorizationCenter.shared.authorizationStatus
    }

    func requestAuthorization() async {
        do {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
        refresh()
    }
}
