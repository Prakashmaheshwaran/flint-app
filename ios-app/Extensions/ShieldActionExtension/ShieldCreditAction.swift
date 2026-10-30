import ManagedSettings

/// The only shield path that can spend an Open credit (ADR-012 §5).
///
/// The action extension learns only which button was pressed, never which labels Apple drew,
/// and a drawn configuration can be cached or stale. `ShieldCopy` therefore draws the
/// secondary "Use one credit" button only for an available credit, and only that button calls
/// `accept`, which re-checks the pool, blocking causes and registration at press time. "Go back"
/// and any unknown action always close without charging. A press refused before commitment
/// charges nothing; a failure after commitment keeps the spent credit. Either way the response
/// is `.defer`, which asks iOS to redraw the shield from current state.
enum ShieldCreditAction {
    /// `accept` returns true only for a committed, released (or replayed live) grant. With the
    /// release gate off it is never called; a stray secondary press only requests a redraw.
    static func response(to action: ShieldAction, acceptanceEnabled: Bool,
                         accept: () -> Bool) -> ShieldActionResponse {
        switch action {
        case .secondaryButtonPressed:
            guard acceptanceEnabled else { return .defer }
            return accept() ? .close : .defer
        case .primaryButtonPressed:
            return .close
        default:
            return .close
        }
    }
}
