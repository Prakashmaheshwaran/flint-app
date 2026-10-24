import ManagedSettings
import ManagedSettingsUI
import UIKit
import FlintCore

/// The Ember & Mist block screen that replaces Apple's generic gray shield: the night colour,
/// the ember as its icon, "<App> is resting." Apple fixes the layout (icon, title, subtitle,
/// buttons); only text and colours are ours.
///
/// **Open Limits:** for a token an enabled `FlintOpenLimitRule` covers (and no other layer hard-
/// blocks), the primary button reads "Use app (N left)" — the same
/// `FlintOpenLimitEnforcer` lookup the ShieldAction extension spends against, so the label and
/// the tap behavior can't drift apart. Category-driven shields keep the plain screen: category
/// blocks can't be open-limited (opaque tokens — see `FlintOpenLimitRule`), and a grant wouldn't
/// lift them.
final class FlintShieldConfiguration: ShieldConfigurationDataSource {

    private func flintShield(
        for name: String?, unit: String = "app", remainingOpens: Int? = nil
    ) -> ShieldConfiguration {
        let subtitle: String
        let primaryButton: String
        switch remainingOpens {
        case .some(let left) where left > 0:
            subtitle = "\(left) intentional \(left == 1 ? "open" : "opens") left today. Notice the reach first."
            primaryButton = "Use \(unit) (\(left) left)"
        case .some:
            // Zero also covers the enforcer's fail-closed path (App Group unreadable reports 0
            // remaining) — only promise a tomorrow reset when the counter is actually readable;
            // fail-closed has no daily reset to promise.
            subtitle = FlintGroupStore() != nil
                ? "Today’s opens are spent. It opens again tomorrow."
                : "Opens are unavailable right now."
            primaryButton = "Go back"
        case .none:
            subtitle = "Notice the reach, then let it pass."
            primaryButton = "Go back"
        }
        let title = name.map { "\($0) is resting." } ?? "This \(unit) is resting."
        return ShieldConfiguration(
            backgroundBlurStyle: nil,
            backgroundColor: FlintBrand.nightUI,
            icon: Self.ember,
            title: ShieldConfiguration.Label(text: title, color: FlintBrand.boneUI),
            subtitle: ShieldConfiguration.Label(text: subtitle, color: FlintBrand.mistUI),
            primaryButtonLabel: ShieldConfiguration.Label(text: primaryButton, color: FlintBrand.onEmberUI),
            primaryButtonBackgroundColor: FlintBrand.emberUI
        )
    }

    /// The ember orb inside its halo, drawn once (design/ember-mist/E07-Shield.dc.html).
    private static let ember: UIImage = {
        let size = CGSize(width: 160, height: 160)
        return UIGraphicsImageRenderer(size: size).image { context in
            let cg = context.cgContext
            let space = CGColorSpaceCreateDeviceRGB()
            let center = CGPoint(x: 80, y: 80)
            let ember = FlintBrand.emberUI
            if let halo = CGGradient(colorsSpace: space, colors: [
                ember.withAlphaComponent(0.45).cgColor, ember.withAlphaComponent(0.1).cgColor,
                ember.withAlphaComponent(0).cgColor,
            ] as CFArray, locations: [0, 0.5, 1]) {
                cg.drawRadialGradient(halo, startCenter: center, startRadius: 0, endCenter: center, endRadius: 80, options: [])
            }
            if let orb = CGGradient(colorsSpace: space, colors: [
                UIColor(red: 1, green: 0.906, blue: 0.761, alpha: 1).cgColor,
                UIColor(red: 0.969, green: 0.745, blue: 0.416, alpha: 1).cgColor,
                UIColor(red: 0.851, green: 0.525, blue: 0.125, alpha: 1).cgColor,
            ] as CFArray, locations: [0, 0.4, 1]) {
                cg.addEllipse(in: CGRect(x: 46, y: 46, width: 68, height: 68))
                cg.clip()
                cg.drawRadialGradient(orb, startCenter: CGPoint(x: 80, y: 76), startRadius: 0,
                                      endCenter: center, endRadius: 37, options: [])
            }
        }
    }()

    override func configuration(shielding application: Application) -> ShieldConfiguration {
        flintShield(
            for: application.localizedDisplayName,
            remainingOpens: application.token.flatMap {
                FlintOpenLimitEnforcer.remainingOpens(application: $0)
            }
        )
    }

    override func configuration(
        shielding application: Application,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: application.localizedDisplayName)
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        flintShield(
            for: webDomain.domain,
            unit: "site",
            remainingOpens: webDomain.token.flatMap {
                FlintOpenLimitEnforcer.remainingOpens(webDomain: $0)
            }
        )
    }

    override func configuration(
        shielding webDomain: WebDomain,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: webDomain.domain)
    }
}
