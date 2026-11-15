import Foundation

/// Stable persisted keys shared by strict reconciliation and legacy convenience readers.
enum FlintRuleStoreKey: String, CaseIterable {
    case schedules = "flint.rules"
    case limits = "flint.limits"
    case openLimits = "flint.openLimit.rules"

    var domain: String {
        switch self { case .schedules: return "schedules"; case .limits: return "limits"; case .openLimits: return "openLimits" }
    }
    var prefix: String {
        switch self { case .schedules: return "flint.rule."; case .limits: return "flint.limit."; case .openLimits: return "flint.openLimit." }
    }
    var marker: String { rawValue + ".initialized.adr012" }
    var original: String { rawValue + ".original.adr012" }
    /// Write-once evidence that a pre-marker domain existed, without migrating its policy.
    var ownership: String { rawValue + ".owned.adr012" }
    /// Write-once proof that legacy initialization found this domain absent with no evidence
    /// and wrote its empty array itself (R2p2-N03). Only the Open-credit import reads it.
    var neverUsed: String { rawValue + ".neverUsed.adr012" }
}
