import CoreText
import SwiftUI
import FlintCore

/// Insights, as drawn inside the report extension: the total, a ridge of days with the peak
/// marked by the ember, and the apps reached for most. Mirrors design/ember-mist/E12-Insights.
struct TotalActivityView: View {
    let summary: ActivitySummary

    var body: some View {
        ScrollView {
            TotalActivityContent(summary: summary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color.clear)
    }

}

/// The same report content is used by the on-device scroll container and pure SwiftUI render
/// tests. An unhosted test bundle cannot snapshot the platform-backed scrolling container.
struct TotalActivityContent: View {
    let summary: ActivitySummary

    private var isWeek: Bool { summary.days.count > 1 }
    private var peak: ActivitySummary.Day? { summary.days.max { $0.duration < $1.duration } }

    var body: some View {
        if summary.contentState == .activity {
            activityContent
        } else {
            emptyContent
        }
    }

    private var emptyContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(summary.contentState == .empty ? "No screen time recorded" : "No activity reported")
                .font(ReportFont.display(26))
                .foregroundStyle(FlintBrand.bone)
                .accessibilityAddTraits(.isHeader)
            Text(summary.contentState == .empty
                 ? "Screen Time recorded no usage in the selected range. Try another range, or return after using your apps."
                 : "Screen Time hasn’t supplied activity for the selected range. Try another range, or check back after using your device.")
                .font(ReportFont.text(16))
                .foregroundStyle(FlintBrand.mist)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(24)
    }

    private var activityContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text(isWeek ? "SCREEN TIME THIS WEEK" : "SCREEN TIME TODAY")
                    .font(ReportFont.text(11, medium: true)).tracking(2).foregroundStyle(FlintBrand.mist)
                Text(Self.format(summary.total))
                    .font(ReportFont.display(52)).tracking(-1).monospacedDigit().foregroundStyle(FlintBrand.bone)
                if isWeek, let peak, peak.duration > 0 {
                    Text("Your highest day was \(peak.start.formatted(.dateTime.weekday(.wide))), \(Self.format(peak.duration)).")
                        .font(ReportFont.text(14)).foregroundStyle(FlintBrand.mist)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 30)

            if isWeek {
                DayRidge(days: summary.days, peak: peak?.start)
                    .frame(height: 170)
                    .padding(.top, 12)
            }

            if !summary.apps.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    Text("MOST REACHED FOR")
                        .font(ReportFont.text(11, medium: true)).tracking(2).foregroundStyle(FlintBrand.mist)
                        .padding(.bottom, 6)
                    ForEach(summary.apps) { app in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(app.name).font(ReportFont.text(15, medium: true)).foregroundStyle(FlintBrand.bone)
                                Text("\(app.pickups) \(app.pickups == 1 ? "pickup" : "pickups")")
                                    .font(ReportFont.text(13)).foregroundStyle(FlintBrand.mist)
                            }
                            Spacer()
                            Text(Self.format(app.duration))
                                .font(ReportFont.text(14)).monospacedDigit().foregroundStyle(FlintBrand.mist)
                        }
                        .frame(minHeight: 54)
                        .overlay(alignment: .top) { Rectangle().fill(FlintBrand.bone(0.08)).frame(height: 1) }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, isWeek ? 8 : 30)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color.clear)
    }

    static func format(_ t: TimeInterval) -> String {
        let minutes = Int(t / 60)
        let h = minutes / 60, m = minutes % 60
        if h == 0 { return "\(m)m" }
        return "\(h)h \(m)m"
    }
}

/// One point per day joined into a ridge line over a soft fill; the peak day carries the ember.
private struct DayRidge: View {
    let days: [ActivitySummary.Day]
    let peak: Date?

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height - 22
            let maxValue = max(days.map(\.duration).max() ?? 1, 1)
            let step = w / CGFloat(max(days.count, 1))
            let points = days.enumerated().map { i, day in
                CGPoint(x: step * (CGFloat(i) + 0.5), y: 12 + (h - 24) * (1 - CGFloat(day.duration / maxValue)))
            }
            ZStack(alignment: .topLeading) {
                if let first = points.first, let last = points.last {
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: first.y))
                        points.forEach { p.addLine(to: $0) }
                        p.addLine(to: CGPoint(x: w, y: last.y))
                        p.addLine(to: CGPoint(x: w, y: h))
                        p.addLine(to: CGPoint(x: 0, y: h))
                        p.closeSubpath()
                    }
                    .fill(LinearGradient(colors: [FlintBrand.ridge, FlintBrand.ground], startPoint: .top, endPoint: .bottom))
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: first.y))
                        points.forEach { p.addLine(to: $0) }
                        p.addLine(to: CGPoint(x: w, y: last.y))
                    }
                    .stroke(FlintBrand.bone(0.5), style: StrokeStyle(lineWidth: 1.2, lineJoin: .round))
                }
                ForEach(Array(zip(days, points)), id: \.0.start) { day, point in
                    if day.start == peak {
                        Circle().fill(FlintBrand.ember.opacity(0.22)).frame(width: 24, height: 24).position(point)
                        Circle().fill(FlintBrand.ember).frame(width: 9, height: 9).position(point)
                    }
                    Text(day.start.formatted(.dateTime.weekday(.narrow)))
                        .font(ReportFont.text(11, medium: Calendar.current.isDateInToday(day.start)))
                        .foregroundStyle(Calendar.current.isDateInToday(day.start) ? FlintBrand.bone : FlintBrand.stone)
                        .position(x: point.x, y: geo.size.height - 8)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(days.map { "\($0.start.formatted(.dateTime.weekday(.wide))) \(TotalActivityView.format($0.duration))" }
            .joined(separator: ", "))
    }
}

/// The report runs in its own process, so it registers its own copy of the bundled fonts.
enum ReportFont {
    private static let registered: Void = {
        for name in ["ShipporiMincho-Regular", "HankenGrotesk-Regular", "HankenGrotesk-Medium"] {
            if let url = Bundle.main.url(forResource: name, withExtension: "ttf") {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }()

    static func display(_ size: CGFloat) -> Font {
        _ = registered
        return UIFont(name: "ShipporiMincho-Regular", size: size) != nil
            ? .custom("ShipporiMincho-Regular", size: size, relativeTo: .title) : .system(.title, design: .serif)
    }

    static func text(_ size: CGFloat, medium: Bool = false) -> Font {
        _ = registered
        let name = medium ? "HankenGrotesk-Medium" : "HankenGrotesk-Regular"
        return UIFont(name: name, size: size) != nil
            ? .custom(name, size: size, relativeTo: .body) : .system(.body, weight: medium ? .medium : .regular)
    }
}

#Preview {
    TotalActivityView(summary: ActivitySummary(total: 51_600, days: [], apps: [
        .init(name: "Instagram", duration: 15_000, pickups: 61),
    ]))
    .background(FlintBrand.ground)
}
