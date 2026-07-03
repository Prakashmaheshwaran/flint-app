#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// "Start a Flint session" — begins a Block Now session over the user's saved selection (or a named
/// app-group) for a chosen length and strictness. `openAppWhenRun = false` lets Siri fire it
/// hands-free, in the background.
///
/// Reality check (honest docs): on the Simulator the shield won't actually apply — Screen Time
/// enforcement is device-only — but the `FlintActiveSession` is still recorded, so the build and the
/// app's logic path are exercised. On a real, authorized device this applies the block immediately.
struct StartSessionIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Focus Session"
    static var description = IntentDescription("Block your selected apps and websites for a set time.")

    /// Background by design: blocking shouldn't yank the user out of whatever they're doing.
    static var openAppWhenRun = false

    @Parameter(title: "Minutes", description: "How long to focus. 0 means until you stop.", default: 25)
    var minutes: Int

    @Parameter(title: "Strictness", default: .easy)
    var strictness: SessionBreakLevel

    @Parameter(title: "App Group", description: "A saved group to block. Leave empty to reuse your last selection.")
    var appGroup: FlintAppGroupEntity?

    static var parameterSummary: some ParameterSummary {
        Summary("Focus for \(\.$minutes) minutes at \(\.$strictness) strictness") {
            \.$appGroup
        }
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let request = FlintSessionRequest(
            appGroupID: appGroup?.id,
            durationMinutes: max(0, minutes),
            breakLevel: strictness.breakLevel,
            name: "Focus"
        )
        let session = try FlintShortcutsRunner.start(request)
        if session.endsAt != nil {
            return .result(dialog: "Focus on — blocking for \(minutes) minutes.")
        }
        return .result(dialog: "Focus on — blocking until you stop.")
    }
}
#endif
