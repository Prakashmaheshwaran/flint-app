import Foundation
import FlintCore

/// Read-only presentation of lane B's contributor query and Open-credit preview. No storage
/// or enforcement work belongs here.
///
/// **Open credits (ADR-012 §5):** the secondary button is the only control that can spend a
/// credit, and it exists only when the preview shows an available credit. The primary button
/// is always "Go back" and never charges. The action extension cannot see which labels Apple
/// drew, so a stale or cached shield must never offer a spend that it did not draw.
struct ShieldCopy: Equatable {
    enum Unit { case app, site }

    /// What the configuration extension knows about Open credits for this shield.
    enum OpenCredit: Equatable {
        /// An enabled Open Limit covers this exact app/site token.
        case target(FlintOpenCreditPreview)
        /// The shield comes from a category that an enabled Open Limit selected. Opaque
        /// category tokens are never credit targets.
        case categoryTarget
        /// An Open Limit covers this shield, but this build's shield acceptance gate is off.
        case acceptanceDisabled
    }

    let title: String
    let subtitle: String
    let primaryButtonLabel: String
    /// Non-nil only for an available credit; `FlintShieldAction` spends only on this button.
    let secondaryButtonLabel: String?

    init(name: String?, unit: Unit, contributors: FlintProtectionStatus.Contributors?,
         now: Date, openCredit: OpenCredit? = nil,
         locale: Locale = .current, timeZone: TimeZone = .current) {
        if let name {
            title = String(localized: "\(name) is resting.", locale: locale)
        } else {
            switch unit {
            case .app: title = String(localized: "This app is resting.", locale: locale)
            case .site: title = String(localized: "This site is resting.", locale: locale)
            }
        }
        primaryButtonLabel = String(localized: "Go back", locale: locale)

        let credit = openCredit.flatMap {
            Self.creditCopy($0, unit: unit, locale: locale, timeZone: timeZone)
        }
        subtitle = credit?.subtitle
            ?? Self.causeSubtitle(contributors, now: now, locale: locale, timeZone: timeZone)
            ?? String(localized: "Notice the reach, then let it pass.", locale: locale)
        secondaryButtonLabel = credit?.offer
    }

    /// Whether the preview offers a credit: no blocking cause, no live grant, a positive pool
    /// and a positive (possibly clipped) interval. This is necessary for `accept` to charge, not
    /// sufficient: accept still re-checks capacity, restoration and registration at press time.
    static func offersCredit(_ preview: FlintOpenCreditPreview) -> Bool {
        guard preview.blockedBy == nil, preview.grant == nil,
              let remaining = preview.remaining, remaining > 0,
              let duration = preview.duration, duration > 0 else { return false }
        return true
    }

    private static func creditCopy(_ credit: OpenCredit, unit: Unit, locale: Locale,
                                   timeZone: TimeZone) -> (subtitle: String, offer: String?)? {
        let preview: FlintOpenCreditPreview
        switch credit {
        case .acceptanceDisabled:
            return (String(localized: "Open credits aren't available yet", locale: locale), nil)
        case .categoryTarget:
            switch unit {
            case .app:
                return (String(localized: "Open credits work for individual apps — pick apps in Flint", locale: locale), nil)
            case .site:
                return (String(localized: "Open credits work for individual sites — pick sites in Flint", locale: locale), nil)
            }
        case let .target(value):
            preview = value
        }

        if offersCredit(preview), let remaining = preview.remaining, let duration = preview.duration {
            let subtitle: String
            if remaining == 1 {
                subtitle = String(localized: "Last credit today", locale: locale)
            } else {
                let cap = preview.cap ?? remaining
                subtitle = String(localized: "\(remaining) of \(cap) credits left today", locale: locale)
            }
            return (subtitle, offerLabel(duration: duration, locale: locale))
        }

        // A committed credit keeps its clock even when another cause now blocks the target,
        // so "no credit used" is only claimed when no grant is live.
        let tail = preview.grant.map { _ in activeCredit(preview.duration, locale: locale) }
            ?? String(localized: "no credit used", locale: locale)

        if preview.grant != nil, preview.blockedBy == nil {
            // The shield is visible, so the target may not be open right now. Describe the
            // committed credit, not current access, and never offer a replay: a stale press
            // after its deadline would spend a new credit.
            let minutes = Int(max(0, preview.duration ?? 0) / 60)
            let subtitle = minutes >= 1
                ? String(localized: "Credit active for \(minutes) more min · no extra credit used", locale: locale)
                : String(localized: "Credit active for under 1 more min · no extra credit used", locale: locale)
            return (subtitle, nil)
        }

        switch preview.blockedBy {
        case nil where preview.remaining == 0, .exhausted?:
            if (preview.cap ?? 1) <= 0 {
                // A zero allowance never refills at the reset.
                return (String(localized: "This Open Limit allows no credits", locale: locale), nil)
            }
            if preview.timeUncertain {
                // The reset follows elapsed time; only its wall-clock estimate is uncertain.
                return (String(localized: "No credits left today · the reset time is uncertain", locale: locale), nil)
            }
            guard let resetAt = preview.resetAtEstimate else {
                return (String(localized: "No credits left today", locale: locale), nil)
            }
            let time = shortTime(resetAt, locale: locale, timeZone: timeZone)
            return (String(localized: "No credits left today · resets about \(time)", locale: locale), nil)
        case nil:
            // No pool, or no positive interval before the reset: accept would refuse.
            return (unavailable(locale), nil)
        case .otherContributor?:
            let lead = otherContributor(preview.blockingContributors, unit: unit, locale: locale)
            return (String(localized: "\(lead) · \(tail)", locale: locale), nil)
        case .categoryOverlap?:
            let lead = unit == .app
                ? String(localized: "Another category rule may still block this app", locale: locale)
                : String(localized: "Another category rule may still block this site", locale: locale)
            return (String(localized: "\(lead); \(tail)", locale: locale), nil)
        case .identityNeedsReselection?:
            let lead = unit == .app
                ? String(localized: "Flint can't match this app to its credits yet", locale: locale)
                : String(localized: "Flint can't match this site to its credits yet", locale: locale)
            return (String(localized: "\(lead) · \(tail)", locale: locale), nil)
        case .notOpenLimited?:
            // The service found no covering Open Limit: present the shield's ordinary cause.
            return nil
        case .applying?:
            // Committed before native release failed: the credit is spent.
            return (String(localized: "A credit was used, but access hasn't opened yet", locale: locale), nil)
        case .unavailable?, .registrationFailed?, .staleOperation?, .safetyFloor?:
            return (unavailable(locale), nil)
        }
    }

    private static func activeCredit(_ duration: TimeInterval?, locale: Locale) -> String {
        let minutes = Int(max(0, duration ?? 0) / 60)
        return minutes >= 1
            ? String(localized: "credit active for \(minutes) more min", locale: locale)
            : String(localized: "credit active for under 1 more min", locale: locale)
    }

    /// Whole minutes, rounded down, so a midnight-clipped credit never reads longer than it is.
    private static func offerLabel(duration: TimeInterval, locale: Locale) -> String {
        let minutes = Int(min(300, duration) / 60)
        switch minutes {
        case 5...: return String(localized: "Use one credit · 5 minutes", locale: locale)
        case 1: return String(localized: "Use one credit · 1 minute", locale: locale)
        case 2...4: return String(localized: "Use one credit · \(minutes) minutes", locale: locale)
        default: return String(localized: "Use one credit · under 1 minute", locale: locale)
        }
    }

    /// Kinds only: saved rule names never appear on the shield for another cause. The names
    /// are `FlintOpenCredit.otherStores()` display names, pinned by `ShieldCopyTests`.
    private static func otherContributor(_ names: [String], unit: Unit, locale: Locale) -> String {
        let session = names.contains("Current session")
        let schedule = names.contains { $0.hasPrefix("Schedule: ") }
        let timeLimit = names.contains { $0.hasPrefix("Time Limit: ") }
        let safari = names.contains("Safari restriction")
        switch (unit, session, schedule, timeLimit, safari) {
        case (.app, true, _, _, _): return String(localized: "A focus session still blocks this app", locale: locale)
        case (.site, true, _, _, _): return String(localized: "A focus session still blocks this site", locale: locale)
        case (.app, _, true, _, _): return String(localized: "A schedule still blocks this app", locale: locale)
        case (.site, _, true, _, _): return String(localized: "A schedule still blocks this site", locale: locale)
        case (.app, _, _, true, _): return String(localized: "A Time Limit still blocks this app", locale: locale)
        case (.site, _, _, true, _): return String(localized: "A Time Limit still blocks this site", locale: locale)
        case (.site, _, _, _, true): return String(localized: "A Safari restriction still blocks this site", locale: locale)
        case (.app, _, _, _, _): return String(localized: "Another protection still blocks this app", locale: locale)
        case (.site, _, _, _, _): return String(localized: "Another protection still blocks this site", locale: locale)
        }
    }

    /// Unreadable state cannot prove that no credit was committed, so it claims nothing more.
    private static func unavailable(_ locale: Locale) -> String {
        String(localized: "Flint can't confirm your credits right now", locale: locale)
    }

    private static func shortTime(_ date: Date, locale: Locale, timeZone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: date)
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
            let time = shortTime(end, locale: locale, timeZone: timeZone)
            short = String(localized: "\(strongest.displayName), until \(time).", locale: locale)
            withReminder = String(localized: "\(strongest.displayName), until \(time). Notice the reach, then let it pass.", locale: locale)
        }
        // Apple owns layout and exposes no fit measurement. Keep the optional reminder only
        // for compact localized copy; long names retain the complete cause and deadline.
        return withReminder.count <= 90 ? withReminder : short
    }
}
