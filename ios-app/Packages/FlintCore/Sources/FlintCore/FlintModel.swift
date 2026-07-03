import Foundation

/// How hard it is to break/stop a block early. `hardcore` (Deep Focus) is paywalled in Opal;
/// in Flint it is FREE — the whole point. Enforced in app-state logic on top of the OS shield.
public enum BreakLevel: String, Codable, CaseIterable, Sendable {
    case easy
    case harder
    case hardcore
}

/// A recurring schedule window. `daysOfWeek` uses `Calendar` weekday numbering (1 = Sunday …
/// 7 = Saturday); empty means every day. Maps onto a repeating `DeviceActivitySchedule`.
public struct FlintSchedule: Codable, Equatable, Sendable {
    public var daysOfWeek: Set<Int>
    public var startHour: Int
    public var startMinute: Int
    public var endHour: Int
    public var endMinute: Int

    public init(
        daysOfWeek: Set<Int> = [],
        startHour: Int,
        startMinute: Int = 0,
        endHour: Int,
        endMinute: Int = 0
    ) {
        self.daysOfWeek = daysOfWeek
        self.startHour = startHour
        self.startMinute = startMinute
        self.endHour = endHour
        self.endMinute = endMinute
    }
}

/// A configured block. `schedule == nil` means a manual "Block Now" session. Flint imposes no
/// count cap and no 24h-advance cap (both are Opal paywalls). The selected app/site tokens are
/// stored separately in the App Group (see `FlintGroupStore`) because tokens are opaque.
public struct FlintSession: Codable, Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var breakLevel: BreakLevel
    public var schedule: FlintSchedule?
    /// Allow-list ("brick phone") mode: shield everything except the allowed tokens.
    public var allowListMode: Bool
    public var enabled: Bool

    public init(
        id: String = UUID().uuidString,
        name: String,
        breakLevel: BreakLevel = .easy,
        schedule: FlintSchedule? = nil,
        allowListMode: Bool = false,
        enabled: Bool = true
    ) {
        self.id = id
        self.name = name
        self.breakLevel = breakLevel
        self.schedule = schedule
        self.allowListMode = allowListMode
        self.enabled = enabled
    }
}

// MARK: - Wave-1 vertical scaffolding (shared stubs)
//
// The types below are the cross-cutting model shapes the Wave-1 iOS verticals build on. They live
// here in FlintCore — not in each vertical's `Flint/Features/<X>` folder — because their
// enforcement crosses the app↔extension boundary: the host app writes them, but the system
// extensions (which cannot import the app target) must read them to keep enforcing while the app is
// closed. Each is pure Foundation + Codable to match the rest of FlintCore and stay testable.
//
// Ownership: web-restriction (I-PRIVACY → FlintWebRestrictions.swift) and open-limit
// (I-OPENLIMITS → FlintOpenLimit.swift) types live in those verticals' own FlintCore files, so
// they are intentionally NOT defined here (same module = duplicate symbols otherwise).

/// A Pomodoro cycle: a focus interval, a break interval, and how many rounds to run. Referenced by
/// the "Start Pomodoro" App Intent (I-SHORTCUTS) and Block-Now's Pomodoro mode. Pure data — the
/// controller turns each round into a timed `FlintActiveSession`.
public struct FlintPomodoroConfig: Codable, Equatable, Sendable {
    public var focusMinutes: Int
    public var breakMinutes: Int
    public var rounds: Int

    public init(focusMinutes: Int = 25, breakMinutes: Int = 5, rounds: Int = 4) {
        self.focusMinutes = focusMinutes
        self.breakMinutes = breakMinutes
        self.rounds = rounds
    }

    /// The classic 25 / 5 × 4 cycle.
    public static let standard = FlintPomodoroConfig()

    /// Whole-cycle wall-clock minutes (focus rounds + the breaks between them).
    public var totalMinutes: Int {
        guard rounds > 0 else { return 0 }
        return rounds * focusMinutes + max(0, rounds - 1) * breakMinutes
    }
}

/// A portable description of a session to start, so an App Intent (Shortcuts / Siri, I-SHORTCUTS)
/// or a Focus Filter (I-FOCUSFILTER) can ask the app to begin a block without holding opaque
/// FamilyControls tokens itself. Resolve `appGroupID` against the saved `FlintAppGroup`s
/// (`FlintGroupStore.loadAppGroups()`); a nil id means "use the current/last selection".
public struct FlintSessionRequest: Codable, Equatable, Sendable {
    /// Saved app-group/preset to block. nil = the current/last selection.
    public var appGroupID: String?
    /// Run length in minutes; <= 0 means open-ended (until manually stopped).
    public var durationMinutes: Int
    public var breakLevel: BreakLevel
    /// Allow-list ("brick phone") mode: shield everything except the selection.
    public var allowListMode: Bool
    public var name: String

    public init(
        appGroupID: String? = nil,
        durationMinutes: Int = 0,
        breakLevel: BreakLevel = .easy,
        allowListMode: Bool = false,
        name: String = "Focus"
    ) {
        self.appGroupID = appGroupID
        self.durationMinutes = durationMinutes
        self.breakLevel = breakLevel
        self.allowListMode = allowListMode
        self.name = name
    }

    /// Duration as a `TimeInterval` for `FlintSessionController.startBlockNow(...)` (0 = open-ended).
    public var duration: TimeInterval { max(0, TimeInterval(durationMinutes) * 60) }
}

/// Persisted settings for Flint's iOS **Focus Filter** (`SetFocusFilterIntent`, I-FOCUSFILTER):
/// when a system Focus (Work / Sleep / …) turns ON, Flint starts the block this describes; OFF
/// stops it. One-directional by design — ending the Focus ends the block, but stopping the block
/// in-app does not turn the Focus off.
public struct FlintFocusFilterConfig: Codable, Equatable, Sendable {
    public var appGroupID: String?
    public var breakLevel: BreakLevel
    public var allowListMode: Bool

    public init(appGroupID: String? = nil, breakLevel: BreakLevel = .easy, allowListMode: Bool = false) {
        self.appGroupID = appGroupID
        self.breakLevel = breakLevel
        self.allowListMode = allowListMode
    }

    /// The open-ended session this filter starts while its Focus is active.
    public var sessionRequest: FlintSessionRequest {
        FlintSessionRequest(
            appGroupID: appGroupID,
            durationMinutes: 0,
            breakLevel: breakLevel,
            allowListMode: allowListMode,
            name: "Focus Filter"
        )
    }
}

/// Bedtime wind-down strictness (Opal's "Sleep Assist" tiers — all FREE in Flint).
public enum FlintSleepAssistLevel: String, Codable, CaseIterable, Sendable {
    case off          // no bedtime block
    case windDown     // harder app access; the user keeps control
    case fullAssist   // block everything except the allow-list; early exit only via Emergency Pass
}

/// Post-wake strictness (Opal's "Morning Assist" tiers — all FREE in Flint).
public enum FlintMorningAssistLevel: String, Codable, CaseIterable, Sendable {
    case off
    case slowUplift   // gentle reminders if the phone is used soon after waking
    case fullAssist   // block all non-allow-listed apps for a window after waking
}

/// **Sleep Mode + Morning Assist** — a bedtime/wake downtime engine separate from Sessions and
/// Limits (I-SLEEP). The window drives a `DeviceActivitySchedule`; Full Assist shields everything
/// except `allowGroupID`'s selection, with the free weekly Emergency Pass (`FlintEmergencyPass`)
/// as the only early exit. Bundled soundscapes / sleep-stories are intentionally out of scope for
/// v1 (content, not a blocking control).
public struct FlintSleepConfig: Codable, Equatable, Sendable {
    public var enabled: Bool
    /// Bedtime → wake window. `daysOfWeek` empty = every night. Reuses `FlintSchedule`.
    public var schedule: FlintSchedule
    public var sleepAssist: FlintSleepAssistLevel
    public var morningAssist: FlintMorningAssistLevel
    /// Saved app-group whose selection stays allowed during Full Assist (nil = allow nothing).
    public var allowGroupID: String?
    /// How long Morning Full Assist keeps blocking after wake, in minutes.
    public var morningAssistMinutes: Int

    public init(
        enabled: Bool = false,
        schedule: FlintSchedule = FlintSchedule(startHour: 22, startMinute: 0, endHour: 7, endMinute: 0),
        sleepAssist: FlintSleepAssistLevel = .off,
        morningAssist: FlintMorningAssistLevel = .off,
        allowGroupID: String? = nil,
        morningAssistMinutes: Int = 60
    ) {
        self.enabled = enabled
        self.schedule = schedule
        self.sleepAssist = sleepAssist
        self.morningAssist = morningAssist
        self.allowGroupID = allowGroupID
        self.morningAssistMinutes = morningAssistMinutes
    }

    /// Sensible starting point: 10pm–7am every night, both assists off until the user opts in.
    public static let `default` = FlintSleepConfig()
}

// MARK: - App Group persistence for the stubs
//
// `FlintGroupStore` is the canonical App Group I/O, but it's a shared/locked file in the parallel
// build, so these helpers persist the new stubs into the SAME `group.com.flint.peakfocus` suite
// with the same JSON encoding — the host app and the system extensions read identical bytes without
// anyone editing `FlintGroupStore`. Keys stay namespaced under "flint.*" to match its convention.

extension UserDefaults {
    /// Flint's shared App Group defaults (nil if the App Group entitlement is missing).
    public static var flintGroup: UserDefaults? { UserDefaults(suiteName: FlintGroupStore.appGroupID) }
}

private enum FlintStubStoreKey {
    static let sleepConfig = "flint.sleepConfig"
    static let focusFilter = "flint.focusFilterConfig"
    static let pomodoro = "flint.pomodoroConfig"
}

extension FlintSleepConfig {
    public static func load(from defaults: UserDefaults? = .flintGroup) -> FlintSleepConfig {
        guard let data = defaults?.data(forKey: FlintStubStoreKey.sleepConfig),
              let config = try? JSONDecoder().decode(FlintSleepConfig.self, from: data) else { return .default }
        return config
    }

    public func save(to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: FlintStubStoreKey.sleepConfig)
    }
}

extension FlintFocusFilterConfig {
    public static func load(from defaults: UserDefaults? = .flintGroup) -> FlintFocusFilterConfig {
        guard let data = defaults?.data(forKey: FlintStubStoreKey.focusFilter),
              let config = try? JSONDecoder().decode(FlintFocusFilterConfig.self, from: data) else {
            return FlintFocusFilterConfig()
        }
        return config
    }

    public func save(to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: FlintStubStoreKey.focusFilter)
    }
}

extension FlintPomodoroConfig {
    public static func load(from defaults: UserDefaults? = .flintGroup) -> FlintPomodoroConfig {
        guard let data = defaults?.data(forKey: FlintStubStoreKey.pomodoro),
              let config = try? JSONDecoder().decode(FlintPomodoroConfig.self, from: data) else { return .standard }
        return config
    }

    public func save(to defaults: UserDefaults? = .flintGroup) {
        defaults?.set(try? JSONEncoder().encode(self), forKey: FlintStubStoreKey.pomodoro)
    }
}
