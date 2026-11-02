import ActivityKit
import SwiftUI
import WidgetKit
import FlintCore

@available(iOS 16.1, *)
struct FlintFocusLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FlintFocusActivityAttributes.self) { context in
            FlintFocusActivityView(state: context.state, needsCheck: needsCheck(context))
            .activityBackgroundTint(FlintBrand.ground)
            .activitySystemActionForegroundColor(FlintBrand.bone)
            .foregroundStyle(FlintBrand.bone)
            .widgetURL(FlintGlancePresentation.openURL)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.state.cause, systemImage: "flame.fill").font(.headline)
                }
                DynamicIslandExpandedRegion(.trailing) { Text(context.state.tier).font(.caption) }
                DynamicIslandExpandedRegion(.center) {
                    if needsCheck(context) { Text("Open Flint to check") }
                    else {
                        VStack {
                            Text(context.state.countdownLabel).font(.caption)
                            countdown(context.state).font(.title2)
                        }
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 3) {
                        Text("Planned end")
                        Text(context.state.endsAt, style: .time)
                        Text("· Open Flint")
                    }.font(.caption)
                }
            } compactLeading: {
                Image(systemName: "flame.fill").foregroundStyle(FlintBrand.ember)
            } compactTrailing: {
                if needsCheck(context) { Image(systemName: "questionmark") }
                else {
                    HStack(spacing: 2) {
                        if context.state.staleAt < context.state.endsAt {
                            Image(systemName: "arrow.clockwise").font(.system(size: 8))
                        }
                        countdown(context.state).frame(maxWidth: 55).font(.caption)
                    }
                    .accessibilityLabel(context.state.countdownLabel)
                }
            } minimal: {
                Image(systemName: needsCheck(context) ? "questionmark" : "flame.fill")
            }
            .widgetURL(FlintGlancePresentation.openURL)
        }
    }

    private func needsCheck(_ context: ActivityViewContext<FlintFocusActivityAttributes>) -> Bool {
        if #available(iOS 16.2, *) { return context.isStale || context.state.needsCheck }
        return context.state.needsCheck
    }

    private func countdown(_ state: FlintFocusActivityAttributes.ContentState) -> some View {
        Text(timerInterval: state.startedAt...max(state.startedAt, state.countdownEnd), countsDown: true)
            .monospacedDigit()
    }
}
