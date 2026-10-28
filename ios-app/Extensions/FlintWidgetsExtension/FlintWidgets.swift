import SwiftUI
import WidgetKit
import FlintCore

struct FlintGlanceEntry: TimelineEntry {
    let date: Date
    let input: FlintGlanceInput
    var presentation: FlintGlancePresentation { .init(input, at: date) }
}

struct FlintGlanceProvider: TimelineProvider {
    func placeholder(in context: Context) -> FlintGlanceEntry {
        let now = Date()
        return .init(date: now, input: .unavailable(at: now))
    }

    func getSnapshot(in context: Context, completion: @escaping (FlintGlanceEntry) -> Void) {
        let now = Date()
        completion(.init(date: now, input: FlintGlanceReader().read(at: now)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<FlintGlanceEntry>) -> Void) {
        let now = Date()
        let input = FlintGlanceReader().read(at: now)
        let dates = [now] + FlintGlancePresentation.boundaries(input, after: now)
        let entries = dates.map { FlintGlanceEntry(date: $0, input: input) }
        // OS refresh is best-effort. Precomputed boundary entries withdraw expired claims
        // without needing the host or a successful refresh at the exact deadline.
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(15 * 60))))
    }
}

struct FlintProtectionWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: FlintGlancePresentation.widgetKind, provider: FlintGlanceProvider()) { entry in
            FlintGlanceWidgetView(entry: entry)
        }
        .configurationDisplayName("Flint at a glance")
        .description("Protection status and local focus sessions. Open Flint to check or make changes.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}

private struct FlintGlanceWidgetView: View {
    let entry: FlintGlanceEntry
    @Environment(\.widgetFamily) private var family
    private var model: FlintGlancePresentation { entry.presentation }

    var body: some View {
        Group {
            if #available(iOS 17.0, *) {
                content.containerBackground(FlintBrand.ground, for: .widget)
            } else {
                content.padding(isAccessory ? 0 : 12)
                    .background(isAccessory ? Color.clear : FlintBrand.ground)
            }
        }
        .widgetURL(FlintGlancePresentation.openURL)
    }

    private var isAccessory: Bool {
        [.accessoryInline, .accessoryCircular, .accessoryRectangular].contains(family)
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .accessoryInline:
            HStack {
                Image(systemName: "flame")
                Text(model.title)
                if let end = model.countdownEnd { countdown(end) }
            }
        case .accessoryCircular:
            VStack(spacing: 2) {
                Image(systemName: "flame")
                if let end = model.countdownEnd { countdown(end).font(.caption2) }
                else { Text("Check").font(.caption2) }
            }
            .accessibilityLabel("Flint. \(model.title). Open Flint to check.")
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 2) {
                Text(model.title).font(.headline).lineLimit(1).minimumScaleFactor(0.8)
                if let end = model.countdownEnd {
                    HStack { countdown(end); Text("·"); nextOccurrence }.font(.caption)
                } else { nextOccurrence.font(.caption) }
                Text("Focused today: \(model.historyValue)").font(.caption2).lineLimit(1)
            }
        default:
            home
        }
    }

    private var home: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 4 : 7) {
            Label("FLINT", systemImage: "flame.fill")
                .font(.caption2.weight(.semibold)).foregroundStyle(FlintBrand.ember)
            Text(model.title).font(family == .systemSmall ? .caption.weight(.semibold) : .headline)
                .foregroundStyle(FlintBrand.bone)
                .lineLimit(2).minimumScaleFactor(0.8)
            if let end = model.countdownEnd {
                HStack { countdown(end).font(.headline.monospacedDigit()); Text("to check").font(.caption2) }
                    .foregroundStyle(FlintBrand.bone)
            }
            nextOccurrence.font(.caption2).foregroundStyle(FlintBrand.mist).lineLimit(1)
            Spacer(minLength: 0)
            HStack(alignment: .firstTextBaseline) {
                Text("Focused today").font(.caption2)
                Spacer(minLength: 2)
                Text(model.historyValue).font(.caption2.weight(.semibold)).lineLimit(2)
            }
            .foregroundStyle(FlintBrand.bone)
            if family == .systemMedium {
                Text(model.historyNote ?? model.detail).font(.caption2).foregroundStyle(FlintBrand.mist)
                    .lineLimit(1)
            }
            Text("Open Flint").font(.caption2).foregroundStyle(FlintBrand.ember)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var nextOccurrence: some View {
        if let start = model.nextStart {
            HStack(spacing: 3) { Text("Next"); Text(start, style: .time) }
        } else { Text("Next: open Flint to check") }
    }

    private func countdown(_ end: Date) -> some View {
        // timerInterval stops at zero; Date-style .timer would count up after the end.
        Text(timerInterval: entry.date...max(entry.date, end), countsDown: true)
            .monospacedDigit()
    }
}

@main
struct FlintWidgetBundle: WidgetBundle {
    @WidgetBundleBuilder var body: some Widget {
        FlintProtectionWidget()
        if #available(iOS 16.1, *) { FlintFocusLiveActivity() }
    }
}
