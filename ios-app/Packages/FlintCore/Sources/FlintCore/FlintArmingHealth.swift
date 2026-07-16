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
/// DeviceActivity calls stay in the controllers. The active session's auto-clear and the
/// Sleep-Mode windows also hold slots outside these domains; `capHeadroom` leaves room for them.
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
    /// counts calls made to the framework; `armed` counts calls that returned successfully.
    public struct DomainReport: Codable, Equatable {
        public var enabled: Int
        public var attempted: Int
        public var armed: Int
        public var failures: [Failure]
        public var updatedAt: Date

        public init(
            enabled: Int,
            attempted: Int,
            armed: Int,
            failures: [Failure],
            updatedAt: Date = Date()
        ) {
            self.enabled = enabled
            self.attempted = attempted
            self.armed = armed
            self.failures = failures
            self.updatedAt = updatedAt
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
            case enabled, attempted, armed, failures, updatedAt
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
        }
    }

    /// Empirical warning threshold, not an Apple contract. DeviceActivity refusals have been
    /// observed around this count, but the OS may vary it. Flint never drops registrations to
    /// stay under the threshold; actual refusals are recorded separately.
    public static let observedActivityThreshold = 20

    /// Slots kept free before `isNearCap` warns: the active session's one-shot auto-clear and
    /// Sleep Mode's windows occupy slots that aren't counted in the rule domains below.
    public static let capHeadroom = 3

    /// Reports keyed by domain ("schedules", "limits", "openLimits").
    public var domains: [String: DomainReport]

    public init(domains: [String: DomainReport] = [:]) {
        self.domains = domains
    }

    // MARK: Derived

    /// Total enabled rules across all recorded domains at their last reload.
    public var enabledTotal: Int {
        domains.values.reduce(0) { $0 + $1.enabled }
    }

    /// Total registrations attempted across all recorded domains at their last reload.
    public var attemptedTotal: Int {
        domains.values.reduce(0) { $0 + $1.attempted }
    }

    /// Total registrations successfully armed across all recorded domains at their last reload.
    public var armedTotal: Int {
        domains.values.reduce(0) { $0 + $1.armed }
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
        in store: FlintGroupStore? = FlintGroupStore()
    ) {
        guard let store else { return }
        let merged = store.loadArmingHealth()
            .replacing(domain, with: DomainReport(
                enabled: enabled,
                attempted: attempted,
                armed: armed,
                failures: failures
            ))
        store.saveArmingHealth(merged)
    }
}
