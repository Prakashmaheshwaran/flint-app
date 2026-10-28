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
        for name: String?, unit: ShieldCopy.Unit = .app, targetKey: String? = nil,
        remainingOpens: Int? = nil
    ) -> ShieldConfiguration {
        let now = Date()
        let copy = ShieldCopy(name: name, unit: unit,
                              contributors: targetKey.flatMap { contributors(for: $0, now: now) },
                              now: now, remainingOpens: remainingOpens,
                              openStoreAvailable: FlintGroupStore() != nil)
        return ShieldConfiguration(
            backgroundBlurStyle: nil,
            backgroundColor: FlintBrand.nightUI,
            icon: Self.ember,
            title: ShieldConfiguration.Label(text: copy.title, color: FlintBrand.boneUI),
            subtitle: ShieldConfiguration.Label(text: copy.subtitle, color: FlintBrand.mistUI),
            primaryButtonLabel: ShieldConfiguration.Label(text: copy.primaryButtonLabel, color: FlintBrand.onEmberUI),
            primaryButtonBackgroundColor: FlintBrand.emberUI
        )
    }

    private func contributors(for targetKey: String, now: Date) -> FlintProtectionStatus.Contributors? {
        guard let defaults = UserDefaults.flintGroup else { return nil }
        // Never call ledger.read(): it can initialize/migrate storage. Lane B's #137 adds
        // the read-only peek() seam; until then an unavailable pass keeps the copy neutral.
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults)
        return FlintProtectionStatus.contributors(for: targetKey, inputs: inputs,
                                                  coverageByCauseID: coverage(for: inputs))
    }

    private func coverage(for inputs: FlintProtectionStatusBuilder.Inputs)
        -> [String: FlintProtectionStatus.TargetScope] {
        // Replace with FlintProtectionStatus.coverageScopes(for: inputs) when lane B's #137
        // publishes the read-only target coverage adapter. Missing coverage stays uncertain.
        [:]
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
            targetKey: application.token.map { FlintOpenLimit.key(for: $0) },
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
            unit: .site,
            targetKey: webDomain.token.map { FlintOpenLimit.key(for: $0) },
            remainingOpens: webDomain.token.flatMap {
                FlintOpenLimitEnforcer.remainingOpens(webDomain: $0)
            }
        )
    }

    override func configuration(
        shielding webDomain: WebDomain,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: webDomain.domain, unit: .site)
    }
}
