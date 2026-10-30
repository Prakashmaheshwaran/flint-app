import Foundation

public enum FlintOpenCreditTargetKind { case application, webDomain }

/// What kind of protection another store is. Shield copy names only the kind; saved rule
/// names never leave the store snapshot.
public enum FlintOpenCreditContributorKind: String, CaseIterable, Comparable, Sendable {
    case session, schedule, timeLimit, safariRestriction

    public static func < (lhs: Self, rhs: Self) -> Bool {
        allCases.firstIndex(of: lhs)! < allCases.firstIndex(of: rhs)!
    }
}

/// Snapshot of one *currently enforcing* non-Open native store. Opaque categories are
/// represented only as restrictions plus proven exceptions; no membership is inferred.
public struct FlintOpenCreditContributor {
    public let kind: FlintOpenCreditContributorKind
    public let applications: Set<String>
    public let webDomains: Set<String>
    /// nil means no category restriction; an empty set means no proven exceptions.
    public let applicationCategoryExceptions: Set<String>?
    public let webCategoryExceptions: Set<String>?
    /// A domain token cannot prove its plaintext domain is a filter exception.
    public let webContentMayBlock: Bool
    public init(kind: FlintOpenCreditContributorKind, applications: Set<String> = [], webDomains: Set<String> = [],
                applicationCategoryExceptions: Set<String>? = nil, webCategoryExceptions: Set<String>? = nil,
                webContentMayBlock: Bool = false) {
        self.kind = kind; self.applications = applications; self.webDomains = webDomains
        self.applicationCategoryExceptions = applicationCategoryExceptions; self.webCategoryExceptions = webCategoryExceptions
        self.webContentMayBlock = webContentMayBlock
    }
}

public struct FlintOpenCreditBlocking: Equatable {
    public let reason: FlintOpenCreditBlock?
    /// Distinct kinds, in `FlintOpenCreditContributorKind` order.
    public let contributors: [FlintOpenCreditContributorKind]
    public static func query(target: String, kind: FlintOpenCreditTargetKind,
                             stores: [FlintOpenCreditContributor]?) -> Self {
        guard let stores else { return .init(reason: .unavailable, contributors: []) }
        var explicit: [FlintOpenCreditContributorKind] = []
        var categories: [FlintOpenCreditContributorKind] = []
        for store in stores {
            let tokens = kind == .application ? store.applications : store.webDomains
            let exceptions = kind == .application ? store.applicationCategoryExceptions : store.webCategoryExceptions
            if tokens.contains(target) { explicit.append(store.kind) }
            if kind == .webDomain && store.webContentMayBlock { explicit.append(store.kind) }
            if let exceptions, !exceptions.contains(target) { categories.append(store.kind) }
        }
        return .init(reason: !categories.isEmpty ? .categoryOverlap : (explicit.isEmpty ? nil : .otherContributor),
                     contributors: Array(Set(explicit + categories)).sorted())
    }
}
