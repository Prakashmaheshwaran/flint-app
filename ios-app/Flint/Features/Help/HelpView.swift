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
                    HelpParagraph("Each focus session you start in Flint has a strictness level. It decides how you can end that session early.")
                    HelpItem(title: "Easy", text: "End the session whenever you choose.")
                    HelpItem(title: "Harder", text: "Ending early asks you to wait first, so the urge has time to pass.")
                    HelpItem(title: "Hardcore", text: "Flint won’t end the session early. It runs until the end time you chose, unless you use an Emergency Pass.")
                    HelpParagraph("A session started by a Focus mode ends when that Focus turns off.")
                }

                HelpSection(title: "Emergency Pass") {
                    HelpParagraph("An Emergency Pass ends a running Hardcore session early. It’s free. After you use one, the next becomes available seven days later.")
                    HelpStatusLine(pass: pass)
                }

                HelpSection(title: "What Flint can’t prevent") {
                    HelpItem(title: "Turning off Screen Time access",
                             text: "Flint’s Screen Time access can be turned off in Settings. Flint’s blocking stops while it’s off.")
                    HelpItem(title: "Emergency calls",
                             text: "Emergency SOS and emergency calls from the Lock Screen are never blocked by Flint.")
                    HelpItem(title: "Deleting apps",
                             text: "A Hardcore session can restrict deleting apps until it ends. iOS applies this to every app on this iPhone, not only Flint.")
                    HelpItem(title: "Changing the clock",
                             text: "Flint can’t stop the date or time being changed, and may not notice every change while it isn’t running.")
                    HelpItem(title: "Timing",
                             text: "iOS starts and ends blocks for Flint. It can take a moment, especially if Flint hasn’t been opened in a while.")
                    HelpItem(title: "Websites",
                             text: "Website blocks use Screen Time. Coverage in browsers other than Safari depends on iOS and the browser.")
                }

                HelpSection(title: "Screen Time passcode") {
                    HelpParagraph("iOS has its own Screen Time passcode and Content & Privacy Restrictions in Settings → Screen Time. They’re separate from Flint: Flint can’t set, see or enforce them. Some people ask someone they trust to set the passcode.")
                }

                FlintPrivacyNote(text: "No account. No analytics. Your lists, rules and history stay on this iPhone.",
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
        if FlintEmergencyPass.isAvailable(lastUsed: lastUsed, now: now) { return .available }
        return FlintEmergencyPass.nextAvailable(lastUsed: lastUsed).map(HelpPassStatus.next) ?? .available
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

    @ViewBuilder private var label: some View {
        switch pass {
        case .available:
            Text("Emergency Pass available now")
        case .next(let date):
            Text("Next Emergency Pass: \(date.formatted(date: .abbreviated, time: .shortened))")
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

/// A titled fact. VoiceOver reads the title and text as one element.
private struct HelpItem: View {
    let title: LocalizedStringKey
    let text: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).flintRowTitle()
            Text(text)
                .font(FlintFont.text(14))
                .foregroundStyle(FlintBrand.mist)
                .lineSpacing(2)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
