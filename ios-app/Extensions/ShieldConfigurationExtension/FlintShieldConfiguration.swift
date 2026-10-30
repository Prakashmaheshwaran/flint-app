import ManagedSettings
import ManagedSettingsUI
import UIKit
import FlintCore

/// The Ember & Mist block screen that replaces Apple's generic gray shield: the night colour,
/// the ember as its icon, "<App> is resting." Apple fixes the layout (icon, title, subtitle,
/// buttons); only text and colours are ours.
///
/// **Open Limits:** for a token an enabled `FlintOpenLimitRule` covers, the copy follows the
/// read-only `FlintOpenCredit.preview` — the same service the ShieldAction extension accepts
/// through, which re-checks everything at press time. Only an available credit draws the
/// secondary "Use one credit · 5 minutes" button; "Go back" never spends. Category-driven
/// shields never offer credits: opaque category tokens are not Open-credit targets.
final class FlintShieldConfiguration: ShieldConfigurationDataSource {

    private func flintShield(
        for name: String?, unit: ShieldCopy.Unit = .app, targetKey: String? = nil,
        openCredit: ShieldCopy.OpenCredit? = nil
    ) -> ShieldConfiguration {
        let now = Date()
        let copy = ShieldCopy(name: name, unit: unit,
                              contributors: targetKey.flatMap { contributors(for: $0, now: now) },
                              now: now, openCredit: openCredit)
        return ShieldConfiguration(
            backgroundBlurStyle: nil,
            backgroundColor: FlintBrand.nightUI,
            icon: Self.ember,
            title: ShieldConfiguration.Label(text: copy.title, color: FlintBrand.boneUI),
            subtitle: ShieldConfiguration.Label(text: copy.subtitle, color: FlintBrand.mistUI),
            primaryButtonLabel: ShieldConfiguration.Label(text: copy.primaryButtonLabel, color: FlintBrand.onEmberUI),
            primaryButtonBackgroundColor: FlintBrand.emberUI,
            secondaryButtonLabel: copy.secondaryButtonLabel.map {
                ShieldConfiguration.Label(text: $0, color: FlintBrand.boneUI)
            }
        )
    }

    /// Covering rules come from the saved Open Limits; the preview never initializes or
    /// mutates the ledger. With the acceptance gate off, no preview runs and no button is drawn.
    private func openCredit(targetKey: String, preview: () -> FlintOpenCreditPreview) -> ShieldCopy.OpenCredit? {
        let covering = FlintOpenLimitRule.loadAll().filter { rule in
            rule.enabled && (rule.selection.applicationTokens.contains { FlintOpenLimit.key(for: $0) == targetKey } ||
                rule.selection.webDomainTokens.contains { FlintOpenLimit.key(for: $0) == targetKey })
        }
        guard !covering.isEmpty else { return nil }
        guard FlintOpenCredit.shieldAcceptanceEnabled else { return .acceptanceDisabled }
        return .target(preview(), hardcore: covering.contains { $0.breakLevel == .hardcore })
    }

    private func categoryOpenCredit(_ token: ActivityCategoryToken?) -> ShieldCopy.OpenCredit? {
        guard let token,
              FlintOpenLimitRule.loadAll().contains(where: { $0.enabled && $0.selection.categoryTokens.contains(token) })
        else { return nil }
        return FlintOpenCredit.shieldAcceptanceEnabled ? .categoryTarget : .acceptanceDisabled
    }

    private func contributors(for targetKey: String, now: Date) -> FlintProtectionStatus.Contributors? {
        // peek() reads only the last committed ledger. Never call read(), which can initialize
        // or migrate storage. Missing/corrupt state cannot support a cause or release promise.
        guard let defaults = UserDefaults.flintGroup,
              let ledger = FlintProtectionLedger.appGroup(),
              case let .success(state) = ledger.peek() else { return nil }
        let inputs = FlintProtectionStatus.currentInputs(now: now, defaults: defaults, ledgerPass: state.pass)
        return FlintProtectionStatus.contributors(for: targetKey, inputs: inputs,
                                                  coverageByCauseID: coverage(for: inputs))
    }

    private func coverage(for inputs: FlintProtectionStatusBuilder.Inputs)
        -> [String: FlintProtectionStatus.TargetScope] {
        guard let group = FlintGroupStore() else { return [:] }
        let selection = inputs.activeSession.flatMap { group.loadSessionSelection(sessionID: $0.session.id) }
        // FlintSessionController currently applies block lists only. Read its frozen selection,
        // never the mutable picker; schedules carry their own allow-list mode into the adapter.
        return FlintProtectionStatus.coverageScopes(for: .init(
            protection: inputs, sessionSelection: selection, sessionAllowListMode: false,
            schedules: group.loadRules(), timeLimits: group.loadLimits(),
            openLimits: FlintOpenLimitRule.loadAll()
        ))
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
            openCredit: application.token.flatMap { token in
                openCredit(targetKey: FlintOpenLimit.key(for: token)) { FlintOpenCredit.preview(for: token) }
            }
        )
    }

    override func configuration(
        shielding application: Application,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: application.localizedDisplayName, openCredit: categoryOpenCredit(category.token))
    }

    override func configuration(shielding webDomain: WebDomain) -> ShieldConfiguration {
        flintShield(
            for: webDomain.domain,
            unit: .site,
            targetKey: webDomain.token.map { FlintOpenLimit.key(for: $0) },
            openCredit: webDomain.token.flatMap { token in
                openCredit(targetKey: FlintOpenLimit.key(for: token)) { FlintOpenCredit.preview(for: token) }
            }
        )
    }

    override func configuration(
        shielding webDomain: WebDomain,
        in category: ActivityCategory
    ) -> ShieldConfiguration {
        flintShield(for: webDomain.domain, unit: .site, openCredit: categoryOpenCredit(category.token))
    }
}
