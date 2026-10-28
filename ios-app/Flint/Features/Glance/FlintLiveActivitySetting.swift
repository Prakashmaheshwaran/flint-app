import ActivityKit
import SwiftUI
import FlintCore

struct FlintLiveActivitySetting: View {
    @AppStorage(FlintLiveActivityPreference.key) private var enabled = false

    var body: some View {
        if #available(iOS 16.1, *) {
            VStack(alignment: .leading, spacing: 8) {
                Toggle("Live Activities", isOn: $enabled)
                    .tint(FlintBrand.ember)
                    .foregroundStyle(FlintBrand.bone)
                    .accessibilityHint("Show a session countdown and strictness on the Lock Screen. Does not change protection.")
                Text("Off by default. Show session countdowns and strictness on your Lock Screen and Dynamic Island. Anyone who can see your screen may see these details. Open Flint to make changes.")
                    .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                    .fixedSize(horizontal: false, vertical: true)
                if !ActivityAuthorizationInfo().areActivitiesEnabled {
                    Text("Live Activities are disabled in iOS Settings.")
                        .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                }
            }
            .padding(.vertical, 16)
        }
    }
}
