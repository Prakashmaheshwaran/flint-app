#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// "Start a Flint session" — begins a Block Now session over the user's saved selection (or a named
/// app-group) for a chosen length and strictness. `openAppWhenRun = false` lets Siri fire it
/// hands-free, in the background.
///
/// Build and injected adapter tests cover transition logic. Physical-device verification is
/// required to establish Screen Time enforcement.
struct StartSessionIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Focus Session"
    static var description = IntentDescription("Block your selected apps and websites for a set time.")

    /// Background by design: blocking shouldn't yank the user out of whatever they're doing.
    static var openAppWhenRun = false

    @Parameter(title: "Minutes", description: "How long to focus. 0 means until you stop (Easy only).", default: 25)
    var minutes: Int

    @Parameter(title: "Strictness", default: .easy)
    var strictness: SessionBreakLevel

    @Parameter(title: "App Group", description: "A saved group to block. Leave empty to reuse your last selection.")
    var appGroup: FlintAppGroupEntity?

    @Parameter(title: "Retry key", description: "Reuse this value when retrying the same start. Leave empty for automatic retry handling.")
    var requestID: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Focus for \(\.$minutes) minutes at \(\.$strictness) strictness") {
            \.$appGroup
            \.$requestID
        }
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let request = FlintSessionRequest(
            appGroupID: appGroup?.id,
            durationMinutes: max(0, minutes),
            breakLevel: strictness.breakLevel,
            name: "Focus"
        )
        // Hardcore removes the early exit and a replacement retires the running session: both
        // ask first, even from an automation (ADR-012 §2).
        let prepared = try FlintShortcutsRunner.prepare(request, requestID: requestID)
        let session: FlintActiveSession
        do {
            session = try await startConfirmingIfNeeded(
                { try FlintShortcutsRunner.start(prepared, confirmed: $0) },
                hardcore: strictness.breakLevel == .hardcore, minutes: minutes, replaying: prepared.replaying)
        } catch FlintSessionError.startAlreadyEnded(_, _) {
            return .result(dialog: "This request's focus session already ended. It wasn't started again.")
        }
        if prepared.replaying { return .result(dialog: "This request's focus session is already running.") }
        if session.endsAt != nil {
            return .result(dialog: "Focus on — blocking for \(minutes) minutes.")
        }
        return .result(dialog: "Focus on — blocking until you stop.")
    }
}
#endif
