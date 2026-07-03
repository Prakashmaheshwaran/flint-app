#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// Flint's iOS **Focus Filter** (`SetFocusFilterIntent`). The user attaches it to a system Focus
/// (Settings → Focus → a Focus → Add Filter → Flint); iOS then runs `perform()` whenever that Focus
/// turns on or off. On → Flint starts an open-ended block of the chosen preset; off → it stops that
/// block. One-directional: stopping the block inside Flint never turns the Focus off.
///
/// **Verified by build only.** Focus activation is a device-side system event the Simulator can't
/// raise, so the on/off path can't be exercised in CI. What's exercised on device is the reconcile
/// in `perform()`: `Self.current` (the active Flint Focus filter, or a throw when none is) decides
/// whether to start or stop the block.
struct FlintFocusFilter: SetFocusFilterIntent {
    static var title: LocalizedStringResource = "Block apps during a Focus"

    static var description: IntentDescription? =
        "Start a Flint block automatically while this Focus is on, and stop it when the Focus ends."

    /// Which saved preset to block. Unset = whatever you last selected in Block Now.
    @Parameter(title: "Block")
    var preset: FlintAppGroupEntity?

    /// How hard the auto-started block is to stop from inside Flint while the Focus is on.
    @Parameter(title: "Strictness", default: .easy)
    var strictness: FocusFilterBreakLevel

    init() {}

    /// Summary shown for this filter in the Focus settings UI.
    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "Block \(preset?.name ?? "your last selection")",
            subtitle: "\(strictness.label) · stops when the Focus ends"
        )
    }

    /// The open-ended block this filter's parameters describe (lifetime tied to the Focus).
    var sessionRequest: FlintSessionRequest {
        FlintSessionRequest(
            appGroupID: preset?.id,
            durationMinutes: 0,
            breakLevel: strictness.breakLevel,
            allowListMode: false,   // brick/allow-list mode isn't wired into the engine yet — don't imply it
            name: FocusFilterController.sessionName
        )
    }

    func perform() async throws -> some IntentResult {
        let controller = FocusFilterController()
        // `Self.current` is the Flint Focus filter active right now; it throws when none is. Use it
        // purely as "is a Flint Focus on?" and start from *this* filter's params (so a fresh
        // activation doesn't depend on `current` having propagated its payload yet).
        if (try? await Self.current) != nil {
            controller.activate(sessionRequest)
        } else {
            controller.deactivate()
        }
        return .result()
    }

    /// Pre-fill new filters in the Focus settings UI from the user's in-app Focus-Filter defaults
    /// (`FlintFocusFilterConfig`, edited in `FocusFilterView`).
    static func suggestedFocusFilters(for context: FocusFilterSuggestionContext) async -> [FlintFocusFilter] {
        let config = FlintFocusFilterConfig.load()
        var suggestion = FlintFocusFilter()
        suggestion.strictness = FocusFilterBreakLevel(config.breakLevel)
        if let id = config.appGroupID,
           let group = FlintGroupStore()?.loadAppGroups().first(where: { $0.id == id }) {
            suggestion.preset = FlintAppGroupEntity(group)
        }
        return [suggestion]
    }
}
#endif
