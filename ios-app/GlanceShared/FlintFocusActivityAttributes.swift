import ActivityKit
import Foundation

@available(iOS 16.1, *)
struct FlintFocusActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        let cause: String
        let tier: String
        let startedAt: Date
        let endsAt: Date
        let staleAt: Date
        /// Explicitly ended displays never use a completion outcome.
        let needsCheck: Bool

        var countdownEnd: Date { min(endsAt, staleAt) }
    }
    let sessionID: String
}
