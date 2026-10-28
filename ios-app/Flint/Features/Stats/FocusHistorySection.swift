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
                        Text(FocusHistoryText.duration(days.compactMap(\.focusedSeconds).reduce(0, +)))
                            .flintNumber(40)
                            .accessibilityLabel("\(FocusHistoryText.duration(days.compactMap(\.focusedSeconds).reduce(0, +))) focused")
                        Text(showsWeek ? "Focused in the last 7 days" : "Focused today").flintSecondary(14)
                        if days.contains(where: \.inProgress) { Text("Includes a session in progress").flintSecondary(13) }
                        bars
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(days.reduce(0) { $0 + $1.completed }) completed · \(days.reduce(0) { $0 + $1.endedEarly }) ended early")
                                .flintSecondary(14)
                            if summary.streak > 0 { Text("\(summary.streak)-day completion streak").flintSecondary(14) }
                        }
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

struct FocusedTodayCard: View {
    @ObservedObject var history: FocusHistoryModel
    var body: some View {
        VStack(spacing: 6) {
            Text("Focused today").flintEyebrow()
            if let summary = history.summary,
               summary.availability == .value || summary.availability == .degraded {
                Text(FocusHistoryText.today(summary)).flintNumber(38)
            } else {
                Text(FocusHistoryText.today(history.summary)).flintSecondary(16)
            }
            if history.summary?.days.last?.inProgress == true { Text("In progress").flintSecondary(13) }
            if history.summary?.availability == .degraded { Text(FocusHistoryText.degraded).flintSecondary(13) }
            if history.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12) }
        }
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 20).fill(FlintBrand.ground.opacity(0.75)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("today.focusHistory")
    }
}
