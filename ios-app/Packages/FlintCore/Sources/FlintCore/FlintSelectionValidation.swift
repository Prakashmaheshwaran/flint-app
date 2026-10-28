import Foundation
#if canImport(FamilyControls)
import FamilyControls
#endif

public enum FlintSelectionIssue: Error, LocalizedError, Equatable, Sendable {
    case emptySelection
    case tooManyTokens(count: Int, cap: Int)

    /// Keep the same wording as the immediate-session editor.
    public var message: String {
        switch self {
        case .emptySelection:
            return "Pick at least one app or website to block first."
        case let .tooManyTokens(count, cap):
            return "Too many items selected (\(count)). The system limit is \(cap) — use a category instead."
        }
    }

    public var errorDescription: String? { message }
}

/// Individual app and website properties have separate caps. Categories do not consume either
/// token budget. Open Limits require an individual token because categories cannot spend opens.
public enum FlintSelectionValidation {
    public static func issue(
        applications: Int,
        webDomains: Int,
        categories: Int,
        requiresSelection: Bool = true,
        supportsCategories: Bool = true
    ) -> FlintSelectionIssue? {
        if applications > FlintShieldLimits.tokenCap {
            return .tooManyTokens(count: applications, cap: FlintShieldLimits.tokenCap)
        }
        if webDomains > FlintShieldLimits.tokenCap {
            return .tooManyTokens(count: webDomains, cap: FlintShieldLimits.tokenCap)
        }
        if requiresSelection && applications + webDomains + (supportsCategories ? categories : 0) == 0 {
            return .emptySelection
        }
        return nil
    }

    #if canImport(FamilyControls)
    public static func issue(
        for selection: FamilyActivitySelection,
        requiresSelection: Bool = true,
        supportsCategories: Bool = true
    ) -> FlintSelectionIssue? {
        issue(applications: selection.applicationTokens.count,
              webDomains: selection.webDomainTokens.count,
              categories: selection.categoryTokens.count,
              requiresSelection: requiresSelection,
              supportsCategories: supportsCategories)
    }
    #endif
}
