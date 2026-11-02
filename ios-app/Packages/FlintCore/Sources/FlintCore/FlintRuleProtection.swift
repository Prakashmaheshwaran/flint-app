import Foundation

public enum FlintRuleActivity {
    public static func name(prefix: String, id: String, protection: FlintRuleProtection?) -> String {
        guard let protection, !protection.usesLegacyActivityName else { return prefix + id }
        return "\(prefix)\(id).i\(protection.incarnation.replacingOccurrences(of: "-", with: "").lowercased()).r\(protection.activityRevision)"
    }

    static func ruleID(_ name: String, prefix: String) -> String? {
        guard name.hasPrefix(prefix) else { return nil }
        let suffix = String(name.dropFirst(prefix.count))
        if let marker = suffix.range(of: ".r", options: .backwards),
           Int(suffix[marker.upperBound...]) != nil {
            let identity = String(suffix[..<marker.lowerBound])
            if let incarnation = identity.range(of: ".i", options: .backwards) {
                let value = identity[incarnation.upperBound...]
                if value.count == 32, value.allSatisfy({ $0.isHexDigit }) { return String(identity[..<incarnation.lowerBound]) }
            }
            return identity
        }
        return suffix
    }
}

/// Versioned, persisted rule authority. A queued edit changes the template revision without
/// changing the active registration's revision or its frozen window (ADR-012 PC-10/11/14).
public struct FlintRuleProtection: Codable, Equatable, Sendable {
    /// Minted by the arbiter on create, preserved through edits/queues/off-on. A reused rule
    /// ID cannot inherit another incarnation's native callback, wait count or Open credit.
    public let incarnation: String
    public var revision: Int
    public var contractVersion: Int
    public var activityRevision: Int
    public var timeZoneID: String
    public var legacyRightsUntil: Date?
    public var usesLegacyActivityName: Bool
    public var requiresTierReview: Bool
    public var occurrence: FlintRuleWindow?
    /// Captured for this occurrence only. Legacy absence is never filled from a later opt-in.
    public var removalGuard: Bool?
    /// Optional for pre-clock records. The first serialized observation establishes it.
    public var timeAnchor: FlintRuleTimeAnchor?
    public var carrierRepair: FlintRuleCarrierRepair?
    public var queuedChange: FlintQueuedRuleChange?

    public init(revision: Int = 1, timeZoneID: String = TimeZone.current.identifier,
                legacyRightsUntil: Date? = nil, usesLegacyActivityName: Bool = false,
                requiresTierReview: Bool = false, incarnation: String = UUID().uuidString) {
        self.incarnation = incarnation
        self.revision = revision
        contractVersion = 12
        activityRevision = revision
        self.timeZoneID = timeZoneID
        self.legacyRightsUntil = legacyRightsUntil
        self.usesLegacyActivityName = usesLegacyActivityName
        self.requiresTierReview = requiresTierReview
    }

    private enum CodingKeys: String, CodingKey {
        case incarnation, revision, contractVersion, activityRevision, timeZoneID, legacyRightsUntil
        case usesLegacyActivityName, requiresTierReview, occurrence, removalGuard, timeAnchor, carrierRepair, queuedChange
    }

    public init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        incarnation = try values.decode(String.self, forKey: .incarnation)
        revision = try values.decode(Int.self, forKey: .revision)
        contractVersion = try values.decode(Int.self, forKey: .contractVersion)
        activityRevision = try values.decode(Int.self, forKey: .activityRevision)
        timeZoneID = try values.decode(String.self, forKey: .timeZoneID)
        legacyRightsUntil = try values.decodeIfPresent(Date.self, forKey: .legacyRightsUntil)
        usesLegacyActivityName = try values.decode(Bool.self, forKey: .usesLegacyActivityName)
        requiresTierReview = try values.decode(Bool.self, forKey: .requiresTierReview)
        occurrence = try values.decodeIfPresent(FlintRuleWindow.self, forKey: .occurrence)
        removalGuard = try values.decodeIfPresent(Bool.self, forKey: .removalGuard)
        queuedChange = try values.decodeIfPresent(FlintQueuedRuleChange.self, forKey: .queuedChange)
        // Policy decoding stays strict. Bookkeeping cannot disable the recovery surface;
        // retain an unavailable sentinel so absence is still distinguishable from legacy.
        do { timeAnchor = try values.decodeIfPresent(FlintRuleTimeAnchor.self, forKey: .timeAnchor) }
        catch { timeAnchor = .unavailable }
        do { carrierRepair = try values.decodeIfPresent(FlintRuleCarrierRepair.self, forKey: .carrierRepair) }
        catch { carrierRepair = .unavailable }
    }

    var isValid: Bool {
        contractVersion == 12 && UUID(uuidString: incarnation) != nil
            && revision > 0 && revision < Int.max / 2 && activityRevision > 0 && activityRevision <= revision
            && TimeZone(identifier: timeZoneID) != nil
            && (occurrence?.isValid ?? true)
            && (queuedChange.map { queue in
                queue.baseRevision == revision && queue.window.isValid
                    && (occurrence.map { queue.window.end >= $0.end } ?? true)
            } ?? true)
    }
}

public struct FlintRuleWindow: Codable, Equatable, Sendable {
    public var start: Date
    public var end: Date
    public init(start: Date, end: Date) { self.start = start; self.end = end }
    public func contains(_ date: Date) -> Bool { start <= date && date < end }
    var isValid: Bool { FlintProtectionPolicy.issue(forOccurrenceFrom: start, to: end) == nil }
    var identity: String { String(start.timeIntervalSince1970) }
}

public struct FlintQueuedRuleChange: Codable, Equatable, Sendable {
    public enum Action: String, Codable, Sendable { case edit, turnOff, delete }
    public let action: Action
    public let baseRevision: Int
    public let window: FlintRuleWindow
    /// Exact future snapshot, with no nested queue. nil only for deletion.
    public let snapshot: Data?
    public let fingerprint: String
    public var batchID: String? = nil
    public var failureReason: String? = nil
    public var summary: String {
        switch action {
        case .edit:
            guard let snapshot, let fields = (try? JSONSerialization.jsonObject(with: snapshot)) as? [String: Any] else { return "Queued edit" }
            let name = fields["name"] as? String ?? "Rule"
            let tier = (fields["breakLevel"] as? String)?.capitalized ?? ""
            let budget = (fields["thresholdMinutes"] as? Int).map { " · \($0) min/day" }
                ?? (fields["opensAllowed"] as? Int).map { " · \($0) credits/day" } ?? ""
            return "Queued: \(name) · \(tier)\(budget)"
        case .turnOff: return "Queued: turn off"
        case .delete: return "Queued deletion"
        }
    }
}

public struct FlintRuleConfirmation: Equatable, Sendable {
    public let revision: Int
    public let fingerprint: String
    public let confirmsTierIncrease: Bool
    public let preview: [FlintRuleChangePreview]
    public init(revision: Int, fingerprint: String, confirmsTierIncrease: Bool = false,
                preview: [FlintRuleChangePreview] = []) {
        self.revision = revision
        self.fingerprint = fingerprint
        self.confirmsTierIncrease = confirmsTierIncrease
        self.preview = preview
    }
}

/// Human-readable scope derived from the exact snapshot whose fingerprint is confirmed.
/// Opaque target identities stay out of presentation data.
public struct FlintRuleChangePreview: Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let action: FlintQueuedRuleChange.Action
    public let tier: BreakLevel
    public let applicationCount: Int
    public let categoryCount: Int
    public let webDomainCount: Int
    public let allowList: Bool
    public let budget: Int?
    public let isTimeLimit: Bool
    public let timeZoneID: String
    public let window: FlintRuleWindow?
    public var deferredUntil: Date? = nil
    public var offersEarlierHarderEdit = false
}

public enum FlintRuleMutationOrigin: Equatable, Sendable { case interactive, background }

public enum FlintRuleMutationResult: Equatable, Sendable {
    case applied
    case queued(until: Date)
    case confirmationRequired(FlintRuleConfirmation, raisesTier: Bool)
    case waiting(FlintRuleConfirmation, remaining: TimeInterval, required: TimeInterval)
    case deferRequired(FlintRuleConfirmation, until: Date)
    case nextOccurrenceRequired(FlintRuleConfirmation, until: Date)
    case authorizedButNotSaved(FlintRuleConfirmation, until: Date)
    case staleRevision
    case denied(FlintDenialReason, cause: String, tier: BreakLevel, end: Date?)
    case invalid(String)
    case unavailable

    public var didSave: Bool {
        switch self { case .applied, .queued: return true; default: return false }
    }
}
