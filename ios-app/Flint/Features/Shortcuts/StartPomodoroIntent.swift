#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// One timed focus round. Break lengths and round counts are not exposed until the durable
/// multi-round state machine ships; legacy runner requests also execute only one round.
struct StartPomodoroIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Pomodoro Focus Round"
    static var description = IntentDescription("Start one focus round over your blocked apps. Run it again when you're ready for another round.")
    static var openAppWhenRun = false

    @Parameter(title: "Focus minutes", default: 25)
    var focusMinutes: Int

    @Parameter(title: "App Group", description: "A saved group to block. Leave empty to reuse your last selection.")
    var appGroup: FlintAppGroupEntity?

    @Parameter(title: "Retry key", description: "Reuse this value when retrying the same start. Leave empty for automatic retry handling.")
    var requestID: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Pomodoro focus for \(\.$focusMinutes) minutes") {
            \.$appGroup
            \.$requestID
        }
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let config = FlintPomodoroConfig(
            focusMinutes: max(1, focusMinutes),
            breakMinutes: 0,
            rounds: 1
        )
        let request = FlintShortcutsRunner.pomodoroRoundRequest(config, appGroupID: appGroup?.id, breakLevel: .easy)
        let prepared = try FlintShortcutsRunner.prepare(request, source: .pomodoro, requestID: requestID)
        do {
            _ = try await startConfirmingIfNeeded(
                { try FlintShortcutsRunner.start(prepared, confirmed: $0) },
                hardcore: false, minutes: config.focusMinutes, replaying: prepared.replaying)
        } catch FlintSessionError.startAlreadyEnded(_, _) {
            return .result(dialog: "This request's focus round already ended. It wasn't started again.")
        }
        if prepared.replaying { return .result(dialog: "This request's focus round is already running.") }
        return .result(
            dialog: "Focus round started. Blocking for \(config.focusMinutes) minutes."
        )
    }
}
#endif
