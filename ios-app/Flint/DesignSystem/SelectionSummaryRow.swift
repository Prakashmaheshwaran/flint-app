import SwiftUI
import FamilyControls
import ManagedSettings
import FlintCore

/// E05 host summary around Apple's picker: what the selection holds, in words and — on a device
/// with Screen Time access — as the apps', categories' and sites' own icons. Tokens are opaque;
/// only Apple's `Label(token)` can show them, so nothing here reads or stores a name.
struct SelectionSummaryRow: View {
    let selection: FamilyActivitySelection
    /// Shown when nothing is picked yet, e.g. "Choose what rests".
    let emptyTitle: LocalizedStringKey
    /// Leads the counts once something is picked, e.g. "Block".
    var verb: LocalizedStringKey = "Block"
    var maxIcons = 5
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                if counts.total == 0 {
                    Text(emptyTitle).flintRowTitle()
                    Text("Apps, categories and sites").flintSecondary()
                } else {
                    Text("\(Text(verb)) \(counts.summary)").flintRowTitle()
                    if !typeSize.isAccessibilitySize { icons }
                    if counts.categories > 0 {
                        Text("A category includes every app in it.").flintSecondary()
                    }
                    Text("Tap to change").flintSecondary()
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            FlintChevron()
        }
        .accessibilityElement(children: .combine)
    }

    /// Up to `maxIcons` of Apple's token labels, then "+N". Decorative for VoiceOver: the counts
    /// above already say what is selected.
    private var icons: some View {
        let apps = Array(selection.applicationTokens)
        let categories = Array(selection.categoryTokens)
        let sites = Array(selection.webDomainTokens)
        let shownApps = Array(apps.prefix(maxIcons))
        let shownCategories = Array(categories.prefix(max(0, maxIcons - shownApps.count)))
        let shownSites = Array(sites.prefix(max(0, maxIcons - shownApps.count - shownCategories.count)))
        let hidden = counts.total - shownApps.count - shownCategories.count - shownSites.count
        return HStack(spacing: 6) {
            ForEach(shownApps, id: \.self) { Label($0).labelStyle(.iconOnly) }
            ForEach(shownCategories, id: \.self) { Label($0).labelStyle(.iconOnly) }
            ForEach(shownSites, id: \.self) { Label($0).labelStyle(.iconOnly) }
            if hidden > 0 {
                Text("+\(hidden)").font(FlintFont.text(12, weight: .medium)).foregroundStyle(FlintBrand.mist)
            }
        }
        .frame(height: 28)
        .accessibilityHidden(true)
    }

    private var counts: SelectionCounts {
        SelectionCounts(apps: selection.applicationTokens.count,
                        categories: selection.categoryTokens.count,
                        sites: selection.webDomainTokens.count)
    }
}

/// Pure count wording, testable without tokens.
struct SelectionCounts: Equatable {
    var apps = 0
    var categories = 0
    var sites = 0

    var total: Int { apps + categories + sites }

    /// "3 apps · 1 category · 2 sites"; empty parts are omitted.
    var summary: String {
        var parts: [String] = []
        if apps > 0 { parts.append(apps == 1 ? String(localized: "1 app") : String(localized: "\(apps) apps")) }
        if categories > 0 {
            parts.append(categories == 1 ? String(localized: "1 category") : String(localized: "\(categories) categories"))
        }
        if sites > 0 { parts.append(sites == 1 ? String(localized: "1 site") : String(localized: "\(sites) sites")) }
        return parts.joined(separator: " · ")
    }
}
