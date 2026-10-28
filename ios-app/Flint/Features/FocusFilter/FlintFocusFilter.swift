#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// Flint's iOS **Focus Filter** (`SetFocusFilterIntent`). The user attaches it to a system Focus
/// (Settings → Focus → a Focus → Add Filter → Flint); iOS then runs `perform()` whenever that Focus
/// turns on or off. On → Flint starts a block of the chosen preset (Easy may last while the Focus
/// is on; Harder/Hardcore need a set length) unless another session is already running; off →
/// it asks to end that block under its tier: Easy ends, Harder starts its wait, Hardcore keeps
/// running until its end. Stopping the block inside Flint never turns the Focus off.
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

    /// How hard the auto-started block is to stop, from Flint or by turning the Focus off.
    /// Choosing it here is the confirmation of its rights (ADR-012 §2), so its rights are stated
    /// here and in the summary below.
    @Parameter(title: "Strictness",
               description: "Easy ends with the Focus. Harder needs a wait, then confirming in Flint. Hardcore can't be ended early — not in Flint, not by turning the Focus off — until it ends or you use the Emergency Pass.",
               default: .easy)
    var strictness: FocusFilterBreakLevel

    /// Harder and Hardcore blocks need a set length (ADR-012 §1); 0 = while the Focus is on,
    /// which is Easy-only. Filters saved by older builds read 0.
    @Parameter(title: "Minutes", description: "How long the block lasts. 0 = while the Focus is on (Easy only).", default: 0)
    var minutes: Int

    init() {}

    /// Summary shown for this filter in the Focus settings UI.
    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "Block \(preset?.name ?? "your last selection")",
            subtitle: subtitle
        )
    }

    private var subtitle: LocalizedStringResource {
        switch (strictness.breakLevel, minutes > 0) {
        case (.easy, true): return "Easy · \(minutes) min"
        case (.easy, false): return "Easy · while the Focus is on"
        case (.harder, true): return "Harder · \(minutes) min · ending needs a wait"
        case (.hardcore, true): return "Hardcore · \(minutes) min · can't be ended early"
        case (_, false): return "\(strictness.label) needs a set length — set Minutes, or this won't start"
        }
    }

    /// The block this filter's parameters describe.
    var sessionRequest: FlintSessionRequest {
        FlintSessionRequest(
            appGroupID: preset?.id,
            durationMinutes: max(0, minutes),
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
        let suggestion = FlintFocusFilter()
        suggestion.strictness = FocusFilterBreakLevel(config.breakLevel)
        suggestion.minutes = config.durationMinutes
        if let id = config.appGroupID,
           let group = FlintGroupStore()?.loadAppGroups().first(where: { $0.id == id }) {
            suggestion.preset = FlintAppGroupEntity(group)
        }
        return [suggestion]
    }
}
#endif
