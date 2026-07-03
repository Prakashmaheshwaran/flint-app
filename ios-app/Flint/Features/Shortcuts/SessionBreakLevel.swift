#if canImport(AppIntents) && canImport(FamilyControls) && canImport(ManagedSettings) && canImport(DeviceActivity)
import AppIntents
import FlintCore

/// Shortcuts/Siri-facing mirror of `BreakLevel`. App Intents needs an `AppEnum` to render a picker
/// (and to let Siri disambiguate by voice), so we bridge rather than conform `BreakLevel` itself —
/// that keeps the `AppIntents` import in the app layer and `FlintCore` pure Foundation.
enum SessionBreakLevel: String, AppEnum {
    case easy
    case harder
    case hardcore

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Strictness")

    static var caseDisplayRepresentations: [SessionBreakLevel: DisplayRepresentation] = [
        .easy: "Easy — stop anytime",
        .harder: "Harder — adds friction to stop",
        .hardcore: "Hardcore — no early exit (free in Flint)",
    ]

    init(_ level: BreakLevel) { self = SessionBreakLevel(rawValue: level.rawValue) ?? .easy }

    /// The FlintCore value the controller actually enforces.
    var breakLevel: BreakLevel { BreakLevel(rawValue: rawValue) ?? .easy }
}
#endif
