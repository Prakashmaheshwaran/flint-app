import Foundation
import FlintCore

/// Replaced only by public B/F adapters, never by direct reads of enforcement preference keys.
struct FlintGlanceReader: FlintGlanceReading {
    static let changedNotificationName = FlintProtectionStatus.changedNotification
    static let widgetKind = FlintProtectionStatus.widgetKind

    func read(at date: Date) -> FlintGlanceInput {
        let protection = readProtection(at: date)
        let snapshot = FlintHistoryStore.shared.readOnlySnapshot()
        let summary = FlintHistoryAggregator.summarize(snapshot, activeSessionID: protection.activeFocusSessionID,
                                                        now: date, dayCount: 1)
        let history = FlintGlanceHistoryAdapter.project(summary, at: date)
        return protection.replacingHistory(history)
    }

    /// The activity manager needs only B's small status blob, never the history log.
    func readProtection(at date: Date) -> FlintGlanceInput {
        guard let status = FlintProtectionStatus.load(now: date) else { return .unavailable(at: date) }
        let session = status.session.flatMap { source -> FlintGlanceInput.Session? in
            // Open-ended Easy sessions remain valid history identities; ActivityKit needs
            // a real finite logical end and never substitutes the freshness deadline.
            guard let end = source.logicalEnd else { return nil }
            let cause: FlintGlanceInput.Session.Cause
            switch source.kind {
            case .session: cause = .manual
            case .pomodoro: cause = .pomodoro
            }
            return .init(id: source.id, cause: cause, tier: source.tier.rawValue.capitalized,
                         isArmed: source.armed, startedAt: source.startedAt, endsAt: end, name: source.name)
        }
        let headline = status.headline.map {
            FlintGlanceInput.Cause(name: $0.displayName, kind: $0.kind.rawValue,
                                  tier: $0.tier.rawValue.capitalized, endsAt: $0.end)
        }
        let next = status.nextOccurrence.map {
            FlintGlanceInput.Occurrence(startsAt: $0.start, endsAt: $0.end, name: $0.displayName)
        }
        let history = FlintGlanceInput.unavailable(at: date).history
        var input = FlintGlanceStatusAdapter.project(version: status.version, observedAt: status.observedAt,
            validUntil: status.validUntil, state: status.state.rawValue, session: session,
            enforcingEnds: status.causes.filter(\.enforcing).map(\.end), next: next,
            recoveryUntil: status.pass.recoveryUntil, degradedReasons: status.degradedReasons,
            history: history, headline: headline, at: date)
        if input.protection != .unavailable && input.protection != .pass,
           let source = status.session, source.armed {
            input.activeFocusSessionID = source.id
        }
        return input
    }
}
