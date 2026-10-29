import ManagedSettings
import ManagedSettingsUI
import FlintCore

/// "Go back" (primary) closes without charging. Only the secondary "Use one credit · N minutes"
/// button, drawn solely for an available credit, can spend one; see `ShieldCreditAction`.
/// Category and future SDK actions close without charging.
final class FlintShieldAction: ShieldActionDelegate {
    override func handle(
        action: ShieldAction, for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(ShieldCreditAction.response(to: action) {
            FlintOpenCredit.shieldAcceptanceEnabled && (try? FlintOpenCredit.accept(for: application).get()) != nil
        })
    }

    override func handle(
        action: ShieldAction, for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        completionHandler(ShieldCreditAction.response(to: action) {
            FlintOpenCredit.shieldAcceptanceEnabled && (try? FlintOpenCredit.accept(for: webDomain).get()) != nil
        })
    }

    override func handle(
        action: ShieldAction, for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        // Opaque categories are never Open-credit targets.
        completionHandler(.close)
    }
}
