import Foundation

public enum FlintOpenCreditTargetKind { case application, webDomain }

/// Snapshot of one *currently enforcing* non-Open native store. Opaque categories are
/// represented only as restrictions plus proven exceptions; no membership is inferred.
public struct FlintOpenCreditContributor {
    public let name: String
    public let applications: Set<String>
    public let webDomains: Set<String>
    /// nil means no category restriction; an empty set means no proven exceptions.
    public let applicationCategoryExceptions: Set<String>?
    public let webCategoryExceptions: Set<String>?
    /// A domain token cannot prove its plaintext domain is a filter exception.
    public let webContentMayBlock: Bool
    public init(name: String, applications: Set<String> = [], webDomains: Set<String> = [],
                applicationCategoryExceptions: Set<String>? = nil, webCategoryExceptions: Set<String>? = nil,
                webContentMayBlock: Bool = false) {
        self.name = name; self.applications = applications; self.webDomains = webDomains
        self.applicationCategoryExceptions = applicationCategoryExceptions; self.webCategoryExceptions = webCategoryExceptions
        self.webContentMayBlock = webContentMayBlock
    }
}

public struct FlintOpenCreditBlocking: Equatable {
    public let reason: FlintOpenCreditBlock?
    public let contributors: [String]
    public static func query(target: String, kind: FlintOpenCreditTargetKind,
                             stores: [FlintOpenCreditContributor]?) -> Self {
        guard let stores else { return .init(reason: .unavailable, contributors: []) }
        var explicit: [String] = []
        var categories: [String] = []
        for store in stores {
            let tokens = kind == .application ? store.applications : store.webDomains
            let exceptions = kind == .application ? store.applicationCategoryExceptions : store.webCategoryExceptions
            if tokens.contains(target) { explicit.append(store.name) }
            if kind == .webDomain && store.webContentMayBlock { explicit.append(store.name) }
            if let exceptions, !exceptions.contains(target) { categories.append(store.name) }
        }
        return .init(reason: !categories.isEmpty ? .categoryOverlap : (explicit.isEmpty ? nil : .otherContributor),
                     contributors: Array(Set(explicit + categories)).sorted())
    }
}
