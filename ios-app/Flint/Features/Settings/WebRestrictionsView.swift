import SwiftUI
import FlintCore

/// Drafts changes, gates weakening across active causes, then reconciles an approved save.
struct WebRestrictionsView: View {
    @State private var restrictions: FlintWebRestrictions
    @State private var savedConfig: FlintWebRestrictions?
    @State private var readState = FlintWebRestrictionRead.busy
    @State private var isReading = false
    @State private var isSaving = false
    @State private var outcome: FlintWebRestrictionEditor.Outcome?
    @State private var requestID = UUID().uuidString

    init() {
        _restrictions = State(initialValue: .default)
        _savedConfig = State(initialValue: nil)
    }

    var body: some View {
        Form {
            if readState == .busy {
                Section {
                    Text(isReading ? "Loading website rules…" : "Website rules are busy. Try again shortly.")
                    Button("Try again") { Task { await refresh(resetDraft: savedConfig == nil) } }
                        .disabled(isReading)
                }
            } else if readState == .unavailable {
                Section {
                    Text("Saved website rules are unavailable. Existing restrictions are preserved. Rebuild a draft below; saving it must respect every running commitment.")
                    Button("Try again") { Task { await refresh(resetDraft: false) } }
                }
            }
            if savedConfig != nil || readState == .unavailable {
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
                if readState == .unavailable {
                    Section {
                        Button("Re-establish website rules") { attemptSave() }
                    } footer: {
                        Text("Easy commitments allow this repair. Harder requires a wait and confirmation; Hardcore keeps its current protection until it ends.")
                    }
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
                if let message = restrictions.validationMessage {
                    Section { Text(message).foregroundStyle(FlintBrand.ember) }
                }
                if let outcome {
                    Section {
                        Text(message(for: outcome))
                        if outcome == .confirmationRequired {
                            Button("Confirm website changes") { attemptSave(confirmed: true) }
                        } else if case .waiting = outcome {
                            Button("Check wait") { attemptSave() }
                        } else if case .protected = outcome {
                            Button("Try saving again") { attemptSave() }
                        } else if outcome == .busy || outcome == .unavailable {
                            Button("Try again") {
                                Task { await refresh(resetDraft: false); attemptSave() }
                            }
                        } else if case .invalid = outcome, restrictions.validationMessage == nil {
                            Button("Try saving again") { attemptSave() }
                        }
                    }
                }
            }
        }
        .flintForm()
        .navigationTitle("Web & Safari")
        .navigationBarTitleDisplayMode(.inline)
        .disabled(isSaving)
        .task { await refresh() }
        .onChange(of: restrictions) { newValue in
            guard !isReading, newValue != savedConfig else { outcome = nil; return }
            requestID = UUID().uuidString
            outcome = nil
            if savedConfig != nil { attemptSave() }
        }
    }

    private func attemptSave(confirmed: Bool = false) {
        guard readState != .busy, !isSaving else { return }
        let candidate = restrictions
        let identity = requestID
        isSaving = true
        Task { @MainActor in
            // Both transition locks can contend with monitor work. Never wait on the UI thread.
            let result = await Task.detached(priority: .userInitiated) {
                FlintWebRestrictionEditor().save(candidate, requestID: identity, confirmed: confirmed)
            }.value
            isSaving = false
            outcome = result
            if result == .saved {
                savedConfig = candidate
                readState = .available(candidate)
                await FlintProtectionReconciler.shared.reconcile(trigger: .configEdit)
            } else if result == .unavailable || result == .busy {
                await refresh(resetDraft: false)
                if readState == .busy { outcome = .busy }
            }
        }
    }

    @MainActor
    private func refresh(resetDraft: Bool = true) async {
        guard !isReading else { return }
        isReading = true
        let state = await Task.detached(priority: .userInitiated) {
            FlintWebRestrictions.readForUI()
        }.value
        readState = state
        switch state {
        case let .available(config):
            savedConfig = config
            if resetDraft { restrictions = config; outcome = nil }
        case .unavailable: savedConfig = nil
        case .busy: break // A busy read never turns a known policy into unavailable intent.
        }
        isReading = false
    }

    private func message(for outcome: FlintWebRestrictionEditor.Outcome) -> String {
        switch outcome {
        case .saved: return "Website rules saved."
        case let .protected(end):
            let deadline = end.map { "Protected until \($0.formatted(date: .abbreviated, time: .shortened)). " }
                ?? "Protected by an active block. "
            return deadline + "This weakening edit can't be saved during the block. Your changes remain a draft; no automatic change is scheduled."
        case let .waiting(seconds): return "Wait at least \(Int(ceil(seconds))) seconds, then check the wait and explicitly confirm these changes. Nothing has been saved."
        case .confirmationRequired: return "The wait is complete. Review your draft, then confirm to save these website changes."
        case .busy: return "Website rules or current commitments are busy. Try again shortly; your draft is preserved."
        case .unavailable: return "Couldn't verify the current protection state or save changes. Existing website rules were preserved."
        case let .invalid(message): return message
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
        let normalized = FlintWebRestrictions.normalizedDomains(domains)
        return normalized.count < FlintShieldLimits.tokenCap && !normalized.contains(candidate)
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
            if FlintWebRestrictions.normalizedDomains(domains).count >= FlintShieldLimits.tokenCap {
                Text("Limit: \(FlintShieldLimits.tokenCap) sites per list. Remove a site before adding another.")
            }
        }
    }

    private func add() {
        guard let domain = normalizedInput, canAdd else { return }
        domains.append(domain)
        input = ""
    }
}
