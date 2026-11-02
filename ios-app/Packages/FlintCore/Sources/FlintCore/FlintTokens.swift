import Foundation
#if canImport(FamilyControls)
import FamilyControls
#endif
#if canImport(ManagedSettings)
import ManagedSettings
#endif

/// Helpers for working with the opaque, **rotating** Screen Time tokens.
///
/// Apple tokens carry no bundle ID / name / icon, render only via system `Label`s, and rotate
/// over time (FB14082790) — so persisted selections can go stale. Reference selections by a
/// stored ID, re-validate on use, and never assume a stored token still resolves.
public enum FlintTokens {

    #if canImport(FamilyControls)
    /// Total selected app + web-domain tokens (categories are counted separately by the OS).
    public static func tokenCount(in selection: FamilyActivitySelection) -> Int {
        selection.applicationTokens.count + selection.webDomainTokens.count
    }

    #endif
}
