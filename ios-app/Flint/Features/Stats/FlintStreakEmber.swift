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

private struct EmberPalette {
    let coreHot: Color
    let coreMid: Color
    let coreDeep: Color
    let shellTop: Color
    let shellMid: Color
    let shellBase: Color
    let rim: Color
    let halo: Color
    let hasFire: Bool
    let shellStops: [Gradient.Stop]
    let coreStops: [Gradient.Stop]

    static func forMood(_ mood: StreakEmberMood) -> EmberPalette {
        switch mood {
        case .active: return .active
        case .warning: return .warning
        case .dormant: return .dormant
        }
    }

    private static let active = EmberPalette(
        coreHot: Color(red: 1.0, green: 0.965, blue: 0.839),
        coreMid: Color(red: 1.0, green: 0.682, blue: 0.239),
        coreDeep: Color(red: 0.941, green: 0.392, blue: 0.078),
        shellTop: Color(red: 1.0, green: 0.953, blue: 0.863).opacity(0.30),
        shellMid: Color(red: 1.0, green: 0.941, blue: 0.808).opacity(0.58),
        shellBase: Color(red: 1.0, green: 0.906, blue: 0.761).opacity(0.95),
        rim: Color(red: 1.0, green: 0.992, blue: 0.965),
        halo: Color(red: 0.961, green: 0.651, blue: 0.137),
        hasFire: true,
        shellStops: [
            .init(color: Color(red: 1.0, green: 0.953, blue: 0.863).opacity(0.30), location: 0),
            .init(color: Color(red: 1.0, green: 0.941, blue: 0.808).opacity(0.58), location: 0.45),
            .init(color: Color(red: 1.0, green: 0.906, blue: 0.761).opacity(0.95), location: 1),
        ],
        coreStops: [
            .init(color: Color(red: 1.0, green: 0.965, blue: 0.839), location: 0),
            .init(color: Color(red: 1.0, green: 0.682, blue: 0.239), location: 0.50),
            .init(color: Color(red: 0.941, green: 0.392, blue: 0.078).opacity(0.55), location: 0.85),
            .init(color: Color(red: 0.941, green: 0.392, blue: 0.078).opacity(0), location: 1),
        ]
    )

    private static let warning = EmberPalette(
        coreHot: Color(red: 1.0, green: 0.890, blue: 0.722),
        coreMid: Color(red: 0.949, green: 0.635, blue: 0.290),
        coreDeep: Color(red: 0.698, green: 0.396, blue: 0.165),
        shellTop: Color(red: 1.0, green: 0.910, blue: 0.784).opacity(0.30),
        shellMid: Color(red: 0.965, green: 0.867, blue: 0.733).opacity(0.58),
        shellBase: Color(red: 0.922, green: 0.796, blue: 0.620).opacity(0.95),
        rim: Color(red: 1.0, green: 0.953, blue: 0.875),
        halo: Color(red: 0.878, green: 0.541, blue: 0.204),
        hasFire: true,
        shellStops: [
            .init(color: Color(red: 1.0, green: 0.910, blue: 0.784).opacity(0.30), location: 0),
            .init(color: Color(red: 0.965, green: 0.867, blue: 0.733).opacity(0.58), location: 0.45),
            .init(color: Color(red: 0.965, green: 0.843, blue: 0.702).opacity(0.95), location: 1),
        ],
        coreStops: [
            .init(color: Color(red: 1.0, green: 0.890, blue: 0.722), location: 0),
            .init(color: Color(red: 0.949, green: 0.635, blue: 0.290), location: 0.50),
            .init(color: Color(red: 0.698, green: 0.396, blue: 0.165).opacity(0.55), location: 0.85),
            .init(color: Color(red: 0.698, green: 0.396, blue: 0.165).opacity(0), location: 1),
        ]
    )

    private static let dormant = EmberPalette(
        coreHot: Color(red: 0.949, green: 0.949, blue: 0.957),
        coreMid: Color(red: 0.902, green: 0.902, blue: 0.918),
        coreDeep: Color(red: 0.859, green: 0.859, blue: 0.871),
        shellTop: Color(red: 0.949, green: 0.949, blue: 0.957).opacity(0.30),
        shellMid: Color(red: 0.902, green: 0.902, blue: 0.918).opacity(0.58),
        shellBase: Color(red: 0.859, green: 0.859, blue: 0.871).opacity(0.90),
        rim: Color(red: 1.0, green: 1.0, blue: 1.0),
        halo: Color(red: 0.718, green: 0.682, blue: 0.624),
        hasFire: false,
        shellStops: [
            .init(color: Color(red: 0.949, green: 0.949, blue: 0.957).opacity(0.30), location: 0),
            .init(color: Color(red: 0.902, green: 0.902, blue: 0.918).opacity(0.58), location: 0.45),
            .init(color: Color(red: 0.859, green: 0.859, blue: 0.871).opacity(0.90), location: 1),
        ],
        coreStops: [
            .init(color: Color(red: 0.949, green: 0.949, blue: 0.957), location: 0),
            .init(color: Color(red: 0.902, green: 0.902, blue: 0.918), location: 1),
        ]
    )
}

/// Warm cooling chrome for at-risk streak text.
let flintStreakCoolDeep = Color(red: 0.757, green: 0.451, blue: 0.122)

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

/// Reference-based streak flame — Canvas only.
struct FlintStreakEmber: View {
    var mood: StreakEmberMood
    var size: CGFloat = 168
    var milestoneFlash: CGFloat = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    @State private var visible = true
    @State private var shownMood: StreakEmberMood
    @State private var previousMood: StreakEmberMood?
    @State private var fadeStart: Date?
    @State private var phaseAnchor: Double = STATIC_FLAME_SECONDS
    @State private var anchorUptime: Double = ProcessInfo.processInfo.systemUptime
    @State private var pausedPhase: Double = STATIC_FLAME_SECONDS

    init(mood: StreakEmberMood, size: CGFloat = 168, milestoneFlash: CGFloat = 0) {
        self.mood = mood
        self.size = size
        self.milestoneFlash = milestoneFlash
        _shownMood = State(initialValue: mood)
    }

    private var animate: Bool {
        !reduceMotion && shownMood != .dormant && scenePhase == .active && visible
    }

    private var timelineActive: Bool {
        animate || (previousMood != nil && !reduceMotion && scenePhase == .active && visible)
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !timelineActive)) { context in
            let progress = fadeProgress(at: context.date)
            let fading = progress < 1
            let live = animate || (fading && !reduceMotion && scenePhase == .active && visible)
            let seconds = live
                ? phaseAnchor + (ProcessInfo.processInfo.systemUptime - anchorUptime)
                : (reduceMotion ? STATIC_FLAME_SECONDS : pausedPhase)
            let breath = CGFloat(1.0 + 0.04 * sin(seconds * 2.0 * Double.pi / 1.5))
            let flash = min(1, max(0, milestoneFlash))
            Canvas { ctx, canvasSize in
                var incomingOpacity: Double = 1
                if let prev = previousMood, fading {
                    ctx.opacity = Double(1 - progress)
                    drawEmber(context: &ctx, size: canvasSize, mood: prev, phase: CGFloat(seconds), breath: breath, flash: flash)
                    incomingOpacity = Double(progress)
                }
                ctx.opacity = incomingOpacity
                drawEmber(context: &ctx, size: canvasSize, mood: shownMood, phase: CGFloat(seconds), breath: breath, flash: flash)
                ctx.opacity = 1
            }
        }
        .frame(width: size, height: size)
        .background(
            GeometryReader { proxy in
                Color.clear
                    .onAppear { updateVisibility(proxy.frame(in: .global)) }
                    .onChange(of: proxy.frame(in: .global)) { frame in updateVisibility(frame) }
            }
        )
        .onChange(of: mood) { newMood in
            guard newMood != shownMood else { return }
            if reduceMotion {
                shownMood = newMood
                previousMood = nil
                fadeStart = nil
            } else {
                previousMood = shownMood
                shownMood = newMood
                fadeStart = Date()
            }
        }
        .task(id: fadeStart) {
            guard fadeStart != nil else { return }
            try? await Task.sleep(nanoseconds: 360_000_000)
            previousMood = nil
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { previousMood = nil }
        }
        .onChange(of: timelineActive) { active in
            let uptime = ProcessInfo.processInfo.systemUptime
            if active {
                phaseAnchor = reduceMotion ? STATIC_FLAME_SECONDS : pausedPhase
                anchorUptime = uptime
            } else {
                pausedPhase = reduceMotion ? STATIC_FLAME_SECONDS : phaseAnchor + (uptime - anchorUptime)
            }
        }
        .onDisappear { previousMood = nil }
        .accessibilityHidden(true)
    }

    private func fadeProgress(at date: Date) -> CGFloat {
        guard let start = fadeStart, !reduceMotion else { return 1 }
        let p = date.timeIntervalSince(start) / STATE_CROSSFADE_SECONDS
        return CGFloat(min(max(p, 0), 1))
    }

    private func updateVisibility(_ frame: CGRect) {
        guard frame.width > 0, frame.height > 0 else { return }
        guard let windowBounds = Self.hostWindowBounds() else { return }
        let inter = frame.intersection(windowBounds)
        let fraction = (inter.isNull || inter.isEmpty) ? 0 : (inter.width * inter.height) / (frame.width * frame.height)
        visible = fraction > 0.05
    }

    private static func hostWindowBounds() -> CGRect? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        if let keyWindow = windows.first(where: { $0.isKeyWindow }) { return keyWindow.bounds }
        return windows.first?.bounds
    }
}

private let STATIC_FLAME_SECONDS: Double = 0.72
private let STATE_CROSSFADE_SECONDS: Double = 0.32

struct FlintStreakCinematicStage: View {
    var mood: StreakEmberMood
    var body: some View {
        Canvas { ctx, size in
            drawStage(context: &ctx, size: size, mood: mood, phase: 0.35)
        }
    }
}

private func fringeWidths(sizeDP: CGFloat) -> (CGFloat, CGFloat) {
    if sizeDP < 48 { return (0.7, 1.1) }
    if sizeDP < 120 { return (1.0, 1.8) }
    return (1.4, 2.6)
}

private func rgb(_ hex: UInt32, _ opacity: Double = 1) -> Color {
    Color(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255, opacity: opacity)
}

private let activeBodyStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFD24A), location: 0),
    .init(color: rgb(0xFFA02A), location: 0.18),
    .init(color: rgb(0xFF6A1A), location: 0.45),
    .init(color: rgb(0xE63A10), location: 0.72),
    .init(color: rgb(0xC41E08), location: 1),
]
private let atRiskBodyStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFC86A), location: 0),
    .init(color: rgb(0xF2A23A), location: 0.22),
    .init(color: rgb(0xD97828), location: 0.46),
    .init(color: rgb(0xB85A24), location: 0.72),
    .init(color: rgb(0x8C431C), location: 0.90),
    .init(color: rgb(0x6A3318), location: 1),
]
private let dormantBodyStops: [Gradient.Stop] = [
    .init(color: rgb(0xB8A898), location: 0),
    .init(color: rgb(0x9C8C82), location: 0.25),
    .init(color: rgb(0x7A6E68), location: 0.55),
    .init(color: rgb(0x5C524E), location: 0.80),
    .init(color: rgb(0x433C3A), location: 1),
]
private let activeCoreStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFFFFF), location: 0),
    .init(color: rgb(0xFFF6C8), location: 0.20),
    .init(color: rgb(0xFFE08A), location: 0.48),
    .init(color: rgb(0xFFB020), location: 0.78),
    .init(color: rgb(0xFF8A00, 0), location: 1),
]
private let atRiskCoreStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFF0D0), location: 0),
    .init(color: rgb(0xFFD08A), location: 0.30),
    .init(color: rgb(0xF0A040), location: 0.65),
    .init(color: rgb(0xD07020, 0), location: 1),
]
private let dormantCoreStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFD8A8, 0.4), location: 0),
    .init(color: rgb(0xC8A078, 0.2), location: 0.45),
    .init(color: rgb(0x807060, 0), location: 1),
]
private let ashBodyStops: [Gradient.Stop] = [
    .init(color: rgb(0x655E56), location: 0),
    .init(color: rgb(0x3A3530), location: 0.40),
    .init(color: rgb(0x262220), location: 0.72),
    .init(color: rgb(0x151312), location: 1),
]

private func drawEmber(
    context: inout GraphicsContext,
    size: CGSize,
    mood: StreakEmberMood,
    phase: CGFloat,
    breath: CGFloat,
    flash: CGFloat
) {
    let s = min(size.width, size.height)
    let w = s * 0.76
    let h = s * 0.90
    let cx = s * 0.5
    let baseY = s * 0.95
    let t = Double(phase)
    let animate = mood != .dormant
    let rate: Double = mood == .warning ? 0.6 : 1.0
    let intensity: CGFloat = mood == .active ? 1 : (mood == .warning ? 0.88 : 0.45)
    let twoPi = Double.pi * 2

    func reduced(_ freq: Double) -> Double {
        guard animate else { return 0 }
        let x = (t / 6.0) * rate * freq
        return ((x.truncatingRemainder(dividingBy: 1)) + 1).truncatingRemainder(dividingBy: 1)
    }

    let tipDx = animate ? CGFloat(sin(reduced(2.1) * twoPi) * 0.06 + sin(reduced(3.7) * twoPi + 0.8) * 0.025) * (w * 0.5) * intensity : 0
    let tilt = animate ? CGFloat(sin(reduced(0.9) * twoPi + 0.5) * 1.4) * intensity : 0
    let bob = animate ? CGFloat(sin(reduced(2.2) * twoPi)) * s * 0.004 * intensity : 0
    let sway = animate ? (CGFloat(sin(reduced(1.0) * twoPi)) * 0.022 + CGFloat(sin(reduced(2.3) * twoPi + 1.3)) * 0.012) * w * intensity : 0
    let lickL = animate ? CGFloat(sin(reduced(1.3) * twoPi + 1)) * w * 0.032 * intensity : 0
    let lickR = animate ? CGFloat(sin(reduced(1.9) * twoPi + 2.2)) * w * 0.028 * intensity : 0
    let heightScale = animate ? (1 + (breath - 1) * 0.45) : 1
    let widen = animate ? (1 + (breath - 1) * 0.55) : 1
    let compact = s < 48
    let angle = Double(tilt) * Double.pi / 180
    let ca = CGFloat(cos(angle))
    let sa = CGFloat(sin(angle))

    func point(_ fx: CGFloat, _ ft: CGFloat) -> CGPoint {
        let rawX = cx + fx * w * widen + (sway + tipDx) * ft * ft
        let rawY = baseY - ft * h * heightScale
        let dx = rawX - cx
        let dy = rawY - baseY
        return CGPoint(x: cx + dx * ca - dy * sa, y: baseY + dx * sa + dy * ca + bob)
    }

    func outerPath() -> Path {
        var p = Path()
        p.move(to: point(0, 0))
        p.addCurve(to: point(-0.46, 0.20), control1: point(-0.22, 0), control2: point(-0.44, 0.06))
        p.addCurve(to: point(-0.28, 0.50), control1: point(-0.48, 0.32), control2: point(-0.38, 0.42))
        p.addCurve(to: point(-0.02, 0.92), control1: point(-0.22 + (lickL * 0.5)/w, 0.58), control2: point(-0.14 + lickL/w, 0.76))
        p.addCurve(to: point(0.02, 1.00), control1: point(0.00, 0.97), control2: point(0.01, 0.99))
        p.addCurve(to: point(0.22, 0.64), control1: point(0.06, 0.94), control2: point(0.14 + lickR/w, 0.78))
        p.addCurve(to: point(0.46, 0.24), control1: point(0.34, 0.54), control2: point(0.48, 0.40))
        p.addCurve(to: point(0, 0), control1: point(0.44, 0.10), control2: point(0.30, 0.02))
        p.closeSubpath()
        return p
    }

    func corePath(top: CGFloat) -> Path {
        let mid = top * 0.78
        func corePoint(_ fx: CGFloat, _ ft: CGFloat) -> CGPoint {
            let rawX = cx + fx * w * widen * 0.92 + (sway + tipDx * 0.75) * ft * ft
            let rawY = baseY - ft * h * heightScale
            let dx = rawX - cx
            let dy = rawY - baseY
            return CGPoint(x: cx + dx * ca - dy * sa, y: baseY + dx * sa + dy * ca + bob)
        }
        var p = Path()
        p.move(to: corePoint(0, 0.02))
        p.addCurve(to: corePoint(-0.16, 0.26), control1: corePoint(-0.10, 0.03), control2: corePoint(-0.18, 0.12))
        p.addCurve(to: corePoint(-0.03, top - 0.04), control1: corePoint(-0.14, 0.38), control2: corePoint(-0.08, mid))
        p.addCurve(to: corePoint(0.01, top - 0.01), control1: corePoint(-0.01, top - 0.01), control2: corePoint(0, top))
        p.addCurve(to: corePoint(0.16, 0.26), control1: corePoint(0.03, top - 0.04), control2: corePoint(0.08, mid))
        p.addCurve(to: corePoint(0, 0.02), control1: corePoint(0.18, 0.12), control2: corePoint(0.10, 0.03))
        p.closeSubpath()
        return p
    }

    let bodyStops = mood == .active ? activeBodyStops : (mood == .warning ? atRiskBodyStops : dormantBodyStops)
    let bodyShading = GraphicsContext.Shading.linearGradient(
        Gradient(stops: bodyStops),
        startPoint: CGPoint(x: cx, y: baseY),
        endPoint: CGPoint(x: cx, y: baseY - h)
    )
    let bloomCentre = CGPoint(x: cx, y: baseY - h * 0.38)
    let bloomAlpha = (compact ? 0.14 : (mood == .active ? 0.22 : (mood == .warning ? 0.13 : 0.07))) + Double(flash) * 0.06
    let bloomHex: UInt32 = mood == .active ? 0xFF7A1A : (mood == .warning ? 0xE08A30 : 0x807060)
    context.fill(
        Path(ellipseIn: CGRect(x: bloomCentre.x - h, y: bloomCentre.y - h, width: h * 2, height: h * 2)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: rgb(bloomHex, 0.25 * bloomAlpha), location: 0),
                .init(color: rgb(bloomHex, 0.09 * bloomAlpha), location: 0.55),
                .init(color: rgb(bloomHex, 0), location: 1),
            ]),
            center: bloomCentre, startRadius: 0, endRadius: h
        )
    )
    if s >= 48 {
        let contactHex: UInt32 = mood == .active ? 0xFF8A20 : (mood == .warning ? 0xC07028 : 0x606058)
        context.fill(
            Path(ellipseIn: CGRect(x: cx - w * 0.50, y: baseY - h * 0.03, width: w * 1.00, height: h * 0.10)),
            with: .radialGradient(
                Gradient(stops: [.init(color: rgb(contactHex, 0.13), location: 0), .init(color: rgb(contactHex, 0), location: 1)]),
                center: CGPoint(x: cx, y: baseY - h * 0.02), startRadius: 0, endRadius: w * 0.70
            )
        )
    }

    let body = outerPath()
    context.fill(body, with: bodyShading)
    let fringe = fringeWidths(sizeDP: s)
    let fringeA = mood == .active ? 0.14 : (mood == .warning ? 0.10 : 0.06)
    context.stroke(body, with: .color(rgb(0xFF5A18, fringeA)), lineWidth: fringe.0)
    context.stroke(body, with: .color(rgb(0xFFC040, fringeA * 0.5)), lineWidth: fringe.1)

    let coreTop: CGFloat = mood == .active ? 0.62 : (mood == .warning ? 0.54 : 0.50)
    let coreStops = mood == .active ? activeCoreStops : (mood == .warning ? atRiskCoreStops : dormantCoreStops)
    context.fill(
        corePath(top: coreTop),
        with: .radialGradient(
            Gradient(stops: coreStops),
            center: CGPoint(x: cx, y: baseY - h * (mood == .active ? 0.20 : (mood == .warning ? 0.18 : 0.16))),
            startRadius: 0,
            endRadius: h * (mood == .active ? 0.42 : (mood == .warning ? 0.36 : 0.30))
        )
    )

    let baseAlpha = (compact ? 0.34 : 0.42) * Double(intensity) + Double(breath - 1) * 0.8 + Double(flash) * 0.12
    let hotStops: [Gradient.Stop] = {
        switch mood {
        case .active: return [.init(color: rgb(0xFFFFFF, min(1, 0.95 * baseAlpha)), location: 0), .init(color: rgb(0xFFF8D8, min(1, 0.8 * baseAlpha)), location: 0.35), .init(color: rgb(0xFFE8A0, 0), location: 1)]
        case .warning: return [.init(color: rgb(0xFFF0D0, min(1, 0.8 * baseAlpha)), location: 0), .init(color: rgb(0xFFD89A, min(1, 0.53 * baseAlpha)), location: 0.45), .init(color: rgb(0xD08030, 0), location: 1)]
        case .dormant: return [.init(color: rgb(0xE8D8C8, min(1, 0.27 * baseAlpha)), location: 0), .init(color: rgb(0xB0A090, 0), location: 1)]
        }
    }()
    context.fill(
        Path(ellipseIn: CGRect(x: cx - w * 0.14, y: baseY - h * 0.13, width: w * 0.28, height: h * 0.12)),
        with: .radialGradient(Gradient(stops: hotStops), center: CGPoint(x: cx, y: baseY - h * 0.12), startRadius: 0, endRadius: w * 0.28)
    )

    if animate {
        let count = s >= 128 ? 3 : (s >= 64 ? 2 : (s >= 36 ? 1 : 0))
        if count > 0 {
            for i in 0..<count {
                let seed = (reduced(6.0 / 1.7) + Double(i) * 0.37).truncatingRemainder(dividingBy: 1)
                let alpha = sin(seed * Double.pi) * 0.75 * Double(intensity)
                if alpha <= 0.03 { continue }
                let y = baseY - h * (0.42 + CGFloat(seed) * 0.50)
                let x = cx + CGFloat(sin(seed * 5 + Double(i) * 2.1)) * w * 0.14
                let radius = s * (0.004 + 0.0015 * CGFloat(i % 2))
                context.fill(Path(ellipseIn: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2)), with: .color(rgb(0xFFD070, min(0.85, alpha))))
                var streak = Path(); streak.move(to: CGPoint(x: x, y: y + radius * 2.0)); streak.addLine(to: CGPoint(x: x, y: y - radius * 0.8))
                context.stroke(streak, with: .color(rgb(0xFFA040, min(0.35, alpha))), lineWidth: radius * 0.9)
            }
        }
    }
}

/// Retained for API/test compatibility: draws dormant flame silhouette (not ash mound).
private func drawAsh(
    context: inout GraphicsContext,
    s: CGFloat,
    w: CGFloat,
    h: CGFloat,
    cx: CGFloat,
    baseY: CGFloat,
    flash: CGFloat
) {
    // Preserve fire identity: render flame silhouette in low intensity.
    drawEmber(context: &context, size: CGSize(width: s, height: s), mood: .dormant, phase: CGFloat(STATIC_FLAME_SECONDS), breath: 1.0, flash: flash)
}

private func drawStage(context: inout GraphicsContext, size: CGSize, mood: StreakEmberMood, phase: CGFloat) {
    let colors = EmberPalette.forMood(mood)
    let w = size.width
    let h = size.height
    let card = Color(red: 0.082, green: 0.078, blue: 0.086)
    let void = Color(red: 0.027, green: 0.024, blue: 0.031)
    context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(void))
    context.fill(
        Path(CGRect(origin: .zero, size: size)),
        with: .linearGradient(
            Gradient(stops: [
                .init(color: card, location: 0),
                .init(color: card.opacity(0.70), location: 0.10),
                .init(color: void.opacity(0), location: 0.22),
                .init(color: void.opacity(0), location: 0.78),
                .init(color: card.opacity(0.70), location: 0.90),
                .init(color: card, location: 1),
            ]),
            startPoint: .zero, endPoint: CGPoint(x: w, y: 0)
        )
    )
    context.fill(
        Path(CGRect(origin: .zero, size: size)),
        with: .linearGradient(
            Gradient(stops: [
                .init(color: card.opacity(0.40), location: 0),
                .init(color: void.opacity(0), location: 0.16),
                .init(color: void.opacity(0), location: 0.84),
                .init(color: card.opacity(0.45), location: 1),
            ]),
            startPoint: .zero, endPoint: CGPoint(x: 0, y: h)
        )
    )
    drawRockMass(&context, anchor: CGPoint(x: w * -0.04, y: h * 0.28), spanX: w * 0.36, spanY: h * 0.95, phase: phase, alphaScale: 1)
    drawRockMass(&context, anchor: CGPoint(x: w * 1.04, y: h * 0.42), spanX: w * 0.38, spanY: h * 1.00, phase: phase + 0.3, alphaScale: 1)
    let glowA: Double = mood == .active ? 0.12 : (mood == .warning ? 0.10 : 0.05)
    let glowR = min(w, h) * 0.58
    let glowC = CGPoint(x: w * 0.50, y: h * 0.38)
    context.fill(
        Path(ellipseIn: CGRect(x: glowC.x - glowR, y: glowC.y - glowR, width: glowR * 2, height: glowR * 2)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: colors.halo.opacity(glowA), location: 0),
                .init(color: colors.coreMid.opacity(glowA * 0.35), location: 0.40),
                .init(color: colors.halo.opacity(0), location: 1),
            ]),
            center: glowC, startRadius: 0, endRadius: glowR
        )
    )
}

private func drawRockMass(
    _ context: inout GraphicsContext,
    anchor: CGPoint,
    spanX: CGFloat,
    spanY: CGFloat,
    phase: CGFloat,
    alphaScale: CGFloat
) {
    let wobble: CGFloat = 0
    for i in 0...5 {
        let ox = anchor.x + (CGFloat(i) - 2.5) * spanX * 0.11 + wobble
        let oy = anchor.y + CGFloat(i % 3 - 1) * spanY * 0.11
        let rx = spanX * (0.32 + CGFloat(i) * 0.07)
        let ry = spanY * (0.26 + CGFloat(5 - i) * 0.055)
        let a = (0.42 - Double(i) * 0.045) * Double(alphaScale)
        context.fill(
            Path(ellipseIn: CGRect(x: ox - rx, y: oy - ry, width: rx * 2, height: ry * 2)),
            with: .radialGradient(
                Gradient(stops: [
                    .init(color: FlintBrand.ridge.opacity(a), location: 0),
                    .init(color: FlintBrand.mist.opacity(0.10 * Double(alphaScale)), location: 0.48),
                    .init(color: FlintBrand.mist.opacity(0), location: 1),
                ]),
                center: CGPoint(x: ox, y: oy), startRadius: 0, endRadius: rx
            )
        )
    }
}
