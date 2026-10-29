#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// "Start a Flint pomodoro" — begins the first focus round of a Pomodoro cycle (default 25 / 5 × 4)
/// as a timed block, and saves the cadence so the app knows a Pomodoro is in progress.
///
/// Honest scope note: this fires the first focus round; the OS schedule clears the shield when it
/// ends. Automatic round chaining (break → next focus) is on-device app/monitor work and is *not*
/// part of this background intent in v1 — see `Shortcuts/README.md`. Same device-only enforcement
/// caveat as `StartSessionIntent`.
struct StartPomodoroIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Pomodoro"
    static var description = IntentDescription("Start a Pomodoro focus round over your blocked apps. Each run starts one round; run it again after your break for the next.")
    static var openAppWhenRun = false

    @Parameter(title: "Focus minutes", default: 25)
    var focusMinutes: Int

    @Parameter(title: "Break minutes", default: 5)
    var breakMinutes: Int

    @Parameter(title: "Rounds", default: 4)
    var rounds: Int

    @Parameter(title: "App Group", description: "A saved group to block. Leave empty to reuse your last selection.")
    var appGroup: FlintAppGroupEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Pomodoro: \(\.$focusMinutes) min focus, \(\.$breakMinutes) min break, \(\.$rounds) rounds") {
            \.$appGroup
        }
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let config = FlintPomodoroConfig(
            focusMinutes: max(1, focusMinutes),
            breakMinutes: max(0, breakMinutes),
            rounds: max(1, rounds)
        )
        _ = try await startConfirmingIfNeeded(
            { try FlintShortcutsRunner.startPomodoro(config, appGroupID: appGroup?.id, breakLevel: .easy, confirmed: $0) },
            hardcore: false, minutes: config.focusMinutes)
        return .result(
            dialog: "Focus round started — blocking for \(config.focusMinutes) minutes. After your \(config.breakMinutes)-minute break, run this again for the next round."
        )
    }
}
#endif
