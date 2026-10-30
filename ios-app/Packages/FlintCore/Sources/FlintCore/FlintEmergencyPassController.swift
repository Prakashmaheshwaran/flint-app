import Foundation
import CryptoKit

/// Exact occurrence metadata shown before spending. Opaque threshold evidence stays in the
/// local enforcement ledger, never in the published pass lifecycle payload (ADR-012 §11).
public struct FlintPassCommitment: Codable, Equatable, Sendable, Identifiable {
    public enum Kind: String, Codable, Sendable {
        case schedule, oneShot, sleep, timeLimit, openLimit
    }
    public let id: String
    public let name: String
    public let kind: Kind
    public let naturalStart: Date
    public let naturalEnd: Date
    public let storeName: String
    public let activityName: String
    public let wasEnforcing: Bool
    public let tier: BreakLevel?
    /// Local threshold evidence only. Lifecycle publication receives the session list, never
    /// these opaque target encodings. A provable strengthening retains an exhausted budget.
    public let timeLimitThreshold: FlintPassLimitThreshold?

    public init(id: String, name: String, kind: Kind, naturalStart: Date, naturalEnd: Date,
                storeName: String, activityName: String, wasEnforcing: Bool = true, tier: BreakLevel? = nil,
                timeLimitThreshold: FlintPassLimitThreshold? = nil) {
        self.id = id; self.name = name; self.kind = kind
        self.naturalStart = naturalStart; self.naturalEnd = naturalEnd
        self.storeName = storeName; self.activityName = activityName; self.wasEnforcing = wasEnforcing
        self.tier = tier
        self.timeLimitThreshold = timeLimitThreshold
    }
}

public struct FlintPassLimitThreshold: Codable, Equatable, Sendable {
    public let window: DateInterval
    public let fingerprint: String
    public let thresholdMinutes: Int
    public let applications: Set<String>
    public let categories: Set<String>
    public let webDomains: Set<String>
    public let includeEntireCategory: Bool

    public init(window: DateInterval, thresholdMinutes: Int, applications: Set<String>, categories: Set<String>,
                webDomains: Set<String>, includeEntireCategory: Bool) {
        self.window = window; self.thresholdMinutes = thresholdMinutes
        self.applications = applications; self.categories = categories; self.webDomains = webDomains
        self.includeEntireCategory = includeEntireCategory
        fingerprint = Self.digest(thresholdMinutes: thresholdMinutes, applications: applications,
            categories: categories, webDomains: webDomains, includeEntireCategory: includeEntireCategory)
    }

    var isValid: Bool {
        window.start.timeIntervalSinceReferenceDate.isFinite
            && window.end.timeIntervalSinceReferenceDate.isFinite
            && window.duration > 0 && window.duration <= 26 * 3600
            && thresholdMinutes >= 0 && thresholdMinutes <= Int.max / 60
            && applications.count <= FlintShieldLimits.tokenCap && webDomains.count <= FlintShieldLimits.tokenCap
            && categories.count <= 4096
            && applications.union(categories).union(webDomains).allSatisfy { !$0.isEmpty && $0.utf8.count <= 65536 }
            && fingerprint == Self.digest(thresholdMinutes: thresholdMinutes, applications: applications,
                categories: categories, webDomains: webDomains, includeEntireCategory: includeEntireCategory)
    }

    /// No usage reset for the same daily window or a provable strengthening. Opaque category
    /// changes cannot prove containment, and a higher budget cannot inherit exhaustion.
    func provesExhaustion(of current: Self, at now: Date) -> Bool {
        isValid && current.isValid && window.start <= now && now < window.end
            && current.window.start <= window.start && window.end <= current.window.end
            && current.window.start <= now && now < current.window.end
            && current.thresholdMinutes <= thresholdMinutes
            && applications.isSubset(of: current.applications) && webDomains.isSubset(of: current.webDomains)
            && categories == current.categories && includeEntireCategory == current.includeEntireCategory
    }

    private static func digest(thresholdMinutes: Int, applications: Set<String>, categories: Set<String>,
                               webDomains: Set<String>, includeEntireCategory: Bool) -> String {
        let sections = [[String(thresholdMinutes), String(includeEntireCategory)], applications.sorted(),
                        categories.sorted(), webDomains.sorted()]
        let data = (try? JSONEncoder().encode(sections)) ?? Data()
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

public struct FlintPassScope: Codable, Equatable, Sendable {
    public let revision: String
    public let sessions: [FlintActiveSession]
    public let rules: [FlintPassCommitment]
    /// Includes every validated personal rule store, so stale derived settings release too.
    public let stores: [String]

    public init(revision: String, sessions: [FlintActiveSession], rules: [FlintPassCommitment], stores: [String]) {
        self.revision = revision; self.sessions = sessions; self.rules = rules; self.stores = stores
    }

    public var hasActiveCommitment: Bool { !sessions.isEmpty || !rules.isEmpty }
}

public struct FlintPassPreview: Equatable, Sendable {
    public let scope: FlintPassScope
    public let resumeAt: Date
    public let nextPassAt: Date
    public let issuedAt: FlintLedgerInstant
    public var revision: String {
        scope.revision + "." + ((try? JSONEncoder().encode(issuedAt))?.base64EncodedString() ?? "")
    }
}

/// One durable outbox for the last redemption. Flags are acknowledged only after the effect;
/// replay uses the same IDs, original effective time and remaining recovery, never a new spend.
public struct FlintPassRecovery: Codable, Equatable, Sendable {
    public let version: Int
    public let operationID: String
    public let acceptedAt: FlintLedgerInstant
    public let recoveryEnd: Date
    public let carrierName: String
    public let scope: FlintPassScope
    public var releasePending: Bool
    public var eventPending: Bool
    public var resumePending: Bool
    /// Pass-authorized disabling is itself desired state, persisted before touching templates.
    public var disabledRules: [FlintPassCommitment]
    public var cancelledCarriers: [String]?

    public init(operationID: String, at instant: FlintLedgerInstant, scope: FlintPassScope, carrierName: String) {
        version = 1
        self.operationID = operationID; acceptedAt = instant
        recoveryEnd = instant.wall.addingTimeInterval(FlintProtectionConstants.passRecovery)
        self.carrierName = carrierName; self.scope = scope
        releasePending = true; eventPending = true; resumePending = true; disabledRules = []
        cancelledCarriers = []
    }

    func needsApplication(isRecovering: Bool) -> Bool {
        releasePending || eventPending || !disabledRules.isEmpty || (!isRecovering && resumePending)
    }

    var isValid: Bool {
        version == 1 && !operationID.isEmpty && operationID.utf8.count <= 128
            && !scope.revision.isEmpty && scope.revision.utf8.count <= 512 && scope.hasActiveCommitment
            && carrierName.hasPrefix(FlintEmergencyPassController.carrierPrefix)
            && carrierName.utf8.count > FlintEmergencyPassController.carrierPrefix.utf8.count
            && carrierName.utf8.count <= 128
            && acceptedAt.wall.timeIntervalSinceReferenceDate.isFinite
            && acceptedAt.monotonic.isFinite && acceptedAt.monotonic >= 0
            && abs(recoveryEnd.timeIntervalSince(acceptedAt.wall) - FlintProtectionConstants.passRecovery) < 0.001
            && scope.sessions.count <= 1
            && scope.sessions.allSatisfy { !$0.id.isEmpty && $0.id.utf8.count <= 128
                && $0.startedAt.timeIntervalSinceReferenceDate.isFinite
                && ($0.endsAt?.timeIntervalSinceReferenceDate.isFinite ?? true) }
            && Set(scope.sessions.map(\.id)).count == scope.sessions.count
            && Set(scope.rules.map(\.id)).count == scope.rules.count
            && Set(disabledRules.map(\.id)).count == disabledRules.count
            && cancelledCarriers != nil
            && (cancelledCarriers?.allSatisfy(Self.isOpenCreditCarrier) ?? false)
            && scope.rules.allSatisfy { Self.validRule($0)
                && $0.naturalStart <= acceptedAt.wall && acceptedAt.wall < $0.naturalEnd
                && scope.stores.contains($0.storeName) }
            && disabledRules.allSatisfy(Self.validRule)
            && scope.stores.allSatisfy { $0 == "flint" || $0.hasPrefix("flint.")
                || $0.hasPrefix("flintL.") || $0.hasPrefix("flintO.") }
    }

    private static func validRule(_ rule: FlintPassCommitment) -> Bool {
        let idPrefix: String
        let storePrefix: String
        let activityPrefix: String
        switch rule.kind {
        case .schedule, .oneShot, .sleep:
            idPrefix = "schedule:"; storePrefix = "flint."; activityPrefix = "flint.rule."
        case .timeLimit:
            idPrefix = "timeLimit:"; storePrefix = "flintL."; activityPrefix = "flint.limit."
            guard let threshold = rule.timeLimitThreshold, threshold.isValid,
                  threshold.window.start == rule.naturalStart, threshold.window.end == rule.naturalEnd else { return false }
        case .openLimit:
            idPrefix = "openLimit:"; storePrefix = "flintO."; activityPrefix = "flint.openLimit."
        }
        guard rule.id.hasPrefix(idPrefix) else { return false }
        let id = String(rule.id.dropFirst(idPrefix.count))
        guard !id.isEmpty, id.utf8.count <= 100,
              id.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || ".-_".contains($0)) }),
              rule.storeName == storePrefix + id else { return false }
        let baseActivity = activityPrefix + id
        if rule.activityName != baseActivity {
            guard rule.activityName.hasPrefix(baseActivity + ".") else { return false }
            var suffix = String(rule.activityName.dropFirst(baseActivity.count))
            if suffix.hasPrefix(".i") {
                let parts = suffix.dropFirst(2).split(separator: ".", omittingEmptySubsequences: false)
                guard parts.count == 2, parts[0].count == 32,
                      parts[0].allSatisfy({ "0123456789abcdef".contains($0) }) else { return false }
                suffix = "." + String(parts[1])
            }
            let digits = suffix.dropFirst(2)
            guard suffix.hasPrefix(".r"), !digits.isEmpty, digits.allSatisfy({ "0123456789".contains($0) }),
                  let revision = Int(digits), revision > 0, revision < Int.max / 2 else { return false }
        }
        return !rule.id.isEmpty && rule.id.utf8.count <= 128 && !rule.activityName.isEmpty
            && rule.activityName.utf8.count <= 256
            && rule.naturalStart.timeIntervalSinceReferenceDate.isFinite
            && rule.naturalEnd.timeIntervalSinceReferenceDate.isFinite
            && rule.naturalEnd > rule.naturalStart
            && rule.naturalEnd.timeIntervalSince(rule.naturalStart) <= 26 * 3600
    }

    private static func isOpenCreditCarrier(_ name: String) -> Bool {
        let prefix = "flint.open."
        let suffix = name.dropFirst(prefix.count)
        return name.hasPrefix(prefix) && suffix.count == 16
            && suffix.allSatisfy { "0123456789abcdef".contains($0) }
    }
}

/// A capability for one positively read-back pass outbox. It has no memberwise or
/// decoding initializer: ordinary editors cannot manufacture the bypass to tier authorization.
/// The live writer independently verifies it against the durable pending list before writing.
struct FlintPassDisableAuthorization: Sendable {
    let operationID: String
    let rules: [FlintPassCommitment]
    let scopeRevision: String
    let acceptedAt: FlintLedgerInstant

    fileprivate init(_ recovery: FlintPassRecovery) {
        operationID = recovery.operationID; rules = recovery.disabledRules
        scopeRevision = recovery.scope.revision; acceptedAt = recovery.acceptedAt
    }
}

public struct FlintEmergencyPassStatus: Equatable, Sendable {
    public let pass: FlintPassStatus
    public let nextAvailableAt: Date?
    public let recoveryUntil: Date?
    public let recovery: FlintPassRecovery?
    public let timeUncertain: Bool
    public var needsApplication: Bool {
        recovery?.needsApplication(isRecovering: pass.isRecovering) ?? false
    }
    public var isAvailable: Bool { pass.eligibility == .available && !pass.isRecovering && !needsApplication }

    public var label: String {
        if needsApplication { return "Saved recovery needs to finish applying" }
        if let recoveryUntil { return "Recovery until \(Self.dateText(recoveryUntil))" }
        switch pass.eligibility {
        case .available: return "Available"
        case .nothingToRecover: return "Available"
        case .unavailable: return "Protection state unavailable"
        case .coolingDown:
            return nextAvailableAt.map { "Next pass: \(Self.dateText($0))\(timeUncertain ? " (estimate; adjusted after restart or clock change)" : "")" }
                ?? "Protection state unavailable"
        }
    }

    public static func dateText(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy 'at' h:mm a zzz"
        return formatter.string(from: date)
    }
}

public enum FlintEmergencyPassError: Error, LocalizedError, Equatable, Sendable {
    case unavailable
    case scopeUnavailable
    case stalePreview
    case nothingToRecover
    case coolingDown(remaining: TimeInterval)
    case resumeProtectionFirst
    case focusCycleRequired
    case carrierUnavailable
    case applying

    public var errorDescription: String? {
        switch self {
        case .unavailable: return "Protection state unavailable. No new release was authorized."
        case .scopeUnavailable: return "Flint couldn't read the complete recovery scope. Check protection and try again."
        case .stalePreview: return "Your commitments changed. Review the refreshed list before using a pass."
        case .nothingToRecover: return "There is no active personal commitment to recover from. No pass was used."
        case .coolingDown: return "Your next pass is still cooling down. No pass was used."
        case .resumeProtectionFirst: return "Resume protection first"
        case .focusCycleRequired: return "Turn this Focus off, then on again to start a fresh block."
        case .carrierUnavailable: return "iOS couldn't schedule protection to resume. No pass was used."
        case .applying: return "Recovery was saved. Flint still needs to finish applying it; retry to continue the same operation."
        }
    }
}

/// Testable effect boundary. The live environment also holds the shared policy transition
/// lock across validation/commit/effects; ledger transactions remain small and contain no OS release.
struct FlintEmergencyPassEnvironment {
    var ledger: () -> FlintProtectionLedger?
    var clock: FlintClock
    var scope: (FlintLedgerState, FlintLedgerInstant) throws -> FlintPassScope
    var registerCarrier: (String, Date, Date) throws -> Void
    var stopCarrier: (String) -> Void
    var terminate: ([FlintActiveSession], Date) throws -> Void
    var release: (FlintPassScope) throws -> Void
    var resume: (FlintLedgerState, FlintLedgerInstant) throws -> Void
    var disable: (FlintPassDisableAuthorization) throws -> Void
    var emit: (FlintPassRecovery) -> Void
    var publish: () -> Void
    var transition: (() -> Void) -> Bool
    /// Fault injection: a crash after commit leaves every outbox item pending.
    var afterCommit: () throws -> Void
    /// Cancel ordinary/credit grants within the consumption transaction, preserving counters.
    /// Returns their carrier names for post-commit cancellation.
    var cancelGrants: (inout FlintLedgerState) -> [String]
    /// Reconcile already-authorized natural ends/queued edits before taking a spend preview.
    /// Runs under the transition lock and outside the ledger transaction.
    var prepareScope: () throws -> Void
    /// Display-only scope must never migrate or acquire a policy transition.
    var readScope: (FlintLedgerState, FlintLedgerInstant) throws -> FlintPassScope

    init(ledger: @escaping () -> FlintProtectionLedger?, clock: FlintClock,
                scope: @escaping (FlintLedgerState, FlintLedgerInstant) throws -> FlintPassScope,
                registerCarrier: @escaping (String, Date, Date) throws -> Void,
                stopCarrier: @escaping (String) -> Void = { _ in },
                terminate: @escaping ([FlintActiveSession], Date) throws -> Void,
                release: @escaping (FlintPassScope) throws -> Void,
                resume: @escaping (FlintLedgerState, FlintLedgerInstant) throws -> Void,
                disable: @escaping (FlintPassDisableAuthorization) throws -> Void = { _ in },
                emit: @escaping (FlintPassRecovery) -> Void,
                publish: @escaping () -> Void = {},
                transition: @escaping (() -> Void) -> Bool = { body in body(); return true },
                afterCommit: @escaping () throws -> Void = {},
                cancelGrants: @escaping (inout FlintLedgerState) -> [String] = { $0.cancelAccessGrantsForPass() },
                prepareScope: @escaping () throws -> Void = {},
                readScope: ((FlintLedgerState, FlintLedgerInstant) throws -> FlintPassScope)? = nil) {
        self.ledger = ledger; self.clock = clock; self.scope = scope
        self.registerCarrier = registerCarrier; self.stopCarrier = stopCarrier
        self.terminate = terminate; self.release = release; self.resume = resume; self.disable = disable
        self.emit = emit; self.publish = publish; self.transition = transition
        self.afterCommit = afterCommit
        self.cancelGrants = cancelGrants
        self.prepareScope = prepareScope
        self.readScope = readScope ?? scope
    }
}

extension FlintLedgerState {
    /// Same transaction as consumption. Grant/callback authority ends now; the carrier stops
    /// only after the containing recovery intent has been durably read back.
    public mutating func cancelAccessGrantsForPass() -> [String] {
        let carriers = openCredits.grants.values.map(\.activityName).sorted()
        openCredits.grants = [:]
        openCredits.carrierCheckpoints = [:]
        return carriers
    }
}

public final class FlintEmergencyPassController {
    public static let carrierPrefix = "flint.recovery."
    /// Don't accept an old open confirmation with materially stale displayed resume times.
    public static let previewLifetime: TimeInterval = 60
    private let env: FlintEmergencyPassEnvironment
    private static let transitionMarker = "FlintEmergencyPassController.policyTransition"
    /// Live rule/credit mutation boundaries skip a redundant outbox drain while the pass
    /// controller itself is restoring them. This is not a release or tier authorization.
    static var isInPolicyTransition: Bool { Thread.current.threadDictionary[transitionMarker] as? Bool == true }

    init(environment: FlintEmergencyPassEnvironment) { env = environment }

    public static func isRecoveryActivity(_ activity: String) -> Bool {
        activity.hasPrefix(carrierPrefix)
    }

    /// A callback is only a wake-up. Monotonic status decides the boundary; an early,
    /// duplicated or orphan carrier cannot truncate/extend recovery or spend another pass.
    /// An unreadable ledger never proves a carrier orphan: keep its last-good registration.
    public func handleRecoveryBoundary(activity: String) {
        guard Self.isRecoveryActivity(activity) else { return }
        _ = serialized { () -> Result<Void, FlintEmergencyPassError> in
            guard let ledger = self.env.ledger(), case let .success(state) = ledger.peek(),
                  Self.hasValidRecovery(state) else { return .failure(.unavailable) }
            guard state.emergencyRecovery?.carrierName == activity else {
                self.env.stopCarrier(activity); return .success(())
            }
            return self.drain(ledger).map { _ in () }
        }
    }

    /// Start authority is separate from pass eligibility: cooldown permits a new session,
    /// while global recovery and a pass-ended still-on Focus do not. No pass is spent here.
    public func prepareForNewSession(focusOwnerID: String? = nil) -> Result<Void, FlintEmergencyPassError> {
        serialized {
            guard let ledger = self.env.ledger() else { return .failure(.unavailable) }
            if case let .failure(error) = self.drain(ledger) { return .failure(error) }
            guard case let .success(state) = ledger.peek(), Self.hasValidRecovery(state) else { return .failure(.unavailable) }
            let status = state.pass.status(at: .init(clock: self.env.clock), hasActiveCommitment: true)
            guard status.eligibility != .unavailable else { return .failure(.unavailable) }
            guard !status.isRecovering else { return .failure(.resumeProtectionFirst) }
            if let owner = focusOwnerID, state.passSuppressedFocusOwners?.contains(owner) == true {
                return .failure(.focusCycleRequired)
            }
            return .success(())
        }
    }

    /// A real Focus-off callback only acknowledges ownership; it never refunds or redeems.
    public func acknowledgeFocusOff(ownerID: String) -> Result<Void, FlintEmergencyPassError> {
        serialized {
            guard let ledger = self.env.ledger(), case .success = ledger.transaction({ state in
                guard Self.hasValidRecovery(state) else { throw FlintEmergencyPassError.unavailable }
                state.passSuppressedFocusOwners?.remove(ownerID)
            }) else { return .failure(.unavailable) }
            self.env.publish()
            return .success(())
        }
    }

    public func status() -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        // The last durable atomic file remains readable while another process owns the
        // policy locks. Project a copy; display must not checkpoint/rewrite ledger.json.
        guard let ledger = env.ledger(), case var .success(state) = ledger.peek() else { return .failure(.unavailable) }
        let now = FlintLedgerInstant(clock: env.clock)
        guard now.wall.timeIntervalSinceReferenceDate.isFinite, now.monotonic.isFinite, now.monotonic >= 0,
              Self.hasValidRecovery(state) else { return .failure(.unavailable) }
        state.pass.checkpoint(at: now) // in-memory copy only, including uncertainty projection
        guard Self.hasValidRecovery(state), state.pass.policyUsage != .unknown else { return .failure(.unavailable) }
        guard let scope = try? env.readScope(state, now) else {
            // A new spend still requires the complete scope. Already committed recovery
            // and cooldown remain knowable from the ledger, so Resume/Retry stays reachable
            // even if a later rule/config read fails. Never infer Available from that gap.
            let known = Self.status(state, at: now, active: true)
            if case .coolingDown = known.pass.eligibility { return .success(known) }
            return .failure(.scopeUnavailable)
        }
        let status = Self.status(state, at: now, active: scope.hasActiveCommitment)
        if status.pass.eligibility == .available {
            guard FlintPassRecovery(operationID: "status", at: now, scope: scope,
                carrierName: Self.carrierPrefix + "status").isValid else { return .failure(.scopeUnavailable) }
        }
        if now.bootID == nil {
            switch status.pass.eligibility {
            case .available, .nothingToRecover: return .failure(.unavailable)
            default: break
            }
        }
        return .success(status)
    }

    public func preview() -> Result<FlintPassPreview, FlintEmergencyPassError> {
        serialized { self.previewLocked() }
    }

    private func previewLocked() -> Result<FlintPassPreview, FlintEmergencyPassError> {
        guard let ledger = env.ledger() else { return .failure(.unavailable) }
        let initialInstant = FlintLedgerInstant(clock: env.clock)
        guard initialInstant.bootID != nil, initialInstant.wall.timeIntervalSinceReferenceDate.isFinite,
              initialInstant.monotonic.isFinite, initialInstant.monotonic >= 0,
              case let .success(initial) = ledger.read(), Self.hasValidRecovery(initial) else { return .failure(.unavailable) }
        let initialStatus = initial.pass.status(at: initialInstant, hasActiveCommitment: true)
        guard initial.emergencyRecovery?.needsApplication(isRecovering: initialStatus.isRecovering) != true else {
            return .failure(.applying)
        }
        if let refusal = Self.refusal(initialStatus) { return .failure(refusal) }
        do { try env.prepareScope() } catch { return .failure(.scopeUnavailable) }
        let result = ledger.transaction { state -> Result<FlintPassPreview, FlintEmergencyPassError> in
            let now = FlintLedgerInstant(clock: env.clock)
            guard now.bootID != nil, now.monotonic.isFinite, now.monotonic >= 0 else { return .failure(.unavailable) }
            guard Self.hasValidRecovery(state) else { return .failure(.unavailable) }
            state.pass.checkpoint(at: now)
            guard Self.hasValidRecovery(state) else { return .failure(.unavailable) }
            guard let scope = try? env.scope(state, now) else { return .failure(.scopeUnavailable) }
            if let refusal = Self.refusal(state.pass.status(at: now, hasActiveCommitment: scope.hasActiveCommitment)) {
                return .failure(refusal)
            }
            guard FlintPassRecovery(operationID: "preview", at: now, scope: scope,
                carrierName: Self.carrierPrefix + "preview").isValid else { return .failure(.scopeUnavailable) }
            return .success(.init(scope: scope, resumeAt: now.wall.addingTimeInterval(3600),
                                  nextPassAt: now.wall.addingTimeInterval(604800), issuedAt: now))
        }
        return (try? result.get()) ?? .failure(.unavailable)
    }

    /// Explicit in-Flint confirmation only. Neither this method nor its callers generate a new
    /// operation ID on retry: first read back the existing operation, then finish its outbox.
    public func use(previewRevision: String, operationID: String) -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        serialized { self.useLocked(previewRevision: previewRevision, operationID: operationID) }
    }

    private func useLocked(previewRevision: String, operationID: String) -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        guard !operationID.isEmpty, operationID.utf8.count <= 128, previewRevision.utf8.count <= 2048 else {
            return .failure(.unavailable)
        }
        guard let ledger = env.ledger() else { return .failure(.unavailable) }
        if case let .success(state) = ledger.peek(), state.emergencyRecovery?.operationID == operationID {
            guard Self.hasValidRecovery(state), let recovery = state.emergencyRecovery else { return .failure(.unavailable) }
            let recovering = state.pass.status(at: .init(clock: env.clock), hasActiveCommitment: true).isRecovering
            guard recovering || recovery.needsApplication(isRecovering: false) else { return .failure(.stalePreview) }
            return drain(ledger)
        }
        if case let .success(state) = ledger.peek() {
            guard Self.hasValidRecovery(state) else { return .failure(.unavailable) }
            let status = state.pass.status(at: .init(clock: env.clock), hasActiveCommitment: true)
            if state.emergencyRecovery?.needsApplication(isRecovering: status.isRecovering) == true {
                if case .failure = drain(ledger) { return .failure(.applying) }
                return .failure(.stalePreview)
            }
            if let refusal = Self.refusal(status) { return .failure(refusal) }
        }
        let beforePreparation = FlintLedgerInstant(clock: env.clock)
        guard beforePreparation.bootID != nil, beforePreparation.wall.timeIntervalSinceReferenceDate.isFinite,
              beforePreparation.monotonic.isFinite, beforePreparation.monotonic >= 0 else { return .failure(.unavailable) }
        do { try env.prepareScope() } catch { return .failure(.scopeUnavailable) }
        let instant = FlintLedgerInstant(clock: env.clock)
        guard instant.bootID != nil, instant.monotonic.isFinite, instant.monotonic >= 0,
              case let .success(before) = ledger.peek(), Self.hasValidRecovery(before) else { return .failure(.unavailable) }
        guard let beforeScope = try? env.scope(before, instant) else { return .failure(.scopeUnavailable) }
        if let refusal = Self.refusal(before.pass.status(at: instant, hasActiveCommitment: beforeScope.hasActiveCommitment)) {
            return .failure(refusal)
        }
        guard Self.matchesPreview(previewRevision, scope: beforeScope, at: instant) else { return .failure(.stalePreview) }
        // A unique owned registration is harmless before commit. Its callback must consult the
        // ledger; an orphan callback has no release authority. Never reuse another operation's carrier.
        // Keep slow OS registration outside the ledger lock so threshold observations can
        // persist while it runs. The single consumption transaction revalidates everything.
        let carrier = Self.carrierPrefix + UUID().uuidString.lowercased()
        guard FlintPassRecovery(operationID: operationID, at: instant, scope: beforeScope, carrierName: carrier).isValid else {
            return .failure(.scopeUnavailable)
        }
        do { try env.registerCarrier(carrier, instant.wall, instant.wall.addingTimeInterval(3600)) }
        catch { env.stopCarrier(carrier); return .failure(.carrierUnavailable) }
        let committed = ledger.transaction { state -> Result<Bool, FlintEmergencyPassError> in
            guard Self.hasValidRecovery(state) else { return .failure(.unavailable) }
            guard state.emergencyRecovery?.operationID != operationID else { return .success(false) }
            let fresh = FlintLedgerInstant(clock: env.clock)
            guard fresh.bootID == instant.bootID else { return .failure(.unavailable) }
            guard let scope = try? env.scope(state, fresh) else { return .failure(.scopeUnavailable) }
            if let refusal = Self.refusal(state.pass.status(at: fresh, hasActiveCommitment: scope.hasActiveCommitment)) {
                return .failure(refusal)
            }
            guard Self.matchesPreview(previewRevision, scope: scope, at: fresh) else { return .failure(.stalePreview) }
            let recovery = FlintPassRecovery(operationID: operationID, at: instant, scope: scope, carrierName: carrier)
            guard recovery.isValid else { return .failure(.scopeUnavailable) }
            switch state.consumePass(operationID: operationID, hasActiveCommitment: scope.hasActiveCommitment, at: instant) {
            case let .failure(error): return .failure(Self.map(error))
            case let .success(receipt):
                guard !receipt.replayed else { return .failure(.unavailable) }
                state.emergencyRecovery = recovery
                state.passLimitThresholds = state.passLimitThresholds ?? [:]
                for rule in scope.rules where rule.kind == .timeLimit && rule.wasEnforcing {
                    state.passLimitThresholds?[String(rule.id.dropFirst("timeLimit:".count))] = rule.timeLimitThreshold
                }
                state.passSuppressedFocusOwners = state.passSuppressedFocusOwners ?? []
                let cancelled = env.cancelGrants(&state)
                state.emergencyRecovery?.cancelledCarriers = cancelled
                for key in Array(state.harder.keys) { state.cancelHarderWait(cause: key) }
                for session in scope.sessions where session.origin == "focus_filter"
                    || (session.origin == nil && session.name == "Focus Filter") {
                    var owners = state.passSuppressedFocusOwners ?? []
                    owners.insert(session.ownerID ?? "focus-filter")
                    state.passSuppressedFocusOwners = owners
                }
                // Effect work is part of the same accepted intent. An invalid adapter result
                // aborts the entire transaction, including consumption and grant cancellation.
                guard Self.hasValidRecovery(state) else { throw FlintEmergencyPassError.unavailable }
                return .success(true)
            }
        }
        // Failure or an ambiguous fsync/rename result grants NO release. A later retry reads
        // back operationID; it never speculatively consumes again.
        guard case let .success(outcome) = committed else {
            env.stopCarrier(carrier)
            return .failure(.unavailable)
        }
        if case let .failure(error) = outcome {
            env.stopCarrier(carrier)
            return .failure(error)
        }
        if case .success(false) = outcome {
            env.stopCarrier(carrier)
            return drain(ledger, expectedOperation: operationID)
        }
        env.publish()
        do { try env.afterCommit() } catch { return .failure(.applying) }
        return drain(ledger, expectedOperation: operationID)
    }

    /// Host startup/foreground and every monitor callback replay the committed desired state.
    /// In particular this runs synchronously in the extension at the recovery carrier end.
    public func reconcile() -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        serialized { self.reconcileLocked() }
    }

    private func reconcileLocked() -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        guard let ledger = env.ledger() else { return .failure(.unavailable) }
        return drain(ledger)
    }

    public func resumeNow() -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        serialized { self.resumeNowLocked() }
    }

    private func resumeNowLocked() -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        guard let ledger = env.ledger(), case .success = ledger.transaction({ state in
            guard Self.hasValidRecovery(state) else { throw FlintEmergencyPassError.unavailable }
            state.endRecovery(at: FlintLedgerInstant(clock: env.clock))
        }) else { return .failure(.unavailable) }
        env.publish()
        return drain(ledger)
    }

    /// A separate, exact confirmation during recovery. The list is refreshed before commit;
    /// stale rule revisions never silently authorize disabling a different template.
    public func disableCommitments(previewRevision: String, ruleIDs: Set<String>) -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        serialized { self.disableLocked(previewRevision: previewRevision, ruleIDs: ruleIDs) }
    }

    private func disableLocked(previewRevision: String, ruleIDs: Set<String>) -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        guard !ruleIDs.isEmpty else { return .failure(.nothingToRecover) }
        guard let ledger = env.ledger() else { return .failure(.unavailable) }
        let result = ledger.transaction { state -> Result<Void, FlintEmergencyPassError> in
            let now = FlintLedgerInstant(clock: env.clock)
            guard Self.hasValidRecovery(state), state.pass.status(at: now, hasActiveCommitment: true).isRecovering,
                  var recovery = state.emergencyRecovery else { return .failure(.resumeProtectionFirst) }
            guard let scope = try? env.scope(state, now) else { return .failure(.scopeUnavailable) }
            guard scope.revision == previewRevision else { return .failure(.stalePreview) }
            let exact = scope.rules.filter { ruleIDs.contains($0.id) }
            guard Set(exact.map(\.id)) == ruleIDs else { return .failure(.stalePreview) }
            var pending = Dictionary(uniqueKeysWithValues: recovery.disabledRules.map { ($0.id, $0) })
            for rule in exact { pending[rule.id] = rule }
            recovery.disabledRules = pending.values.sorted { $0.id < $1.id }
            state.emergencyRecovery = recovery
            return .success(())
        }
        guard case let .success(outcome) = result else { return .failure(.unavailable) }
        if case let .failure(error) = outcome { return .failure(error) }
        return drain(ledger)
    }

    /// The current naturally active rule list for the recovery screen's exact disable dialog.
    public func currentScope() -> Result<FlintPassScope, FlintEmergencyPassError> {
        guard let ledger = env.ledger(), case let .success(state) = ledger.peek(),
              Self.hasValidRecovery(state), state.pass.policyUsage != .unknown,
              let scope = try? env.readScope(state, FlintLedgerInstant(clock: env.clock)) else { return .failure(.scopeUnavailable) }
        return .success(scope)
    }

    /// Informational projection for the recovery screen. Future occurrences use their saved
    /// natural windows; this neither creates a commitment nor authorizes spending/disabling.
    public func resumingScope() -> Result<FlintPassScope, FlintEmergencyPassError> {
        guard let ledger = env.ledger(), case let .success(state) = ledger.peek(),
              Self.hasValidRecovery(state) else { return .failure(.unavailable) }
        let now = FlintLedgerInstant(clock: env.clock)
        let status = state.pass.status(at: now, hasActiveCommitment: true)
        guard status.eligibility != .unavailable else { return .failure(.unavailable) }
        let remaining = status.recoveryRemaining
        let boundary = FlintLedgerInstant(wall: now.wall.addingTimeInterval(remaining),
            monotonic: now.monotonic + remaining, bootID: now.bootID)
        guard let scope = try? env.readScope(state, boundary) else { return .failure(.scopeUnavailable) }
        return .success(scope)
    }

    private func serialized<T>(_ body: () -> Result<T, FlintEmergencyPassError>) -> Result<T, FlintEmergencyPassError> {
        // Set this BEFORE env.transition: that boundary enters the live rule lock, whose
        // ordinary preparation drains pass work. Setting it inside work would recurse there.
        let previous = Thread.current.threadDictionary[Self.transitionMarker]
        Thread.current.threadDictionary[Self.transitionMarker] = true
        defer {
            if let previous { Thread.current.threadDictionary[Self.transitionMarker] = previous }
            else { Thread.current.threadDictionary.removeObject(forKey: Self.transitionMarker) }
        }
        var result: Result<T, FlintEmergencyPassError>?
        guard env.transition({ result = body() }) else { return .failure(.unavailable) }
        return result ?? .failure(.unavailable)
    }

    private func drain(_ ledger: FlintProtectionLedger, expectedOperation: String? = nil,
                       observationRetries: Int = 2) -> Result<FlintEmergencyPassStatus, FlintEmergencyPassError> {
        // A durable read-back precedes all effects even immediately after a reported commit.
        guard case let .success(snapshot) = ledger.transaction({ state -> FlintLedgerState in
            guard Self.hasValidRecovery(state) else { throw FlintEmergencyPassError.unavailable }
            state.pass.checkpoint(at: FlintLedgerInstant(clock: env.clock)); return state
        }), expectedOperation == nil || snapshot.emergencyRecovery?.operationID == expectedOperation else {
            return .failure(.unavailable)
        }
        guard Self.hasValidRecovery(snapshot) else { return .failure(.unavailable) }
        guard let recovery = snapshot.emergencyRecovery else { return status() }
        guard snapshot.operation(recovery.operationID)?.kind == "pass" || snapshot.pass.lastOperationID == recovery.operationID else {
            return .failure(.unavailable)
        }
        var recovering = false
        do {
            // The pre-commit carrier may have disappeared (or an ambiguous write stopped it).
            // Verify/repair it before any release on replay, never after clearing the stores.
            var instant = FlintLedgerInstant(clock: env.clock)
            var current = snapshot.pass.status(at: instant, hasActiveCommitment: true)
            if current.isRecovering {
                try env.registerCarrier(recovery.carrierName, instant.wall,
                    instant.wall.addingTimeInterval(current.recoveryRemaining))
            }
            if recovery.releasePending {
                // Terminations remain final even after reboot/early resume. Only the exact
                // committed session IDs may be ended, so a later session is never cleared.
                try env.terminate(recovery.scope.sessions, recovery.acceptedAt.wall)
                for name in recovery.cancelledCarriers ?? [] { env.stopCarrier(name) }
            }
            if !recovery.disabledRules.isEmpty { try env.disable(FlintPassDisableAuthorization(recovery)) }
            if snapshot.pass.status(at: .init(clock: env.clock), hasActiveCommitment: true).isRecovering {
                do { try env.release(recovery.scope) }
                catch {
                    // The adapter can notice expiry between individual store writes. An
                    // elapsed interval is resumed now; a still-live failed release is retried.
                    if snapshot.pass.status(at: .init(clock: env.clock), hasActiveCommitment: true).isRecovering { throw error }
                }
            }
            instant = .init(clock: env.clock)
            current = snapshot.pass.status(at: instant, hasActiveCommitment: true)
            recovering = current.isRecovering
            if !recovering && recovery.resumePending {
                try env.resume(snapshot, instant)
            }
            if recovery.eventPending { env.emit(recovery) }
        } catch {
            env.publish()
            return .failure(.applying)
        }
        // Never erase another operation's outbox. All effects above are safe to replay if this
        // acknowledgement fails; lane F deduplicates the stable pass and session event IDs.
        guard case let .success(resumedLatestEvidence) = ledger.transaction({ state -> Bool in
            guard state.emergencyRecovery?.operationID == recovery.operationID else {
                throw FlintEmergencyPassError.unavailable
            }
            state.emergencyRecovery?.releasePending = false
            state.emergencyRecovery?.eventPending = false
            state.emergencyRecovery?.disabledRules = []
            if !recovering && recovery.resumePending {
                // Positive threshold observations deliberately do not acquire the session
                // lock. An observation saved during slow registration must be applied before
                // acknowledging resume; otherwise a busy callback could be lost forever.
                guard state.passLimitThresholds == snapshot.passLimitThresholds else { return false }
                state.emergencyRecovery?.resumePending = false
            }
            return true
        }) else { return .failure(.applying) }
        env.publish()
        if !resumedLatestEvidence {
            guard observationRetries > 0 else { return .failure(.applying) }
            return drain(ledger, expectedOperation: recovery.operationID, observationRetries: observationRetries - 1)
        }
        if !recovering && recovery.resumePending { env.stopCarrier(recovery.carrierName) }
        return status()
    }

    private static func status(_ state: FlintLedgerState, at now: FlintLedgerInstant, active: Bool) -> FlintEmergencyPassStatus {
        let status = state.pass.status(at: now, hasActiveCommitment: active)
        let remaining: TimeInterval?
        if case let .coolingDown(seconds) = status.eligibility { remaining = seconds } else { remaining = nil }
        return .init(pass: status, nextAvailableAt: remaining.map { now.wall.addingTimeInterval($0) },
            recoveryUntil: status.isRecovering ? now.wall.addingTimeInterval(status.recoveryRemaining) : nil,
            recovery: state.emergencyRecovery, timeUncertain: state.pass.timeUncertain == true)
    }

    private static func hasValidRecovery(_ state: FlintLedgerState) -> Bool {
        state.hasValidPassRecovery
    }

    private static func matchesPreview(_ revision: String, scope: FlintPassScope, at now: FlintLedgerInstant) -> Bool {
        guard let separator = revision.lastIndex(of: "."), String(revision[..<separator]) == scope.revision,
              let data = Data(base64Encoded: String(revision[revision.index(after: separator)...])),
              let issued = try? JSONDecoder().decode(FlintLedgerInstant.self, from: data),
              let elapsed = issued.elapsedForRelease(until: now), (0...previewLifetime).contains(elapsed),
              abs(now.wall.timeIntervalSince(issued.wall) - elapsed) <= FlintTimeIntegrity.discontinuityThreshold else { return false }
        return true
    }

    private static func refusal(_ status: FlintPassStatus) -> FlintEmergencyPassError? {
        if status.isRecovering { return .resumeProtectionFirst }
        switch status.eligibility {
        case .available: return nil
        case .nothingToRecover: return .nothingToRecover
        case .unavailable: return .unavailable
        case let .coolingDown(remaining): return .coolingDown(remaining: remaining)
        }
    }

    private static func map(_ error: FlintPassRefusal) -> FlintEmergencyPassError {
        switch error {
        case .unavailable: return .unavailable
        case .nothingToRecover: return .nothingToRecover
        case let .coolingDown(remaining): return .coolingDown(remaining: remaining)
        }
    }
}
