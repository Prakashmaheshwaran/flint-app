import Foundation
import DeviceActivity
import FlintCore

/// Applies and clears shields on schedule boundaries and usage-threshold events.
///
/// Writing shields from the extension (not the app) is the documented best practice — it keeps
/// enforcement alive when the app is closed or killed. Runs in a tight, short-lived sandbox.
///
/// Activity names that arrive here:
///  - `flint.session.<token>` — the manual Block Now session's one-shot auto-clear (older
///                         builds used plain `flint.session`). `FlintSessionController` decides:
///                         only the registration recorded on the active session can end it.
///  - `flint.rule.<id>.i<incarnation>.r<revision>` — a schedule/Sleep rule.
///  - `flint.limit.<id>.i<incarnation>.r<revision>` — a daily Time Limit and matching event.
///  - `flint.openLimit.<id>.i<incarnation>.r<revision>` — a daily Open Limit.
///    The rule authority also recognizes names retained by one-time legacy migration.
///  - `flint.open.<token>` — B5's finite Open credit restoration carrier.
///
/// Anything else is a stale registration (e.g. a rule deleted while the App Group was
/// unreadable): it is deregistered and otherwise ignored — never treated as the session.
final class FlintMonitor: DeviceActivityMonitor {

    override func intervalDidStart(for activity: DeviceActivityName) {
        super.intervalDidStart(for: activity)
        if FlintOpenCredit.isCarrier(activity.rawValue) {
            FlintOpenCredit.reconcile(activity: activity.rawValue, callback: .start)
            return
        }
        if FlintRuleMonitor().handle(.start, activity: activity.rawValue) {
            publishStatus()
            return
        }
        if FlintSessionController.isSessionActivity(activity.rawValue) {
            FlintSessionController().handleMonitorEvent(.intervalDidStart, activity: activity.rawValue)
            return // the session controller publishes its own changes
        } else {
            stopStaleActivity(activity)
        }
        publishStatus()
    }

    override func intervalDidEnd(for activity: DeviceActivityName) {
        super.intervalDidEnd(for: activity)
        if FlintOpenCredit.isCarrier(activity.rawValue) {
            FlintOpenCredit.reconcile(activity: activity.rawValue, callback: .end)
            return
        }
        if FlintRuleMonitor().handle(.end, activity: activity.rawValue) {
            publishStatus()
            return
        }
        if FlintSessionController.isSessionActivity(activity.rawValue) {
            // Natural expiry with the app closed/killed: ending the session clears its store,
            // which drops the shield AND the Hardcore uninstall guard living on it.
            FlintSessionController().handleMonitorEvent(.intervalDidEnd, activity: activity.rawValue)
            return // the session controller publishes its own changes
        } else {
            stopStaleActivity(activity)
        }
        publishStatus()
    }

    /// Only session registrations shorter than 15 minutes carry a warning: it marks their real
    /// end inside the padded interval DeviceActivity requires.
    override func intervalWillEndWarning(for activity: DeviceActivityName) {
        super.intervalWillEndWarning(for: activity)
        if FlintOpenCredit.isCarrier(activity.rawValue) {
            FlintOpenCredit.reconcile(activity: activity.rawValue, callback: .warning)
            return
        }
        guard FlintSessionController.isSessionActivity(activity.rawValue) else { return }
        FlintSessionController().handleMonitorEvent(.intervalWillEndWarning, activity: activity.rawValue)
    }

    override func eventDidReachThreshold(
        _ event: DeviceActivityEvent.Name,
        activity: DeviceActivityName
    ) {
        super.eventDidReachThreshold(event, activity: activity)
        // Both supplied identities must match the effective persisted revision. Looking up
        // the event alone could let a delayed old activity shield a replacement (R-21).
        guard FlintRuleMonitor().handle(.threshold(event.rawValue), activity: activity.rawValue) else { return }
        publishStatus()
    }

    /// Re-publish the shared status projection widgets and the shield read (ADR-012 §11: no
    /// targets in it). Content-unchanged publications skip the widget reload.
    private func publishStatus() {
        FlintProtectionStatus.publishAfterCommit(writer: .monitor)
    }

    /// A boundary fired for a name matching no schedule/limit/open-limit and not a session
    /// registration: a stale registration. It used to fall through to the session branch —
    /// applying the saved-selection shield or, worse, tearing down a *live* session's store and
    /// state. Deregister it so it never fires again; touch nothing else.
    private func stopStaleActivity(_ activity: DeviceActivityName) {
        FlintScheduling.stopMonitoring([activity.rawValue])
    }

}
