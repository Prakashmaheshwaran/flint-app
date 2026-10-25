import SwiftUI
import FlintCore

/// The top of the Sleep screen: a 24-hour dial with the night drawn from bedtime (moon) to
/// wake (ember), the hours of quiet inside it, and the two times as tappable cards.
struct SleepDialHeader: View {
    @ObservedObject var vm: SleepModeViewModel
    @State private var editing: Field?

    private enum Field: Identifiable { case bedtime, wake; var id: Self { self } }

    var body: some View {
        Section {
            VStack(spacing: 24) {
                Text(vm.config.enabled ? daysLine : "Off")
                    .flintSecondary(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                dial
                HStack(spacing: 10) {
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
                title: field == .bedtime ? "Bedtime" : "Wake",
                time: Binding(
                    get: { field == .bedtime ? vm.bedtime : vm.wake },
                    set: { if field == .bedtime { vm.bedtime = $0 } else { vm.wake = $0 } }
                )
            )
        }
    }

    private var daysLine: String {
        let level: String
        switch vm.config.sleepAssist {
        case .off: level = "Sleep Assist off"
        case .windDown: level = "Wind Down"
        case .fullAssist: level = "Full Assist"
        }
        return "\(RulesView.daysSummary(vm.config.schedule)) · \(level)"
    }

    private var quietMinutes: Int {
        let s = vm.config.schedule
        let start = s.startHour * 60 + s.startMinute, end = s.endHour * 60 + s.endMinute
        return (end - start + 1440) % 1440
    }

    private var dial: some View {
        let s = vm.config.schedule
        let startFrac = Double(s.startHour * 60 + s.startMinute) / 1440
        let endFrac = Double(s.endHour * 60 + s.endMinute) / 1440
        return ZStack {
            Circle().stroke(FlintBrand.bone(0.08), lineWidth: 18)
            NightArc(start: startFrac, end: endFrac)
                .stroke(Color(hex: 0x3A3F5C), style: StrokeStyle(lineWidth: 18, lineCap: .round))
            knob(at: startFrac, fill: FlintBrand.bone) {
                Image(systemName: "moon.fill").font(.system(size: 11)).foregroundStyle(Color(hex: 0x3A3F5C))
            }
            knob(at: endFrac, fill: FlintBrand.ember) { EmptyView() }
            ForEach([(0, "00"), (6, "06"), (12, "12"), (18, "18")], id: \.0) { hour, label in
                Text(label)
                    .font(FlintFont.text(11))
                    .foregroundStyle(FlintBrand.stone)
                    .offset(point(Double(hour) / 24, radius: 80))
            }
            VStack(spacing: 4) {
                Text(TodayView.duration(minutes: quietMinutes)).flintNumber(36)
                Text("of quiet").flintSecondary(12)
            }
        }
        .frame(width: 208, height: 208)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(TodayView.duration(minutes: quietMinutes)) of quiet")
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

    private func timeCard(_ label: String, _ hour: Int, _ minute: Int, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            FlintCard {
                Text(label).flintSecondary(12)
                Text(FlintTodaySchedule.clock(hour, minute)).flintNumber(24)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label) \(FlintTodaySchedule.clock(hour, minute))")
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
