import SwiftUI
import FamilyControls
import FlintCore

/// Opal-style detail for one app or site: what covers it today, whether it is blocked now, and
/// today's Open Limit use. Read-only; everything comes from `FlintProtectionStatus.contributors`.
struct AppDetailView: View {
    let target: PickedTarget
    @State private var presentation: AppDetailPresentation?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                target.label
                    .labelStyle(.titleAndIcon)
                    .font(FlintFont.display(26))
                    .foregroundStyle(FlintBrand.bone)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 8)

                if let presentation {
                    headline(presentation.headline)
                        .padding(.top, 14)
                    if presentation.showsDegradedNote {
                        Text("Some protection needs attention. See You for details.")
                            .flintSecondary()
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 8)
                    }
                    if !presentation.rows.isEmpty {
                        Text("Covering today").flintEyebrow()
                            .accessibilityAddTraits(.isHeader)
                            .padding(.top, 28)
                        FlintCard {
                            VStack(alignment: .leading, spacing: 14) {
                                ForEach(presentation.rows) { row in
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(row.name).flintRowTitle()
                                        Text("\(row.kind) · \(row.tier)").flintSecondary()
                                        Text(row.detail).flintSecondary()
                                    }
                                    .fixedSize(horizontal: false, vertical: true)
                                    .accessibilityElement(children: .combine)
                                }
                            }
                        }
                        .padding(.top, 10)
                    }
                    if presentation.showsUncertaintyNote {
                        Text("Another rule that blocks a whole category may also apply to this.")
                            .flintSecondary()
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 10)
                    }
                    if let opens = presentation.opens {
                        Text("Open limit").flintEyebrow()
                            .accessibilityAddTraits(.isHeader)
                            .padding(.top, 28)
                        Group {
                            switch opens {
                            case .used(let count, let cap):
                                Text("Used today: \(count) of \(cap)")
                            case .unavailable(let cap):
                                Text("Today’s count isn’t available. Daily limit: \(cap).")
                            }
                        }
                        .flintSecondary(15)
                        .padding(.top, 8)
                    }
                    Text("To change what covers it, edit the rule in Rules.")
                        .flintSecondary()
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 28)
                } else {
                    ProgressView().padding(.top, 24)
                        .accessibilityLabel("Loading")
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(FlintGroundBackground())
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { presentation = AppDetailLoader.presentation(for: target.key) }
    }

    @ViewBuilder private func headline(_ headline: AppDetailPresentation.Headline) -> some View {
        Group {
            switch headline {
            case .unavailable:
                Text("Protection state is unavailable right now, so Flint won’t guess what covers this.")
            case .paused:
                Text("Protection is paused for Emergency Pass recovery.")
            case .blocked(let name, let until?):
                Text("Blocked now by \(name), until \(until.formatted(date: .omitted, time: .shortened)).")
            case .blocked(let name, nil):
                Text("Blocked now by \(name), until it’s stopped.")
            case .coveredNotBlocking:
                Text("Covered today, but not blocked right now.")
            case .uncertain:
                Text("Flint can’t tell for sure which rules cover this.")
            case .notCovered:
                Text("No rule covers this right now.")
            }
        }
        .font(FlintFont.text(16, weight: .medium))
        .foregroundStyle(FlintBrand.bone)
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// The one call into lane B's read-only status API (ADR-012 §8: no lock, write or migration).
enum AppDetailLoader {
    static func presentation(for targetKey: String, now: Date = Date()) -> AppDetailPresentation {
        // Committed pass via the non-mutating peek; a missing/corrupt ledger stays unavailable.
        let ledgerPass = FlintProtectionLedger.appGroup().flatMap { try? $0.peek().get() }?.pass
        let inputs = FlintProtectionStatus.currentInputs(now: now, ledgerPass: ledgerPass)
        let store = FlintGroupStore()
        // The running session's frozen selection, never the picker's next selection.
        let sessionSelection = inputs.activeSession.flatMap {
            store?.loadSessionSelection(sessionID: $0.session.id)
        }
        let coverage = FlintProtectionStatus.coverageScopes(for: .init(
            protection: inputs, sessionSelection: sessionSelection,
            schedules: store?.loadRules() ?? [], timeLimits: store?.loadLimits() ?? [],
            openLimits: FlintOpenLimitRule.loadAll()))
        let result = FlintProtectionStatus.contributors(
            for: targetKey, inputs: inputs, coverageByCauseID: coverage,
            counterState: strictOpenCounter())
        return AppDetailPresentation(.init(
            state: result.state, causes: result.causes, openCount: result.openCount,
            openCap: result.openCap, coverageUncertain: result.coverageUncertain))
    }

    /// Only a stored, decodable counter counts; a missing or malformed one is "unavailable",
    /// never a fresh zero (ADR-012 §5, §8).
    private static func strictOpenCounter(defaults: UserDefaults? = .flintGroup) -> FlintOpenLimitState? {
        guard let data = defaults?.data(forKey: "flint.openLimit.state") else { return nil }
        return try? JSONDecoder().decode(FlintOpenLimitState.self, from: data)
    }
}
