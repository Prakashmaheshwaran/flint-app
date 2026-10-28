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
    /// The last Focus request Flint refused or couldn't finish (ADR-012 §1: never silent).
    @State private var refusal = FlintSessionHealth.load().lastRefusal

    var body: some View {
        Form {
            if let refusal {
                Section {
                    Text(refusal.reason)
                        .font(.footnote)
                    Button("Dismiss") {
                        FlintSessionHealth.clearRefusal()
                        self.refusal = nil
                    }
                } header: {
                    Text(refusal.action == .start ? "Focus didn't start a block" : "Focus off didn't end the block")
                } footer: {
                    Text(refusal.at.formatted(date: .abbreviated, time: .shortened))
                }
            }

            Section {
                Text("Attach Flint to a system Focus (Work, Sleep, …) so a block starts the moment "
                     + "that Focus turns on. If another session is already running, the Focus "
                     + "starts nothing.")
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
                if config.breakLevel != .easy {
                    Stepper(value: $config.durationMinutes, in: 15...1440, step: 15) {
                        Text("Lasts \(config.durationMinutes) min")
                    }
                }
            } footer: {
                Text(config.breakLevel == .easy
                     ? "Easy blocks follow the Focus: they end when you turn the Focus off."
                     : "Harder and Hardcore blocks run for a set time. Turning the Focus off asks to "
                       + "end early under the same rules as the app: Harder waits first, Hardcore keeps "
                       + "running until its end (the Emergency Pass is still there).")
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
        .flintForm()
        .navigationTitle("Focus Filter")
        .navigationBarTitleDisplayMode(.inline)
        // Persist every edit so newly-created Focus filters pick up the latest defaults.
        .onChange(of: config) { newValue in
            var saved = newValue
            if saved.breakLevel != .easy, saved.durationMinutes <= 0 { saved.durationMinutes = 60 }
            if saved != newValue { config = saved }
            saved.save()
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
