import Foundation

/// A checkpoint of the rule's saved-zone timeline, distinct from the device's editable wall
/// clock. `occurrence` retains the resolved UTC identity/endpoints; this anchor never rewrites
/// them for presentation. Optional in ADR-012 records written before PC-54/55 support.
public struct FlintRuleTimeAnchor: Codable, Equatable, Sendable {
    public let timeline: Date
    public let wall: Date
    public let monotonic: TimeInterval
    public let bootID: String?
    public let deviceZoneID: String

    var isValid: Bool {
        timeline.timeIntervalSinceReferenceDate.isFinite && wall.timeIntervalSinceReferenceDate.isFinite
            && monotonic.isFinite && monotonic >= 0
    }
}

/// A bounded repair budget per resolved occurrence. Duplicate native callbacks must not
/// recursively churn the shared DeviceActivity pool or reset an unspent usage threshold.
public struct FlintRuleCarrierRepair: Codable, Equatable, Sendable {
    public let occurrenceID: String
    public let checkpoint: FlintLedgerInstant
    public let attempts: Int
    var isValid: Bool { !occurrenceID.isEmpty && (1...6).contains(attempts)
        && checkpoint.monotonic.isFinite && checkpoint.monotonic >= 0
        && checkpoint.wall.timeIntervalSinceReferenceDate.isFinite }
}

#if canImport(FamilyControls) && canImport(DeviceActivity) && canImport(ManagedSettings)
/// One authority for rule activation, natural ends, queue due-ness and display (ADR-012 §9).
/// Even an inactive/future rule gets a timeline checkpoint, so a wall jump cannot start it.
enum FlintRuleOccurrenceClock {
    static func date<Rule: FlintGatedRule>(for rule: Rule, clock: FlintClock) -> Date {
        guard let anchor = rule.protection?.timeAnchor else { return clock.now() }
        let checkpoint = FlintLedgerInstant(wall: anchor.wall, monotonic: anchor.monotonic, bootID: anchor.bootID)
        let instant = FlintLedgerInstant(clock: clock)
        if let elapsed = checkpoint.elapsed(until: instant) {
            // With missing boot identity, an undetected reboot can only under-count elapsed.
            // Keep that conservative timeline for finite restrictions as well as counters.
            return anchor.timeline.addingTimeInterval(elapsed)
        }
        // Daily counts preserve the last checkpoint's remaining-to-reset across reboot. No
        // powered-off time or new wall date proves a refill (PC-60). Finite schedules instead
        // use the bounded wall fallback; an already-past saved end is over.
        guard Rule.domain == .schedules, let oldBoot = anchor.bootID,
              let newBoot = instant.bootID, oldBoot != newBoot else { return anchor.timeline }
        if let window = rule.protection?.occurrence, window.contains(anchor.timeline) {
            let remaining = min(window.end.timeIntervalSince(anchor.timeline),
                                max(0, window.end.timeIntervalSince(clock.now())))
            return remaining > 0 ? window.end.addingTimeInterval(-remaining) : max(window.end, clock.now())
        }
        return clock.now()
    }

    static func current<Rule: FlintGatedRule>(for rule: Rule, clock: FlintClock) -> FlintRuleWindow? {
        guard rule.enabled else { return nil }
        let now = date(for: rule, clock: clock)
        if let frozen = rule.protection?.occurrence, frozen.contains(now) { return frozen }
        // Readers cannot activate the old template's successor ahead of its accepted queue.
        // A failed apply explicitly freezes the retained next occurrence before retrying.
        if let queue = rule.protection?.queuedChange, queue.window.end <= now { return nil }
        return rule.templateOccurrence(at: now)
    }

    static func isOver<Rule: FlintGatedRule>(_ window: FlintRuleWindow, for rule: Rule, clock: FlintClock) -> Bool {
        date(for: rule, clock: clock) >= window.end
    }

    static func display<Rule: FlintGatedRule>(_ window: FlintRuleWindow, for rule: Rule, clock: FlintClock) -> FlintRuleWindow {
        let shift = clock.now().timeIntervalSince(date(for: rule, clock: clock))
        if abs(shift) <= FlintTimeIntegrity.discontinuityThreshold { return window }
        return .init(start: window.start.addingTimeInterval(shift), end: window.end.addingTimeInterval(shift))
    }

    /// Called only inside the rule transition lock, before any policy decision. Persisting
    /// both progression clocks makes a second discontinuity detectable without duplicating the
    /// previous one; a process restart in the same boot credits exactly the observed elapsed.
    @discardableResult
    static func observe<Rule: FlintGatedRule>(_ rule: inout Rule, clock: FlintClock) -> String? {
        guard var metadata = rule.protection else { return nil }
        let timeline = date(for: rule, clock: clock)
        let previous = metadata.timeAnchor
        let note: String?
        if let previous {
            let verdict = FlintTimeIntegrity.verdict(wallStart: previous.wall, monotonicStart: previous.monotonic,
                bootSessionID: previous.bootID, now: clock.now(), monotonicNow: clock.monotonic(),
                currentBootSessionID: clock.bootSessionID())
            switch verdict {
            case let .wallAhead(seconds): note = "wallAhead: device clock advanced \(Int(seconds))s; rule boundaries kept on trusted time."
            case let .wallBehind(seconds): note = "wallBehind: device clock moved back \(Int(seconds))s; rule boundaries kept on trusted time."
            case .unverifiable: note = "time_uncertain: reboot or unavailable boot identity; bounded rule fallback applied."
            case .consistent:
                note = previous.deviceZoneID != clock.calendar().timeZone.identifier
                    ? "timeZoneChanged: saved rule zone and occurrence endpoints preserved." : nil
            }
        } else { note = nil }
        if previous == nil, metadata.occurrence == nil { metadata.occurrence = rule.templateOccurrence(at: timeline) }
        metadata.timeAnchor = .init(timeline: timeline, wall: clock.now(), monotonic: clock.monotonic(),
            bootID: clock.bootSessionID(), deviceZoneID: clock.calendar().timeZone.identifier)
        rule.protection = metadata
        return note
    }
}
#endif
