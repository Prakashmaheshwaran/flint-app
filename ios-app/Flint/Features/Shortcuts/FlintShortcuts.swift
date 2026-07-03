#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents

/// Flint's Siri / Shortcuts surface. iOS auto-discovers this provider from the app binary — there's
/// no separate AppIntents extension target (ADR-005/006 fix the target count at 5). It gives Siri
/// the canonical spoken phrases and seeds the Shortcuts app with Flint's actions.
///
/// Each phrase MUST contain `\(.applicationName)`; iOS substitutes the app's names ("Flint").
struct FlintShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartSessionIntent(),
            phrases: [
                "Start a \(.applicationName) session",
                "Start focus with \(.applicationName)",
                "Block apps with \(.applicationName)",
            ],
            shortTitle: "Start Session",
            systemImageName: "moon.fill"
        )
        AppShortcut(
            intent: StartPomodoroIntent(),
            phrases: [
                "Start a \(.applicationName) pomodoro",
                "Begin a pomodoro with \(.applicationName)",
            ],
            shortTitle: "Start Pomodoro",
            systemImageName: "timer"
        )
        AppShortcut(
            intent: StopSessionIntent(),
            phrases: [
                "Stop my \(.applicationName) session",
                "End focus with \(.applicationName)",
            ],
            shortTitle: "Stop Session",
            systemImageName: "stop.circle"
        )
    }
}
#endif
