import SwiftUI
import FlintCore

/// "How Flint works": what each strictness level does, the Emergency Pass, and what Flint cannot
/// prevent on iPhone. Every sentence must stay true of the shipped code and of ADR-012
/// (docs/architecture/protection-contract.md); when session, pass, Focus or removal behavior
/// changes, update the matching section here in the same PR.
struct HelpView: View {
    @State private var pass = HelpPassStatus.current()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("How Flint works").flintTitle(28)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 8)
                Text("What Flint does on this iPhone, and what it can’t do.")
                    .flintSecondary(15)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)

                HelpSection(title: "Strictness") {
                    HelpParagraph("You choose a strictness level for each focus session. It decides how you can end that session early.")
                    HelpItem(title: "Easy", text: "End the session any time after confirming.")
                    HelpItem(title: "Harder", text: "Ending early starts a wait of 1 minute. Each exception you’ve already taken in this session — a break, a replacement — makes the next wait longer: 5, then 15 minutes. Cancelling a wait doesn’t count. After the wait you confirm in Flint. A Shortcut or turning a Focus off can start the wait but never finishes it.")
                    HelpItem(title: "Hardcore", text: "Can’t be ended early — not in Flint, not by a Shortcut and not by turning a Focus off. It ends at its time or with an Emergency Pass. Starting one asks you to confirm.")
                    HelpParagraph("Harder and Hardcore sessions need a set length; an Easy session can run until you stop it. One session runs at a time: a Focus turning on while another session runs starts nothing, and replacing a Hardcore session needs one at least as strong — the same apps or more, ending no earlier.")
                }

                HelpSection(title: "Emergency Pass") {
                    HelpParagraph("An Emergency Pass ends a running Hardcore focus session early. It doesn’t end schedules, Sleep or limits. It’s free. After you use one, the next becomes available seven days later.")
                    HelpStatusLine(pass: pass)
                }

                HelpSection(title: "What Flint can’t prevent") {
                    HelpItem(title: "Turning off Screen Time access",
                             text: "Flint’s Screen Time access can be turned off in Settings. Flint’s blocking stops while it’s off.")
                    HelpItem(title: "Emergency calls",
                             text: "Emergency SOS and emergency calls from the Lock Screen are part of iOS and are expected to stay available. This hasn’t been verified on a device yet. Apps you use for calls can be blocked like any other app, so make sure the ones you rely on stay allowed.")
                    HelpItem(title: "Deleting apps",
                             text: "A Hardcore session restricts deleting apps until it ends. iOS applies this to every app on this iPhone, not only Flint.")
                    HelpItem(title: "Changing the clock",
                             text: "Flint can’t stop the date or time being changed, and may not notice every change while it isn’t running.")
                    HelpItem(title: "Timing",
                             text: "Flint applies a session’s block when it starts. iOS ends it, and starts and ends scheduled rules, even when Flint is closed. That can take a moment.")
                    HelpItem(title: "Websites",
                             text: "Website blocks use Screen Time. Coverage in browsers other than Safari depends on iOS and the browser.")
                }

                HelpSection(title: "App lock") {
                    HelpItem(title: "Can’t unlock?",
                             text: "On the PIN screen, tap Emergency Pass & recovery. It shows whether a block is running and when it ends, lets you use an available Emergency Pass on a Hardcore block, and explains how to turn off Flint’s access in Settings — none of it needs your PIN.")
                }

                HelpSection(title: "Screen Time passcode") {
                    HelpParagraph("iOS has its own Screen Time passcode and Content & Privacy Restrictions in Settings → Screen Time. They’re separate from Flint: Flint can’t set, see or enforce them. Some people ask someone they trust to set the passcode.")
                }

                FlintPrivacyNote(text: String(localized: "No account. No analytics. Your lists, rules and history stay on this iPhone."),
                                 color: FlintBrand.stone)
                    .padding(.top, 28)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(FlintGroundBackground())
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { pass = HelpPassStatus.current() }
    }
}

/// Whether an Emergency Pass can be used now, or when the next one arrives. Rolling seven days
/// after the last use (`FlintEmergencyPass.period`); unreadable storage is reported, not
/// guessed.
enum HelpPassStatus: Equatable {
    case available
    case next(Date)
    case unknown

    static func current(now: Date = Date()) -> HelpPassStatus {
        guard let store = FlintGroupStore() else { return .unknown }
        return status(lastUsed: store.emergencyPassLastUsed(), now: now)
    }

    static func status(lastUsed: Date?, now: Date) -> HelpPassStatus {
        guard let lastUsed, !FlintEmergencyPass.isAvailable(lastUsed: lastUsed, now: now) else { return .available }
        return .next(lastUsed.addingTimeInterval(FlintEmergencyPass.period))
    }
}

private struct HelpStatusLine: View {
    let pass: HelpPassStatus

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle()
                .fill(pass == .available ? FlintBrand.ember : FlintBrand.stone)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            label
                .font(FlintFont.text(14, weight: .medium))
                .foregroundStyle(FlintBrand.bone)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
        .accessibilityElement(children: .combine)
    }

    /// Date, time and zone: eligibility is an elapsed-time estimate, so the zone is shown.
    private static let nextPassFormat = Date.FormatStyle.dateTime
        .weekday(.abbreviated).month(.abbreviated).day().hour().minute().timeZone()

    @ViewBuilder private var label: some View {
        switch pass {
        case .available:
            Text("Emergency Pass available now")
        case .next(let date):
            Text("Next Emergency Pass: \(date, format: Self.nextPassFormat)")
        case .unknown:
            Text("Emergency Pass status is unavailable right now.")
        }
    }
}

private struct HelpSection<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).flintEyebrow()
                .accessibilityAddTraits(.isHeader)
            FlintCard(padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
                VStack(alignment: .leading, spacing: 14) { content }
            }
        }
        .padding(.top, 28)
    }
}

private struct HelpParagraph: View {
    let text: LocalizedStringKey
    init(_ text: LocalizedStringKey) { self.text = text }

    var body: some View {
        Text(text)
            .font(FlintFont.text(15))
            .foregroundStyle(FlintBrand.mist)
            .lineSpacing(3)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A titled fact. The title is a heading, so VoiceOver's headings rotor can jump between facts.
private struct HelpItem: View {
    let title: LocalizedStringKey
    let text: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).flintRowTitle()
                .accessibilityAddTraits(.isHeader)
            Text(text)
                .font(FlintFont.text(14))
                .foregroundStyle(FlintBrand.mist)
                .lineSpacing(2)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
