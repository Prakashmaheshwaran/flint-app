import SwiftUI
import FamilyControls
import FlintCore

/// Today: the ember on the horizon, what's resting now, and the one way into a session.
/// The running session and its Complete screen are presented by `RootView`, over every tab.
struct TodayView: View {
    @ObservedObject var auth: AuthorizationModel
    @ObservedObject var vm: SessionViewModel
    @Binding var showStart: Bool
    @State private var now = Date()
    @State private var rules: [FlintScheduleRule] = []
    @ObservedObject private var history = FocusHistoryModel.shared
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize

    private let presets: [(name: String, minutes: Int)] = [("Deep study", 90), ("Reading", 30), ("Workout", 60)]

    var body: some View {
        ZStack(alignment: .top) {
            EmberLandscape()

            GeometryReader { geometry in
                ScrollView {
                    content
                        .padding(.horizontal, 24)
                        .padding(.top, 12)
                        .padding(.bottom, 28)
                        .frame(minHeight: geometry.size.height, alignment: .top)
                }
                .clipped()
            }
        }
        .sheet(isPresented: $showStart) {
            StartFocusSheet(vm: vm) { showStart = false }
        }
        .task {
            auth.refresh()
            rules = FlintSchedulesController().rules()
            history.refresh()
        }
        .onChange(of: scenePhase) { if $0 == .active { history.refresh() } }
        .onReceive(Timer.publish(every: 30, on: .main, in: .common).autoconnect()) { now = $0; history.refresh() }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(now.formatted(.dateTime.weekday(.wide).day().month(.wide)).replacingOccurrences(of: ",", with: " ·"))
                    .flintEyebrow()
                Spacer()
                FlintMark(size: 20)
            }
            .padding(.top, 18)

            Text(headline)
                .flintTitle(31)
                .padding(.top, 26)
                .fixedSize(horizontal: false, vertical: true)
            Text(subline)
                .flintSecondary(14)
                .padding(.top, 8)

            FocusedTodayCard(history: history)
                .padding(.top, 28)

            VStack(spacing: 6) {
                Text(Self.duration(minutes: vm.durationMinutes)).flintNumber(50)
                Text("Your next session").flintEyebrow()
                    .fixedSize(horizontal: false, vertical: true)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .accessibilityElement(children: .combine)
            .padding(.top, 24)

            Spacer(minLength: 32)

            if let message = vm.errorText {
                errorMessage(message)
                    .padding(16)
                    .background(feedbackBackground)
                    .accessibilityIdentifier("today.session.error")
                    .padding(.bottom, 14)
            }
            if auth.status == .approved {
                actions
            } else {
                grantCard
            }
        }
    }

    private var actions: some View {
        VStack(spacing: 14) {
            FlintWrappingLayout(spacing: 8) {
                ForEach(presets, id: \.name) { preset in
                    Button {
                        vm.durationMinutes = preset.minutes
                        showStart = true
                    } label: {
                        (Text(preset.name) + Text(" \(preset.minutes)m").foregroundColor(FlintBrand.mist))
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.vertical, typeSize.isAccessibilitySize ? 8 : 0)
                    }
                    .buttonStyle(ChipButtonStyle(height: 44))
                    .accessibilityLabel("\(preset.name), \(preset.minutes) minutes")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("Start focus") { showStart = true }
                .buttonStyle(EmberButtonStyle())
        }
    }

    private var grantCard: some View {
        VStack(spacing: 12) {
            Text("Flint needs Screen Time access to rest your apps. Nothing leaves this phone.")
                .flintSecondary(14)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("today.authorization.explanation")
            AuthorizationControls(auth: auth, identifierPrefix: "today.authorization")
        }
        .padding(16)
        .background(feedbackBackground)
    }

    private var feedbackBackground: some View {
        RoundedRectangle(cornerRadius: 24, style: .continuous)
            .fill(FlintBrand.ground.opacity(0.94))
    }

    private func errorMessage(_ message: String) -> some View {
        Text(message)
            .font(FlintFont.text(13))
            .foregroundStyle(FlintBrand.danger)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
    }

    // MARK: Schedule-aware copy (real rules only — nothing invented)

    private var headline: String {
        if let current = FlintTodaySchedule.current(in: rules, at: now) { return "\(current.name) is on." }
        return "A quiet day so far."
    }

    private var subline: String {
        var parts: [String] = []
        if let current = FlintTodaySchedule.current(in: rules, at: now) {
            parts.append("Until \(FlintTodaySchedule.clock(current.schedule.endHour, current.schedule.endMinute))")
        }
        if let next = FlintTodaySchedule.next(in: rules, at: now) {
            parts.append("next, \(next.name) at \(FlintTodaySchedule.clock(next.schedule.startHour, next.schedule.startMinute))")
        }
        if parts.isEmpty { return rules.isEmpty ? "No schedules yet — add one in Rules." : "Nothing else scheduled today." }
        let line = parts.joined(separator: " · ")
        return line.prefix(1).uppercased() + line.dropFirst()
    }

    static func duration(minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        if h == 0 { return "\(m)m" }
        return m == 0 ? "\(h)h" : "\(h)h \(m)m"
    }
}

/// Which enabled schedule is running now, and which starts next today.
enum FlintTodaySchedule {
    static func current(in rules: [FlintScheduleRule], at date: Date) -> FlintScheduleRule? {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: date) * 60 + cal.component(.minute, from: date)
        let weekday = cal.component(.weekday, from: date)
        let yesterday = weekday == 1 ? 7 : weekday - 1
        return rules.first { rule in
            guard rule.enabled else { return false }
            let s = rule.schedule
            let start = s.startHour * 60 + s.startMinute, end = s.endHour * 60 + s.endMinute
            if start < end { return runs(s, on: weekday) && minute >= start && minute < end }
            // Overnight: the evening part belongs to today, the morning part to yesterday's run.
            return (runs(s, on: weekday) && minute >= start) || (runs(s, on: yesterday) && minute < end)
        }
    }

    static func next(in rules: [FlintScheduleRule], at date: Date) -> FlintScheduleRule? {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: date) * 60 + cal.component(.minute, from: date)
        let weekday = cal.component(.weekday, from: date)
        return rules
            .filter { $0.enabled && runs($0.schedule, on: weekday) && $0.schedule.startHour * 60 + $0.schedule.startMinute > minute }
            .min { $0.schedule.startHour * 60 + $0.schedule.startMinute < $1.schedule.startHour * 60 + $1.schedule.startMinute }
    }

    static func clock(_ hour: Int, _ minute: Int) -> String { String(format: "%02d:%02d", hour, minute) }

    private static func runs(_ s: FlintSchedule, on weekday: Int) -> Bool {
        s.daysOfWeek.isEmpty || s.daysOfWeek.contains(weekday)
    }
}
