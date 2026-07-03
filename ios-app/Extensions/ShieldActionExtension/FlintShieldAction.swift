import ManagedSettings
import ManagedSettingsUI
import FlintCore

/// Handles taps on the block screen's buttons. Note (FB15079668): there is no API to open the
/// parent app from the shield, so deeper interventions (e.g. spend an Emergency Pass) fall back
/// to a local notification routing the user back into Flint.
///
/// **Open Limits** ride the primary button (the shield's action button). The tapped token is
/// looked up against the saved `FlintOpenLimitRule`s in `FlintOpenLimitEnforcer` (the shield
/// callback itself can't tell an open-limit token from a hard-block one — FB14237883):
///  - granted   → one open is recorded in the App Group counter, the token is released from the
///                open-limit store, and `.close` dismisses the app — its *next* launch goes
///                straight in (no response can open the app from here).
///  - exhausted → the daily opens are spent; the token is kept/re-shielded and the shield stays.
///  - otherwise → plain hard block (Session / Schedule / Time Limit): the original
///                "Stay focused" behavior, and no open is ever charged.
///
/// Note for the ShieldConfiguration owner: the block screen still labels the primary button
/// "Stay focused" for every token. Open-limited tokens should read "Use app (N left)" — the same
/// rule lookup, but that extension is a different vertical's scope.
final class FlintShieldAction: ShieldActionDelegate {

    override func handle(
        action: ShieldAction,
        for application: ApplicationToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        switch action {
        case .primaryButtonPressed:
            completionHandler(response(for: FlintOpenLimitEnforcer.handleOpenRequest(application: application)))
        case .secondaryButtonPressed:
            completionHandler(.close)
        @unknown default:
            completionHandler(.none)
        }
    }

    override func handle(
        action: ShieldAction,
        for webDomain: WebDomainToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        switch action {
        case .primaryButtonPressed:
            completionHandler(response(for: FlintOpenLimitEnforcer.handleOpenRequest(webDomain: webDomain)))
        case .secondaryButtonPressed:
            completionHandler(.close)
        @unknown default:
            completionHandler(.none)
        }
    }

    override func handle(
        action: ShieldAction,
        for category: ActivityCategoryToken,
        completionHandler: @escaping (ShieldActionResponse) -> Void
    ) {
        // Categories are never open-limited (opaque tokens can't be counted per-app — see
        // FlintOpenLimitRule), so category shields keep the plain hard-block behavior.
        completionHandler(response(for: action))
    }

    /// Open-limit outcome → shield response.
    private func response(for outcome: FlintOpenLimitEnforcer.Outcome) -> ShieldActionResponse {
        switch outcome {
        case .granted:
            return .close   // token released; the very next launch opens unshielded
        case .exhausted, .hardBlocked, .notOpenLimited:
            return .defer   // keep the shield up ("Stay focused")
        }
    }

    /// Plain hard-block behavior (unchanged from before Open Limits).
    private func response(for action: ShieldAction) -> ShieldActionResponse {
        switch action {
        case .primaryButtonPressed:
            return .defer   // "Stay focused" — keep the shield up
        case .secondaryButtonPressed:
            return .close   // dismiss to home
        @unknown default:
            return .none
        }
    }
}
