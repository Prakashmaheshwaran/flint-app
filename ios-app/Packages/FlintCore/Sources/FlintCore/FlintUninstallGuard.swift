import Foundation

/// Decides when Flint should deny its own deletion — the **uninstall guard** for Hardcore
/// (Deep Focus). Without it, deleting the app is the one-tap escape from a "non-bypassable"
/// block (`.individual` authorization imposes none of the parental implicit restrictions —
/// see the iOS strategy doc §6).
///
/// Pure, deterministic state logic (no ManagedSettings import) so it tests like
/// `FlintEmergencyPass`. The actual write is `ManagedSettingsStore.application.denyAppRemoval`
/// on the **same named store the session shield occupies** ("flint"), which makes the lifecycle
/// safe by construction: every session end path — `FlintSessionController.stop()` (allowed
/// stops and the Emergency Pass) and the monitor's `intervalDidEnd` — already calls
/// `clearAllSettings()` on that store, so the guard drops together with the shield and can
/// never outlive it.
///
/// Scope notes (honest ones):
///  - Apple's setting denies app removal **device-wide** while armed, not just for Flint.
///  - Deliberately limited to Hardcore *sessions*. Hardcore schedule rules (incl. Sleep
///    Full Assist) can be toggled off in-app at any time, so denying app removal for them
///    would be friction without an actual lock.
public enum FlintUninstallGuard {
    /// True while `session` is a running (not-yet-expired) Hardcore block. Open-ended Hardcore
    /// (e.g. a Focus-Filter block) denies until it's stopped; Easy/Harder/no session never deny.
    /// Boundary matches `FlintActiveSession.isExpired`: at exactly `endsAt` the guard releases.
    public static func shouldDeny(for session: FlintActiveSession?, now: Date = Date()) -> Bool {
        guard let session, session.breakLevel == .hardcore else { return false }
        guard let endsAt = session.endsAt else { return true }
        return now < endsAt
    }
}
