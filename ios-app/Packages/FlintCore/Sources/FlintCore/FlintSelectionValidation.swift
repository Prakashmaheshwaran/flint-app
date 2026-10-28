import Foundation
#if canImport(FamilyControls)
import FamilyControls
#endif

public enum FlintSelectionContext: Equatable, Sendable {
    case blockList, allowList, openLimit
}

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

    public func message(for context: FlintSelectionContext) -> String {
        guard case let .tooManyTokens(count, cap) = self else { return message }
        switch context {
        case .blockList: return message
        case .allowList:
            return "Too many apps selected (\(count)). The system limit is \(cap) — choose fewer allowed apps."
        case .openLimit:
            return "Too many items selected (\(count)). Pick at most \(cap) apps and \(cap) websites. "
                + "Whole categories cannot be open-limited."
        }
    }
}

/// Individual app and website properties have separate caps. Categories do not consume either
/// token budget. Open Limits require an individual token because categories cannot spend opens.
/// Allow-list shields use only application exceptions, so website/category picks are ignored.
public enum FlintSelectionValidation {
    public static func issue(
        applications: Int,
        webDomains: Int,
        categories: Int,
        context: FlintSelectionContext = .blockList
    ) -> FlintSelectionIssue? {
        if applications > FlintShieldLimits.tokenCap {
            return .tooManyTokens(count: applications, cap: FlintShieldLimits.tokenCap)
        }
        if context != .allowList && webDomains > FlintShieldLimits.tokenCap {
            return .tooManyTokens(count: webDomains, cap: FlintShieldLimits.tokenCap)
        }
        if context != .allowList && applications + webDomains + (context == .blockList ? categories : 0) == 0 {
            return .emptySelection
        }
        return nil
    }

    #if canImport(FamilyControls)
    public static func issue(
        for selection: FamilyActivitySelection,
        context: FlintSelectionContext = .blockList
    ) -> FlintSelectionIssue? {
        issue(applications: selection.applicationTokens.count,
              webDomains: selection.webDomainTokens.count,
              categories: selection.categoryTokens.count,
              context: context)
    }
    #endif
}

/// One source for the enabled count and the stores whose old shields must be released when
/// validation prevents re-registration. Disabled rules are handled by their normal edit path.
public struct FlintRuleArmPlan<Rule, Failure> {
    public let arm: [Rule]
    public let failures: [Failure]
    public let storesToClear: [String]
    public var enabled: Int { arm.count + failures.count }
}
