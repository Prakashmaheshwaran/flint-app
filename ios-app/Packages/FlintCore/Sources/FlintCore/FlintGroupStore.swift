import Foundation
#if canImport(FamilyControls)
import FamilyControls
#endif

/// Shared App Group I/O — the only way the host app and the system extensions exchange state.
/// Stores the `Codable` `FamilyActivitySelection` (the user's opaque token picks) and the list
/// of configured sessions. Named `ManagedSettingsStore`s auto-share on iOS 16+, but the
/// selection blob and app-defined state still need this group.
public final class FlintGroupStore {
    public static let appGroupID = "group.com.flint.peakfocus"

    private enum Key {
        static let selection = "flint.selection"
        static let sessions = "flint.sessions"
        static let activeSession = "flint.activeSession"
        static let rules = "flint.rules"
        static let limits = "flint.limits"
        static let appGroups = "flint.appGroups"
        static let emergencyPassLastUsed = "flint.emergencyPass.lastUsed"
        static let pinHash = "flint.pin.hash"
        static let pinSalt = "flint.pin.salt"
        static let openLimitState = "flint.openLimit.state"
        static let armingHealth = "flint.armingHealth"
    }

    private let defaults: UserDefaults

    /// Returns nil if the App Group is misconfigured (entitlement missing) — fail loudly in dev.
    public init?(suiteName: String = FlintGroupStore.appGroupID) {
        guard let defaults = UserDefaults(suiteName: suiteName) else { return nil }
        self.defaults = defaults
    }

    // MARK: Sessions

    public func saveSessions(_ sessions: [FlintSession]) {
        defaults.set(try? JSONEncoder().encode(sessions), forKey: Key.sessions)
    }

    public func loadSessions() -> [FlintSession] {
        guard let data = defaults.data(forKey: Key.sessions) else { return [] }
        return (try? JSONDecoder().decode([FlintSession].self, from: data)) ?? []
    }

    // MARK: Active session

    public func saveActiveSession(_ session: FlintActiveSession) {
        defaults.set(try? JSONEncoder().encode(session), forKey: Key.activeSession)
    }

    public func loadActiveSession() -> FlintActiveSession? {
        guard let data = defaults.data(forKey: Key.activeSession) else { return nil }
        return try? JSONDecoder().decode(FlintActiveSession.self, from: data)
    }

    public func clearActiveSession() {
        defaults.removeObject(forKey: Key.activeSession)
    }

    // MARK: Emergency pass

    public func emergencyPassLastUsed() -> Date? {
        let t = defaults.double(forKey: Key.emergencyPassLastUsed)
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }

    public func recordEmergencyPassUse(_ date: Date = Date()) {
        defaults.set(date.timeIntervalSince1970, forKey: Key.emergencyPassLastUsed)
    }

    // MARK: App PIN

    public func pinRecord() -> (hash: String, salt: String)? {
        guard let hash = defaults.string(forKey: Key.pinHash),
              let salt = defaults.string(forKey: Key.pinSalt) else { return nil }
        return (hash, salt)
    }

    public func setPINRecord(hash: String, salt: String) {
        defaults.set(hash, forKey: Key.pinHash)
        defaults.set(salt, forKey: Key.pinSalt)
    }

    public func clearPINRecord() {
        defaults.removeObject(forKey: Key.pinHash)
        defaults.removeObject(forKey: Key.pinSalt)
    }

    // MARK: Open-limit counters

    public func loadOpenLimitState() -> FlintOpenLimitState {
        guard let data = defaults.data(forKey: Key.openLimitState) else { return FlintOpenLimitState() }
        return (try? JSONDecoder().decode(FlintOpenLimitState.self, from: data)) ?? FlintOpenLimitState()
    }

    public func saveOpenLimitState(_ state: FlintOpenLimitState) {
        defaults.set(try? JSONEncoder().encode(state), forKey: Key.openLimitState)
    }

    // MARK: Arming health (what DeviceActivity actually accepted)

    public func saveArmingHealth(_ health: FlintArmingHealth) {
        defaults.set(try? JSONEncoder().encode(health), forKey: Key.armingHealth)
    }

    public func loadArmingHealth() -> FlintArmingHealth {
        guard let data = defaults.data(forKey: Key.armingHealth) else { return FlintArmingHealth() }
        return (try? JSONDecoder().decode(FlintArmingHealth.self, from: data)) ?? FlintArmingHealth()
    }

    // MARK: Selection (opaque tokens)

    #if canImport(FamilyControls)
    public func saveSelection(_ selection: FamilyActivitySelection) {
        defaults.set(try? JSONEncoder().encode(selection), forKey: Key.selection)
    }

    public func loadSelection() -> FamilyActivitySelection? {
        guard let data = defaults.data(forKey: Key.selection) else { return nil }
        return try? JSONDecoder().decode(FamilyActivitySelection.self, from: data)
    }

    // MARK: Scheduled rules

    public func saveRules(_ rules: [FlintScheduleRule]) {
        defaults.set(try? JSONEncoder().encode(rules), forKey: Key.rules)
    }

    public func loadRules() -> [FlintScheduleRule] {
        guard let data = defaults.data(forKey: Key.rules) else { return [] }
        return (try? JSONDecoder().decode([FlintScheduleRule].self, from: data)) ?? []
    }

    // MARK: Time limits

    public func saveLimits(_ limits: [FlintLimitRule]) {
        defaults.set(try? JSONEncoder().encode(limits), forKey: Key.limits)
    }

    public func loadLimits() -> [FlintLimitRule] {
        guard let data = defaults.data(forKey: Key.limits) else { return [] }
        return (try? JSONDecoder().decode([FlintLimitRule].self, from: data)) ?? []
    }

    // MARK: Saved app groups (presets)

    public func saveAppGroups(_ groups: [FlintAppGroup]) {
        defaults.set(try? JSONEncoder().encode(groups), forKey: Key.appGroups)
    }

    public func loadAppGroups() -> [FlintAppGroup] {
        guard let data = defaults.data(forKey: Key.appGroups) else { return [] }
        return (try? JSONDecoder().decode([FlintAppGroup].self, from: data)) ?? []
    }
    #endif
}
