import SwiftUI
import FlintCore

/// App-owned focus sessions. Screen Time report content remains in its own extension.
struct FocusHistorySection: View {
    @ObservedObject var history: FocusHistoryModel
    @State private var showsWeek = true
    @Environment(\.dynamicTypeSize) private var typeSize

    private var days: [FlintHistoryDay] {
        let values = history.summary?.days ?? []
        return showsWeek ? values : Array(values.suffix(1))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            FocusStreakCard(presentation: FocusStreakPresentation.make(history.summary))
            FlintCard {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Focus sessions").flintTitle(24).accessibilityAddTraits(.isHeader)
                    FlintSegmented(options: [(false, "Today"), (true, "7 days")], selection: $showsWeek,
                                   height: 44, compact: !typeSize.isAccessibilitySize)
                        .accessibilityIdentifier("history.range")
                    if history.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12) }
                    if let summary = history.summary {
                        if summary.availability == .unavailable {
                            Text("Focus history unavailable").flintSecondary(16)
                        } else if summary.availability == .empty {
                            Text("No focus sessions yet").flintSecondary(16)
                        } else {
                            Text(FocusHistoryText.duration(FocusHistoryText.total(days)))
                                .flintNumber(40)
                                .accessibilityLabel("\(FocusHistoryText.duration(FocusHistoryText.total(days))) focused")
                            Text(showsWeek ? FocusHistoryText.weekLabel(days) : "Focused today").flintSecondary(14)
                            if days.contains(where: \.inProgress) { Text("Includes a session in progress").flintSecondary(13) }
                            if let note = FocusHistoryText.unconfirmed(days) { Text(note).flintSecondary(13) }
                            bars
                            Text("\(days.reduce(0) { $0 + $1.completed }) completed · \(days.reduce(0) { $0 + $1.endedEarly }) ended early")
                                .flintSecondary(14)
                        }
                        if summary.availability == .degraded { Text(FocusHistoryText.degraded).flintSecondary(13) }
                    } else {
                        Text("Loading focus history…").flintSecondary(16)
                    }
                    Text(FocusHistoryText.definition).flintSecondary(13)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityIdentifier("history.section")
        }
    }

    private var bars: some View {
        let maximum = max(60, days.compactMap(\.focusedSeconds).max() ?? 60)
        return HStack(alignment: .bottom, spacing: 10) {
            ForEach(days) { day in
                VStack(spacing: 8) {
                    ZStack(alignment: .bottom) {
                        Color.clear.frame(height: 88)
                        if let seconds = day.focusedSeconds, seconds > 0 {
                            RoundedRectangle(cornerRadius: 5)
                                .fill(FlintBrand.ember)
                                .frame(height: max(2, 88 * seconds / maximum))
                        } else if day.focusedSeconds != nil {
                            Rectangle().fill(FlintBrand.stone).frame(height: 1)
                        }
                    }
                    Text(day.start.formatted(.dateTime.weekday(.narrow))).flintSecondary(12)
                    Text(FocusHistoryText.duration(day.focusedSeconds)).flintSecondary(11)
                        .lineLimit(1).minimumScaleFactor(0.7)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(day.start.formatted(date: .abbreviated, time: .omitted)), \(FocusHistoryText.duration(day.focusedSeconds))\(day.inProgress ? ", in progress" : "")")
            }
        }
    }
}

/// Dedicated Focus Streak card for Insights. Uses existing aggregation only.
struct FocusStreakCard: View {
    let presentation: FocusStreakPresentation
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var previousStreak = 0
    @State private var milestoneFlash: CGFloat = 0

    var body: some View {
        switch presentation {
        case .hidden:
            EmptyView()
        case .empty:
            FlintCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text(FocusHistoryText.streakTitle).flintTitle(24).accessibilityAddTraits(.isHeader)
                    Text(FocusHistoryText.streakEmpty).flintSecondary(15)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityIdentifier("history.streak")
            .accessibilityElement(children: .combine)
        case .visible(let streak, let streakCaption, _, let bestCaption, _, let weekCaption, let days,
                      let footnote, _, let statusLine, _, let emberMood):
            FlintCard {
                VStack(alignment: .center, spacing: 12) {
                    Text(FocusHistoryText.streakTitle)
                        .flintTitle(24)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityAddTraits(.isHeader)
                    cinematicHero(
                        streak: streak,
                        caption: streakCaption,
                        statusLine: statusLine,
                        mood: emberMood
                    )
                    if let statusLine {
                        Text(statusLine)
                            .font(FlintFont.text(13, weight: .medium))
                            .foregroundStyle(statusColor(emberMood))
                            .accessibilityHidden(true)
                    }
                    Text(streakCaption).flintSecondary(15)
                        .accessibilityHidden(true)
                    if let bestCaption {
                        Text(bestCaption).flintSecondary(13)
                            .foregroundStyle(FlintBrand.mist)
                            .accessibilityLabel(bestCaption)
                    }
                    weekDots(days)
                    Text(weekCaption).flintSecondary(14)
                    Text(footnote).flintSecondary(13)
                }
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityIdentifier("history.streak")
            .onChange(of: streak) { newValue in
                let grew = newValue > previousStreak
                let milestone = grew && (newValue == 7 || newValue == 14 || newValue == 30 || newValue % 50 == 0)
                previousStreak = newValue
                guard milestone, !reduceMotion else { return }
                withAnimation(.easeOut(duration: 0.35)) { milestoneFlash = 1 }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
                    withAnimation(.easeIn(duration: 0.45)) { milestoneFlash = 0 }
                }
            }
            .onAppear { previousStreak = streak }
        }
    }

    private func statusColor(_ mood: StreakEmberMood) -> Color {
        switch mood {
        case .active: return FlintBrand.ember
        case .warning: return flintStreakCoolDeep
        case .dormant: return FlintBrand.mist
        }
    }

    private func cinematicHero(
        streak: Int,
        caption: String,
        statusLine: String?,
        mood: StreakEmberMood
    ) -> some View {
        let numberColor: Color = {
            switch mood {
            case .active: return FlintBrand.ember
            case .warning: return flintStreakCoolDeep
            case .dormant: return FlintBrand.mist.opacity(0.72)
            }
        }()
        // Streak sheet (Opal in-app composition): flame → bold number → DAY STREAK → subtitle.
        return VStack(spacing: 0) {
            FlintStreakEmber(mood: mood, size: 160, milestoneFlash: milestoneFlash)
            Text("\(streak)")
                .font(FlintFont.text(typeSize.isAccessibilitySize ? 56 : 72, weight: .semibold))
                .tracking(-1.6)
                .foregroundStyle(streakNumberFill(mood))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .modifier(StreakContentTransition(reduceMotion: reduceMotion))
                .animation(reduceMotion ? .easeInOut(duration: 0.2) : .spring(response: 0.45, dampingFraction: 0.82), value: streak)
                .padding(.top, 10)
                .accessibilityHidden(true)
            Text(FocusHistoryText.dayStreakLabel.uppercased())
                .font(FlintFont.text(11, weight: .medium))
                .tracking(2.0)
                .foregroundStyle(numberColor.opacity(0.95))
                .padding(.top, 8)
                .accessibilityHidden(true)
            Text(FocusHistoryText.streakMotivation(mood: mood, streak: streak))
                .font(FlintFont.text(13))
                .foregroundStyle(FlintBrand.mist.opacity(0.78))
                .multilineTextAlignment(.center)
                .padding(.top, 14)
                .padding(.horizontal, 12)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
        // A minimum, not a fixed height: the poster keeps 420 at default sizes but can never
        // clip a scaled number, label or motivation line at accessibility sizes.
        .frame(minHeight: 420)
        .padding(.vertical, 28)
        .background {
            FlintStreakCinematicStage(mood: mood)
                .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
        }
        .clipShape(RoundedRectangle(cornerRadius: 32, style: .continuous))
        .accessibilityElement(children: .ignore)
        // The card header above already says "Focus streak", so the hero speaks only the data it
        // draws: number + caption + today's status. Best/week/footnote stay separate elements.
        .accessibilityLabel([caption, statusLine, FocusHistoryText.streakMotivation(mood: mood, streak: streak)]
            .compactMap { $0 }.joined(separator: ", "))
    }

    private func weekDots(_ days: [FocusStreakDayPresentation]) -> some View {
        HStack(spacing: 10) {
            ForEach(days) { day in
                Circle()
                    .fill(dotColor(day))
                    .frame(width: day.isToday ? 9 : 7, height: day.isToday ? 9 : 7)
                    .accessibilityLabel(day.accessibilityLabel)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(NSLocalizedString("Focus days this week", comment: "Focus streak strip"))
        .padding(.top, 4)
    }

    private func dotColor(_ day: FocusStreakDayPresentation) -> Color {
        switch day.kind {
        case .completed:
            return FlintBrand.ember.opacity(day.isToday ? 0.95 : 0.7)
        case .incomplete:
            return day.isToday ? FlintBrand.mist.opacity(0.55) : FlintBrand.bone(0.18)
        case .unavailable:
            return FlintBrand.bone(0.12)
        }
    }
}

struct FocusedTodayCard: View {
    @ObservedObject var history: FocusHistoryModel
    let nextSessionDuration: String
    var body: some View {
        VStack(spacing: 6) {
            Text("Focused today").flintEyebrow()
            if let summary = history.summary,
               summary.availability == .value || summary.availability == .degraded {
                Text(FocusHistoryText.today(summary)).flintNumber(50)
            } else {
                Text(FocusHistoryText.today(history.summary)).flintSecondary(16)
            }
            if history.summary?.days.last?.inProgress == true { Text("In progress").flintSecondary(13) }
            if let day = history.summary?.days.last,
               let note = FocusHistoryText.unconfirmed([day]) { Text(note).flintSecondary(13) }
            if history.summary?.availability == .degraded { Text(FocusHistoryText.degraded).flintSecondary(13) }
            if history.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12) }
            Text("Your next session · \(nextSessionDuration)")
                .flintSecondary(14)
                .padding(.top, 8)
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 20).fill(FlintBrand.ground))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("today.focusHistory")
    }
}

/// `ContentTransition.numericText()` is iOS 17+; the app targets iOS 16. Keep the numeric
/// roll where available and fall back to interpolation so the target stays buildable.
private struct StreakContentTransition: ViewModifier {
    let reduceMotion: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 17.0, *) {
            content.contentTransition(reduceMotion ? .opacity : .numericText())
        } else {
            content.contentTransition(reduceMotion ? .opacity : .interpolate)
        }
    }
}
