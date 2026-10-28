import SwiftUI
import FlintCore

/// The top of the Sleep screen: a 24-hour dial with the night drawn from bedtime (moon) to
/// wake (ember), the hours of quiet inside it, and the two times as tappable cards.
struct SleepDialHeader: View {
    @ObservedObject var vm: SleepModeViewModel
    @State private var editing: Field?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    // E11-Sleep.dc.html's night arc and moon glyph; retained per design review.
    private static let nightArcColor = Color(hex: 0x3A3F5C)

    private enum Field: Identifiable { case bedtime, wake; var id: Self { self } }

    var body: some View {
        Section {
            VStack(spacing: 24) {
                Text(vm.config.enabled ? daysLine : String(localized: "Off"))
                    .flintSecondary(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                dial
                timeCardLayout {
                    timeCard("Bedtime", vm.config.schedule.startHour, vm.config.schedule.startMinute) { editing = .bedtime }
                    timeCard("Wake", vm.config.schedule.endHour, vm.config.schedule.endMinute) { editing = .wake }
                }
            }
            .padding(.vertical, 8)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
        }
        .sheet(item: $editing) { field in
            TimePickerSheet(
                title: field == .bedtime ? String(localized: "Bedtime") : String(localized: "Wake"),
                time: Binding(
                    get: { field == .bedtime ? vm.bedtime : vm.wake },
                    set: { if field == .bedtime { vm.bedtime = $0 } else { vm.wake = $0 } }
                )
            )
        }
    }

    private var timeCardLayout: AnyLayout {
        dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 10))
            : AnyLayout(HStackLayout(spacing: 10))
    }

    private var daysLine: String {
        String(localized: "\(SleepPresentation.daysSummary(vm.config.schedule)) · \(SleepPresentation.assistSummary(vm.config.sleepAssist))")
    }

    private var dial: some View {
        VStack(spacing: 16) {
            dialFace
            if dynamicTypeSize.isAccessibilitySize { quietDuration }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Sleep window")
        .accessibilityValue(Text(SleepPresentation.dialValue(vm.config.schedule)))
    }

    private var quietDuration: some View {
        VStack(spacing: 4) {
            Text(SleepPresentation.duration(vm.config.schedule))
                .font(FlintFont.display(36, relativeTo: .largeTitle))
                .monospacedDigit()
                .foregroundStyle(FlintBrand.bone)
                .fixedSize(horizontal: false, vertical: true)
            Text("of quiet").flintSecondary(12)
        }
        .multilineTextAlignment(.center)
    }

    private var dialFace: some View {
        let s = vm.config.schedule
        let startFrac = Double(s.startHour * 60 + s.startMinute) / 1440
        let endFrac = Double(s.endHour * 60 + s.endMinute) / 1440
        return ZStack {
            Circle().stroke(FlintBrand.bone(0.08), lineWidth: 18)
            NightArc(start: startFrac, end: endFrac)
                .stroke(Self.nightArcColor, style: StrokeStyle(lineWidth: 18, lineCap: .round))
            knob(at: startFrac, fill: FlintBrand.bone) {
                Image(systemName: "moon.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(Self.nightArcColor)
                    .accessibilityHidden(true)
            }
            knob(at: endFrac, fill: FlintBrand.ember) { EmptyView() }
            if !dynamicTypeSize.isAccessibilitySize {
                ForEach([0, 6, 12, 18], id: \.self) { hour in
                    Text(hour, format: .number.precision(.integerLength(2)))
                        .font(FlintFont.text(11))
                        .foregroundStyle(FlintBrand.stone)
                        .offset(point(Double(hour) / 24, radius: 80))
                }
                quietDuration.frame(maxWidth: 144)
            }
        }
        .frame(width: 208, height: 208)
        .padding(14) // Keep the end knobs inside the row's measured bounds.
        .accessibilityHidden(true)
    }

    private func knob<Icon: View>(at frac: Double, fill: Color, @ViewBuilder icon: () -> Icon) -> some View {
        ZStack {
            Circle().fill(fill).frame(width: 26, height: 26)
            icon()
        }
        .offset(point(frac, radius: 104))
    }

    private func point(_ frac: Double, radius: CGFloat) -> CGSize {
        let angle = frac * 2 * .pi - .pi / 2
        return CGSize(width: cos(angle) * radius, height: sin(angle) * radius)
    }

    private func timeCard(_ label: LocalizedStringKey, _ hour: Int, _ minute: Int, action: @escaping () -> Void) -> some View {
        let time = SleepPresentation.clock(hour, minute)
        return Button(action: action) {
            FlintCard {
                Text(label).flintSecondary(12)
                Text(time)
                    .font(FlintFont.display(24, relativeTo: .title2))
                    .foregroundStyle(FlintBrand.bone)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(time))
        .accessibilityHint("Change the time")
    }
}

/// Locale-aware presentation only; schedule/tier behavior remains in SleepModeController.
enum SleepPresentation {
    private static func calendar(_ locale: Locale) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    static func weekdayName(_ weekday: Int, locale: Locale = .current) -> String {
        guard (1...7).contains(weekday) else { return String(localized: "Unknown day", locale: locale) }
        return calendar(locale).standaloneWeekdaySymbols[weekday - 1]
    }

    static func weekdaySymbol(_ weekday: Int, locale: Locale = .current) -> String {
        guard (1...7).contains(weekday) else { return "?" }
        return calendar(locale).veryShortStandaloneWeekdaySymbols[weekday - 1]
    }

    static func daysSummary(_ schedule: FlintSchedule, locale: Locale = .current) -> String {
        if schedule.daysOfWeek.isEmpty || schedule.daysOfWeek == Set(1...7) {
            return String(localized: "Every night", locale: locale)
        }
        let formatter = ListFormatter()
        formatter.locale = locale
        let names = schedule.daysOfWeek.sorted().map { weekdayName($0, locale: locale) }
        let days = formatter.string(from: names) ?? names.joined(separator: ", ")
        return String(localized: "\(days) nights", locale: locale)
    }

    static func clock(_ hour: Int, _ minute: Int, locale: Locale = .current) -> String {
        guard (0...23).contains(hour), (0...59).contains(minute) else {
            return String(localized: "Invalid time", locale: locale)
        }
        let calendar = calendar(locale)
        let date = calendar.date(from: DateComponents(year: 2001, month: 1, day: 1, hour: hour, minute: minute))!
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }

    static func timeSummary(_ schedule: FlintSchedule, locale: Locale = .current) -> String {
        let start = clock(schedule.startHour, schedule.startMinute, locale: locale)
        let end = clock(schedule.endHour, schedule.endMinute, locale: locale)
        return schedule.startMinuteOfDay >= schedule.endMinuteOfDay
            ? String(localized: "\(start) → \(end) next morning", locale: locale)
            : String(localized: "\(start) → \(end)", locale: locale)
    }

    static func duration(_ schedule: FlintSchedule, locale: Locale = .current,
                         style: DateComponentsFormatter.UnitsStyle = .abbreviated) -> String {
        let formatter = DateComponentsFormatter()
        formatter.calendar = calendar(locale)
        formatter.allowedUnits = [.hour, .minute]
        formatter.unitsStyle = style
        formatter.zeroFormattingBehavior = .dropAll
        return formatter.string(from: TimeInterval(schedule.windowMinutes * 60))
            ?? String(localized: "\(schedule.windowMinutes) minutes", locale: locale)
    }

    static func dialValue(_ schedule: FlintSchedule, locale: Locale = .current) -> String {
        let bedtime = clock(schedule.startHour, schedule.startMinute, locale: locale)
        let wake = clock(schedule.endHour, schedule.endMinute, locale: locale)
        let duration = duration(schedule, locale: locale, style: .full)
        return String(localized: "Bedtime \(bedtime). Wake \(wake). \(duration) of quiet.", locale: locale)
    }

    // Match the current saved assist mapping, not the future defaults in ADR-012.
    static func assistSummary(_ level: FlintSleepAssistLevel) -> String {
        switch level {
        case .off: return String(localized: "Sleep Assist off")
        case .windDown: return String(localized: "Wind Down · Harder")
        case .fullAssist: return String(localized: "Full Assist · Hardcore")
        }
    }
}

/// Clockwise arc on a 24-hour dial from `start` to `end` (fractions of a day, 0 at the top).
private struct NightArc: Shape {
    let start: Double
    let end: Double
    func path(in rect: CGRect) -> Path {
        let span = (end - start + 1).truncatingRemainder(dividingBy: 1)
        var p = Path()
        p.addArc(center: CGPoint(x: rect.midX, y: rect.midY), radius: rect.width / 2,
                 startAngle: .radians(start * 2 * .pi - .pi / 2),
                 endAngle: .radians((start + span) * 2 * .pi - .pi / 2), clockwise: false)
        return p
    }
}
