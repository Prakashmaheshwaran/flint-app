#if canImport(AppIntents) && canImport(FamilyControls)
import AppIntents
import FlintCore

/// A saved app/site preset (`FlintAppGroup`) surfaced to iOS's Focus settings UI so the user can
/// pick *which* preset a Focus blocks. Only `id` + `name` cross the AppIntents boundary; the opaque
/// FamilyControls selection is re-resolved from the id at block time (see `FocusFilterController`),
/// because tokens aren't representable as intent parameters.
struct FlintAppGroupEntity: AppEntity, Identifiable {
    let id: String
    let name: String

    init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    init(_ group: FlintAppGroup) {
        self.init(id: group.id, name: group.name)
    }

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Preset"

    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }

    static var defaultQuery = FlintAppGroupQuery()
}

/// Feeds the preset picker from the saved app groups in the shared App Group. Read-only — the user
/// creates presets in Block Now; here they only choose among them.
struct FlintAppGroupQuery: EntityQuery {
    init() {}

    func entities(for identifiers: [String]) async throws -> [FlintAppGroupEntity] {
        let wanted = Set(identifiers)
        return savedGroups().filter { wanted.contains($0.id) }.map(FlintAppGroupEntity.init)
    }

    func suggestedEntities() async throws -> [FlintAppGroupEntity] {
        savedGroups().map(FlintAppGroupEntity.init)
    }

    private func savedGroups() -> [FlintAppGroup] {
        FlintGroupStore()?.loadAppGroups() ?? []
    }
}

/// AppIntents-facing mirror of `BreakLevel`. `BreakLevel` lives in FlintCore (shared with the
/// extensions) and can't adopt `AppEnum` there, so this enum carries the same cases for the Focus
/// settings picker and converts both ways.
enum FocusFilterBreakLevel: String, AppEnum {
    case easy
    case harder
    case hardcore

    init(_ level: BreakLevel) {
        self = FocusFilterBreakLevel(rawValue: level.rawValue) ?? .easy
    }

    var breakLevel: BreakLevel { BreakLevel(rawValue: rawValue) ?? .easy }

    /// Short label for the filter summary line.
    var label: String {
        switch self {
        case .easy: return "Easy to stop"
        case .harder: return "Harder to stop"
        case .hardcore: return "Hardcore"
        }
    }

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Strictness"

    static var caseDisplayRepresentations: [FocusFilterBreakLevel: DisplayRepresentation] = [
        .easy: DisplayRepresentation(title: "Easy", subtitle: "Stop anytime"),
        .harder: DisplayRepresentation(title: "Harder", subtitle: "A speed bump before you stop"),
        .hardcore: DisplayRepresentation(title: "Hardcore", subtitle: "Can only end when the Focus does"),
    ]
}
#endif
