import ActivityKit
import SwiftUI
import WidgetKit
import FlintCore

@available(iOS 16.1, *)
struct FlintFocusLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: FlintFocusActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Label("Flint · \(context.state.cause)", systemImage: "flame.fill")
                    Spacer()
                    Text(context.state.tier)
                }.font(.headline)
                if needsCheck(context) {
                    Text("Open Flint to check")
                } else {
                    HStack {
                        countdown(context.state).font(.title2)
                        Spacer()
                        VStack(alignment: .trailing) {
                            Text("Planned end").font(.caption)
                            Text(context.state.endsAt, style: .time)
                        }
                    }
                    // Static wording stays truthful even on iOS 16.1, where ActivityKit
                    // has no staleDate: a zero timer never asserts continuing protection.
                    Text("At zero, open Flint to check").font(.caption)
                }
                Text("Open Flint").font(.caption.weight(.semibold))
            }
            .padding(16)
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
                    else { countdown(context.state).font(.title2) }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text("At zero, open Flint to check").font(.caption)
                }
            } compactLeading: {
                Image(systemName: "flame.fill").foregroundStyle(FlintBrand.ember)
            } compactTrailing: {
                if needsCheck(context) { Image(systemName: "questionmark") }
                else { countdown(context.state).frame(maxWidth: 62).font(.caption) }
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
