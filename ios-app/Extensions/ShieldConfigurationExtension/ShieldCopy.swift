import Foundation
import FlintCore

/// Read-only presentation of lane B's contributor query. No storage or enforcement work
/// belongs here: Open-limit labels still follow the existing enforcer's remaining value.
struct ShieldCopy: Equatable {
    enum Unit { case app, site }

    let title: String
    let subtitle: String
    let primaryButtonLabel: String
    let secondaryButtonLabel: String? = nil

    init(name: String?, unit: Unit, contributors: FlintProtectionStatus.Contributors?,
         now: Date, remainingOpens: Int?, openStoreAvailable: Bool,
         locale: Locale = .current, timeZone: TimeZone = .current) {
        if let name {
            title = String(localized: "\(name) is resting.", locale: locale)
        } else {
            switch unit {
            case .app: title = String(localized: "This app is resting.", locale: locale)
            case .site: title = String(localized: "This site is resting.", locale: locale)
            }
        }

        switch remainingOpens {
        case .some(let left) where left > 0:
            // Keep the action extension's current wording until lane B changes its semantics.
            subtitle = left == 1
                ? String(localized: "\(left) intentional open left today. Notice the reach first.", locale: locale)
                : String(localized: "\(left) intentional opens left today. Notice the reach first.", locale: locale)
            switch unit {
            case .app:
                primaryButtonLabel = String(localized: "Use app (\(left) left)", locale: locale)
            case .site:
                primaryButtonLabel = String(localized: "Use site (\(left) left)", locale: locale)
            }
        case .some:
            subtitle = openStoreAvailable
                ? String(localized: "Today’s opens are spent. It opens again tomorrow.", locale: locale)
                : String(localized: "Opens are unavailable right now.", locale: locale)
            primaryButtonLabel = String(localized: "Go back", locale: locale)
        case .none:
            subtitle = Self.causeSubtitle(contributors, now: now, locale: locale, timeZone: timeZone)
                ?? String(localized: "Notice the reach, then let it pass.", locale: locale)
            primaryButtonLabel = String(localized: "Go back", locale: locale)
        }
    }

    private static func causeSubtitle(_ contributors: FlintProtectionStatus.Contributors?,
                                      now: Date, locale: Locale, timeZone: TimeZone) -> String? {
        guard let contributors, !contributors.coverageUncertain,
              contributors.state != .unavailable, contributors.state != .suspended else { return nil }
        // contributors(for:) preserves the status builder's tier / latest-end / stable-ID
        // ordering. Do not introduce a second policy ordering in the configuration extension.
        let enforcing = contributors.causes.filter { $0.enforcing && ($0.end.map { $0 > now } ?? true) }
        guard let strongest = enforcing.first else { return nil }

        let short: String
        let withReminder: String
        if enforcing.contains(where: { $0.end == nil }) {
            // An unknown end on a strict cause must not be described as stoppable.
            guard enforcing.allSatisfy({ $0.end != nil || $0.tier == .easy }) else { return nil }
            short = String(localized: "\(strongest.displayName), until you stop.", locale: locale)
            withReminder = String(localized: "\(strongest.displayName), until you stop. Notice the reach, then let it pass.", locale: locale)
        } else {
            guard let end = enforcing.compactMap(\.end).max() else { return nil }
            let formatter = DateFormatter()
            formatter.locale = locale
            formatter.timeZone = timeZone
            formatter.dateStyle = .none
            formatter.timeStyle = .short
            let time = formatter.string(from: end)
            short = String(localized: "\(strongest.displayName), until \(time).", locale: locale)
            withReminder = String(localized: "\(strongest.displayName), until \(time). Notice the reach, then let it pass.", locale: locale)
        }
        // Apple owns layout and exposes no fit measurement. Keep the optional reminder only
        // for compact localized copy; long names retain the complete cause and deadline.
        return withReminder.count <= 90 ? withReminder : short
    }
}
