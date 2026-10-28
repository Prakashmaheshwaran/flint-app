import SwiftUI
import FamilyControls
import ManagedSettings
import FlintCore

/// One picked app or site. Tokens are opaque: only Apple's `Label(token)` can show them, and the
/// key is lane B's `FlintProtectionStatus.targetKey(for:)` (the Open Limit encoding).
enum PickedTarget: Hashable, Identifiable {
    case app(ApplicationToken)
    case site(WebDomainToken)

    var key: String {
        switch self {
        case .app(let token): FlintProtectionStatus.targetKey(for: token)
        case .site(let token): FlintProtectionStatus.targetKey(for: token)
        }
    }

    var id: String { key }

    @ViewBuilder var label: some View {
        switch self {
        case .app(let token): Label(token)
        case .site(let token): Label(token)
        }
    }
}

/// "Apps and sites": everything picked in a saved rule, limit or Open Limit, each linking to what
/// covers it today. Categories are listed by their rules, not here — their members are opaque.
struct AppsListView: View {
    @State private var targets: [PickedTarget] = []
    @State private var readable = true
    @State private var categoriesPicked = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Apps and sites").flintTitle(28)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 8)
                Text("Everything you’ve picked in a rule or limit. Open one to see what covers it today.")
                    .flintSecondary(15)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)

                if !readable {
                    Text("Flint can’t read its saved rules right now.")
                        .flintSecondary(15)
                        .padding(.top, 24)
                } else if targets.isEmpty {
                    Text("Apps and sites you add to schedules and limits appear here.")
                        .flintSecondary(15)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 24)
                } else {
                    VStack(spacing: 0) {
                        ForEach(targets) { target in
                            NavigationLink { AppDetailView(target: target) } label: {
                                HStack(spacing: 14) {
                                    target.label
                                        .font(FlintFont.text(15, weight: .medium))
                                        .foregroundStyle(FlintBrand.bone)
                                    Spacer(minLength: 8)
                                    FlintChevron()
                                }
                                .frame(minHeight: 58)
                                .overlay(alignment: .bottom) { FlintHairline() }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .overlay(alignment: .top) { FlintHairline() }
                    .padding(.top, 20)
                }
                if categoriesPicked {
                    Text("Categories you picked aren’t listed: iOS doesn’t tell Flint which apps they contain.")
                        .flintSecondary()
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 14)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(FlintGroundBackground())
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
    }

    private func load() {
        guard let store = FlintGroupStore() else {
            readable = false
            return
        }
        let selections = store.loadRules().map(\.selection)
            + store.loadLimits().map(\.selection)
            + FlintOpenLimitRule.loadAll().map(\.selection)
        var seen = Set<String>()
        var result: [PickedTarget] = []
        for selection in selections {
            for token in selection.applicationTokens {
                let target = PickedTarget.app(token)
                if seen.insert(target.key).inserted { result.append(target) }
            }
            for token in selection.webDomainTokens {
                let target = PickedTarget.site(token)
                if seen.insert(target.key).inserted { result.append(target) }
            }
        }
        categoriesPicked = selections.contains { !$0.categoryTokens.isEmpty }
        targets = result
        readable = true
    }
}
