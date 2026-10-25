#if DEBUG
import Foundation
import FamilyControls

/// Explicit UI-only fixtures. They never read, grant, revoke, or persist system authorization.
@MainActor
final class AuthorizationPreview {
    private(set) var status: AuthorizationStatus = .notDetermined
    private let scenario: String
    private let errorMessage: String?
    private var attempts = 0

    init?(arguments: [String]) {
        func value(after flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
            return arguments[index + 1]
        }
        errorMessage = value(after: "-FlintUIPreviewAuthorizationError")
        if arguments.contains("-FlintUIPreview") {
            scenario = "approved"
            status = .approved
        } else if let name = value(after: "-FlintUIPreviewAuthorizationScenario"),
                  ["denied", "transient", "hung", "timeout", "cancelled"].contains(name) {
            scenario = name
            if name == "denied" { status = .denied }
        } else if errorMessage != nil {
            scenario = "error"
        } else {
            return nil
        }
    }

    func request() async throws {
        attempts += 1
        if scenario == "cancelled" { throw CancellationError() }
        if scenario == "error" || (scenario == "transient" && attempts == 1) {
            throw NSError(domain: "FlintUIPreview", code: 1, userInfo: [
                NSLocalizedDescriptionKey: errorMessage ?? "Screen Time could not finish this request. Please try again."
            ])
        }
        if (scenario == "hung" || scenario == "timeout") && attempts == 1 {
            try await Task.sleep(nanoseconds: 3_600_000_000_000)
        }
        status = .approved
    }

    func waitForTimeout() async throws {
        try await Task.sleep(nanoseconds: scenario == "timeout" ? 2_000_000_000 : 120_000_000_000)
    }
}
#endif
