#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// "Stop my Flint session" — ends the running block. Honors Hardcore (Deep Focus), which refuses to
/// stop until it expires: the user gets a clear spoken/printed reason instead of a silent no-op.
struct StopSessionIntent: AppIntent {
    static var title: LocalizedStringResource = "Stop Focus Session"
    static var description = IntentDescription("End the block that's currently running.")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try FlintShortcutsRunner.stopActiveSession()
        return .result(dialog: "Focus ended — your apps are unblocked.")
    }
}
#endif
