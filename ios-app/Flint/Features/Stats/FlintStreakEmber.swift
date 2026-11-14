import SwiftUI
import Foundation
import FlintCore

/// Visual mood for the cinematic ember — mapped from streak lifecycle, not a separate rule.
enum StreakEmberMood: Equatable {
    /// Golden — streak alive and today completed; full colour, full motion.
    case active
    /// Same flame cooling — reduced heat, slower motion. Amber chrome, never red/rose.
    case warning
    /// Cooled ash with a faint ember; no animation.
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
    /// Per-mood gradient stops — constants, shared across frames.
    let shellStops: [Gradient.Stop]
    let coreStops: [Gradient.Stop]

    static func forMood(_ mood: StreakEmberMood) -> EmberPalette {
        switch mood {
        case .active: return .active
        case .warning: return .warning
        case .dormant: return .dormant
        }
    }

    // Glass-drop flame — milky shell, glowing rim, fire core.
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
        // Heat leaving the flame: warm amber chrome, no red/rose anywhere.
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

/// Warm cooling chrome for at-risk streak text (mirrors Android's `StreakCoolDeep`).
/// Used for the number and the "DAY STREAK" label so no legacy red/rose returns.
let flintStreakCoolDeep = Color(red: 0.757, green: 0.451, blue: 0.122)

/// Hero streak number fill — Opal sheet gradient: light top melting into the mood color.
func streakNumberFill(_ mood: StreakEmberMood) -> AnyShapeStyle {
    switch mood {
    case .active:
        return AnyShapeStyle(LinearGradient(
            colors: [Color(red: 1.0, green: 0.914, blue: 0.690), FlintBrand.ember],
            startPoint: .top, endPoint: .bottom
        ))
    case .warning:
        // Warm cooling chrome, matching Android's StreakCoolTop/StreakCoolDeep.
        return AnyShapeStyle(LinearGradient(
            colors: [Color(red: 0.949, green: 0.784, blue: 0.573),
                     Color(red: 0.757, green: 0.451, blue: 0.122)],
            startPoint: .top, endPoint: .bottom
        ))
    case .dormant:
        return AnyShapeStyle(FlintBrand.mist.opacity(0.72))
    }
}

/// Reference-based streak flame — Canvas only (not video, emoji, or third-party art).
struct FlintStreakEmber: View {
    var mood: StreakEmberMood
    var size: CGFloat = 168
    var milestoneFlash: CGFloat = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    @State private var visible = true
    /// The mood currently being drawn; trails `mood` by one frame so a dissolve can start.
    @State private var shownMood: StreakEmberMood
    /// The mood dissolving out, if a state change is in flight.
    @State private var previousMood: StreakEmberMood?
    @State private var fadeStart: Date?
    /// Phase the loop resumes from (mirrors Android's elapsed anchor). First appearance
    /// starts from the deterministic static pose.
    @State private var phaseAnchor: Double = STATIC_FLAME_SECONDS
    /// Monotonic instant `phaseAnchor` was captured at. `systemUptime` never jumps with
    /// wall-clock changes, so the phase delta is immune to time/zone adjustments.
    @State private var anchorUptime: Double = ProcessInfo.processInfo.systemUptime
    /// Phase frozen while the loop is paused; a resume continues from here, not from
    /// absolute time, so re-entry never snaps the pose.
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

    /// A dissolve to dormant still needs frames even though the target is static.
    private var timelineActive: Bool {
        animate || (previousMood != nil && !reduceMotion && scenePhase == .active && visible)
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0, paused: !timelineActive)) { context in
            let progress = fadeProgress(at: context.date)
            let fading = progress < 1
            // Keep the outgoing flame live during the dissolve so its phase never jumps.
            let live = animate || (fading && !reduceMotion && scenePhase == .active && visible)
            // Anchored loop time: while live, continue from the paused phase; while paused,
            // draw the frozen phase (or the deterministic static pose under Reduce Motion).
            let seconds = live
                ? phaseAnchor + (ProcessInfo.processInfo.systemUptime - anchorUptime)
                : (reduceMotion ? STATIC_FLAME_SECONDS : pausedPhase)
            // Mirrors Android's breathPhase01 + breathScale: 1.00 ± 0.04 on a 1.5 s cycle.
            let breath = CGFloat(1.0 + 0.04 * sin(seconds * 2.0 * Double.pi / 1.5))
            let flash = min(1, max(0, milestoneFlash))
            Canvas { ctx, canvasSize in
                // Android's dissolve is a convex blend: outgoing at 1 - p, incoming at p.
                // Drawing the incoming layer at full opacity would stack two live flames
                // and can exceed both endpoint colours wherever they overlap.
                var incomingOpacity: Double = 1
                if let prev = previousMood, fading {
                    ctx.opacity = Double(1 - progress)
                    drawEmber(
                        context: &ctx,
                        size: canvasSize,
                        mood: prev,
                        phase: CGFloat(seconds),
                        breath: breath,
                        flash: flash
                    )
                    incomingOpacity = Double(progress)
                }
                ctx.opacity = incomingOpacity
                drawEmber(
                    context: &ctx,
                    size: canvasSize,
                    mood: shownMood,
                    phase: CGFloat(seconds),
                    breath: breath,
                    flash: flash
                )
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
            // An interrupted dissolve must not leave the timeline active on a stale mood.
            if phase != .active { previousMood = nil }
        }
        // Freeze the phase on pause and re-anchor on resume. Keyed on eligibility only, so
        // an Active <-> AtRisk mood change never restarts or jumps the loop.
        .onChange(of: timelineActive) { active in
            let uptime = ProcessInfo.processInfo.systemUptime
            if active {
                phaseAnchor = reduceMotion ? STATIC_FLAME_SECONDS : pausedPhase
                anchorUptime = uptime
            } else {
                pausedPhase = reduceMotion
                    ? STATIC_FLAME_SECONDS
                    : phaseAnchor + (uptime - anchorUptime)
            }
        }
        .onDisappear { previousMood = nil }
        .accessibilityHidden(true)
    }

    /// One-shot dissolve progress; 1 means no dissolve (including Reduce Motion).
    private func fadeProgress(at date: Date) -> CGFloat {
        guard let start = fadeStart, !reduceMotion else { return 1 }
        let p = date.timeIntervalSince(start) / STATE_CROSSFADE_SECONDS
        return CGFloat(min(max(p, 0), 1))
    }

    /// Pauses the loop while the ember is scrolled out of its window.
    private func updateVisibility(_ frame: CGRect) {
        guard frame.width > 0, frame.height > 0 else { return }
        // SwiftUI's `.global` space is the hosting window's coordinate space, not the physical
        // screen: sheets sit inset, and Stage Manager / split view windows are smaller than the
        // display, so a whole-display rect reports a fully visible ember as clipped. Before any
        // scene window exists the frame carries no meaningful geometry, so leave `visible` as
        // it is instead of guessing a rect or forcing the loop paused.
        guard let windowBounds = Self.hostWindowBounds() else { return }
        let inter = frame.intersection(windowBounds)
        let fraction = (inter.isNull || inter.isEmpty)
            ? 0
            : (inter.width * inter.height) / (frame.width * frame.height)
        visible = fraction > 0.05
    }

    /// Bounds of the key window across connected scenes, falling back to the first window of
    /// the first scene. Nil only before the view has a window, which the caller treats as
    /// "not measurable yet". Window scenes and `UIWindowScene.windows` are iOS 15+, below the
    /// iOS 16.0 deployment target.
    private static func hostWindowBounds() -> CGRect? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
        if let keyWindow = windows.first(where: { $0.isKeyWindow }) { return keyWindow.bounds }
        return windows.first?.bounds
    }
}

/// One deterministic, fully layered pose for Reduce Motion and goldens.
private let STATIC_FLAME_SECONDS: Double = 0.72

/// Mirrors Android's `STATE_CROSSFADE_MS`.
private let STATE_CROSSFADE_SECONDS: Double = 0.32

/// Still backdrop. The sheet already runs the flame's clock; a second 12 fps loop only
/// doubled wake-ups, so this stage is drawn once at a fixed phase.
struct FlintStreakCinematicStage: View {
    var mood: StreakEmberMood

    var body: some View {
        Canvas { ctx, size in
            drawStage(context: &ctx, size: size, mood: mood, phase: 0.35)
        }
    }
}

/// Mirrors Android's `fringeWidthsDp`: ~1 dp halo at badge sizes, fuller at hero sizes.
private func fringeWidths(sizeDP: CGFloat) -> (CGFloat, CGFloat) {
    if sizeDP < 48 { return (0.6, 1.0) }
    if sizeDP < 120 { return (1.0, 1.8) }
    return (1.4, 2.8)
}

/// Shared hex → sRGB colour for the flame palette, mirroring Android's `Color(0xAARRGGBB)`.
/// Alpha rides on the stop's own opacity, exactly as the Compose gradient stops do.
private func rgb(_ hex: UInt32, _ opacity: Double = 1) -> Color {
    Color(
        .sRGB,
        red: Double((hex >> 16) & 0xFF) / 255,
        green: Double((hex >> 8) & 0xFF) / 255,
        blue: Double(hex & 0xFF) / 255,
        opacity: opacity
    )
}

/// Constant gradient stops, hoisted out of the per-frame draw so a 30 fps flame does not
/// rebuild the same arrays on every tick (Android caches these in its brush set).
private let activeBodyStops: [Gradient.Stop] = [
    .init(color: rgb(0xFB922F), location: 0),
    .init(color: rgb(0xFB7A22), location: 0.34),
    .init(color: rgb(0xF34415), location: 0.70),
    .init(color: rgb(0xC7300B), location: 1),
]

/// At-risk keeps saturated amber low and cools progressively upward.
private let atRiskBodyStops: [Gradient.Stop] = [
    .init(color: rgb(0xF6A23C), location: 0),
    .init(color: rgb(0xEA8A2B), location: 0.22),
    .init(color: rgb(0xCF6E1E), location: 0.46),
    .init(color: rgb(0xA6551C), location: 0.72),
    .init(color: rgb(0x7E4017), location: 0.90),
    .init(color: rgb(0x5C3014), location: 1),
]

private let activeCoreStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFF6C8), location: 0),
    .init(color: rgb(0xFFE08A), location: 0.35),
    .init(color: rgb(0xFDCC3B), location: 0.72),
    .init(color: rgb(0xFB922F, 0), location: 1),
]

/// Residual heat: a compact amber pocket low in the body.
private let atRiskCoreStops: [Gradient.Stop] = [
    .init(color: rgb(0xFFD27E, 230.0 / 255.0), location: 0),
    .init(color: rgb(0xFFB35A, 0.60), location: 0.42),
    .init(color: rgb(0xE08A2E, 0), location: 1),
]

/// Ash mound ramp: warm char top into near-black soot at the base.
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
    // Mirrors Android's FlameGeometry: the contour spans ±0.45 w against a 0.90 s tall
    // body, so the *visible* aspect is h / (0.90 w) = s / w, not h / w. w = 0.76 s is
    // what renders the approved 1.30–1.35 silhouette (H:W ≈ 1.32); the previous
    // narrower scale measured 1.45 and is measurably too narrow.
    let s = min(size.width, size.height)
    let w = s * 0.76
    let h = s * 0.90
    let cx = s * 0.5
    let baseY = s * 0.95
    let t = Double(phase)
    let rate: Double = mood == .warning ? 0.6 : 1.0
    let intensity: CGFloat = mood == .active ? 1 : 0.85
    let twoPi = Double.pi * 2

    func reduced(_ freq: Double) -> Double {
        let x = (t / 6.0) * rate * freq
        return ((x.truncatingRemainder(dividingBy: 1)) + 1).truncatingRemainder(dividingBy: 1)
    }

    if mood == .dormant {
        drawAsh(context: &context, s: s, w: w, h: h, cx: cx, baseY: baseY, flash: flash)
        return
    }

    let tipDx = CGFloat(sin(reduced(2.1) * twoPi) * 0.05 + sin(reduced(3.7) * twoPi + 0.8) * 0.02)
        * (w * 0.5) * intensity
    let tilt = CGFloat(sin(reduced(0.9) * twoPi + 0.5) * 1.2) * intensity
    let bob = CGFloat(sin(reduced(2.2) * twoPi)) * s * 0.004 * intensity
    let sway = (CGFloat(sin(reduced(1.0) * twoPi)) * 0.020
        + CGFloat(sin(reduced(2.3) * twoPi + 1.3)) * 0.010) * w * intensity
    let lickL = CGFloat(sin(reduced(1.3) * twoPi + 1)) * w * 0.030 * intensity
    let lickR = CGFloat(sin(reduced(1.9) * twoPi + 2.2)) * w * 0.026 * intensity
    let heightScale = 1 + (breath - 1) * 0.45
    let widen = 1 + (breath - 1) * 0.55
    let compact = s < 48
    let angle = Double(tilt) * Double.pi / 180
    let ca = CGFloat(cos(angle))
    let sa = CGFloat(sin(angle))

    func point(_ fx: CGFloat, _ ft: CGFloat) -> CGPoint {
        let rawX = cx + fx * w * widen + (sway + tipDx) * ft * ft
        let rawY = baseY - ft * h * heightScale
        let dx = rawX - cx
        let dy = rawY - baseY
        return CGPoint(
            x: cx + dx * ca - dy * sa,
            y: baseY + dx * sa + dy * ca + bob
        )
    }

    func outerPath() -> Path {
        var p = Path()
        p.move(to: point(0, 0))
        p.addCurve(to: point(-0.40, 0.10), control1: point(-0.24, 0), control2: point(-0.40, 0.02))
        p.addCurve(to: point(-0.44, 0.24), control1: point(-0.42, 0.16), control2: point(-0.45, 0.21))
        p.addCurve(to: point(-0.30, 0.36), control1: point(-0.40, 0.29), control2: point(-0.33, 0.33))
        p.addCurve(to: point(-0.34, 0.52), control1: point(-0.31, 0.42), control2: point(-0.34, 0.47))
        p.addCurve(to: point(-0.24, 0.60), control1: point(-0.33, 0.55), control2: point(-0.27 + lickL / w, 0.58))
        p.addCurve(to: point(0.05, 1.00), control1: point(-0.15, 0.74), control2: point(-0.01, 0.90))
        p.addCurve(to: point(0.30, 0.66), control1: point(0.08, 0.88), control2: point(0.18, 0.74))
        p.addCurve(to: point(0.24, 0.58), control1: point(0.29, 0.62), control2: point(0.26 + lickR / w, 0.60))
        p.addCurve(to: point(0.44, 0.38), control1: point(0.33, 0.50), control2: point(0.42, 0.44))
        p.addCurve(to: point(0.38, 0.12), control1: point(0.45, 0.30), control2: point(0.42, 0.28))
        p.addCurve(to: point(0, 0), control1: point(0.33, 0.04), control2: point(0.18, 0))
        p.closeSubpath()
        return p
    }

    func corePath(top: CGFloat) -> Path {
        let mid = top * 0.80
        func corePoint(_ fx: CGFloat, _ ft: CGFloat) -> CGPoint {
            let rawX = cx + fx * w * widen * 0.92 + (sway + tipDx * 0.8) * ft * ft
            let rawY = baseY - ft * h * heightScale
            let dx = rawX - cx
            let dy = rawY - baseY
            return CGPoint(
                x: cx + dx * ca - dy * sa,
                y: baseY + dx * sa + dy * ca + bob
            )
        }
        var p = Path()
        p.move(to: corePoint(0, 0.02))
        p.addCurve(to: corePoint(-0.13, 0.20), control1: corePoint(-0.07, 0.03), control2: corePoint(-0.13, 0.10))
        p.addCurve(to: corePoint(-0.055, mid), control1: corePoint(-0.13, 0.34), control2: corePoint(-0.11, mid - 0.06))
        p.addCurve(to: corePoint(0, top), control1: corePoint(-0.025, top - 0.02), control2: corePoint(-0.012, top))
        p.addCurve(to: corePoint(0.055, mid), control1: corePoint(0.012, top), control2: corePoint(0.025, top - 0.02))
        p.addCurve(to: corePoint(0.13, 0.20), control1: corePoint(0.11, mid - 0.06), control2: corePoint(0.13, 0.34))
        p.addCurve(to: corePoint(0, 0.02), control1: corePoint(0.13, 0.10), control2: corePoint(0.07, 0.03))
        p.closeSubpath()
        return p
    }

    let bodyShading = GraphicsContext.Shading.linearGradient(
        Gradient(stops: mood == .active ? activeBodyStops : atRiskBodyStops),
        startPoint: CGPoint(x: cx, y: baseY),
        endPoint: CGPoint(x: cx, y: baseY - h)
    )
    let bloomCentre = CGPoint(x: cx, y: baseY - h * 0.34)
    let bloomInnerCentre = CGPoint(x: cx, y: baseY - h * 0.30)

    // Bloom — same two-level alpha as Android: gradient stop alpha × draw alpha.
    let bloomAlpha = (compact ? 0.10 : (mood == .active ? 0.20 : 0.11)) + Double(flash) * 0.06
    context.fill(
        Path(ellipseIn: CGRect(x: bloomCentre.x - h * 1.05, y: bloomCentre.y - h * 1.05,
                                width: h * 2.10, height: h * 2.10)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: rgb(0xFF9A3C, 0.2196 * bloomAlpha), location: 0),
                .init(color: rgb(0xFF9A3C, 0.0784 * bloomAlpha), location: 0.55),
                .init(color: rgb(0xFF9A3C, 0), location: 1),
            ]),
            center: bloomCentre,
            startRadius: 0,
            endRadius: h * 1.05
        )
    )
    let bloomInnerAlpha = (compact ? 0.06 : (mood == .active ? 0.16 : 0.09)) + Double(flash) * 0.05
    context.fill(
        Path(ellipseIn: CGRect(x: bloomInnerCentre.x - h * 0.58, y: bloomInnerCentre.y - h * 0.58,
                                width: h * 1.16, height: h * 1.16)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: rgb(0xFFD27A, 0.1804 * bloomInnerAlpha), location: 0),
                .init(color: rgb(0xFFD27A, 0), location: 1),
            ]),
            // Android's bloomInner brush is centred at -0.34 h but drawn at -0.30 h;
            // the gradient centre, not the disc, carries the falloff.
            center: bloomCentre,
            startRadius: 0,
            endRadius: h * 0.58
        )
    )
    // Contact light on the surface (hero sizes only).
    if s >= 48 {
        context.fill(
            Path(ellipseIn: CGRect(x: cx - w * 0.52, y: baseY - h * 0.03,
                                    width: w * 1.04, height: h * 0.10)),
            with: .radialGradient(
                Gradient(stops: [
                    .init(color: rgb(0xFB922F, 0.1216), location: 0),
                    .init(color: rgb(0xFB922F, 0), location: 1),
                ]),
                center: CGPoint(x: cx, y: baseY - h * 0.02),
                startRadius: 0,
                endRadius: w * 0.70
            )
        )
    }

    let body = outerPath()
    context.fill(body, with: bodyShading)
    // Optically thin fringe, width-tiered exactly like Android (~1 dp at 24 pt).
    let fringe = fringeWidths(sizeDP: s)
    context.stroke(body, with: .color(rgb(0xFF6A2A, 0.10)), lineWidth: fringe.0)
    context.stroke(body, with: .color(rgb(0xFFC24A, 0.04)), lineWidth: fringe.1)

    // Hot core.
    let coreTop: CGFloat = mood == .active ? 0.56 : 0.44
    let coreStops: [Gradient.Stop] = mood == .active ? activeCoreStops : atRiskCoreStops
    context.fill(
        corePath(top: coreTop),
        with: .radialGradient(
            Gradient(stops: coreStops),
            center: CGPoint(x: cx, y: baseY - h * (mood == .active ? 0.14 : 0.10)),
            startRadius: 0,
            endRadius: h * (mood == .active ? 0.46 : 0.34)
        )
    )

    // Hot base. At-risk keeps residual heat, but it is amber, never white-hot.
    // Android brush stops (1.0 / 0.651 active, 0.902 / 0.6 at-risk) × draw alpha.
    let baseAlpha = (compact ? 0.24 : 0.32) + Double(breath - 1) * 0.8 + Double(flash) * 0.14
    let baseHotHex: UInt32 = mood == .active ? 0xFFFFFF : 0xFFD9A0
    let baseMidHex: UInt32 = mood == .active ? 0xFFFDE6 : 0xFFB35A
    let baseHotStop = mood == .active ? 1.0 : 0.902
    let baseMidStop = mood == .active ? 0.651 : 0.600
    context.fill(
        Path(ellipseIn: CGRect(x: cx - w * 0.12, y: baseY - h * 0.12,
                                width: w * 0.24, height: h * 0.11)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: rgb(baseHotHex, baseHotStop * baseAlpha), location: 0),
                .init(color: rgb(baseMidHex, baseMidStop * baseAlpha), location: 0.45),
                .init(color: rgb(baseMidHex, 0), location: 1),
            ]),
            center: CGPoint(x: cx, y: baseY - h * 0.08),
            startRadius: 0,
            endRadius: w * 0.24
        )
    )

    // Restrained embers — same size tiers as Android (128 / 64 / 36).
    let count = s >= 128 ? 3 : (s >= 64 ? 2 : (s >= 36 ? 1 : 0))
    if count > 0 {
        for i in 0..<count {
            let seed = (reduced(6.0 / 1.7) + Double(i) * 0.37)
                .truncatingRemainder(dividingBy: 1)
            let alpha = sin(seed * Double.pi) * 0.75 * Double(intensity)
            if alpha <= 0.03 { continue }
            let y = baseY - h * (0.38 + CGFloat(seed) * 0.52)
            let x = cx + CGFloat(sin(seed * 5 + Double(i) * 2.1)) * w * 0.16
            let radius = s * (0.004 + 0.0015 * CGFloat(i % 2))
            context.fill(
                Path(ellipseIn: CGRect(x: x - radius, y: y - radius,
                                        width: radius * 2, height: radius * 2)),
                with: .color(rgb(0xFFB86B, min(0.8, alpha)))
            )
            var streak = Path()
            streak.move(to: CGPoint(x: x, y: y + radius * 2.2))
            streak.addLine(to: CGPoint(x: x, y: y - radius * 0.8))
            context.stroke(streak, with: .color(rgb(0xFF8A3C, min(0.35, alpha))), lineWidth: radius * 0.9)
        }
    }
}

private func drawAsh(
    context: inout GraphicsContext,
    s: CGFloat,
    w: CGFloat,
    h: CGFloat,
    cx: CGFloat,
    baseY: CGFloat,
    flash: CGFloat
) {
    func pt(_ fx: CGFloat, _ ft: CGFloat) -> CGPoint {
        CGPoint(x: cx + fx * w, y: baseY - ft * h)
    }
    let compact = s < 48
    let emberX = cx - w * 0.06
    let emberY = baseY - h * 0.20
    let emberR = w * (compact ? 0.11 : 0.085)

    // Jagged crown: broken peaks and notches, never a smooth dome.
    var mound = Path()
    mound.move(to: pt(0, 0))
    mound.addCurve(to: pt(-0.42, 0.06), control1: pt(-0.24, 0), control2: pt(-0.38, 0.01))
    mound.addLine(to: pt(-0.36, 0.17))
    mound.addLine(to: pt(-0.32, 0.145))
    mound.addLine(to: pt(-0.26, 0.29))
    mound.addLine(to: pt(-0.20, 0.215))
    mound.addLine(to: pt(-0.12, 0.245))
    mound.addLine(to: pt(-0.04, 0.20))
    mound.addLine(to: pt(0.02, 0.315))
    mound.addLine(to: pt(0.09, 0.30))
    mound.addLine(to: pt(0.15, 0.225))
    mound.addLine(to: pt(0.25, 0.285))
    mound.addLine(to: pt(0.33, 0.175))
    mound.addCurve(to: pt(0.40, 0.03), control1: pt(0.40, 0.12), control2: pt(0.44, 0.06))
    mound.addCurve(to: pt(0, 0), control1: pt(0.32, 0.01), control2: pt(0.14, 0))
    mound.closeSubpath()

    // Warm ember glow behind the ash (Android brush alpha 0.1804 × draw alpha). Android
    // paints its bloomInner brush — a 0.58 h radial on the flame axis at -0.34 h — into
    // a 0.30 h disc around the coal, so the falloff is broad and offset from the coal
    // instead of a tight coal-centred glow.
    let glowAlpha = (compact ? 0.20 : 0.12) + Double(flash) * 0.05
    context.fill(
        Path(ellipseIn: CGRect(x: emberX - h * 0.30, y: emberY - h * 0.30,
                                width: h * 0.60, height: h * 0.60)),
        with: .radialGradient(
            Gradient(stops: [
                .init(color: rgb(0xFFD27A, 0.1804 * glowAlpha), location: 0),
                .init(color: rgb(0xFFD27A, 0), location: 1),
            ]),
            center: CGPoint(x: cx, y: baseY - h * 0.34),
            startRadius: 0,
            endRadius: h * 0.58
        )
    )
    context.fill(
        mound,
        with: .linearGradient(
            Gradient(stops: ashBodyStops),
            startPoint: CGPoint(x: cx, y: baseY - h * 0.32),
            endPoint: CGPoint(x: cx, y: baseY)
        )
    )

    func shard(_ side: CGFloat) -> Path {
        var p = Path()
        if side < 0 {
            p.move(to: pt(-0.40, 0.04))
            p.addLine(to: pt(-0.30, 0.21))
            p.addLine(to: pt(-0.16, 0.09))
            p.addLine(to: pt(-0.24, 0.03))
        } else {
            p.move(to: pt(0.14, 0.06))
            p.addLine(to: pt(0.24, 0.20))
            p.addLine(to: pt(0.36, 0.08))
            p.addLine(to: pt(0.28, 0.03))
        }
        p.closeSubpath()
        return p
    }
    // A charcoal shard always breaks the silhouette.
    context.fill(shard(-1), with: .color(rgb(0x2C2521)))
    if !compact {
        context.fill(shard(1), with: .color(rgb(0x352D26)))
        var facetA = Path()
        facetA.move(to: pt(-0.30, 0.05))
        facetA.addLine(to: pt(-0.12, 0.21))
        facetA.addLine(to: pt(0.00, 0.20))
        facetA.addLine(to: pt(-0.10, 0.07))
        facetA.closeSubpath()
        context.fill(facetA, with: .color(rgb(0xFFFFFF, 13.0 / 255.0)))
        var facetB = Path()
        facetB.move(to: pt(0.10, 0.20))
        facetB.addLine(to: pt(0.30, 0.16))
        facetB.addLine(to: pt(0.36, 0.06))
        facetB.addLine(to: pt(0.14, 0.03))
        facetB.closeSubpath()
        context.fill(facetB, with: .color(rgb(0x000000, 26.0 / 255.0)))
    }
    context.stroke(mound, with: .color(rgb(0xB3A99A, 36.0 / 255.0)), lineWidth: max(1, s * 0.007))

    // Coal: charred rim, warm body, hotter centre. No white.
    context.fill(
        Path(ellipseIn: CGRect(x: emberX - emberR * 1.12, y: emberY - emberR * 1.12,
                                width: emberR * 2.24, height: emberR * 2.24)),
        with: .color(rgb(0x17120F, 0.92))
    )
    context.fill(
        Path(ellipseIn: CGRect(x: emberX - emberR, y: emberY - emberR,
                                width: emberR * 2, height: emberR * 2)),
        with: .color(rgb(0xD45E1D, 0.95))
    )
    context.fill(
        Path(ellipseIn: CGRect(x: emberX - emberR * 0.58, y: emberY - emberR * 0.58,
                                width: emberR * 1.16, height: emberR * 1.16)),
        with: .color(rgb(0xFFB066, 0.85 + Double(flash) * 0.15))
    )
    context.fill(
        Path(ellipseIn: CGRect(x: emberX - emberR * 0.28, y: emberY - emberR * 0.28,
                                width: emberR * 0.56, height: emberR * 0.56)),
        with: .color(rgb(0xFFD9A0, 0.55 + Double(flash) * 0.20))
    )

    if !compact {
        var crack = Path()
        crack.move(to: CGPoint(x: emberX - w * 0.02, y: emberY - w * 0.015))
        crack.addLine(to: CGPoint(x: emberX - w * 0.10, y: emberY + h * 0.045))
        crack.addLine(to: CGPoint(x: emberX - w * 0.16, y: emberY + h * 0.035))
        context.stroke(crack, with: .color(rgb(0x5A2C12, 0.60)), lineWidth: max(1, s * 0.007))
    }
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
            startPoint: .zero,
            endPoint: CGPoint(x: w, y: 0)
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
            startPoint: .zero,
            endPoint: CGPoint(x: 0, y: h)
        )
    )

    drawRockMass(&context, anchor: CGPoint(x: w * -0.04, y: h * 0.28), spanX: w * 0.36, spanY: h * 0.95, phase: phase, alphaScale: 1)
    drawRockMass(&context, anchor: CGPoint(x: w * -0.02, y: h * 0.62), spanX: w * 0.28, spanY: h * 0.70, phase: phase + 0.2, alphaScale: 0.85)
    drawRockMass(&context, anchor: CGPoint(x: w * 1.04, y: h * 0.42), spanX: w * 0.38, spanY: h * 1.00, phase: phase + 0.3, alphaScale: 1)
    drawRockMass(&context, anchor: CGPoint(x: w * 1.02, y: h * 0.72), spanX: w * 0.30, spanY: h * 0.75, phase: phase + 0.5, alphaScale: 0.80)

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
            center: glowC,
            startRadius: 0,
            endRadius: glowR
        )
    )

    for i in 0..<18 {
        let xBias = CGFloat((i * 73) % 100) / 100
        let yBias = CGFloat((i * 47) % 100) / 100
        let r: CGFloat = 0.8 + CGFloat(i % 4) * 0.45
        let speed: CGFloat = 0.08 + CGFloat(i % 5) * 0.02
        let lag = CGFloat(i) * 0.11
        let t = ((phase * speed) + lag).truncatingRemainder(dividingBy: 1)
        let envelope: CGFloat
        if t < 0.2 { envelope = t / 0.2 }
        else if t > 0.8 { envelope = (1 - t) / 0.2 }
        else { envelope = 1 }
        let a = envelope * 0.35 * (mood == .dormant ? 0.45 : 0.7)
        guard a > 0.02 else { continue }
        let x = xBias * w + sin((phase + lag) * 2 * .pi) * w * 0.012
        let y = ((yBias + t * 0.25).truncatingRemainder(dividingBy: 1)) * h
        context.fill(
            Path(ellipseIn: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)),
            with: .color(colors.shellMid.opacity(Double(a)))
        )
    }
}

private func drawRockMass(
    _ context: inout GraphicsContext,
    anchor: CGPoint,
    spanX: CGFloat,
    spanY: CGFloat,
    phase: CGFloat,
    alphaScale: CGFloat
) {
    // Rock masses sit still — their shimmer came free with the static palette.
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
                center: CGPoint(x: ox, y: oy),
                startRadius: 0,
                endRadius: rx
            )
        )
    }
}
