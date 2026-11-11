#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// A whole Pomodoro focus/rest chain: one commitment spanning every focus round and the planned
/// rests between them (F01). Every parameter defaults to the saved cadence in Flint, so a bare
/// "Start Pomodoro" keeps working. A single round runs as one plain focus session, exactly as the
/// legacy intent did; a chain outside the plan bounds is refused with its limits.
struct StartPomodoroIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Pomodoro"
    static var description = IntentDescription("Start a Pomodoro chain over your blocked apps: focus rounds with planned rests between them, all one commitment. Use your saved Flint cadence, or set the lengths here.")
    static var openAppWhenRun = false

    @Parameter(title: "Focus minutes", description: "5–120, in steps of 5. Leave empty for your saved cadence.")
    var focusMinutes: Int?

    @Parameter(title: "Rest minutes", description: "1–60. Leave empty for your saved cadence.")
    var restMinutes: Int?

    @Parameter(title: "Rounds", description: "2–8 for a chain, 1 for a single focus session. Leave empty for your saved cadence.")
    var rounds: Int?

    @Parameter(title: "Long rest minutes", description: "Optional 5–60 rest, at least the normal rest. Leave empty for none.")
    var longRestMinutes: Int?

    @Parameter(title: "Long rest every", description: "Optional: place the long rest every 2–7 rounds. Set both long-rest values or neither.")
    var longRestEvery: Int?

    @Parameter(title: "App Group", description: "A saved group to block. Leave empty to reuse your last selection.")
    var appGroup: FlintAppGroupEntity?

    @Parameter(title: "Retry key", description: "Reuse this value when retrying the same start. Leave empty for automatic retry handling.")
    var requestID: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Start a Pomodoro with \(\.$rounds) rounds") {
            \.$focusMinutes
            \.$restMinutes
            \.$longRestMinutes
            \.$longRestEvery
            \.$appGroup
            \.$requestID
        }
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let saved = FlintPomodoroConfig.load()
        let config = FlintPomodoroConfig(
            focusMinutes: focusMinutes ?? saved.focusMinutes,
            breakMinutes: restMinutes ?? saved.breakMinutes,
            rounds: rounds ?? saved.rounds,
            longRestMinutes: longRestMinutes,
            longRestEvery: longRestMinutes == nil ? nil : (longRestEvery ?? saved.longRestEvery)
        )
        let level = BreakLevel.easy
        do {
            if let plan = FlintPomodoroPlan(config: config) {
                let prepared = try FlintShortcutsRunner.prepare(
                    FlintShortcutsRunner.pomodoroRequest(plan, appGroupID: appGroup?.id, breakLevel: level),
                    source: .pomodoro, requestID: requestID, pomodoro: plan)
                _ = try await startConfirmingIfNeeded(
                    { try FlintShortcutsRunner.start(prepared, confirmed: $0) },
                    hardcore: level == .hardcore, minutes: plan.totalMinutes, replaying: prepared.replaying)
                if prepared.replaying {
                    return .result(dialog: "This Pomodoro chain is already running.")
                }
                return .result(dialog: "Pomodoro started: \(plan.rounds) rounds of \(plan.focusMinutes) minutes with \(Self.restText(plan)) rests. \(plan.totalMinutes) minutes in all.")
            }
            // A chain the plan bounds reject is refused with its limits rather than silently run
            // as something else; only a single round is an ordinary focus session.
            guard config.rounds <= 1 else { throw FlintShortcutsError.invalidPomodoroPlan }
            let request = FlintShortcutsRunner.pomodoroRoundRequest(config, appGroupID: appGroup?.id,
                                                                   breakLevel: level)
            let prepared = try FlintShortcutsRunner.prepare(request, source: .pomodoro, requestID: requestID)
            _ = try await startConfirmingIfNeeded(
                { try FlintShortcutsRunner.start(prepared, confirmed: $0) },
                hardcore: level == .hardcore, minutes: config.focusMinutes, replaying: prepared.replaying)
            if prepared.replaying { return .result(dialog: "This request's focus session is already running.") }
            return .result(dialog: "Focus session started. Blocking for \(config.focusMinutes) minutes.")
        } catch FlintSessionError.startAlreadyEnded(_, _) {
            return .result(dialog: "This request already ended. It wasn't started again.")
        }
    }

    private static func restText(_ plan: FlintPomodoroPlan) -> String {
        guard let long = plan.longRestMinutes, let every = plan.longRestEvery else {
            return "\(plan.restMinutes)-minute"
        }
        return "\(plan.restMinutes)-minute (a \(long)-minute rest every \(every) rounds)"
    }
}
#endif
