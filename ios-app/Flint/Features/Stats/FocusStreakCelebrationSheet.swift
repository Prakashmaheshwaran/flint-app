import SwiftUI
import FlintCore

/// Bottom sheet celebration for Focus Streak — rises from below with flame, count, message, Continue.
struct FocusStreakCelebrationSheet: View {
    let streak: Int
    let mood: StreakEmberMood
    var onContinue: () -> Void

    @Environment(\.dynamicTypeSize) private var typeSize

    private var numberColor: Color {
        switch mood {
        case .active: return FlintBrand.ember
        case .warning: return flintStreakCoolDeep
        case .dormant: return FlintBrand.mist.opacity(0.75)
        }
    }

    var body: some View {
        ZStack {
            FlintStreakCinematicStage(mood: mood)
                .accessibilityHidden(true)
            VStack(spacing: 0) {
                // Only the poster scrolls. Continue stays pinned so the single action can never be
                // pushed off-screen when Dynamic Type or a short sheet leaves less room.
                ScrollView {
                    poster
                }
                .streakScrollBounce()
                Button(FocusHistoryText.streakContinue, action: onContinue)
                    .buttonStyle(EmberButtonStyle())
                    .padding(.horizontal, 28)
                    .padding(.bottom, 28)
            }
        }
        .frame(maxWidth: .infinity)
        .background(Color(red: 0.039, green: 0.035, blue: 0.043))
        // The poster is one composition: a medium detent opens it truncated, so present it whole.
        .presentationDetents([.large])
        .presentationDragIndicator(.hidden)
        // Sibling sheets set this explicitly so the poster chrome never follows a light scheme.
        .preferredColorScheme(.dark)
        .accessibilityIdentifier("streak.celebration")
    }

    private var poster: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(FlintBrand.bone.opacity(0.18))
                .frame(width: 40, height: 5)
                .padding(.top, 10)
                .accessibilityHidden(true)
            FlintStreakEmber(mood: mood, size: 148)
                .padding(.top, 12)
            Text("\(streak)")
                .font(FlintFont.text(typeSize.isAccessibilitySize ? 52 : 68, weight: .semibold))
                .tracking(-1.4)
                .foregroundStyle(streakNumberFill(mood))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.top, 8)
            Text(FocusHistoryText.dayStreakLabel.uppercased())
                .font(FlintFont.text(11, weight: .medium))
                .tracking(2.0)
                .foregroundStyle(numberColor.opacity(0.92))
                .padding(.top, 6)
            Text(FocusHistoryText.streakMotivation(mood: mood, streak: streak))
                .font(FlintFont.text(15))
                .foregroundStyle(FlintBrand.mist.opacity(0.85))
                .multilineTextAlignment(.center)
                .padding(.top, 16)
                .padding(.horizontal, 28)
                .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity)
        // 460 pt is the designed poster height; it is a minimum on the scrolling content, so a
        // smaller sheet scrolls instead of clipping the number or the motivation.
        .frame(minHeight: 460)
    }
}

private extension View {
    /// Rubber-banding only when the poster overflows (iOS 16.4+); a no-op earlier.
    @ViewBuilder func streakScrollBounce() -> some View {
        if #available(iOS 16.4, *) {
            scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}
