import Foundation

public enum FlintRuleProtectionStatus: Equatable {
    case off, needsAttention, unverified, registered

    /// A toggle expresses intent. Only a fresh, successful observation can support current
    /// registration copy; a failure always wins, even if an older observation lists the rule.
    public static func status(
        enabled: Bool, activityName: String, domain: String,
        health: FlintArmingHealth, observation: FlintProtectionObservation?, at now: Date = Date(),
        authorized: Bool = true
    ) -> Self {
        guard enabled else { return .off }
        guard authorized else { return .needsAttention }
        let report = health.domains[domain]
        let matches: (FlintArmingHealth.Failure) -> Bool = {
            $0.activityName == activityName || $0.activityName == domain
        }
        if report?.failures.contains(where: matches) == true {
            return .needsAttention
        }
        if let observation, observation.domains[domain]?.failures.contains(where: matches) == true,
           report == nil || report!.updatedAt <= observation.observedAt {
            return .needsAttention
        }
        guard let observation, !observation.isStale(at: now), let report,
              report.observedActivities?.contains(activityName) == true else { return .unverified }
        return .registered
    }

    public var label: String {
        switch self {
        case .off: return "Off"
        case .needsAttention: return "Needs attention"
        case .unverified: return "Not verified"
        case .registered: return "Registered with iOS"
        }
    }
}
