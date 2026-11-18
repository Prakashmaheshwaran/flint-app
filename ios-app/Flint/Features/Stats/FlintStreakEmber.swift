import SwiftUI
import Foundation
import ImageIO
import UIKit

/// Visual mood for the streak flame — derived from the streak lifecycle only.
enum StreakEmberMood: Equatable {
    /// Streak alive and today completed: full opacity, full motion.
    case active
    /// Streak alive but not yet completed today: same frames and motion.
    case warning
    /// No active streak: same frames at lower opacity; motion only when the host forces it.
    case dormant
}

/// Frame duration of the streak atlas. Every frame is authored at 60 ms.
internal let streakFrameInterval: TimeInterval = 0.06

/// Single owner of the streak mood rule: Active = streak alive and a session completed
/// today; Warning = alive but not yet today; Dormant = no active streak. Callers keep
/// their own inputs; only the mapping is shared.
extension StreakEmberMood {
    static func forStreak(_ streak: Int, completedToday: Bool) -> StreakEmberMood {
        if streak <= 0 { return .dormant }
        return completedToday ? .active : .warning
    }
}

/// Frame index for the atlas loop. A missing atlas must not divide by zero.
internal func flameFrameIndex(elapsed: TimeInterval, frameCount: Int) -> Int {
    guard frameCount > 0 else { return 0 }
    let index = Int(elapsed / streakFrameInterval) % frameCount
    return index >= 0 ? index : 0
}

/// The flame only advances its clock when it can actually be seen.
internal func flameShouldAnimate(
    animate: Bool,
    reduceMotion: Bool,
    mood: StreakEmberMood,
    forceAnimate: Bool
) -> Bool {
    animate && !reduceMotion && (mood != .dormant || forceAnimate)
}

/// The streak flame: one frame of the bundled fire atlas, advanced on a 60 ms clock.
struct FlintStreakEmber: View {
    let mood: StreakEmberMood
    var size: CGFloat = 96
    /// When true, keeps the loop running for dormant moods (e.g. streak sheet).
    var forceAnimate: Bool = false
    /// When false the flame stays on the first frame (landing header badge).
    var animate: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let frames = StreakGifFrames.shared.frames
        TimelineView(.animation(paused: !shouldAnimate)) { timeline in
            let elapsed = shouldAnimate ? timeline.date.timeIntervalSince(startDate) : 0
            let index = flameFrameIndex(elapsed: elapsed, frameCount: frames.count)
            Group {
                if frames.isEmpty {
                    Image(systemName: "flame.fill")
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(FlintBrand.ember)
                } else {
                    Image(uiImage: frames[index])
                        .resizable()
                        .scaledToFit()
                }
            }
            .frame(width: size, height: size)
            .opacity(mood == .dormant ? 0.92 : 1)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private var shouldAnimate: Bool {
        flameShouldAnimate(
            animate: animate,
            reduceMotion: reduceMotion,
            mood: mood,
            forceAnimate: forceAnimate
        )
    }

    @State private var startDate = Date()
}

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
            FlintStreakEmber(mood: mood, size: 148, forceAnimate: true)
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

// MARK: - Streak sheet support

/// Warm cooling chrome for at-risk streak text (mirrors Android's `StreakCoolDeep`).
let flintStreakCoolDeep = Color(red: 0.757, green: 0.451, blue: 0.122)

/// Hero streak number fill: light top melting into the mood color.
func streakNumberFill(_ mood: StreakEmberMood) -> AnyShapeStyle {
    switch mood {
    case .active:
        return AnyShapeStyle(LinearGradient(
            colors: [Color(red: 1.0, green: 0.914, blue: 0.690), FlintBrand.ember],
            startPoint: .top, endPoint: .bottom
        ))
    case .warning:
        return AnyShapeStyle(LinearGradient(
            colors: [Color(red: 0.949, green: 0.784, blue: 0.573),
                     Color(red: 0.757, green: 0.451, blue: 0.122)],
            startPoint: .top, endPoint: .bottom
        ))
    case .dormant:
        return AnyShapeStyle(FlintBrand.mist.opacity(0.72))
    }
}

/// Quiet backdrop for the streak sheet.
struct FlintStreakCinematicStage: View {
    var mood: StreakEmberMood

    var body: some View {
        ZStack {
            Color(red: 0.027, green: 0.024, blue: 0.031)
            RadialGradient(
                colors: [
                    Color(red: 1.0, green: 0.55, blue: 0.18).opacity(mood == .active ? 0.14 : 0.08),
                    .clear
                ],
                center: UnitPoint(x: 0.5, y: 0.38),
                startRadius: 0,
                endRadius: 260
            )
        }
        .ignoresSafeArea()
    }
}

/// Loads and caches the animated streak GIF frames from the app bundle.
final class StreakGifFrames {
    static let shared = StreakGifFrames()
    let frames: [UIImage]

    private init() {
        // App: Bundle.main carries the resource. Test host: the resource sits in the app
        // bundle that hosts the class, so fall back to the class bundle instead of
        // silently reporting an empty frame list.
        let url = Bundle.main.url(forResource: "flint_streak_fire", withExtension: "gif")
            ?? Bundle(for: StreakGifFrames.self).url(forResource: "flint_streak_fire", withExtension: "gif")
        guard let url,
              let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            frames = []
            return
        }
        let count = CGImageSourceGetCount(source)
        var images: [UIImage] = []
        images.reserveCapacity(count)
        for index in 0..<count {
            if let cgImage = CGImageSourceCreateImageAtIndex(source, index, nil) {
                images.append(UIImage(cgImage: cgImage))
            }
        }
        frames = images
    }
}
