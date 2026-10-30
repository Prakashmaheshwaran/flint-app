import Foundation
#if canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)

/// The saved preference describes future policy. Each accepted live cause keeps its own
/// child, including an explicit opt-out or unavailable intent. Callers hold the transition.
final class FlintWebRestrictionChildren {
    struct Cause: Codable, Equatable {
        enum Scope: String, Codable { case session, rule }
        let key: String
        let ownerID: String
        let storeName: String
        let scope: Scope

        static func session(_ session: FlintActiveSession) -> Self {
            .init(key: "session:\(session.id)@\(session.startedAt.timeIntervalSince1970)", ownerID: session.id,
                  storeName: FlintWebRestrictionLifecycle.sessionStoreName, scope: .session)
        }

        static func rule(_ rule: FlintScheduleRule, window: FlintRuleWindow) -> Self {
            .init(key: FlintRuleArbiter.causeID(rule, window: window), ownerID: rule.id,
                  storeName: rule.storeName, scope: .rule)
        }

        func applies(_ config: FlintWebRestrictions) -> Bool {
            scope == .session ? config.enabled : config.applyDuringSchedules
        }

        var isValid: Bool {
            guard !ownerID.isEmpty else { return false }
            let prefix: String
            switch scope {
            case .session:
                guard storeName == FlintWebRestrictionLifecycle.sessionStoreName else { return false }
                prefix = "session:\(ownerID)@"
            case .rule:
                guard storeName == "flint.\(ownerID)" else { return false }
                prefix = FlintRuleStoreKey.schedules.rawValue + ":\(ownerID):"
            }
            guard key.hasPrefix(prefix) else { return false }
            let suffix = String(key.dropFirst(prefix.count))
            let timestamp: String
            if scope == .rule {
                let parts = suffix.split(separator: "@", omittingEmptySubsequences: false)
                guard parts.count == 2, UUID(uuidString: String(parts[0])) != nil else { return false }
                timestamp = String(parts[1])
            } else { timestamp = suffix }
            return Double(timestamp)?.isFinite == true
        }

    }

    struct Child: Codable, Equatable {
        let cause: Cause
        /// nil is an explicitly unavailable frozen child; it is never an opt-out/default.
        var config: FlintWebRestrictions?
    }

    struct Envelope: Codable, Equatable {
        let version: Int
        var children: [Child]
        /// Exact damaged entries survive ordinary writes until an authorized repair archives them.
        var retainedUnavailable: [Data]
        init(children: [Child] = [], retainedUnavailable: [Data] = []) {
            version = 1; self.children = children; self.retainedUnavailable = retainedUnavailable
        }
        var isValid: Bool {
            version == 1 && Set(children.map { $0.cause.key }).count == children.count
                && children.allSatisfy {
                    $0.cause.isValid && $0.config?.validationMessage == nil
                }
        }

        func hasUnavailableEntry(for cause: Cause) -> Bool {
            retainedUnavailable.contains { bytes in
                let root = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
                let key = (root?["cause"] as? [String: Any])?["key"] as? String
                return key == cause.key
            }
        }

        static func read(_ data: Data) -> Self? {
            struct Header: Decodable { let version: Int }
            guard let header = try? JSONDecoder().decode(Header.self, from: data), header.version == 1,
                  let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let records = root["children"] as? [Any] else { return nil }
            var result = Self()
            if let retained = root["retainedUnavailable"] {
                if let bytes = try? JSONSerialization.data(withJSONObject: retained),
                   let decoded = try? JSONDecoder().decode([Data].self, from: bytes) { result.retainedUnavailable = decoded }
                else { result.retainedUnavailable.append(data) }
            }
            var invalidKeys: Set<String> = []
            var originals: [String: Data] = [:]
            for record in records {
                guard let bytes = try? JSONSerialization.data(withJSONObject: record, options: [.fragmentsAllowed]) else { continue }
                let fields = record as? [String: Any]
                let rawKey = (fields?["cause"] as? [String: Any])?["key"] as? String
                let causeBytes = (fields?["cause"]).flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.fragmentsAllowed]) }
                let decodedCause = causeBytes.flatMap { try? JSONDecoder().decode(Cause.self, from: $0) }
                guard let cause = decodedCause, cause.isValid else {
                    result.retainedUnavailable.append(bytes)
                    if let rawKey { invalidKeys.insert(rawKey) }
                    // A valid owner/key still identifies exactly one cause if only its store
                    // binding is damaged. Keep that cause explicitly unknown, never guess
                    // an owner for an unparseable record or poison unrelated future causes.
                    if let cause = decodedCause {
                        let canonical = Cause(key: cause.key, ownerID: cause.ownerID,
                            storeName: cause.scope == .session ? FlintWebRestrictionLifecycle.sessionStoreName : "flint.\(cause.ownerID)",
                            scope: cause.scope)
                        if canonical.isValid, !result.children.contains(where: { $0.cause.key == canonical.key }) {
                            result.children.append(.init(cause: canonical, config: nil))
                        }
                    }
                    continue
                }
                if let index = result.children.firstIndex(where: { $0.cause.key == cause.key }) {
                    result.children[index].config = nil
                    invalidKeys.insert(cause.key)
                    if let original = originals[cause.key] { result.retainedUnavailable.append(original) }
                    result.retainedUnavailable.append(bytes)
                    continue
                }
                let child = try? JSONDecoder().decode(Child.self, from: bytes)
                if let child, child.config?.validationMessage == nil {
                    result.children.append(child)
                } else {
                    result.children.append(.init(cause: cause, config: nil))
                    result.retainedUnavailable.append(bytes)
                    invalidKeys.insert(cause.key)
                }
                originals[cause.key] = bytes
            }
            for index in result.children.indices where invalidKeys.contains(result.children[index].cause.key) {
                if result.children[index].config != nil, let original = originals[result.children[index].cause.key] {
                    result.retainedUnavailable.append(original)
                }
                result.children[index].config = nil
            }
            return result.isValid ? result : nil
        }
    }

    enum Read: Equatable { case legacy, available(Envelope), unavailable }
    static let schemaKey = "_flintWebChildrenSchema"
    static let bytesKey = "_flintWebChildren"
    static let markerKey = FlintWebRestrictions.childInitializedMarkerKey
    private let defaults: UserDefaults?
    private var memory: Read?
    private let acceptSave: () -> Bool
    private let recoveryDirectory: URL?

    init(defaults: UserDefaults?, recoveryDirectory: URL? = FlintRuleRecovery.directory,
         acceptSave: @escaping () -> Bool = { true }) {
        self.defaults = defaults; self.recoveryDirectory = recoveryDirectory; self.acceptSave = acceptSave
    }
    private init(memory: Read) { defaults = nil; recoveryDirectory = nil; self.memory = memory; acceptSave = { true } }
    static func isolated() -> FlintWebRestrictionChildren { .init(memory: .legacy) }
    static var live: FlintWebRestrictionChildren { .init(defaults: .flintGroup) }

    static func knows(_ rule: FlintScheduleRule) -> Bool {
        rule.protection != nil && rule.validationIssue == nil
    }

    var needsRecovery: Bool {
        switch read() {
        case .legacy: return false
        case .unavailable: return true
        case let .available(envelope): return !envelope.retainedUnavailable.isEmpty || envelope.children.contains { $0.config == nil }
        }
    }

    func read() -> Read {
        if let memory { return memory }
        guard let defaults, defaults.synchronize() else { return .unavailable }
        let object = defaults.object(forKey: FlintWebRestrictions.storageKey)
        let root = (object as? Data).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
        let established = defaults.object(forKey: Self.markerKey) != nil
            || root?[Self.schemaKey] != nil || root?[Self.bytesKey] != nil
        guard established else { return .legacy }
        struct Header: Decodable {
            let schema: Int
            let bytes: Data
            enum CodingKeys: String, CodingKey {
                case schema = "_flintWebChildrenSchema", bytes = "_flintWebChildren"
            }
        }
        guard let data = object as? Data, let header = try? JSONDecoder().decode(Header.self, from: data),
              header.schema == 1, let envelope = Envelope.read(header.bytes),
              envelope.isValid else { return .unavailable }
        return .available(envelope)
    }

    @discardableResult
    private func persist(_ envelope: Envelope, global: FlintWebRestrictions? = nil) -> Bool {
        guard envelope.isValid, acceptSave(), let data = try? JSONEncoder().encode(envelope) else { return false }
        if memory != nil { memory = .available(envelope); return true }
        return FlintWebRestrictions.writeStorage(to: defaults, config: global, children: data)
    }

    /// Only proven strengthening reaches a live child outside the editor's weakening gates.
    static func strengthens(_ candidate: FlintWebRestrictions, over old: FlintWebRestrictions, cause: Cause) -> Bool {
        guard candidate.validationMessage == nil else { return false }
        guard cause.applies(old) else { return true }
        return cause.applies(candidate) && !FlintWebRestrictionEditor.weakensContent(old, candidate)
    }

    func resolve(_ cause: Cause, global: FlintWebRestrictions?, captureNew: Bool = true) -> FlintWebRestrictions? {
        var envelope: Envelope
        switch read() {
        case .unavailable: return nil
        case .legacy: envelope = .init()
        case let .available(saved): envelope = saved
        }
        if let index = envelope.children.firstIndex(where: { $0.cause.key == cause.key }) {
            guard envelope.children[index].cause == cause else { return nil }
            let frozen = envelope.children[index].config
            if let frozen, let global, global != frozen, Self.strengthens(global, over: frozen, cause: cause) {
                envelope.children[index].config = global
                guard persist(envelope) else { return nil }
                return global
            }
            return frozen
        }
        guard captureNew else { return nil }
        let unavailable = envelope.hasUnavailableEntry(for: cause)
        var config = unavailable ? nil : global.flatMap { $0.validationMessage == nil ? $0 : nil }
        // A replacement whose optional child write failed can recover from the surviving
        // predecessor even after process death. Never recapture a weaker global over it.
        if cause.scope == .session, !unavailable {
            for child in envelope.children where child.cause.scope == .session {
                guard let previous = child.config else { config = nil; break }
                if let candidate = config {
                    if !Self.strengthens(candidate, over: previous, cause: cause) {
                        guard Self.strengthens(previous, over: candidate, cause: cause) else { config = nil; break }
                        config = previous
                    }
                } else { config = previous }
            }
        }
        envelope.children.append(.init(cause: cause, config: config))
        return persist(envelope) ? config : nil
    }

    /// A freeze hook reads global intent once, then captures all rule children before arming.
    @discardableResult
    func capture(_ causes: [Cause], global: FlintWebRestrictions?, rules: [FlintScheduleRule]? = nil,
                 clock: FlintClock? = nil, completeRules: Bool = false) -> Bool {
        var envelope: Envelope
        let legacy: Bool
        switch read() {
        case .unavailable: return false
        case .legacy: envelope = .init(); legacy = true
        case let .available(saved): envelope = saved; legacy = false
        }
        let before = envelope
        if let rules, let clock {
            envelope.children.removeAll { child in
                guard child.cause.scope == .rule else { return false }
                guard let owner = rules.first(where: { $0.id == child.cause.ownerID }) else { return completeRules }
                guard Self.knows(owner) else { return false }
                guard owner.enabled else { return true }
                guard let accepted = owner.protection?.occurrence else { return completeRules }
                guard owner.occurrence(at: clock.now(), clock: clock) == accepted else { return completeRules }
                return child.cause.key != Cause.rule(owner, window: accepted).key
            }
        }
        for cause in causes where !envelope.children.contains(where: { $0.cause.key == cause.key }) {
            let config = envelope.hasUnavailableEntry(for: cause) ? nil : global.flatMap { $0.validationMessage == nil ? $0 : nil }
            envelope.children.append(.init(cause: cause, config: config))
        }
        // Establish an explicit empty envelope too, rather than treating later absence as empty.
        return (!legacy && envelope == before) || persist(envelope)
    }

    /// Readers may migrate the legacy envelope once. After establishment, a missing rule
    /// child is unavailable; only the accepted pre-arm freeze may capture its global policy.
    @discardableResult
    func observe(_ snapshot: FlintWebRestrictionEditor.Snapshot, global: FlintWebRestrictions?, clock: FlintClock) -> Bool {
        let live = Self.causes(snapshot, clock: clock)
        if read() == .unavailable { return true } // only gated repair can replace these bytes
        if read() == .legacy { guard capture(live, global: global) else { return false } }
        else {
            if let session = snapshot.session, session.isEnforcing(at: clock.now(), clock: clock) {
                let cause = Cause.session(session)
                _ = resolve(cause, global: global)
                guard case let .available(envelope) = read(), envelope.children.contains(where: { $0.cause == cause }) else { return false }
            }
            guard capture(live.filter { $0.scope == .rule }, global: nil) else { return false }
        }
        return prune(snapshot, clock: clock)
    }

    func frozen(_ cause: Cause, fallback: FlintWebRestrictions?) -> FlintWebRestrictions? {
        switch read() {
        case .legacy: return fallback
        case let .available(envelope): return envelope.children.first { $0.cause == cause }?.config ?? nil
        case .unavailable: return nil
        }
    }

    @discardableResult
    func retire(scope: Cause.Scope, ownerID: String? = nil, keeping cause: Cause? = nil) -> Bool {
        guard case var .available(envelope) = read() else { return false }
        let before = envelope
        envelope.children.removeAll {
            $0.cause.scope == scope && (ownerID == nil || $0.cause.ownerID == ownerID)
                && $0.cause.key != cause?.key
        }
        return envelope == before || persist(envelope)
    }

    /// Only a complete, trusted actual-owner snapshot grants pruning or repair authority.
    @discardableResult
    func prune(_ snapshot: FlintWebRestrictionEditor.Snapshot, clock: FlintClock) -> Bool {
        guard case var .available(envelope) = read() else { return false }
        let old = envelope
        let sessionKey = snapshot.session.flatMap {
            $0.isEnforcing(at: clock.now(), clock: clock) ? Cause.session($0).key : nil
        }
        let hasSessionChild = sessionKey.map { key in envelope.children.contains { $0.cause.key == key } } ?? false
        envelope.children.removeAll { child in
            if child.cause.scope == .session {
                // Keep predecessor evidence if a replacement child still cannot be saved.
                if sessionKey != nil, !hasSessionChild { return false }
                return child.cause.key != sessionKey
            }
            guard let rule = snapshot.rules.first(where: { $0.id == child.cause.ownerID }) else { return true }
            guard Self.knows(rule) else { return false } // unknown saved policy cannot prove an end
            guard let window = rule.occurrence(at: clock.now(), clock: clock) else { return true }
            return child.cause.key != Cause.rule(rule, window: window).key
        }
        return envelope == old || persist(envelope)
    }

    static func causes(_ snapshot: FlintWebRestrictionEditor.Snapshot, clock: FlintClock) -> [Cause] {
        var causes: [Cause] = []
        if let session = snapshot.session, session.isEnforcing(at: clock.now(), clock: clock) {
            causes.append(.session(session))
        }
        for rule in snapshot.rules where Self.knows(rule) {
            if let window = rule.occurrence(at: clock.now(), clock: clock) { causes.append(.rule(rule, window: window)) }
        }
        return causes
    }

    /// A gated editor acceptance replaces globals and every live child's policy in one blob.
    /// Unknown/corrupt child storage may be repaired only after the editor gated all owners.
    func saveAuthorized(_ config: FlintWebRestrictions, snapshot: FlintWebRestrictionEditor.Snapshot,
                        clock: FlintClock) -> Bool {
        var envelope: Envelope
        let previous = read()
        if case let .available(saved) = previous { envelope = saved } else { envelope = .init() }
        if memory == nil {
            let repair = previous == .unavailable || !envelope.retainedUnavailable.isEmpty || envelope.children.contains { $0.config == nil }
            guard FlintWebRestrictions.retainUnavailableIntent(to: defaults, recoveryDirectory: recoveryDirectory,
                                                              force: repair) else { return false }
        }
        let causes = Self.causes(snapshot, clock: clock)
        envelope.retainedUnavailable.removeAll()
        envelope.children.removeAll { child in !causes.contains(where: { $0.key == child.cause.key }) }
        for cause in causes {
            if let index = envelope.children.firstIndex(where: { $0.cause == cause }) {
                envelope.children[index].config = config
            } else { envelope.children.append(.init(cause: cause, config: config)) }
        }
        return persist(envelope, global: config)
    }
}
#endif
