import ManagedSettings
import ManagedSettingsUI
import UIKit
import FlintCore

/// The branded "Peak Focus" block screen that replaces Apple's generic gray shield.
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
            subtitle = "Open-limited — \(left) intentional \(left == 1 ? "open" : "opens") left today."
            primaryButton = "Use \(unit) (\(left) left)"
        case .some:
            subtitle = "Open-limited — today's opens are spent. It unlocks tomorrow."
            primaryButton = "Stay focused"
        case .none:
            subtitle = name.map { "“\($0)” is off-limits during your focus session." }
                ?? "Off-limits during your focus session."
            primaryButton = "Stay focused"
        }
        return ShieldConfiguration(
            backgroundBlurStyle: .systemThinMaterialDark,
            backgroundColor: FlintBrand.flintUI,
            icon: nil,
            title: ShieldConfiguration.Label(text: "Blocked by Flint", color: .white),
            subtitle: ShieldConfiguration.Label(text: subtitle, color: FlintBrand.stoneUI),
            primaryButtonLabel: ShieldConfiguration.Label(text: primaryButton, color: FlintBrand.onAccentUI),
            primaryButtonBackgroundColor: FlintBrand.sparkUI
        )
    }

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
