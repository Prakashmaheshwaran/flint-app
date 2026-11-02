import SwiftUI
import WidgetKit

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
        StaticConfiguration(kind: FlintGlanceReader.widgetKind, provider: FlintGlanceProvider()) { entry in
            FlintGlanceWidgetView(entry: entry)
        }
        .configurationDisplayName("Flint at a glance")
        .description("Protection status and local focus sessions. Open Flint to check or make changes.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}

@main
struct FlintWidgetBundle: WidgetBundle {
    @WidgetBundleBuilder var body: some Widget {
        FlintProtectionWidget()
        if #available(iOS 16.1, *) { FlintFocusLiveActivity() }
    }
}
