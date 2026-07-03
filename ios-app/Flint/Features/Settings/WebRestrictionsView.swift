import SwiftUI
import FlintCore

/// Configures Flint's Safari content restrictions + Private-Browsing lockdown (Opal features
/// 1.8 / 1.9, FREE in Flint). This screen only edits + persists the `FlintWebRestrictions`
/// preference; the session lifecycle is what calls `FlintWebRestrictionStore().applySaved()` /
/// `.clear()` to enforce it. Reachable from `SettingsView`; the Wave-2 integrator owns app nav.
struct WebRestrictionsView: View {
    @State private var restrictions = FlintWebRestrictions.load()

    var body: some View {
        Form {
            Section {
                Toggle("Restrict Safari during sessions", isOn: $restrictions.enabled)
                    .tint(FlintBrand.spark)
            } footer: {
                Text("While a focus session is running, Safari follows the rules below. Turning this "
                     + "on also **disables Private Browsing** and locks *Clear History and Website "
                     + "Data* — iOS ties both to web restrictions, so they can't be evaded mid-session.")
            }

            if restrictions.enabled {
                Section {
                    Picker("Mode", selection: $restrictions.filter) {
                        ForEach(FlintWebFilter.allCases, id: \.self) { mode in
                            Text(mode.title).tag(mode)
                        }
                    }
                } footer: {
                    Text(restrictions.filter.detail)
                }

                if restrictions.filter == .allowedOnly {
                    DomainListSection(
                        title: "Allowed sites",
                        footer: "Safari can reach only these sites. Everything else is blocked.",
                        addPrompt: "Add an allowed site",
                        domains: $restrictions.allowedDomains
                    )
                } else {
                    DomainListSection(
                        title: "Also block",
                        footer: "Block these sites on top of the adult-content filter. Opal can't do "
                              + "this on iOS — Flint lets you type any domain.",
                        addPrompt: "Add a site to block",
                        domains: $restrictions.blockedDomains
                    )
                    DomainListSection(
                        title: "Always allow",
                        footer: "Keep these sites reachable even when the filter would block them.",
                        addPrompt: "Add an allowed site",
                        domains: $restrictions.allowedDomains
                    )
                }

                Section {
                    Toggle("Block explicit music & podcasts", isOn: $restrictions.denyExplicitMedia)
                        .tint(FlintBrand.spark)
                } footer: {
                    Text("Also applies Apple's explicit-content restriction to Music and Podcasts.")
                }
            }

            Section {
                Text("Safari is the only browser iOS filters uniformly. Restrictions take effect on a "
                     + "real device — the Simulator can't apply them.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Web & Safari")
        .navigationBarTitleDisplayMode(.inline)
        // Persist every edit to the shared App Group so a starting session enforces the latest config.
        .onChange(of: restrictions) { newValue in
            newValue.save()
        }
    }
}

/// An editable, validated list of domains: existing rows (swipe to delete) plus an add field that
/// normalizes free-form input ("https://www.X.com/y" → "x.com") and rejects non-hostnames.
private struct DomainListSection: View {
    let title: String
    let footer: String
    let addPrompt: String
    @Binding var domains: [String]

    @State private var input = ""

    private var normalizedInput: String? { FlintWebRestrictions.normalizedDomain(input) }
    private var canAdd: Bool {
        guard let candidate = normalizedInput else { return false }
        return !FlintWebRestrictions.normalizedDomains(domains).contains(candidate)
    }

    var body: some View {
        Section {
            ForEach(domains, id: \.self) { domain in
                Text(domain)
            }
            .onDelete { offsets in domains.remove(atOffsets: offsets) }

            HStack {
                TextField(addPrompt, text: $input)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.done)
                    .onSubmit(add)
                Button(action: add) {
                    Image(systemName: "plus.circle.fill")
                }
                .disabled(!canAdd)
                .tint(FlintBrand.spark)
            }
        } header: {
            Text(title)
        } footer: {
            Text(footer)
        }
    }

    private func add() {
        guard let domain = normalizedInput, canAdd else { return }
        domains.append(domain)
        input = ""
    }
}
