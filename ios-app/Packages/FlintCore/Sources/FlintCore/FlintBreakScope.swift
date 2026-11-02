import Foundation

/// Frozen local token bytes, never names, category members, or status/export identifiers.
/// The native adapter must encode each token canonically (sorted JSON keys) before lookup.
public struct FlintBreakScope: Codable, Equatable, Sendable {
    /// Bounds the aggregate opaque payload as well as individual tokens (base64 adds ~1/3).
    public static let maximumTokenBytes = 262_144
    public struct Target: Codable, Hashable, Sendable {
        public enum Kind: String, Codable, Sendable { case application, webDomain, category }
        public let kind: Kind
        public let token: Data
        public init(kind: Kind, token: Data) { self.kind = kind; self.token = token }
        private enum CodingKeys: String, CodingKey { case kind, token }
        var key: String { kind.rawValue + ":" + token.base64EncodedString() }
    }

    public let targets: Set<Target>
    public init(targets: Set<Target>) { self.targets = targets }
    private enum CodingKeys: String, CodingKey { case targets }
    /// The UI must disclose these as whole categories, never as single-app access (PC-24).
    public var wholeCategories: Set<Target> { targets.filter { $0.kind == .category } }
    var isValid: Bool {
        guard !targets.isEmpty && targets.count <= 4096 else { return false }
        var remaining = Self.maximumTokenBytes
        for target in targets {
            guard !target.token.isEmpty, target.token.count <= 65_536, target.token.count <= remaining else { return false }
            remaining -= target.token.count
        }
        return true
    }

    /// Opaque categories cannot prove disjoint membership, including against another category.
    /// Conservatively prevents stacking across a possible overlap without broadening a grant.
    public func mayOverlap(_ other: Self) -> Bool {
        !targets.isDisjoint(with: other.targets) ||
            (!targets.isEmpty && !other.targets.isEmpty &&
             (!wholeCategories.isEmpty || !other.wholeCategories.isEmpty))
    }
}

/// Already reconciled, currently enforcing selections from *all* stores. An unexhausted limit
/// or a planned Pomodoro rest is not an enforcing contributor. nil state/selection is unknown,
/// never an empty selection. Phase 2 supplies these under the transition and ledger locks.
/// Supply readable selections even for inactive entries and trusted time for every active
/// entry; authorization deliberately refuses an incompletely reconciled snapshot.
public struct FlintBreakContributor: Equatable, Sendable {
    public enum Selection: Equatable, Sendable {
        case blockList(FlintBreakScope)
        case allowList(allowedApplications: Set<FlintBreakScope.Target>)
    }
    public let cause: FlintCauseState?
    public let selection: Selection?
    /// Existing paired-clock type, projected from the cause's logical remaining time.
    /// nil is valid only for known unbounded Easy (cause.end == nil and timeKnown == true).
    /// Finite Easy and other tiers require a valid, future, same-boot logical end;
    /// unknown time must set timeKnown = false.
    public let logicalEnd: FlintLedgerInstant?
    public let timeKnown: Bool
    /// The exact persisted ledger entry; nil for a Harder cause means unavailable, not n = 0.
    public let harder: FlintLedgerState.Harder?

    public init(cause: FlintCauseState?, selection: Selection?, logicalEnd: FlintLedgerInstant?,
                timeKnown: Bool, harder: FlintLedgerState.Harder? = nil) {
        self.cause = cause; self.selection = selection; self.logicalEnd = logicalEnd
        self.timeKnown = timeKnown; self.harder = harder
    }
}

public enum FlintBreakScopeResolution {
    public struct Blocking: Equatable, Sendable {
        public let cause: FlintBreakGrant.Cause
        public let targets: Set<FlintBreakScope.Target>
    }
    public struct Result: Equatable, Sendable {
        public let blocking: [Blocking]
        /// Only these explicit targets may be released, and only after every cause authorizes.
        public let releasable: FlintBreakScope
        public let unresolved: Set<FlintBreakScope.Target>
        public let stateUnavailable: Bool
        public var wholeCategories: Set<FlintBreakScope.Target> { releasable.wholeCategories }
    }

    public static func resolve(request: FlintBreakScope, contributors: [FlintBreakContributor]) -> Result {
        var blocking: [Blocking] = [], unresolved: Set<FlintBreakScope.Target> = []
        var unavailable = false
        for contributor in contributors {
            guard let cause = contributor.cause, let selection = contributor.selection else {
                unavailable = true; continue
            }
            guard cause.isActive else { continue }
            var affected: Set<FlintBreakScope.Target> = []
            switch selection {
            case let .blockList(frozen):
                affected = request.targets.intersection(frozen.targets)
                // An explicit whole-category request can clear that exact category. A missing
                // category or unknown membership never silently expands a single-target request.
                for target in request.targets {
                    let uncertain = frozen.targets.contains { blocked in
                        guard blocked != target else { return false }
                        if blocked.kind == .category { return !request.targets.contains(blocked) }
                        return target.kind == .category && !request.targets.contains(blocked)
                    }
                    if uncertain { unresolved.insert(target) }
                }
            case let .allowList(allowed):
                // Only application exemptions are representable/proven in an application allow list.
                guard allowed.allSatisfy({ $0.kind == .application && !$0.token.isEmpty }) else {
                    unavailable = true; continue
                }
                for target in request.targets {
                    if target.kind == .application {
                        if !allowed.contains(target) { affected.insert(target) }
                    } else { unresolved.insert(target) }
                }
            }
            if !affected.isEmpty { blocking.append(.init(cause: .init(cause), targets: affected)) }
        }
        let covered = blocking.reduce(into: Set<FlintBreakScope.Target>()) { $0.formUnion($1.targets) }
        unresolved.formUnion(request.targets.subtracting(covered))
        return .init(blocking: blocking,
                     releasable: .init(targets: unavailable ? [] : covered.subtracting(unresolved)),
                     unresolved: unresolved, stateUnavailable: unavailable)
    }
}
