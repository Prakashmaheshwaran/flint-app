import SwiftUI
import FlintCore

/// All rule editors render the arbiter's result. Display time never authorizes a mutation;
/// even a matured wait returns to the persisted gate for a final explicit confirmation.
@MainActor
final class RuleMutationFlow: ObservableObject {
    @Published var isPresented = false
    @Published private(set) var result: FlintRuleMutationResult = .unavailable
    private(set) var scope = "Rule"
    private(set) var checkedAt = Date()
    private var operation: ((FlintRuleConfirmation?) -> FlintRuleMutationResult)?
    private var cancellation: (FlintRuleConfirmation?) -> Void = { _ in }
    private var ticket: FlintRuleConfirmation?
    private var saved: () -> Void = {}

    func run(scope: String, operation: @escaping (FlintRuleConfirmation?) -> FlintRuleMutationResult,
             cancel: @escaping (FlintRuleConfirmation?) -> Void = { _ in }, onSaved: @escaping () -> Void = {}) {
        self.scope = scope; self.operation = operation; cancellation = cancel; saved = onSaved
        ticket = nil
        accept(operation(nil))
    }
    func confirm(_ ticket: FlintRuleConfirmation) { if let operation { accept(operation(ticket)) } }
    func checkAgain() { if let operation { accept(operation(nil)) } }
    func cancel() { cancellation(ticket); operation = nil; cancellation = { _ in }; ticket = nil; isPresented = false }
    private func accept(_ result: FlintRuleMutationResult) {
        self.result = result; checkedAt = Date()
        switch result {
        case let .confirmationRequired(ticket, _), let .deferRequired(ticket, _), let .waiting(ticket, _, _): self.ticket = ticket
        default: break
        }
        if result.didSave {
            operation = nil; cancellation = { _ in }; ticket = nil; isPresented = false; saved()
        } else { isPresented = true }
    }
}

extension View {
    func ruleMutationFlow(_ flow: RuleMutationFlow) -> some View {
        modifier(RuleMutationPresenter(flow: flow))
    }
}

private struct RuleMutationPresenter: ViewModifier {
    @ObservedObject var flow: RuleMutationFlow
    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(get: { flow.isPresented }, set: { if !$0 { flow.cancel() } })) {
            RuleMutationSheet(flow: flow)
        }
    }
}

private struct RuleMutationSheet: View {
    @ObservedObject var flow: RuleMutationFlow
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                Text(flow.scope).font(.title2).bold()
                content
                Spacer(minLength: 0)
                Button("Go back", role: .cancel) { flow.cancel() }.frame(minHeight: 44)
            }
            .padding(24)
            .navigationTitle("Rule change")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder private var content: some View {
        switch flow.result {
        case let .confirmationRequired(ticket, raisesTier):
            Text(raisesTier
                 ? "Confirm the new tier. Harder requires a wait before weakening an active rule. Hardcore keeps its protection until the occurrence ends; an available Emergency Pass is a separate recovery option."
                 : "Confirm this change to the named rule. Its saved revision will be checked again before anything changes.")
            Button(raisesTier ? "Confirm tier and save" : "Confirm change") { flow.confirm(ticket) }.buttonStyle(.borderedProminent)
        case let .waiting(ticket, remaining, required):
            Text("Available in \(Int(required / 60)) \(required == 60 ? "minute" : "minutes")")
            Text("The wait belongs to this rule and this exact change. Coming back to this screen does not reset committed waits.")
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let display = max(0, remaining - context.date.timeIntervalSince(flow.checkedAt))
                Text(display > 0 ? "About \(Int(ceil(display))) seconds remaining" : "Check the saved wait, then confirm your change.")
                    .monospacedDigit()
            }
            if remaining == 0 {
                Button("Confirm change") { flow.confirm(ticket) }.buttonStyle(.borderedProminent)
            } else {
                Button("Check saved wait") { flow.checkAgain() }.buttonStyle(.borderedProminent)
            }
        case let .deferRequired(ticket, end):
            Text("Protected until \(end.formatted(date: .abbreviated, time: .shortened)). Save for after this occurrence.")
            Text("The current rule stays effective. Your queued change will appear separately.")
            Button("Save for after this occurrence") { flow.confirm(ticket) }.buttonStyle(.borderedProminent)
        case let .denied(_, cause, tier, end):
            Text("\(cause) · \(tier.rawValue.capitalized)")
            if let end { Text("Protected until \(end.formatted(date: .abbreviated, time: .shortened)).") }
            Text(passStatus)
        case .staleRevision:
            Text("This rule changed while you were editing. Go back and reopen its latest version before confirming.")
        case let .invalid(reason): Text(reason)
        case .unavailable:
            Text("Rule changes are unavailable. Your draft and existing protection were kept. Go back and try again.")
        case .applied, .queued: EmptyView()
        }
    }

    private var passStatus: String {
        guard let state = try? FlintProtectionLedger.appGroup()?.read().get() else { return "Emergency Pass status unavailable." }
        let status = state.pass.status(at: .init(clock: .system), hasActiveCommitment: true)
        switch status.eligibility {
        case .available: return "Emergency Pass available in recovery."
        case let .coolingDown(remaining): return "Emergency Pass available in about \(Int(ceil(remaining / 3600))) hours."
        case .nothingToRecover: return "No active commitment to recover."
        case .unavailable: return "Emergency Pass status unavailable."
        }
    }
}
