import SwiftUI
import FlintCore

/// Edits the shared Safari filter and immediately reconciles currently active causes.
struct WebRestrictionsView: View {
    @State private var restrictions = FlintWebRestrictions.load()

    var body: some View {
        Form {
            Section {
                Toggle("Focus sessions", isOn: $restrictions.enabled)
                    .tint(FlintBrand.spark)
                Toggle("Schedules and Sleep", isOn: $restrictions.applyDuringSchedules)
                    .tint(FlintBrand.spark)
            } footer: {
                Text("""
                    Apply the Safari web-content filter during focus sessions, or during schedules \
                    and Sleep when that switch is on. While the filter is active, iOS also disables \
                    Private Browsing and locks Clear History and Website Data as a side effect.
                    """)
            }

            if restrictions.enabled || restrictions.applyDuringSchedules {
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
                        footer: "Block these sites on top of the adult-content filter.",
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
                Text("This filter applies only to Safari. Other browsers are not filtered. "
                     + "Safari filtering and its side effects cannot be verified in the Simulator.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .flintForm()
        .navigationTitle("Web & Safari")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: restrictions) { newValue in
            newValue.save()
            Task { await FlintProtectionReconciler.shared.reconcile(trigger: .configEdit) }
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
