import SwiftUI
import Foundation
import FlintCore
import ImageIO
import UIKit

/// Visual mood for the cinematic ember — mapped from streak lifecycle.
enum StreakEmberMood: Equatable {
    /// Golden — streak alive and today completed; full colour, full motion.
    case active
    /// Same flame cooling — reduced heat, slower motion. Amber chrome.
    case warning
    /// Recognizable low-intensity flame; no animation.
    case dormant
}

/// Cross-platform alias for the flame display state (Android: StreakFlameState).
internal enum StreakFlameState {
    case active, atRisk, inactive
}

extension StreakEmberMood {
    internal func toFlameState() -> StreakFlameState {
        switch self {
        case .active: return .active
        case .warning: return .atRisk
        case .dormant: return .inactive
        }
    }
}

extension StreakFlameState {
    internal func toEmberMood() -> StreakEmberMood {
        switch self {
        case .active: return .active
        case .atRisk: return .warning
        case .inactive: return .dormant
        }
    }
}

private struct EmberPalette {
    let bodyStops: [Gradient.Stop]
    let innerStops: [Gradient.Stop]
    let coreStops: [Gradient.Stop]
    let hotBaseStops: [Gradient.Stop]
    let bloomColor: Color
    let bloomInnerColor: Color
    let contactColor: Color
    let intensity: CGFloat
    let hasFire: Bool

    static func forMood(_ mood: StreakEmberMood) -> EmberPalette {
        switch mood {
        case .active: return .active
        case .warning: return .warning
        case .dormant: return .dormant
        }
    }

    private static let active = EmberPalette(
        bodyStops: [
            .init(color: Color(red: 1.0, green: 0.769, blue: 0.0), location: 0.00),
            .init(color: Color(red: 1.0, green: 0.541, blue: 0.0), location: 0.18),
            .init(color: Color(red: 1.0, green: 0.302, blue: 0.0), location: 0.42),
            .init(color: Color(red: 0.898, green: 0.224, blue: 0.208), location: 0.70),
            .init(color: Color(red: 0.776, green: 0.157, blue: 0.157), location: 1.00),
        ],
        innerStops: [
            .init(color: Color(red: 1.0, green: 0.961, blue: 0.616), location: 0.00),
            .init(color: Color(red: 1.0, green: 0.835, blue: 0.310), location: 0.28),
            .init(color: Color(red: 1.0, green: 0.627, blue: 0.0), location: 0.58),
            .init(color: Color(red: 1.0, green: 0.427, blue: 0.0), location: 0.88),
            .init(color: .clear, location: 1.00),
        ],
        coreStops: [
            .init(color: .white, location: 0.00),
            .init(color: Color(red: 1.0, green: 0.992, blue: 0.906), location: 0.18),
            .init(color: Color(red: 1.0, green: 0.945, blue: 0.463), location: 0.42),
            .init(color: Color(red: 1.0, green: 0.835, blue: 0.310), location: 0.72),
            .init(color: Color(red: 1.0, green: 0.702, blue: 0.0).opacity(0), location: 1.00),
        ],
        hotBaseStops: [
            .init(color: Color.white.opacity(0.94), location: 0.00),
            .init(color: Color(red: 1.0, green: 0.992, blue: 0.906).opacity(0.80), location: 0.30),
            .init(color: Color(red: 1.0, green: 0.961, blue: 0.616).opacity(0.40), location: 0.60),
            .init(color: Color(red: 1.0, green: 0.757, blue: 0.027).opacity(0), location: 1.00),
        ],
        bloomColor: Color(red: 1.0, green: 0.427, blue: 0.0).opacity(0.24),
        bloomInnerColor: Color(red: 1.0, green: 0.769, blue: 0.0).opacity(0.18),
        contactColor: Color(red: 1.0, green: 0.341, blue: 0.133).opacity(0.13),
        intensity: 1.0,
        hasFire: true
    )

    private static let warning = EmberPalette(
        bodyStops: [
            .init(color: Color(red: 1.0, green: 0.824, blue: 0.478), location: 0.00),
            .init(color: Color(red: 0.957, green: 0.635, blue: 0.227), location: 0.22),
            .init(color: Color(red: 0.878, green: 0.439, blue: 0.157), location: 0.46),
            .init(color: Color(red: 0.753, green: 0.314, blue: 0.133), location: 0.72),
            .init(color: Color(red: 0.431, green: 0.165, blue: 0.078), location: 1.00),
        ],
        innerStops: [
            .init(color: Color(red: 1.0, green: 0.910, blue: 0.659), location: 0.00),
            .init(color: Color(red: 0.969, green: 0.784, blue: 0.353), location: 0.30),
            .init(color: Color(red: 0.878, green: 0.541, blue: 0.180), location: 0.60),
            .init(color: .clear, location: 1.00),
        ],
        coreStops: [
            .init(color: Color(red: 1.0, green: 0.973, blue: 0.882), location: 0.00),
            .init(color: Color(red: 1.0, green: 0.878, blue: 0.510), location: 0.30),
            .init(color: Color(red: 0.957, green: 0.659, blue: 0.251), location: 0.62),
            .init(color: Color(red: 0.816, green: 0.439, blue: 0.125).opacity(0), location: 1.00),
        ],
        hotBaseStops: [
            .init(color: Color(red: 1.0, green: 0.973, blue: 0.882).opacity(0.82), location: 0.00),
            .init(color: Color(red: 1.0, green: 0.878, blue: 0.510).opacity(0.53), location: 0.42),
            .init(color: Color(red: 0.816, green: 0.502, blue: 0.188).opacity(0), location: 1.00),
        ],
        bloomColor: Color(red: 0.878, green: 0.478, blue: 0.157).opacity(0.14),
        bloomInnerColor: Color(red: 0.878, green: 0.690, blue: 0.376).opacity(0.12),
        contactColor: Color(red: 0.784, green: 0.439, blue: 0.157).opacity(0.08),
        intensity: 0.82,
        hasFire: true
    )

    private static let dormant = EmberPalette(
        bodyStops: [
            .init(color: Color(red: 0.847, green: 0.604, blue: 0.290), location: 0.00),
            .init(color: Color(red: 0.722, green: 0.416, blue: 0.204), location: 0.25),
            .init(color: Color(red: 0.541, green: 0.267, blue: 0.149), location: 0.55),
            .init(color: Color(red: 0.369, green: 0.180, blue: 0.110), location: 0.80),
            .init(color: Color(red: 0.278, green: 0.133, blue: 0.086), location: 1.00),
        ],
        innerStops: [
            .init(color: Color(red: 0.878, green: 0.690, blue: 0.439).opacity(0.60), location: 0.00),
            .init(color: Color(red: 0.784, green: 0.533, blue: 0.251).opacity(0.40), location: 0.40),
            .init(color: Color(red: 0.565, green: 0.376, blue: 0.188).opacity(0.20), location: 0.75),
            .init(color: .clear, location: 1.00),
        ],
        coreStops: [
            .init(color: Color(red: 1.0, green: 0.847, blue: 0.627).opacity(0.40), location: 0.00),
            .init(color: Color(red: 0.784, green: 0.565, blue: 0.314).opacity(0.19), location: 0.45),
            .init(color: .clear, location: 1.00),
        ],
        hotBaseStops: [
            .init(color: Color(red: 0.910, green: 0.784, blue: 0.596).opacity(0.33), location: 0.00),
            .init(color: Color(red: 0.690, green: 0.533, blue: 0.345).opacity(0.13), location: 0.50),
            .init(color: .clear, location: 1.00),
        ],
        bloomColor: Color(red: 0.502, green: 0.314, blue: 0.188).opacity(0.06),
        bloomInnerColor: Color(red: 0.565, green: 0.439, blue: 0.314).opacity(0.05),
        contactColor: Color(red: 0.376, green: 0.251, blue: 0.157).opacity(0.03),
        intensity: 0.52,
        hasFire: true
    )
}

internal let STATIC_FLAME_SECONDS: Double = 0.72
internal let STATE_CROSSFADE_SECONDS: Double = 0.32
internal func twoPi() -> Double { 2.0 * .pi }

private struct FlameGeometry {
    let s: CGFloat
    let w: CGFloat
    let h: CGFloat
    let cx: CGFloat
    let baseY: CGFloat

    init(s: CGFloat) {
        self.s = s
        self.w = s * 0.76
        self.h = s * 0.90
        self.cx = s * 0.50
        self.baseY = s * 0.95
    }
}

internal func flameTick(_ elapsedSeconds: Double, fps: Double) -> Int64 { Int64(max(0.0, elapsedSeconds * fps)) }
internal func breathPhase01(_ t: Double) -> Double { (sin(t * 2.0 * .pi / 1.5) + 1.0) / 2.0 }
internal func reducedPhase(_ t: Double, rate: Double, freq: Double) -> Double {
    let x = (t / 6.0) * rate * freq
    return x.truncatingRemainder(dividingBy: 1.0).nonNegativeRemainder
}
private extension Double { var nonNegativeRemainder: Double { let r = truncatingRemainder(dividingBy: 1.0); return r < 0 ? r + 1.0 : r } }
internal func sparkSeed(_ t: Double, rate: Double, index: Int) -> Double {
    let base = reducedPhase(t, rate: rate, freq: 6.0 / 1.7)
    let s = (base + Double(index) * 0.37).truncatingRemainder(dividingBy: 1.0)
    let nn = s < 0 ? s + 1.0 : s
    return nn >= 1.0 ? 0.0 : nn
}
internal func tipFlickerDx(_ t: Double, rate: Double, halfW: Double) -> Double {
    sin(reducedPhase(t, rate: rate, freq: 2.1) * twoPi()) * halfW * 0.07 +
    sin(reducedPhase(t, rate: rate, freq: 3.7) * twoPi() + 0.8) * halfW * 0.03
}
internal func tiltDegrees(_ t: Double, rate: Double) -> Double {
    sin(reducedPhase(t, rate: rate, freq: 0.9) * twoPi() + 0.5) * 2.0
}
internal func bobOffset(_ t: Double, rate: Double, unit: Double) -> Double {
    sin(reducedPhase(t, rate: rate, freq: 2.2) * twoPi()) * unit * 0.005
}
internal func sparkAlpha(_ seed: Double, intensity: Double) -> Double { sin(seed * .pi) * 0.75 * intensity }
internal func frameElapsed(anchorSeconds: Double, startDate: Date, now: Date) -> Double { anchorSeconds + now.timeIntervalSince(startDate) }
internal func breathScale(_ breath01: Double) -> Double { 0.97 + 0.06 * breath01 }
internal func flameIntensity(_ mood: StreakEmberMood) -> Double {
    switch mood { case .active: return 1.0; case .warning: return 0.82; case .dormant: return 0.52 }
}
internal func fringeWidthsDp(sizeDp: Double) -> (Double, Double) {
    if sizeDp < 48 { return (0.7, 1.1) } else if sizeDp < 120 { return (1.0, 1.8) } else { return (1.4, 2.6) }
}

/// Reference fire-emoji outer silhouette (parity with Android StreakFlame).
private func buildOuter(geo: FlameGeometry, tipDx: CGFloat, sway: CGFloat, heightScale: CGFloat, widen: CGFloat, lickL: CGFloat, lickR: CGFloat, morph: CGFloat) -> Path {
    let w = geo.w, h = geo.h, cx = geo.cx, baseY = geo.baseY
    func x(_ fx: CGFloat, _ ft: CGFloat) -> CGFloat { cx + fx * w * widen + sway * ft * ft + tipDx * ft * ft }
    func y(_ ft: CGFloat) -> CGFloat { baseY - ft * h * heightScale }
    let tipX = 0.055 + morph * 0.010
    let notchIn = 0.030 * morph
    let rightBulge = 0.018 * morph
    let leftLobe = 0.012 * morph
    var p = Path()
    p.move(to: CGPoint(x: x(tipX, 1.00), y: y(1.00)))
    p.addCurve(to: CGPoint(x: x(0.43 + rightBulge, 0.60) + lickR * 0.85, y: y(0.60)),
               control1: CGPoint(x: x(0.19, 0.90), y: y(0.90)),
               control2: CGPoint(x: x(0.36 + rightBulge, 0.76) + lickR * 0.45, y: y(0.76)))
    p.addCurve(to: CGPoint(x: x(0.40, 0.16), y: y(0.16)),
               control1: CGPoint(x: x(0.50 + rightBulge, 0.46), y: y(0.46)),
               control2: CGPoint(x: x(0.50 + rightBulge * 0.7, 0.30), y: y(0.30)))
    p.addCurve(to: CGPoint(x: x(0.16, -0.012), y: y(-0.012)),
               control1: CGPoint(x: x(0.45, 0.09), y: y(0.09)),
               control2: CGPoint(x: x(0.34, 0.005), y: y(0.005)))
    p.addCurve(to: CGPoint(x: x(-0.26, 0.015), y: y(0.015)),
               control1: CGPoint(x: x(0.00, -0.018), y: y(-0.018)),
               control2: CGPoint(x: x(-0.14, -0.010), y: y(-0.010)))
    p.addCurve(to: CGPoint(x: x(-0.42 - leftLobe, 0.30), y: y(0.30)),
               control1: CGPoint(x: x(-0.41, 0.05), y: y(0.05)),
               control2: CGPoint(x: x(-0.47 - leftLobe, 0.17), y: y(0.17)))
    p.addCurve(to: CGPoint(x: x(-0.29, 0.52) + lickL * 0.55, y: y(0.52)),
               control1: CGPoint(x: x(-0.38 - leftLobe * 0.7, 0.39), y: y(0.39)),
               control2: CGPoint(x: x(-0.33, 0.46) + lickL * 0.35, y: y(0.46)))
    // Clear upper-left notch/dip
    p.addCurve(to: CGPoint(x: x(-0.08, 0.70), y: y(0.70)),
               control1: CGPoint(x: x(-0.23 + notchIn, 0.47), y: y(0.47)),
               control2: CGPoint(x: x(-0.14 + notchIn * 0.6, 0.58), y: y(0.58)))
    p.addCurve(to: CGPoint(x: x(tipX, 1.00), y: y(1.00)),
               control1: CGPoint(x: x(-0.03, 0.82), y: y(0.82)),
               control2: CGPoint(x: x(0.01, 0.93), y: y(0.93)))
    p.closeSubpath()
    return p
}

/// Inner yellow/orange flame tongue.
private func buildCore(geo: FlameGeometry, tipDx: CGFloat, sway: CGFloat, heightScale: CGFloat, widen: CGFloat, top: CGFloat, morph: CGFloat) -> Path {
    let w = geo.w, h = geo.h, cx = geo.cx, baseY = geo.baseY
    func x(_ fx: CGFloat, _ ft: CGFloat) -> CGFloat { cx + fx * w * widen + sway * ft * ft + tipDx * ft * ft * 0.65 }
    func y(_ ft: CGFloat) -> CGFloat { baseY - ft * h * heightScale }
    let tipY = min(top, 0.62) + morph * 0.03
    var p = Path()
    p.move(to: CGPoint(x: x(0.02 + morph * 0.01, tipY), y: y(tipY)))
    p.addCurve(to: CGPoint(x: x(0.18, 0.36), y: y(0.36)),
               control1: CGPoint(x: x(0.11, tipY - 0.10), y: y(tipY - 0.10)),
               control2: CGPoint(x: x(0.19, 0.46), y: y(0.46)))
    p.addCurve(to: CGPoint(x: x(0.02, 0.13), y: y(0.13)),
               control1: CGPoint(x: x(0.17, 0.27), y: y(0.27)),
               control2: CGPoint(x: x(0.11, 0.18), y: y(0.18)))
    p.addCurve(to: CGPoint(x: x(-0.16, 0.27), y: y(0.27)),
               control1: CGPoint(x: x(-0.06, 0.11), y: y(0.11)),
               control2: CGPoint(x: x(-0.14, 0.17), y: y(0.17)))
    p.addCurve(to: CGPoint(x: x(-0.06, 0.54), y: y(0.54)),
               control1: CGPoint(x: x(-0.17, 0.36), y: y(0.36)),
               control2: CGPoint(x: x(-0.12, 0.46), y: y(0.46)))
    p.addCurve(to: CGPoint(x: x(0.02 + morph * 0.01, tipY), y: y(tipY)),
               control1: CGPoint(x: x(-0.02, 0.58), y: y(0.58)),
               control2: CGPoint(x: x(0.00, tipY - 0.04), y: y(tipY - 0.04)))
    p.closeSubpath()
    return p
}

private func buildAsh(geo: FlameGeometry) -> Path { buildOuter(geo: geo, tipDx: 0, sway: 0, heightScale: 1, widen: 1, lickL: 0, lickR: 0, morph: 0) }

/// Glowing streak flame (emoji-like 🔥 silhouette in all states).
struct FlintStreakEmber: View {
    let mood: StreakEmberMood
    var milestoneFlash: CGFloat = 0
    var size: CGFloat = 96
    /// When true, keeps a gentle live/burning motion even for dormant (e.g. streak sheet).
    var forceAnimate: Bool = false
    /// When false, the GIF remains a static streak icon (landing header).
    var animate: Bool = true

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let frames = StreakGifFrames.shared.frames
        TimelineView(.animation(paused: !shouldAnimate)) { timeline in
            let elapsed = shouldAnimate ? timeline.date.timeIntervalSince(startDate) : 0
            let index = frames.isEmpty ? 0 : Int(elapsed / 0.06) % frames.count
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

    private var shouldAnimate: Bool { animate && !reduceMotion && (mood != .dormant || forceAnimate) }

    @State private var startDate = Date()
    @State private var anchorSeconds = STATIC_FLAME_SECONDS
    private func animationTime(_ now: Date) -> Double { frameElapsed(anchorSeconds: anchorSeconds, startDate: startDate, now: now) }

    private func drawEmber(in context: inout GraphicsContext, size s: CGFloat, elapsed t: Double) {
        let geo = FlameGeometry(s: s)
        let palette = EmberPalette.forMood(mood)
        let compact = s < 48
        let emberCount = s >= 128 ? 3 : (s >= 64 ? 2 : (s >= 36 ? 1 : 0))
        let animate = shouldAnimate
        let baseRate: Double = (mood == .dormant && forceAnimate) ? 0.72 : (mood == .dormant ? 0.45 : (mood == .warning ? 0.6 : 1.0))
        let rate = animate ? baseRate : 0.0
        let baseIntensity = flameIntensity(mood)
        let intensity = (forceAnimate && mood == .dormant) ? 0.62 : baseIntensity

        let tipDx = animate ? tipFlickerDx(t, rate: rate, halfW: Double(geo.w) * 0.5) * intensity : 0
        let tilt = animate ? tiltDegrees(t, rate: rate) * intensity : 0
        let bob = animate ? bobOffset(t, rate: rate, unit: Double(geo.s)) * intensity : 0
        let breath01 = animate ? breathPhase01(t) : 0.5
        let breath = animate ? breathScale(breath01) : 1.0
        let sway = animate ? (sin(reducedPhase(t, rate: rate, freq: 1.0) * twoPi()) * 0.022 +
            sin(reducedPhase(t, rate: rate, freq: 2.4) * twoPi() + 1.3) * 0.011) * Double(geo.w) * intensity : 0
        let lickL = animate ? sin(reducedPhase(t, rate: rate, freq: 1.5) * twoPi() + 1.0) * Double(geo.w) * 0.032 * intensity : 0
        let lickR = animate ? sin(reducedPhase(t, rate: rate, freq: 2.0) * twoPi() + 2.2) * Double(geo.w) * 0.026 * intensity : 0
        let morph = animate ? CGFloat(sin(reducedPhase(t, rate: rate, freq: 1.15) * twoPi() + 0.6)) : 0
        let innerDance = animate ? sin(reducedPhase(t, rate: rate, freq: 2.6) * twoPi() + 1.9) : 0
        let innerPulse = animate ? (0.98 + 0.045 * sin(reducedPhase(t, rate: rate, freq: 2.1) * twoPi())) : 1.0
        let flicker = animate ? (0.94 + 0.06 * ((sin(reducedPhase(t, rate: rate, freq: 3.4) * twoPi()) + 1.0) * 0.5)) : 1.0
        let heightScale = 1.0 + (breath - 1.0) * 0.60 + (animate ? innerDance * 0.012 : 0)
        let widen = 1.0 + (breath - 1.0) * 0.40
        let flash = Double(milestoneFlash)
        let coreTop: CGFloat = CGFloat((mood == .active ? 0.62 : (mood == .warning ? 0.58 : 0.55)) + innerDance * 0.02)

        let glowPulse = animate ? 0.90 + 0.10 * ((sin(reducedPhase(t, rate: rate, freq: 0.9) * twoPi() + 0.4) + 1.0) * 0.5) : 1.0
        let bloomA = (mood == .active ? (compact ? 0.16 : 0.24) : (mood == .warning ? (compact ? 0.10 : 0.15) : (compact ? 0.06 : 0.09))) * glowPulse + flash * 0.06
        let bloomR = geo.h * (0.92 + CGFloat(breath - 1.0) * 0.6)
        context.fill(Path(ellipseIn: CGRect(x: geo.cx - bloomR, y: geo.baseY - geo.h * 0.43 - bloomR, width: bloomR * 2, height: bloomR * 2)), with: .color(palette.bloomColor.opacity(min(0.35, bloomA))))
        let bloomIR = geo.h * (0.48 + CGFloat(breath - 1.0) * 0.4)
        context.fill(Path(ellipseIn: CGRect(x: geo.cx - bloomIR, y: geo.baseY - geo.h * 0.38 - bloomIR, width: bloomIR * 2, height: bloomIR * 2)), with: .color(palette.bloomInnerColor.opacity(min(0.30, bloomA * 0.85))))
        if !compact {
            context.fill(Path(ellipseIn: CGRect(x: geo.cx - geo.w * 0.52, y: geo.baseY - geo.h * 0.03 - geo.h * 0.05, width: geo.w * 1.04, height: geo.h * 0.10)), with: .color(palette.contactColor))
        }

        var flame = context
        flame.translateBy(x: 0, y: CGFloat(bob))
        flame.translateBy(x: geo.cx, y: geo.baseY * 0.92)
        flame.rotate(by: .degrees(tilt))
        flame.translateBy(x: -geo.cx, y: -geo.baseY * 0.92)

        let outer = buildOuter(geo: geo, tipDx: CGFloat(tipDx), sway: CGFloat(sway), heightScale: CGFloat(heightScale), widen: CGFloat(widen), lickL: CGFloat(lickL), lickR: CGFloat(lickR), morph: morph)
        flame.opacity = flicker
        flame.fill(outer, with: .linearGradient(Gradient(stops: palette.bodyStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h)))
        flame.opacity = 1.0

        let innerSway = sway * 0.78 + (animate ? sin(reducedPhase(t, rate: rate, freq: 2.8) * twoPi() + 0.7) * Double(geo.w) * 0.008 * intensity : 0)
        let innerTipDx = tipDx * 0.75 + (animate ? sin(reducedPhase(t, rate: rate, freq: 3.1) * twoPi() + 1.4) * Double(geo.w) * 0.010 * intensity : 0)
        let innerPath = buildCore(geo: geo, tipDx: CGFloat(innerTipDx), sway: CGFloat(innerSway), heightScale: CGFloat(heightScale * innerPulse), widen: CGFloat(widen * 0.97), top: coreTop, morph: CGFloat(innerDance))
        let innerAlpha = 0.92 + 0.08 * ((sin(reducedPhase(t, rate: rate, freq: 2.9) * twoPi() + 0.9) + 1.0) * 0.5)
        flame.opacity = min(1.0, max(0.86, innerAlpha))
        flame.fill(innerPath, with: .linearGradient(Gradient(stops: palette.innerStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h * 0.10), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h * 0.88)))
        flame.opacity = 1.0

        let hotA = ((mood == .active ? (compact ? 0.52 : 0.62) : (mood == .warning ? (compact ? 0.34 : 0.42) : (compact ? 0.20 : 0.26))) + (breath - 1.0) * 1.1 + flash * 0.12)
        let coreCx = geo.cx + geo.w * 0.015 + CGFloat(sway) * 0.12
        let coreCy = geo.baseY - geo.h * CGFloat(0.24 - innerDance * 0.015)
        let hotRect = CGRect(x: coreCx - geo.w * 0.145, y: coreCy - geo.h * 0.075 - geo.h * 0.085, width: geo.w * 0.29, height: geo.h * 0.17)
        flame.fill(Path(ellipseIn: hotRect), with: .radialGradient(Gradient(stops: palette.hotBaseStops), center: CGPoint(x: coreCx, y: coreCy), startRadius: 0, endRadius: geo.w * 0.24))
        let coreRect = CGRect(x: coreCx - geo.w * 0.095, y: coreCy - geo.h * 0.11 - geo.h * 0.08, width: geo.w * 0.19, height: geo.h * 0.16)
        flame.fill(Path(ellipseIn: coreRect), with: .radialGradient(Gradient(stops: palette.coreStops), center: CGPoint(x: coreCx, y: coreCy - geo.h * 0.02), startRadius: 0, endRadius: geo.h * 0.18))

        if animate && emberCount > 0 {
            drawEmbers(in: &flame, geo: geo, t: t, rate: rate, intensity: intensity, count: emberCount)
        }
    }

    private func drawAsh(in context: inout GraphicsContext, geo: FlameGeometry, palette: EmberPalette, compact: Bool, flash: Double) {
        let outer = buildOuter(geo: geo, tipDx: 0, sway: 0, heightScale: 1, widen: 1, lickL: 0, lickR: 0, morph: 0)
        context.fill(outer, with: .linearGradient(Gradient(stops: palette.bodyStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h)))
        let inner = buildCore(geo: geo, tipDx: 0, sway: 0, heightScale: 1, widen: 1, top: 0.60, morph: 0)
        context.fill(inner, with: .linearGradient(Gradient(stops: palette.innerStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h * 0.10), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h * 0.88)))
    }

    private func drawEmbers(in context: inout GraphicsContext, geo: FlameGeometry, t: Double, rate: Double, intensity: CGFloat, count: Int) {
        for i in 0..<count {
            let seed = sparkSeed(t, rate: rate, index: i)
            let alpha = sparkAlpha(seed, intensity: Double(intensity))
            if alpha <= 0.03 { continue }
            let y = geo.baseY - geo.h * CGFloat(0.44 + seed * 0.46)
            let x = geo.cx + CGFloat(sin(seed * 5.0 + Double(i) * 2.1)) * geo.w * 0.10 + geo.w * 0.02
            let r = geo.s * CGFloat(0.005 + 0.002 * Double(i % 2))
            context.fill(Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)), with: .color(Color(red: 1.0, green: 0.945, blue: 0.463).opacity(alpha)))
        }
    }
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


// MARK: - Streak sheet support (consolidated)

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
        guard let url = Bundle.main.url(forResource: "flint_streak_fire", withExtension: "gif"),
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
