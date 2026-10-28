import Foundation

/// Stable persisted keys shared by strict reconciliation and legacy convenience readers.
enum FlintRuleStoreKey: String, CaseIterable {
    case schedules = "flint.rules"
    case limits = "flint.limits"
    case openLimits = "flint.openLimit.rules"
}
