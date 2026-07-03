import SwiftUI
import FamilyControls
import FlintCore

/// In-app companion for Flint's Focus Filter. The per-Focus choices (which preset, how strict) are
/// set by iOS itself in Settings → Focus; this screen sets the **defaults** new filters pre-fill
/// from (via `FlintFocusFilter.suggestedFocusFilters`) and explains how to attach the filter.
/// Editing here persists `FlintFocusFilterConfig` to the shared App Group. Reachable from
/// `SettingsView`; the Wave-2 integrator owns app navigation — this is just a pushable `View`.
struct FocusFilterView: View {
    @State private var config = FlintFocusFilterConfig.load()
    @State private var savedGroups: [FlintAppGroup] = FlintGroupStore()?.loadAppGroups() ?? []

    var body: some View {
        Form {
            Section {
                Text("Attach Flint to a system Focus (Work, Sleep, …) so a block starts the moment "
                     + "that Focus turns on — and stops when it turns off. No tap required.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section {
                Picker("Block", selection: $config.appGroupID) {
                    Text("Last selection").tag(String?.none)
                    ForEach(savedGroups) { group in
                        Text(group.name).tag(String?.some(group.id))
                    }
                }
                .tint(FlintBrand.spark)
            } header: {
                Text("Default preset")
            } footer: {
                Text(savedGroups.isEmpty
                     ? "Save an app group in Block Now to choose it here. Until then, Focus filters "
                       + "block your last selection."
                     : "New Focus filters start from this preset; you can override it per-Focus in "
                       + "Settings → Focus.")
            }

            Section {
                Picker("Strictness", selection: $config.breakLevel) {
                    ForEach(BreakLevel.allCases, id: \.self) { level in
                        Text(strictnessLabel(level)).tag(level)
                    }
                }
                .tint(FlintBrand.spark)
            } footer: {
                Text("A Focus Filter block ends the moment you turn the Focus off — even on Hardcore "
                     + "(the Focus is its timer). For a block that can't be lifted early, start a "
                     + "timed Hardcore session in Block Now instead.")
            }

            Section {
                Label("Settings → Focus → pick a Focus → Add Filter → Flint", systemImage: "moon.circle")
                    .font(.footnote)
            } header: {
                Text("How to turn it on")
            } footer: {
                Text("Focus filters fire on a real device — the Simulator can't trigger a Focus, so "
                     + "this is build-verified only.")
            }
        }
        .navigationTitle("Focus Filter")
        .navigationBarTitleDisplayMode(.inline)
        // Persist every edit so newly-created Focus filters pick up the latest defaults.
        .onChange(of: config) { newValue in
            newValue.save()
        }
    }

    private func strictnessLabel(_ level: BreakLevel) -> String {
        switch level {
        case .easy: return "Easy to stop"
        case .harder: return "Harder to stop"
        case .hardcore: return "Hardcore"
        }
    }
}
