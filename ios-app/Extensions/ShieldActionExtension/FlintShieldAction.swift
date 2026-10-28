import ManagedSettings
import ManagedSettingsUI
import FlintCore

/// The configured primary button is the explicit “Use one credit · 5 minutes” acceptance.
/// All other actions close without charging, including category and future SDK actions.
final class FlintShieldAction: ShieldActionDelegate {
    override func handle(
        action: ShieldAction, for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        switch action {
        case .primaryButtonPressed:
            _ = FlintOpenCredit.accept(for: application)
            completionHandler(.close)
        case .secondaryButtonPressed:
            completionHandler(.close)
        default:
            completionHandler(.close)
        }
    }

    override func handle(
        action: ShieldAction, for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        switch action {
        case .primaryButtonPressed:
            _ = FlintOpenCredit.accept(for: webDomain)
            completionHandler(.close)
        case .secondaryButtonPressed:
            completionHandler(.close)
        default:
            completionHandler(.close)
        }
    }

    override func handle(
        action: ShieldAction, for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        // Opaque categories are never Open-credit targets.
        completionHandler(.close)
    }
}
