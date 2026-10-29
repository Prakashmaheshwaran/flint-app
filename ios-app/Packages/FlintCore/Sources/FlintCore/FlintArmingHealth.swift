import Foundation

/// The honest ledger of what `DeviceActivityCenter.startMonitoring` actually accepted.
///
/// iOS caps how many activities one app may keep registered at once — past the cap the
/// framework throws (`excessiveActivities`; Apple doesn't document the number, ~20 observed).
/// Flint fans out one activity per schedule rule, one per Time Limit, and one per Open Limit,
/// so a heavy user can reach the cap. These registrations used to be `try?`-swallowed: the
/// rule past the cap simply never fired, with nothing anywhere saying so.
///
/// Every controller `reload()` now records the full enabled → attempted → armed funnel and what
/// failed (keyed by domain, each controller owning its own slice), Settings shows the result, and
/// a near-cap warning fires *before* rules start silently dying. Pure Foundation — all
/// DeviceActivity calls stay in the controllers. Session recovery contributes its own domain;
/// `capHeadroom` keeps a safety margin because Apple does not publish or promise a stable limit.
public struct FlintArmingHealth: Codable, Equatable {

    /// One enabled activity that failed validation or registration, and the reason.
    public struct Failure: Codable, Equatable {
        public let activityName: String
        public let reason: String

        public init(activityName: String, reason: String) {
            self.activityName = activityName
            self.reason = reason
        }
    }

    /// What one controller's `reload()` found, attempted, and successfully armed.
    ///
    /// `enabled` includes locally invalid rules that never reach `startMonitoring`; `attempted`
    /// counts calls made to the framework; `armed` counts accepted requests plus unchanged
    /// definitions already observed in iOS. `observedActivities` is the separate post-write read.
    public struct DomainReport: Codable, Equatable {
        public var enabled: Int
        public var attempted: Int
        public var armed: Int
        public var failures: [Failure]
        public var updatedAt: Date
        /// A read of DeviceActivityCenter, not an inference from a successful start call.
        /// nil means this report predates observations or the layer has no activity to observe.
        public var observedActivities: [String]?
        public var observed: Int? { observedActivities?.count }
        /// Session recovery outcomes. nil identifies older reports or other domains.
        public var notAuthorized: Bool?
        public var rearmFailed: Bool?
        public var recordUnreadable: Bool?
        /// Intentional Emergency Pass suspension, distinct from a failed registration.
        public var suspendedUntil: Date?

        public init(
            enabled: Int,
            attempted: Int,
            armed: Int,
            failures: [Failure],
            updatedAt: Date = Date(),
            observedActivities: [String]? = nil,
            notAuthorized: Bool? = nil,
            rearmFailed: Bool? = nil,
            recordUnreadable: Bool? = nil,
            suspendedUntil: Date? = nil
        ) {
            self.enabled = enabled
            self.attempted = attempted
            self.armed = armed
            self.failures = failures
            self.updatedAt = updatedAt
            self.observedActivities = observedActivities
            self.notAuthorized = notAuthorized
            self.rearmFailed = rearmFailed
            self.recordUnreadable = recordUnreadable
            self.suspendedUntil = suspendedUntil
        }

        /// Source-compatible initializer for callers that only know the pre-funnel model. Old
        /// reports contain registration failures only, so attempted − failures is their best
        /// available armed count.
        public init(attempted: Int, failures: [Failure], updatedAt: Date = Date()) {
            self.init(
                enabled: attempted,
                attempted: attempted,
                armed: max(0, attempted - failures.count),
                failures: failures,
                updatedAt: updatedAt
            )
        }

        private enum CodingKeys: String, CodingKey {
            case enabled, attempted, armed, failures, updatedAt, observedActivities, notAuthorized, rearmFailed, recordUnreadable, suspendedUntil
        }

        /// Existing installs persisted only `attempted`, `failures`, and `updatedAt`. Decode that
        /// shape without discarding the ledger, while every newly encoded report carries all
        /// three funnel counts.
        public init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            attempted = try values.decode(Int.self, forKey: .attempted)
            failures = try values.decode([Failure].self, forKey: .failures)
            updatedAt = try values.decode(Date.self, forKey: .updatedAt)
            enabled = try values.decodeIfPresent(Int.self, forKey: .enabled) ?? attempted
            armed = try values.decodeIfPresent(Int.self, forKey: .armed)
                ?? max(0, attempted - failures.count)
            observedActivities = try values.decodeIfPresent([String].self, forKey: .observedActivities)
            notAuthorized = try values.decodeIfPresent(Bool.self, forKey: .notAuthorized)
            rearmFailed = try values.decodeIfPresent(Bool.self, forKey: .rearmFailed)
            recordUnreadable = try values.decodeIfPresent(Bool.self, forKey: .recordUnreadable)
            suspendedUntil = try values.decodeIfPresent(Date.self, forKey: .suspendedUntil)
        }
    }

    /// Empirical warning threshold, not an Apple contract. DeviceActivity refusals have been
    /// observed around this count, but the OS may vary it. Flint never drops registrations to
    /// stay under the threshold; actual refusals are recorded separately.
    public static let observedActivityThreshold = 20

    /// Safety margin before `isNearCap` warns around an empirical, potentially variable limit.
    /// Session auto-clear and Sleep's ordinary schedule rules are already represented in health.
    public static let capHeadroom = 3

    /// Reports keyed by domain (session, schedules, limits, openLimits, webRestrictions).
    public var domains: [String: DomainReport]

    public init(domains: [String: DomainReport] = [:]) {
        self.domains = domains
    }

    // MARK: Derived

    /// Total enabled activity owners at their last reload; web settings reuse those owners.
    public var enabledTotal: Int {
        domains.filter { $0.key != "webRestrictions" }.values.reduce(0) { $0 + $1.enabled }
    }

    /// Total registrations attempted across all recorded domains at their last reload.
    public var attemptedTotal: Int {
        domains.filter { $0.key != "webRestrictions" }.values.reduce(0) { $0 + $1.attempted }
    }

    /// Total registrations successfully armed; applied web settings do not register activities.
    public var armedTotal: Int {
        domains.filter { $0.key != "webRestrictions" }.values.reduce(0) { $0 + $1.armed }
    }

    /// Every recorded failure, ordered by domain name for stable display.
    public var failures: [Failure] {
        domains.sorted { $0.key < $1.key }.flatMap { $0.value.failures }
    }

    public var isHealthy: Bool { failures.isEmpty }

    /// True when registrations are close enough to the OS cap that the next few rules are at
    /// risk — the UI warns while there is still room to consolidate.
    public var isNearCap: Bool {
        armedTotal >= Self.observedActivityThreshold - Self.capHeadroom
    }

    // MARK: Recording

    /// Replace one domain's report, keeping the others — each controller owns its slice.
    public func replacing(_ domain: String, with report: DomainReport) -> FlintArmingHealth {
        var next = self
        next.domains[domain] = report
        return next
    }

    /// Load-merge-save one domain's report into the App Group. No-ops when the group is
    /// unavailable — recording must never be the thing that breaks arming.
    public static func record(
        domain: String,
        enabled: Int,
        attempted: Int,
        armed: Int,
        failures: [Failure],
        in store: FlintGroupStore? = FlintGroupStore(),
        observedActivities: [String]? = nil
    ) {
        guard let store else { return }
        let merged = store.loadArmingHealth()
            .replacing(domain, with: DomainReport(
                enabled: enabled,
                attempted: attempted,
                armed: armed,
                failures: failures,
                observedActivities: observedActivities
            ))
        store.saveArmingHealth(merged)
    }
}
