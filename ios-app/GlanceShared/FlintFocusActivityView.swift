import SwiftUI
import FlintCore

/// The same content is used by ActivityKit and the explicitly labeled DEBUG gallery.
@available(iOS 16.1, *)
struct FlintFocusActivityView: View {
    let state: FlintFocusActivityAttributes.ContentState
    let needsCheck: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("Flint · \(state.cause)", systemImage: "flame.fill")
                Spacer()
                Text(state.tier)
            }.font(.headline)
            if needsCheck {
                Text("Open Flint to check")
            } else {
                HStack {
                    VStack(alignment: .leading) {
                        Text(state.countdownLabel).font(.caption)
                        Text(timerInterval: state.startedAt...max(state.startedAt, state.countdownEnd), countsDown: true)
                            .monospacedDigit().font(.title2)
                    }
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text("Planned end").font(.caption)
                        Text(state.endsAt, style: .time)
                    }
                }
                // On 16.1 the timer stays bounded even without staleDate support.
                Text("At zero, open Flint to check").font(.caption)
            }
            Text("Open Flint").font(.caption.weight(.semibold))
        }
        .padding(16)
        .foregroundStyle(FlintBrand.bone)
    }
}
