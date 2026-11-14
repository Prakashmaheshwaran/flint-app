import SwiftUI
import Foundation
import FlintCore

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
        self.cx = s * 0.5
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

/// Emoji-like 🔥 outer silhouette.
private func buildOuter(geo: FlameGeometry, tipDx: CGFloat, sway: CGFloat, heightScale: CGFloat, widen: CGFloat, lickL: CGFloat, lickR: CGFloat) -> Path {
    let w = geo.w, h = geo.h, cx = geo.cx, baseY = geo.baseY
    func x(_ fx: CGFloat, _ ft: CGFloat) -> CGFloat { cx + fx * w * widen + sway * ft * ft + tipDx * ft * ft }
    func y(_ ft: CGFloat) -> CGFloat { baseY - ft * h * heightScale }
    var p = Path()
    p.move(to: CGPoint(x: x(0.04, 1.00), y: y(1.00)))
    p.addCurve(to: CGPoint(x: x(0.36, 0.58) + lickR * 0.7, y: y(0.58)), control1: CGPoint(x: x(0.14, 0.90), y: y(0.90)), control2: CGPoint(x: x(0.30, 0.72) + lickR * 0.5, y: y(0.72)))
    p.addCurve(to: CGPoint(x: x(0.40, 0.22), y: y(0.22)), control1: CGPoint(x: x(0.42, 0.46), y: y(0.46)), control2: CGPoint(x: x(0.46, 0.34), y: y(0.34)))
    p.addCurve(to: CGPoint(x: x(0.22, 0.00), y: y(0.00)), control1: CGPoint(x: x(0.46, 0.14), y: y(0.14)), control2: CGPoint(x: x(0.38, 0.04), y: y(0.04)))
    p.addCurve(to: CGPoint(x: x(-0.22, 0.00), y: y(0.00)), control1: CGPoint(x: x(0.08, -0.01), y: y(-0.01)), control2: CGPoint(x: x(-0.08, -0.01), y: y(-0.01)))
    p.addCurve(to: CGPoint(x: x(-0.38, 0.28), y: y(0.28)), control1: CGPoint(x: x(-0.38, 0.04), y: y(0.04)), control2: CGPoint(x: x(-0.44, 0.16), y: y(0.16)))
    p.addCurve(to: CGPoint(x: x(-0.24, 0.56) + lickL * 0.7, y: y(0.56)), control1: CGPoint(x: x(-0.34, 0.38), y: y(0.38)), control2: CGPoint(x: x(-0.30, 0.46) + lickL * 0.4, y: y(0.46)))
    p.addCurve(to: CGPoint(x: x(-0.02, 0.92), y: y(0.92)), control1: CGPoint(x: x(-0.18, 0.68) + lickL * 0.4, y: y(0.68)), control2: CGPoint(x: x(-0.08, 0.82), y: y(0.82)))
    p.addCurve(to: CGPoint(x: x(0.04, 1.00), y: y(1.00)), control1: CGPoint(x: x(0.00, 0.96), y: y(0.96)), control2: CGPoint(x: x(0.02, 0.985), y: y(0.985)))
    p.closeSubpath()
    return p
}

/// Inner yellow/orange flame tongue.
private func buildCore(geo: FlameGeometry, tipDx: CGFloat, sway: CGFloat, heightScale: CGFloat, widen: CGFloat, top: CGFloat) -> Path {
    let w = geo.w, h = geo.h, cx = geo.cx, baseY = geo.baseY
    func x(_ fx: CGFloat, _ ft: CGFloat) -> CGFloat { cx + fx * w * widen * 0.92 + sway * ft * ft + tipDx * ft * ft * 0.75 }
    func y(_ ft: CGFloat) -> CGFloat { baseY - ft * h * heightScale }
    let tipY = min(top, 0.90)
    var p = Path()
    p.move(to: CGPoint(x: x(0.03, tipY), y: y(tipY)))
    p.addCurve(to: CGPoint(x: x(0.18, 0.42), y: y(0.42)), control1: CGPoint(x: x(0.10, tipY - 0.10), y: y(tipY - 0.10)), control2: CGPoint(x: x(0.18, 0.56), y: y(0.56)))
    p.addCurve(to: CGPoint(x: x(0.04, 0.12), y: y(0.12)), control1: CGPoint(x: x(0.20, 0.30), y: y(0.30)), control2: CGPoint(x: x(0.14, 0.18), y: y(0.18)))
    p.addCurve(to: CGPoint(x: x(-0.14, 0.28), y: y(0.28)), control1: CGPoint(x: x(-0.04, 0.10), y: y(0.10)), control2: CGPoint(x: x(-0.12, 0.16), y: y(0.16)))
    p.addCurve(to: CGPoint(x: x(-0.06, 0.64), y: y(0.64)), control1: CGPoint(x: x(-0.16, 0.40), y: y(0.40)), control2: CGPoint(x: x(-0.12, 0.52), y: y(0.52)))
    p.addCurve(to: CGPoint(x: x(0.03, tipY), y: y(tipY)), control1: CGPoint(x: x(-0.02, 0.74), y: y(0.74)), control2: CGPoint(x: x(0.00, tipY - 0.08), y: y(tipY - 0.08)))
    p.closeSubpath()
    return p
}

private func buildAsh(geo: FlameGeometry) -> Path { buildOuter(geo: geo, tipDx: 0, sway: 0, heightScale: 1, widen: 1, lickL: 0, lickR: 0) }

/// Glowing streak flame (emoji-like 🔥 silhouette in all states).
struct FlintStreakEmber: View {
    let mood: StreakEmberMood
    var milestoneFlash: CGFloat = 0
    var size: CGFloat = 96

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: nil, paused: !shouldAnimate)) { timeline in
            let t = shouldAnimate ? animationTime(timeline.date) : STATIC_FLAME_SECONDS
            Canvas { context, canvasSize in
                drawEmber(in: &context, size: min(canvasSize.width, canvasSize.height), elapsed: t)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }

    private var shouldAnimate: Bool { !reduceMotion && mood != .dormant }

    @State private var startDate = Date()
    @State private var anchorSeconds = STATIC_FLAME_SECONDS
    private func animationTime(_ now: Date) -> Double { frameElapsed(anchorSeconds: anchorSeconds, startDate: startDate, now: now) }

    private func drawEmber(in context: inout GraphicsContext, size s: CGFloat, elapsed t: Double) {
        let geo = FlameGeometry(s: s)
        let palette = EmberPalette.forMood(mood)
        let compact = s < 48
        let emberCount = s >= 128 ? 3 : (s >= 64 ? 2 : (s >= 36 ? 1 : 0))
        let animate = shouldAnimate
        let rate = mood == .warning ? 0.6 : 1.0
        let intensity = palette.intensity

        let tipDx = animate ? tipFlickerDx(t, rate: rate, halfW: Double(geo.w) * 0.5) * intensity : 0
        let tilt = animate ? tiltDegrees(t, rate: rate) * intensity : 0
        let bob = animate ? bobOffset(t, rate: rate, unit: Double(geo.s)) * intensity : 0
        let breath01 = animate ? breathPhase01(t) : 0.5
        let breath = animate ? breathScale(breath01) : 1.0
        let sway = animate ? (sin(reducedPhase(t, rate: rate, freq: 1.0) * twoPi()) * 0.020 +
            sin(reducedPhase(t, rate: rate, freq: 2.3) * twoPi() + 1.3) * 0.010) * Double(geo.w) * intensity : 0
        let lickL = animate ? sin(reducedPhase(t, rate: rate, freq: 1.4) * twoPi() + 1.0) * Double(geo.w) * 0.030 * intensity : 0
        let lickR = animate ? sin(reducedPhase(t, rate: rate, freq: 1.9) * twoPi() + 2.2) * Double(geo.w) * 0.024 * intensity : 0
        let innerPulse = animate ? (0.985 + 0.03 * sin(reducedPhase(t, rate: rate, freq: 1.8) * twoPi())) : 1.0
        let heightScale = 1.0 + (breath - 1.0) * 0.55
        let widen = 1.0 + (breath - 1.0) * 0.45
        let flash = Double(milestoneFlash)
        let coreTop: CGFloat = mood == .active ? 0.72 : (mood == .warning ? 0.66 : 0.60)

        let bloomA = (mood == .active ? (compact ? 0.13 : 0.20) : (mood == .warning ? (compact ? 0.09 : 0.13) : (compact ? 0.05 : 0.07))) + flash * 0.06
        let bloomR = geo.h * 0.95
        context.fill(Path(ellipseIn: CGRect(x: geo.cx - bloomR, y: geo.baseY - geo.h * 0.42 - bloomR, width: bloomR * 2, height: bloomR * 2)), with: .color(palette.bloomColor.opacity(bloomA)))
        let bloomIR = geo.h * 0.50
        context.fill(Path(ellipseIn: CGRect(x: geo.cx - bloomIR, y: geo.baseY - geo.h * 0.38 - bloomIR, width: bloomIR * 2, height: bloomIR * 2)), with: .color(palette.bloomInnerColor.opacity(bloomA * 0.85)))
        if !compact {
            context.fill(Path(ellipseIn: CGRect(x: geo.cx - geo.w * 0.50, y: geo.baseY - geo.h * 0.03 - geo.h * 0.05, width: geo.w * 1.00, height: geo.h * 0.10)), with: .color(palette.contactColor))
        }

        var flame = context
        flame.translateBy(x: 0, y: CGFloat(bob))
        flame.translateBy(x: geo.cx, y: geo.baseY * 0.90)
        flame.rotate(by: .degrees(tilt))
        flame.translateBy(x: -geo.cx, y: -geo.baseY * 0.90)

        let outer = buildOuter(geo: geo, tipDx: CGFloat(tipDx), sway: CGFloat(sway), heightScale: CGFloat(heightScale), widen: CGFloat(widen), lickL: CGFloat(lickL), lickR: CGFloat(lickR))
        flame.fill(outer, with: .linearGradient(Gradient(stops: palette.bodyStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h)))

        let inner = buildCore(geo: geo, tipDx: CGFloat(tipDx * 0.85), sway: CGFloat(sway * 0.80), heightScale: CGFloat(heightScale * innerPulse), widen: CGFloat(widen * 0.97), top: coreTop)
        flame.fill(inner, with: .linearGradient(Gradient(stops: palette.innerStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h * 0.10), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h * 0.88)))

        let hotA = ((mood == .active ? (compact ? 0.42 : 0.52) : (mood == .warning ? (compact ? 0.28 : 0.34) : (compact ? 0.16 : 0.20))) + (breath - 1.0) * 1.2 + flash * 0.12)
        let coreCx = geo.cx + geo.w * 0.02 + CGFloat(sway) * 0.15
        let hotRect = CGRect(x: coreCx - geo.w * 0.13, y: geo.baseY - geo.h * 0.26 - geo.h * 0.09, width: geo.w * 0.26, height: geo.h * 0.18)
        flame.fill(Path(ellipseIn: hotRect), with: .radialGradient(Gradient(stops: palette.hotBaseStops), center: CGPoint(x: coreCx, y: geo.baseY - geo.h * 0.20), startRadius: 0, endRadius: geo.w * 0.26))
        let coreRect = CGRect(x: coreCx - geo.w * 0.09, y: geo.baseY - geo.h * 0.30 - geo.h * 0.08, width: geo.w * 0.18, height: geo.h * 0.16)
        flame.fill(Path(ellipseIn: coreRect), with: .radialGradient(Gradient(stops: palette.coreStops), center: CGPoint(x: coreCx + geo.w * 0.00, y: geo.baseY - geo.h * 0.28), startRadius: 0, endRadius: geo.h * 0.18))

        if animate && emberCount > 0 {
            drawEmbers(in: &flame, geo: geo, t: t, rate: rate, intensity: intensity, count: emberCount)
        }
    }

    private func drawAsh(in context: inout GraphicsContext, geo: FlameGeometry, palette: EmberPalette, compact: Bool, flash: Double) {
        let outer = buildOuter(geo: geo, tipDx: 0, sway: 0, heightScale: 1, widen: 1, lickL: 0, lickR: 0)
        context.fill(outer, with: .linearGradient(Gradient(stops: palette.bodyStops), startPoint: CGPoint(x: geo.cx, y: geo.baseY), endPoint: CGPoint(x: geo.cx, y: geo.baseY - geo.h)))
        let inner = buildCore(geo: geo, tipDx: 0, sway: 0, heightScale: 1, widen: 1, top: 0.60)
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
