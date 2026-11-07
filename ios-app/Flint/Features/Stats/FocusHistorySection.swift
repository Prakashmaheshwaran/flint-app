import SwiftUI
import FlintCore

/// App-owned focus sessions, laid out as M12's comparison card and week strip.
/// Screen Time report content remains in its own extension.
struct FocusHistorySection: View {
    @ObservedObject var history: FocusHistoryModel
    var range: InsightsRange = .week
    @Environment(\.dynamicTypeSize) private var typeSize

    private var allDays: [FlintHistoryDay] { history.summary?.days ?? [] }
    private var days: [FlintHistoryDay] {
        range == .week ? allDays : Array(allDays.suffix(1))
    }

    private var hasValues: Bool {
        history.summary.map { $0.availability == .value || $0.availability == .degraded } ?? false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            FlintCard(radius: 24, padding: EdgeInsets(top: 18, leading: 16, bottom: 16, trailing: 16)) {
                VStack(alignment: .leading, spacing: 18) {
                    comparison
                    if hasValues {
                        Rectangle().fill(FlintBrand.bone(0.08)).frame(height: 1)
                        bars
                        Text("Measured on this phone. Nothing is uploaded.")
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.stone)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .multilineTextAlignment(.center)
                    }
                    if let summary = history.summary, summary.availability == .degraded {
                        Text(FocusHistoryText.degraded).flintSecondary(13)
                    }
                    if history.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12) }
                }
            }
            .accessibilityIdentifier("history.section")

            weekSection
        }
    }

    // MARK: Sentence

    @ViewBuilder private var comparison: some View {
        if let summary = history.summary {
            if summary.availability == .unavailable {
                Text("Focus history unavailable")
                    .font(FlintFont.text(17, weight: .semibold))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            } else if summary.availability == .empty {
                Text("No focus sessions yet. Start one from Today and it will show up here.")
                    .font(FlintFont.text(17, weight: .semibold))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text(sentence)
                    .font(FlintFont.text(17, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel(spokenSentence)
            }
        } else {
            Text("Loading focus history…")
                .font(FlintFont.text(17, weight: .semibold))
                .foregroundStyle(FlintBrand.mist)
        }
    }

    private var sentence: AttributedString {
        var text = AttributedString("You focused ")
        var amount = AttributedString(todayText)
        amount.foregroundColor = FlintBrand.emberText
        text += amount
        if let diff = difference {
            text += AttributedString(" today, \(FocusHistoryText.duration(abs(diff))) ")
            text += AttributedString(diff >= 0 ? "more" : "less")
            text += AttributedString(" than your 7-day average.")
        } else {
            text += AttributedString(" today.")
        }
        return text
    }

    private var spokenSentence: String {
        var line = "You focused \(todayText) today"
        if let diff = difference {
            line += ", \(FocusHistoryText.duration(abs(diff))) \(diff >= 0 ? "more" : "less") than your 7-day average"
        }
        return line + "."
    }

    private var todayText: String {
        FocusHistoryText.duration(days.last?.focusedSeconds)
    }

    /// Mean of the days before today that have data; nil when there is nothing to compare.
    private var average: TimeInterval? {
        let prior = allDays.dropLast().compactMap(\.focusedSeconds)
        guard !prior.isEmpty else { return nil }
        return prior.reduce(0, +) / Double(prior.count)
    }

    private var difference: TimeInterval? {
        guard let average, let today = days.last?.focusedSeconds else { return nil }
        return today - average
    }

    // MARK: Bars

    private var bars: some View {
        let today = days.last?.focusedSeconds
        let averageValue = average
        let peak = max(today ?? 0, averageValue ?? 0, 60)
        return HStack(alignment: .bottom, spacing: 16) {
            barColumn(value: today, peak: peak, label: "Focused today", ember: true)
            if let averageValue {
                barColumn(value: averageValue, peak: peak, label: "Your 7-day average", ember: false)
            }
            if range == .today, averageValue == nil {
                Text("Focus a few days to see your average.")
                    .flintSecondary(13)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 6)
        .frame(maxWidth: .infinity)
    }

    private func barColumn(value: TimeInterval?, peak: TimeInterval, label: String, ember: Bool) -> some View {
        let fraction = max(0.02, min(1, (value ?? 0) / peak))
        return VStack(spacing: 10) {
            VStack {
                Spacer(minLength: 0)
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(ember
                        ? LinearGradient(stops: [
                            .init(color: FlintBrand.emberTop, location: 0),
                            .init(color: FlintBrand.ember, location: 0.55),
                            .init(color: FlintBrand.ember.opacity(0.25), location: 1),
                        ], startPoint: .top, endPoint: .bottom)
                        : LinearGradient(stops: [
                            .init(color: FlintBrand.bone.opacity(0.22), location: 0),
                            .init(color: FlintBrand.bone.opacity(0.10), location: 0.6),
                            .init(color: FlintBrand.bone.opacity(0.03), location: 1),
                        ], startPoint: .top, endPoint: .bottom))
                    .frame(width: 64, height: max(8, 120 * fraction))
            }
            .frame(height: 140, alignment: .bottom)
            VStack(spacing: 2) {
                Text(FocusHistoryText.duration(value))
                    .font(FlintFont.display(24, relativeTo: .title2))
                    .monospacedDigit()
                    .foregroundStyle(ember ? FlintBrand.bone : FlintBrand.mist)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(label)
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.mist)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label), \(FocusHistoryText.duration(value))")
    }

    // MARK: Week

    private var weekSection: some View {
        let week = Array(allDays.suffix(7))
        let peak = max(1, week.compactMap(\.focusedSeconds).max() ?? 1)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("Focus this week").flintSection()
                Spacer()
                Text("focused time").font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
            }
            .padding(.horizontal, 4)
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(week) { day in
                    let isToday = Calendar.current.isDateInToday(day.start)
                    let height = day.focusedSeconds.map { max(4, 40 * min(1, ($0 / peak))) }
                    if let height {
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .fill(isToday ? FlintBrand.ember : FlintBrand.bone(0.18))
                            .frame(height: height)
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel("\(day.start.formatted(date: .abbreviated, time: .omitted)), \(FocusHistoryText.duration(day.focusedSeconds))")
                    } else {
                        RoundedRectangle(cornerRadius: 2, style: .continuous)
                            .strokeBorder(FlintBrand.bone(0.2), style: StrokeStyle(lineWidth: 1, dash: [2, 2]))
                            .frame(height: 4)
                            .frame(maxWidth: .infinity)
                            .accessibilityLabel("\(day.start.formatted(date: .abbreviated, time: .omitted)), no data")
                    }
                }
            }
            .frame(height: 40, alignment: .bottom)
            HStack(spacing: 8) {
                ForEach(week) { day in
                    let isToday = Calendar.current.isDateInToday(day.start)
                    Text(day.start.formatted(.dateTime.weekday(.narrow)))
                        .font(FlintFont.text(11, weight: isToday ? .bold : .regular))
                        .foregroundStyle(isToday ? FlintBrand.bone : FlintBrand.stone)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }
}
