import Foundation

/// Field projection of B's public v1 contract. This is not a stored schema or writer.
/// Its primitive seam lets fixtures test mapping before the FlintCore reader is available.
enum FlintGlanceStatusAdapter {
    static let maximumDisplayAge: TimeInterval = 300

    static func project(version: Int, observedAt: Date, validUntil: Date?, state: String,
                        session: FlintGlanceInput.Session?, enforcingEnds: [Date?],
                        next: FlintGlanceInput.Occurrence?, recoveryUntil: Date?,
                        degradedReasons: [String], history: FlintGlanceInput.History,
                        at date: Date) -> FlintGlanceInput {
        func unavailable() -> FlintGlanceInput {
            .init(protection: .unavailable, checkedAt: nil, validUntil: nil, session: nil,
                  protectionEndsAt: nil, next: nil, history: history)
        }
        guard version == 1, observedAt.timeIntervalSince1970.isFinite, observedAt <= date,
              let validUntil, validUntil.timeIntervalSince1970.isFinite,
              validUntil > date, validUntil > observedAt,
              !degradedReasons.contains("time_uncertain") else { return unavailable() }
        let freshness = min(validUntil, observedAt.addingTimeInterval(maximumDisplayAge))
        guard freshness > date else { return unavailable() }
        let protection: FlintGlanceInput.Protection
        switch state {
        case "protected":
            // An inconsistent whole-device success is not a new protection claim.
            guard !enforcingEnds.isEmpty else { return unavailable() }
            protection = !degradedReasons.isEmpty || session.map { !$0.isArmed } == true ? .degraded : .enforcing
        case "not_protected": protection = .idle
        case "degraded": protection = .degraded
        case "suspended":
            protection = recoveryUntil.map { $0 > date } == true ? .pass : .suspended
        case "unavailable": return unavailable()
        default: return unavailable()
        }
        // Until must include the final known contributor; an open-ended contributor
        // prevents inventing a finite whole-protection deadline.
        let end: Date?
        if protection == .pass { end = recoveryUntil }
        else if !enforcingEnds.isEmpty && enforcingEnds.allSatisfy({ $0 != nil }) {
            end = enforcingEnds.compactMap { $0 }.max()
        } else { end = nil }
        return .init(protection: protection, checkedAt: observedAt, validUntil: freshness,
                     session: session, protectionEndsAt: end, next: next, history: history)
    }
}
