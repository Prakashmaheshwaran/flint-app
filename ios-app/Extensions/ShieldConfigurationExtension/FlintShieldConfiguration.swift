import ManagedSettings
import ManagedSettingsUI
import UIKit
import FlintCore

/// The branded "Peak Focus" block screen that replaces Apple's generic gray shield.
final class FlintShieldConfiguration: ShieldConfigurationDataSource {

    private func flintShield(for name: String?) -> ShieldConfiguration {
        ShieldConfiguration(
            backgroundBlurStyle: .systemThinMaterialDark,
            backgroundColor: FlintBrand.flintUI,
            icon: nil,
            title: ShieldConfiguration.Label(text: "Blocked by Flint", color: .white),
            subtitle: ShieldConfiguration.Label(
                text: name.map { "“\($0)” is off-limits during your focus session." }
                    ?? "Off-limits during your focus session.",
                color: FlintBrand.stoneUI
            ),
            primaryButtonLabel: ShieldConfiguration.Label(text: "Stay focused", color: FlintBrand.onAccentUI),
            primaryButtonBackgroundColor: FlintBrand.sparkUI
        )
    }

    override func configuration(shielding application: Application) -> ShieldConfiguration {
        flintShield(for: application.localizedDisplayName)
    }

    override func configuration(
        shielding application: Application,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: application.localizedDisplayName)
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        flintShield(for: webDomain.domain)
    }

    override func configuration(
        shielding webDomain: WebDomain,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: webDomain.domain)
    }
}
