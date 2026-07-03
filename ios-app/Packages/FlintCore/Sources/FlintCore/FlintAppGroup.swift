import Foundation
#if canImport(FamilyControls)
import FamilyControls

/// A saved, named selection of apps/sites the user can reuse across sessions, schedules, and
/// limits — Opal's "App Groups"/presets, free in Flint. (Apple's opaque tokens mean a group is a
/// user-saved selection, not a data-shipped preset; you re-pick once, then reuse.)
public struct FlintAppGroup: Codable, Identifiable, Equatable {
    public var id: String
    public var name: String
    public var selection: FamilyActivitySelection

    public init(
        id: String = String(UUID().uuidString.prefix(8)),
        name: String,
        selection: FamilyActivitySelection
    ) {
        self.id = id
        self.name = name
        self.selection = selection
    }

    public var itemCount: Int {
        selection.applicationTokens.count
            + selection.categoryTokens.count
            + selection.webDomainTokens.count
    }
}
#endif
