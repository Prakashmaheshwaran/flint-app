import Foundation
#if canImport(FamilyControls)
import FamilyControls
import ManagedSettings

extension FlintProtectionStatus {
    /// Caller-owned model/selection snapshots used only in-process. No Codable conformance,
    /// storage, token derivation or mutable picker lookup crosses the shared DTO boundary.
    public struct CoverageInputs {
        public var protection: FlintProtectionStatusBuilder.Inputs
        /// The active session's frozen selection, never the next session's picker selection.
        public var sessionSelection: FamilyActivitySelection?
        public var sessionAllowListMode: Bool
        public var schedules: [FlintScheduleRule]
        public var timeLimits: [FlintLimitRule]
        public var openLimits: [FlintOpenLimitRule]

        public init(protection: FlintProtectionStatusBuilder.Inputs,
                    sessionSelection: FamilyActivitySelection? = nil, sessionAllowListMode: Bool = false,
                    schedules: [FlintScheduleRule] = [], timeLimits: [FlintLimitRule] = [],
                    openLimits: [FlintOpenLimitRule] = []) {
            self.protection = protection
            self.sessionSelection = sessionSelection
            self.sessionAllowListMode = sessionAllowListMode
            self.schedules = schedules
            self.timeLimits = timeLimits
            self.openLimits = openLimits
        }
    }

    /// Exact alias of the existing counter-key encoding; no second hashing algorithm.
    public static func targetKey(for token: ApplicationToken) -> String { FlintOpenLimit.key(for: token) }
    public static func targetKey(for token: WebDomainToken) -> String { FlintOpenLimit.key(for: token) }

    /// Pure scope adapter shared by app/detail and shield callers. Opaque categories cannot
    /// prove app membership, so their scope is unknown and contributors reports uncertainty.
    /// Missing frozen selections are unknown too, never replaced with the mutable picker.
    public static func coverageScopes(for inputs: CoverageInputs) -> [String: TargetScope] {
        let snapshot = FlintProtectionStatusBuilder.make(inputs.protection)
        guard snapshot.state != .unavailable else { return [:] }
        let activeIDs = Set(snapshot.causes.map(\.id))
        var result = Dictionary(uniqueKeysWithValues: activeIDs.map { ($0, TargetScope.unknown) })
        func scope(_ selection: FamilyActivitySelection, allowList: Bool = false) -> TargetScope {
            guard selection.categoryTokens.isEmpty else { return .unknown }
            let apps = Set(selection.applicationTokens.map { targetKey(for: $0) })
            if allowList {
                // The production allow-list adapter exempts applications only.
                return .allExcept(apps)
            }
            let sites = Set(selection.webDomainTokens.map { targetKey(for: $0) })
            return .only(apps.union(sites))
        }
        if let id = inputs.protection.activeSession?.session.id, activeIDs.contains(id),
           let selection = inputs.sessionSelection {
            result[id] = scope(selection, allowList: inputs.sessionAllowListMode)
        }
        for rule in inputs.schedules where activeIDs.contains(rule.id) {
            result[rule.id] = scope(rule.selection, allowList: rule.allowListMode)
        }
        for rule in inputs.timeLimits where activeIDs.contains(rule.id) {
            result[rule.id] = scope(rule.selection)
        }
        for rule in inputs.openLimits where activeIDs.contains(rule.id) {
            result[rule.id] = scope(rule.selection)
        }
        return result
    }
}
#endif
