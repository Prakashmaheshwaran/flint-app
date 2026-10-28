import SwiftUI
import FlintCore

/// One explicit confirmation surface for Sessions, Settings and PIN-independent recovery.
/// Holds last two seconds; the accessible alternative presents the same scope, then confirms.
struct EmergencyPassSheet: View {
    let onKeep: () -> Void
    let onApplied: () -> Void
    var redacted = false
    @State private var status: FlintEmergencyPassStatus?
    @State private var preview: FlintPassPreview?
    @State private var scope: FlintPassScope?
    @State private var resumingScope: FlintPassScope?
    @State private var error: String?
    @State private var operationID = UUID().uuidString
    @State private var fill: CGFloat = 0
    @State private var accessibleConfirmation = false
    @State private var disableConfirmation = false
    @State private var applying = false
    @State private var holding = false
    @State private var heldPreview: FlintPassPreview?
    @State private var accessiblePreview: FlintPassPreview?
    @State private var disablingScope: FlintPassScope?
    private let controller = FlintEmergencyPassController()

    var body: some View {
        ZStack(alignment: .bottom) {
            Color(hex: 0x05060A).opacity(0.6).ignoresSafeArea().onTapGesture(perform: onKeep)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    FlintSheetGrabber()
                    HStack {
                        Text(status?.pass.isRecovering == true ? "Recovery" : "Emergency Pass").flintTitle(26)
                        Spacer()
                        Button("Done", action: onKeep).buttonStyle(GhostButtonStyle())
                    }
                    Text(status?.label ?? "Protection state unavailable").flintSecondary(14)
                    Text("One pass per rolling 7 days after use. Global recovery lasts up to 60 minutes; you can resume sooner.")
                        .flintSecondary(13)
                    if status?.timeUncertain == true {
                        Text("Times are estimates adjusted after a restart or clock change. The pass stays spent.")
                            .flintSecondary(13)
                    }
                    if status?.pass.isRecovering == true {
                        recovery
                    } else if let preview {
                        confirmation(preview)
                    } else if let error {
                        Text(error).flintSecondary(15)
                    }
                    if let error, preview != nil || status?.pass.isRecovering == true {
                        Text(error).font(FlintFont.text(14)).foregroundStyle(FlintBrand.danger)
                    }
                    if status?.needsApplication == true, status?.pass.isRecovering != true {
                        Text("The pass was saved. Finish its remaining work before starting new protection.").flintSecondary(14)
                        Button("Finish saved recovery") { apply(controller.reconcile()) }.buttonStyle(QuietButtonStyle())
                    }
                    Button("Refresh status", action: refresh).buttonStyle(QuietButtonStyle())
                    FlintPrivacyNote(text: "Your rules and counters stay on this iPhone.", color: FlintBrand.stone)
                }
                .padding(24)
            }
            .frame(maxHeight: 670)
            .background(UnevenTop(radius: 32).fill(FlintBrand.sheet).ignoresSafeArea(edges: .bottom))
        }
        .preferredColorScheme(.dark)
        .onAppear(perform: refresh)
        .onReceive(Timer.publish(every: 15, on: .main, in: .common).autoconnect()) { _ in refresh() }
        .confirmationDialog("Use this Emergency Pass?", isPresented: $accessibleConfirmation, titleVisibility: .visible) {
            Button("Use pass for 60-minute recovery") {
                if let accepted = accessiblePreview { redeem(accepted) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { Text(confirmationText) }
        .confirmationDialog("End/disable these commitments?", isPresented: $disableConfirmation, titleVisibility: .visible) {
            Button("Disable listed commitments", role: .destructive, action: disable)
            Button("Keep commitments", role: .cancel) {}
        } message: {
            Text((disablingScope?.rules.map { EmergencyPassDisclosure.rule($0, redacted: redacted) }.joined(separator: "\n") ?? "")
                 + "\nThese templates will be disabled. Usage counters are kept.")
        }
        .accessibilityAddTraits(.isModal)
    }

    private func confirmation(_ preview: FlintPassPreview) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("End these sessions now").flintEyebrow()
            if preview.scope.sessions.isEmpty { Text("No transient session").flintSecondary(14) }
            ForEach(preview.scope.sessions) { Text(EmergencyPassDisclosure.session($0, redacted: redacted)).flintSecondary(15) }
            Text("Suspend these active commitments").flintEyebrow()
            commitmentList(preview.scope.rules)
            Text("All personal protection pauses, including rules starting during recovery. Safari child restrictions and eligible removal guards release. Pending waits and access grants cancel; counters stay spent.")
                .flintSecondary(14)
            Text("Resume estimate: \(FlintEmergencyPassStatus.dateText(preview.resumeAt))").flintSecondary(14)
            Text("Next pass estimate: \(FlintEmergencyPassStatus.dateText(preview.nextPassAt))").flintSecondary(14)
            holdButton(preview)
            Button("Use pass with confirmation…") {
                accessiblePreview = preview
                accessibleConfirmation = true
            }
                .buttonStyle(QuietButtonStyle())
                .accessibilityHint("Reviews the affected commitments, resume time and next eligibility before the final confirmation.")
            Button("Keep protection", action: onKeep).buttonStyle(BoneButtonStyle())
        }
    }

    private var recovery: some View {
        VStack(alignment: .leading, spacing: 14) {
            if status?.needsApplication == true {
                Text("Recovery is saved. Flint still needs to finish applying it; some restrictions may remain until that succeeds.")
                    .flintSecondary(14)
            }
            if let ended = status?.recovery?.scope.sessions, !ended.isEmpty {
                Text("Ended: \(ended.map { EmergencyPassDisclosure.session($0, redacted: redacted) }.joined(separator: ", "))").flintSecondary(14)
            }
            Text("Active commitments suspended now").flintEyebrow()
            if let scope { commitmentList(scope.rules) }
            else { Text("The current commitment list is unavailable. Refresh status.").flintSecondary(14) }
            Text("Expected at the recovery end").flintEyebrow()
            if let resumingScope { commitmentList(resumingScope.rules) }
            else { Text("Flint couldn't read the complete resume list. Refresh status.").flintSecondary(14) }
            Text("Rules keep their original ends. An occurrence ending during recovery will not replay. New sessions require you to resume first.")
                .flintSecondary(14)
            Button("Resume protection now") { apply(controller.resumeNow()) }
                .buttonStyle(BoneButtonStyle())
            if !(scope?.rules.isEmpty ?? true) {
                Button("End/disable these commitments") {
                    disablingScope = scope
                    disableConfirmation = true
                }
                    .buttonStyle(QuietButtonStyle())
            }
            Button("Retry recovery application") { apply(controller.reconcile()) }
                .buttonStyle(QuietButtonStyle())
        }
    }

    private func commitmentList(_ rules: [FlintPassCommitment]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if rules.isEmpty { Text("No active rule occurrence").flintSecondary(14) }
            ForEach(rules) { rule in
                Text(EmergencyPassDisclosure.rule(rule, redacted: redacted))
                    .flintSecondary(14).fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func holdButton(_ preview: FlintPassPreview) -> some View {
        ZStack(alignment: .leading) {
            GeometryReader { geometry in
                Rectangle().fill(FlintBrand.ember.opacity(0.2)).frame(width: geometry.size.width * fill)
            }
            Text("Hold for 2 seconds to use pass").font(FlintFont.text(15, weight: .medium))
                .frame(maxWidth: .infinity)
        }
        .frame(height: 52).clipShape(Capsule())
        .overlay(Capsule().strokeBorder(FlintBrand.ember.opacity(0.5)))
        .contentShape(Capsule())
        .onLongPressGesture(minimumDuration: 2, pressing: { pressing in
            holding = pressing
            if pressing { heldPreview = preview }
            withAnimation(pressing ? .linear(duration: 2) : .easeOut(duration: 0.2)) { fill = pressing ? 1 : 0 }
        }, perform: {
            if let accepted = heldPreview { redeem(accepted) }
        })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Use Emergency Pass")
        .accessibilityHint("Hold for two seconds, or activate to review and confirm.")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction {
            accessiblePreview = preview
            accessibleConfirmation = true
        }
        .disabled(applying)
    }

    private var confirmationText: String {
        guard let preview = accessiblePreview else { return "Refresh the recovery scope first." }
        return (preview.scope.sessions.map { EmergencyPassDisclosure.session($0, redacted: redacted) }
            + preview.scope.rules.map { EmergencyPassDisclosure.rule($0, redacted: redacted) }).joined(separator: "\n")
            + "\nResume estimate: \(FlintEmergencyPassStatus.dateText(preview.resumeAt))"
            + "\nNext pass estimate: \(FlintEmergencyPassStatus.dateText(preview.nextPassAt))"
    }

    private func redeem(_ preview: FlintPassPreview) {
        guard !applying else { return }
        holding = false
        accessibleConfirmation = false
        fill = 0
        applying = true
        apply(controller.use(previewRevision: preview.revision, operationID: operationID))
        applying = false
    }

    private func disable() {
        guard let scope = disablingScope else { return }
        disableConfirmation = false
        apply(controller.disableCommitments(previewRevision: scope.revision, ruleIDs: Set(scope.rules.map(\.id))))
    }

    private func apply(_ result: Result<FlintEmergencyPassStatus, FlintEmergencyPassError>) {
        switch result {
        case let .success(value):
            status = value; error = nil
            NotificationCenter.default.post(name: .flintProtectionConfigurationChanged, object: nil)
            refresh()
            onApplied()
        case let .failure(failure):
            refresh()
            error = failure.localizedDescription
        }
    }

    private func refresh() {
        guard !accessibleConfirmation, !disableConfirmation, !holding else { return }
        status = try? controller.status().get()
        // Once read-back proves this operation committed, subsequent explicit uses get a new
        // ID. Recovery retries use reconcile(), while an ambiguous read retains the old ID.
        if status?.recovery?.operationID == operationID { operationID = UUID().uuidString }
        scope = try? controller.currentScope().get()
        resumingScope = try? controller.resumingScope().get()
        switch controller.preview() {
        case let .success(value): preview = value; error = nil
        case let .failure(failure):
            preview = nil
            if status?.pass.isRecovering != true { error = failure.localizedDescription }
        }
    }
}

/// Shared by visible rows and accessible/disable confirmations so the PIN route cannot leak
/// a name through a secondary dialog or VoiceOver label.
enum EmergencyPassDisclosure {
    static func session(_ session: FlintActiveSession, redacted: Bool, clock: FlintClock = .system) -> String {
        guard redacted else { return session.name }
        let end = session.stableLogicalEnd(clock: clock).map { FlintEmergencyPassStatus.dateText($0) } ?? "until ended"
        return "\(session.breakLevel.rawValue.capitalized) · \(end)"
    }

    static func rule(_ rule: FlintPassCommitment, redacted: Bool) -> String {
        let label = redacted ? (rule.tier?.rawValue.capitalized ?? "Protection") : rule.name
        return "\(label) · ends \(FlintEmergencyPassStatus.dateText(rule.naturalEnd))"
    }
}
