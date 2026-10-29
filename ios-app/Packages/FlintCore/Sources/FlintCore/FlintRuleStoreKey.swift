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
}
